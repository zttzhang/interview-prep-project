package com.interview.redis.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】缓存穿透解决方案
 *
 * 问题描述：缓存穿透 - 查询不存在的数据，每次都打到 DB
 * 场景：攻击者用不存在的 ID 发起大量请求，绕过缓存直接打到数据库
 *
 * 【面试速记】三大缓存问题区别：
 * - 缓存穿透：key 不存在（DB 也没有），每次都查 DB
 * - 缓存击穿：热点 key 过期，大量请求同时打到 DB（见 CacheBreakdownDemo）
 * - 缓存雪崩：大量 key 同时过期，或 Redis 宕机（见 CacheAvalancheDemo）
 *
 * 【面试追问】如何区分缓存穿透和缓存击穿？
 * → 穿透：数据根本不存在（DB 也没有）
 * → 击穿：数据存在，只是缓存过期了
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachePenetrationDemo {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    // 空值缓存的特殊标记（区分"数据不存在"和"缓存未命中"）
    private static final String NULL_VALUE = "NULL";
    // 空值缓存的 TTL（短一些，避免长时间缓存无效数据）
    private static final long NULL_TTL_SECONDS = 60;

    /**
     * 【面试考点】方案1：缓存空值（Cache Null Value）
     *
     * 问题描述：查询不存在的数据时，每次都打到 DB
     * 解决思路：DB 查询结果为空时，也缓存一个空值（"NULL"），下次直接返回
     *
     * ========== 方案对比 ==========
     * ✅ 缓存空值方案：
     *    优点：实现简单，效果立竿见影
     *    缺点：
     *      1. 浪费内存（大量不存在的 key 都要缓存）
     *      2. 数据不一致：DB 新增了数据，但 Redis 还缓存着空值
     *         解决：设置较短的 TTL（如60秒），或者新增数据时主动删除空值缓存
     *
     * ✅ 布隆过滤器方案（见 queryWithBloomFilter）：
     *    优点：内存效率高，不存在的 key 直接拦截
     *    缺点：有误判率，不支持删除
     * ==============================
     *
     * 【面试追问】空值缓存的 TTL 设置多少合适？
     * → 答：不能太长（数据不一致时间长），不能太短（频繁打 DB）
     * → 一般设置 1-5 分钟，具体根据业务容忍度决定
     *
     * @param id 查询的 ID
     * @return 查询结果（null 表示数据不存在）
     */
    public String queryWithNullCache(Long id) {
        String key = "product:" + id;

        // 第一步：查缓存
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            // 命中缓存
            if (NULL_VALUE.equals(cached)) {
                // 命中空值缓存，直接返回 null（不查 DB）
                log.info("【缓存穿透-空值方案】命中空值缓存，直接返回null: key={}", key);
                return null;
            }
            log.info("【缓存穿透-空值方案】缓存命中: key={}", key);
            return cached;
        }

        // 第二步：缓存未命中，查 DB（模拟）
        log.info("【缓存穿透-空值方案】缓存未命中，查DB: key={}", key);
        String dbResult = queryFromDB(id);

        if (dbResult == null) {
            // DB 也没有，缓存空值（防止下次再打 DB）
            redisTemplate.opsForValue().set(key, NULL_VALUE, NULL_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("【缓存穿透-空值方案】DB无数据，缓存空值: key={}, ttl={}s", key, NULL_TTL_SECONDS);
            return null;
        }

        // DB 有数据，正常缓存
        redisTemplate.opsForValue().set(key, dbResult, 30, TimeUnit.MINUTES);
        log.info("【缓存穿透-空值方案】DB有数据，写入缓存: key={}", key);
        return dbResult;
    }

    /**
     * 【面试考点】方案2：布隆过滤器前置拦截
     *
     * 问题描述：缓存空值浪费内存，且有数据不一致问题
     * 解决思路：布隆过滤器预先存储所有合法 ID，查询前先判断 ID 是否存在
     *
     * 布隆过滤器特性：
     * - 判断"不存在"：100% 准确（不存在的一定不存在）
     * - 判断"存在"：可能误判（存在的可能实际不存在，误判率约 1%）
     * - 内存极小：1亿个元素只需约 120MB（远小于缓存空值方案）
     * - 不支持删除：删除元素会影响其他元素的判断
     *
     * 【面试追问】布隆过滤器误判了怎么办？
     * → 答：误判只会让少量不存在的 key 穿透到 DB，不会造成大量穿透
     * → 可以接受少量误判，换取内存效率
     *
     * 【面试追问】布隆过滤器如何处理数据删除？
     * → 答：标准布隆过滤器不支持删除
     * → 方案1：定期重建布隆过滤器
     * → 方案2：使用 Counting Bloom Filter（支持删除，但内存更大）
     *
     * @param id 查询的 ID
     * @return 查询结果
     */
    public String queryWithBloomFilter(Long id) {
        String filterName = "product:bloom:filter";

        // 第一步：布隆过滤器判断 ID 是否可能存在
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(filterName);

        if (!bloomFilter.contains(id)) {
            // 布隆过滤器判断不存在，100% 准确，直接返回 null
            log.info("【缓存穿透-布隆过滤器】布隆过滤器判断ID不存在，直接拦截: id={}", id);
            return null;
        }

        // 第二步：布隆过滤器判断可能存在，查缓存
        String key = "product:" + id;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            log.info("【缓存穿透-布隆过滤器】缓存命中: key={}", key);
            return cached;
        }

        // 第三步：缓存未命中，查 DB
        log.info("【缓存穿透-布隆过滤器】缓存未命中，查DB: key={}", key);
        String dbResult = queryFromDB(id);

        if (dbResult != null) {
            redisTemplate.opsForValue().set(key, dbResult, 30, TimeUnit.MINUTES);
        }
        // 注意：布隆过滤器方案不需要缓存空值（布隆过滤器已经拦截了大部分不存在的 key）

        return dbResult;
    }

    /**
     * 【面试考点】初始化布隆过滤器（系统启动时预热）
     *
     * 问题描述：布隆过滤器需要预先加载所有合法 ID
     * 解决思路：系统启动时，从 DB 加载所有合法 ID 到布隆过滤器
     *
     * 【面试追问】布隆过滤器的参数如何设置？
     * → expectedInsertions：预期插入元素数量（影响内存大小）
     * → falseProbability：误判率（越小内存越大，一般设置 0.01 即 1%）
     *
     * 【面试追问】布隆过滤器如何处理新增数据？
     * → 答：新增数据时，同时向布隆过滤器添加新 ID
     * → 注意：布隆过滤器不支持删除，删除数据时无法从布隆过滤器中移除
     */
    public void initBloomFilter() {
        String filterName = "product:bloom:filter";
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(filterName);

        // 初始化布隆过滤器（预期100万个元素，误判率1%）
        bloomFilter.tryInit(1_000_000L, 0.01);
        log.info("【布隆过滤器】初始化完成: expectedInsertions=1000000, falseProbability=0.01");

        // 预热：将所有合法 ID 加入布隆过滤器（模拟从 DB 加载）
        for (long id = 1; id <= 1000; id++) {
            bloomFilter.add(id);
        }
        log.info("【布隆过滤器】预热完成，加载了1000个合法ID");
        log.info("【布隆过滤器】当前元素数量: {}", bloomFilter.count());
    }

    /**
     * 模拟从数据库查询（实际项目中替换为真实的 DB 查询）
     */
    private String queryFromDB(Long id) {
        // 模拟：ID 在 1-1000 范围内的数据存在，其他不存在
        if (id >= 1 && id <= 1000) {
            return "product_data_" + id;
        }
        log.info("【模拟DB】数据不存在: id={}", id);
        return null;
    }
}
