package com.interview.redis.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】缓存雪崩解决方案
 *
 * 问题描述：缓存雪崩 - 大量缓存同时过期，或 Redis 宕机，导致大量请求打到 DB
 *
 * 【面试速记】缓存雪崩的两种场景：
 * 场景1：大量 key 同时过期（如电商大促，商品缓存统一设置2小时过期）
 *   → 解决：随机 TTL，避免同一时刻大量 key 过期
 *
 * 场景2：Redis 宕机（单点故障）
 *   → 解决：Redis 高可用（哨兵/集群）+ 服务降级兜底
 *
 * 【面试追问】缓存雪崩和缓存击穿的区别？
 * → 雪崩：大量 key 同时过期（面积大）
 * → 击穿：单个热点 key 过期（点状）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheAvalancheDemo {

    private final StringRedisTemplate redisTemplate;

    // 本地缓存（Caffeine 的简化模拟，实际项目引入 Caffeine 依赖）
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    private static final Random RANDOM = new Random();

    /**
     * 【面试考点】方案1：随机 TTL（解决大量 key 同时过期）
     *
     * 问题描述：所有商品缓存设置相同的 TTL，同一时刻大量 key 过期
     * 解决思路：在基础 TTL 上加一个随机值，分散过期时间
     *
     * ========== 方案对比 ==========
     * ❌ 固定 TTL（有雪崩风险）：
     *    redisTemplate.opsForValue().set(key, value, 120, TimeUnit.MINUTES);
     *    问题：所有商品缓存在同一时刻过期，瞬间大量请求打到 DB
     *
     * ✅ 随机 TTL（推荐）：
     *    long ttl = baseTtl + random(0, 10);  // 基础TTL + 随机0-10分钟
     *    redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.MINUTES);
     *    优点：过期时间分散，不会同时过期
     * ==============================
     *
     * 【面试追问】随机范围设置多少合适？
     * → 答：根据业务容忍度决定，一般是基础 TTL 的 10%-20%
     * → 如基础 TTL 为 60分钟，随机范围设置 0-10分钟
     *
     * @param key          缓存 key
     * @param value        缓存 value
     * @param baseTtlMinutes 基础过期时间（分钟）
     */
    public void setWithRandomTTL(String key, String value, long baseTtlMinutes) {
        // 随机 TTL = 基础 TTL + 随机 0-10 分钟
        long randomExtra = RANDOM.nextInt(10);
        long finalTtl = baseTtlMinutes + randomExtra;

        redisTemplate.opsForValue().set(key, value, finalTtl, TimeUnit.MINUTES);
        log.info("【缓存雪崩-随机TTL】设置缓存: key={}, baseTtl={}min, randomExtra={}min, finalTtl={}min",
                key, baseTtlMinutes, randomExtra, finalTtl);
    }

    /**
     * 【面试考点】方案2：多级缓存（本地 Caffeine + Redis）
     *
     * 问题描述：Redis 宕机时，所有请求直接打到 DB
     * 解决思路：本地缓存（L1）+ Redis（L2），Redis 宕机时降级到本地缓存
     *
     * ========== 多级缓存架构 ==========
     * 请求 → L1本地缓存（Caffeine）→ L2 Redis → DB
     *
     * L1 本地缓存（Caffeine）：
     *   优点：速度最快（纳秒级），无网络开销
     *   缺点：容量小，集群环境下数据不一致
     *   TTL：短（如5分钟），避免数据长时间不一致
     *
     * L2 Redis：
     *   优点：容量大，集群共享，持久化
     *   缺点：有网络开销（毫秒级）
     *   TTL：长（如30分钟）
     * ==============================
     *
     * 【面试追问】多级缓存如何保证数据一致性？
     * → 答：数据更新时，先更新 DB，再删除 Redis，再删除本地缓存
     * → 本地缓存设置较短的 TTL，自动过期
     * → 或者使用消息队列广播缓存失效事件
     *
     * @param key 缓存 key
     * @return 缓存值
     */
    public String queryWithLocalCache(String key) {
        // 第一步：查 L1 本地缓存（最快）
        String localValue = localCache.get(key);
        if (localValue != null) {
            log.info("【多级缓存】L1本地缓存命中: key={}", key);
            return localValue;
        }

        // 第二步：查 L2 Redis
        try {
            String redisValue = redisTemplate.opsForValue().get(key);
            if (redisValue != null) {
                log.info("【多级缓存】L2 Redis缓存命中: key={}", key);
                // 回填 L1 本地缓存
                localCache.put(key, redisValue);
                return redisValue;
            }
        } catch (Exception e) {
            // Redis 不可用，降级到 DB
            log.warn("【多级缓存】Redis不可用，降级查DB: key={}, error={}", key, e.getMessage());
        }

        // 第三步：查 DB
        String dbValue = queryFromDB(key);
        if (dbValue != null) {
            // 回填 L2 Redis 和 L1 本地缓存
            try {
                redisTemplate.opsForValue().set(key, dbValue, 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("【多级缓存】写入Redis失败（Redis不可用）: key={}", key);
            }
            localCache.put(key, dbValue);
            log.info("【多级缓存】DB查询成功，回填缓存: key={}", key);
        }

        return dbValue;
    }

    /**
     * 【面试考点】方案3：服务降级兜底（Redis 宕机时保护 DB）
     *
     * 问题描述：Redis 宕机时，大量请求直接打到 DB，DB 也可能被压垮
     * 解决思路：Redis 不可用时，返回默认值或降级数据，不查 DB
     *
     * ========== 降级策略 ==========
     * 1. 返回默认值：适合非核心数据（如推荐商品、广告）
     * 2. 返回本地缓存：适合允许短暂不一致的数据
     * 3. 返回错误提示：适合核心数据（如价格、库存）
     * 4. 限流：只允许少量请求查 DB，其他返回降级数据
     * ==============================
     *
     * 【面试追问】如何判断 Redis 是否可用？
     * → 答：捕获 Redis 连接异常（RedisConnectionFailureException）
     * → 或者使用 Sentinel/Circuit Breaker（如 Resilience4j）监控 Redis 健康状态
     *
     * 【面试追问】降级数据如何保证最终一致性？
     * → 答：Redis 恢复后，通过消息队列或定时任务重新同步数据
     *
     * @param key 缓存 key
     * @return 缓存值（Redis 不可用时返回默认值）
     */
    public String queryWithFallback(String key) {
        try {
            // 尝试查 Redis
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.info("【服务降级】Redis缓存命中: key={}", key);
                return value;
            }

            // Redis 未命中，查 DB
            String dbValue = queryFromDB(key);
            if (dbValue != null) {
                redisTemplate.opsForValue().set(key, dbValue, 30, TimeUnit.MINUTES);
            }
            return dbValue;

        } catch (Exception e) {
            // Redis 不可用，执行降级逻辑
            log.error("【服务降级】Redis不可用，执行降级: key={}, error={}", key, e.getMessage());

            // 降级策略1：返回本地缓存
            String localValue = localCache.get(key);
            if (localValue != null) {
                log.info("【服务降级】返回本地缓存: key={}", key);
                return localValue;
            }

            // 降级策略2：返回默认值（保护 DB 不被打垮）
            log.warn("【服务降级】返回默认值，保护DB: key={}", key);
            return getDefaultValue(key);
        }
    }

    /**
     * 模拟从数据库查询
     */
    private String queryFromDB(String key) {
        log.info("【模拟DB】查询数据库: key={}", key);
        return "db_value_" + key;
    }

    /**
     * 获取降级默认值
     */
    private String getDefaultValue(String key) {
        // 实际项目中可以返回预设的默认数据
        return "default_value_for_" + key;
    }
}
