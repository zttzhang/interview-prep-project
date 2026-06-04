package com.interview.redis.datatype;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】Redis String 类型核心操作演示
 *
 * 问题描述：String 是 Redis 最基础的数据类型，底层是 SDS（Simple Dynamic String）
 * 解决思路：掌握 String 的典型使用场景，能在面试中举例说明
 *
 * 【面试速记】String 底层结构 SDS 的优势：
 * 1. O(1) 获取字符串长度（记录了 len 字段）
 * 2. 空间预分配，减少内存重分配次数
 * 3. 二进制安全，可以存储任意数据（包括 \0）
 *
 * 【面试追问】String 最大存储多大？
 * → 答：最大 512MB，但实际建议单个 value 不超过 10KB
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StringOpsDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * 【面试考点】计数器场景 - INCR/INCRBY 原子性保证
     *
     * 问题描述：多线程并发计数，如何保证原子性？
     * 解决思路：Redis INCR 命令是原子操作，天然线程安全
     *
     * 【对比方案】
     * ❌ 方案一（错误）：先 GET 再 SET（非原子，并发下会丢失计数）
     *    String count = redisTemplate.opsForValue().get(key);
     *    redisTemplate.opsForValue().set(key, String.valueOf(Long.parseLong(count) + 1));
     *
     * ✅ 方案二（正确）：使用 INCR 原子自增
     *    redisTemplate.opsForValue().increment(key);
     *
     * 【面试追问】INCR 为什么是原子的？
     * → 答：Redis 是单线程处理命令，INCR 是单个命令，不会被其他命令打断
     *
     * 适用场景：PV/UV 统计、点赞数、评论数、限流计数
     */
    public void counterDemo() {
        String pvKey = "page:home:pv";
        String uvKey = "page:home:uv";

        // INCR：自增1，返回自增后的值
        Long pv = redisTemplate.opsForValue().increment(pvKey);
        log.info("【计数器】页面PV: {}", pv);

        // INCRBY：自增指定步长
        Long uvIncrement = redisTemplate.opsForValue().increment(uvKey, 5);
        log.info("【计数器】UV增加5后: {}", uvIncrement);

        // DECR：自减1
        Long decremented = redisTemplate.opsForValue().decrement(pvKey);
        log.info("【计数器】PV自减后: {}", decremented);

        // 设置初始值并自增（原子操作）
        redisTemplate.opsForValue().set("article:123:likes", "100");
        Long likes = redisTemplate.opsForValue().increment("article:123:likes");
        log.info("【计数器】文章点赞数: {}", likes);

        // 清理测试数据
        redisTemplate.delete(pvKey);
        redisTemplate.delete(uvKey);
        redisTemplate.delete("article:123:likes");
    }

    /**
     * 【面试考点】Token 存储场景 - SET key value EX seconds
     *
     * 问题描述：用户登录后如何存储 Token，并设置过期时间？
     * 解决思路：SET key value EX seconds 一条命令完成设置+过期，保证原子性
     *
     * 【对比方案】
     * ❌ 方案一（错误）：先 SET 再 EXPIRE（非原子，宕机可能导致 Token 永不过期）
     *    redisTemplate.opsForValue().set(key, token);
     *    redisTemplate.expire(key, 30, TimeUnit.MINUTES);
     *
     * ✅ 方案二（正确）：SET key value EX seconds（原子操作）
     *    redisTemplate.opsForValue().set(key, token, 30, TimeUnit.MINUTES);
     *
     * 【面试追问】Token 过期后如何处理？
     * → 答：返回 401，前端跳转登录页，或使用 Refresh Token 机制续期
     *
     * 适用场景：JWT Token 黑名单、Session Token、验证码
     */
    public void tokenDemo() {
        String userId = "user:1001";
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.example";
        String tokenKey = "token:" + userId;

        // SET key value EX seconds（原子操作，同时设置值和过期时间）
        redisTemplate.opsForValue().set(tokenKey, token, 30, TimeUnit.MINUTES);
        log.info("【Token存储】Token已存储: key={}, ttl=30min", tokenKey);

        // 验证 Token
        String storedToken = redisTemplate.opsForValue().get(tokenKey);
        boolean isValid = token.equals(storedToken);
        log.info("【Token验证】Token有效: {}", isValid);

        // 获取剩余过期时间
        Long ttl = redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);
        log.info("【Token存储】剩余TTL: {}秒", ttl);

        // 登出时删除 Token（Token 黑名单场景）
        redisTemplate.delete(tokenKey);
        log.info("【Token存储】Token已删除（用户登出）");
    }

    /**
     * 【面试考点】分布式 Session 场景
     *
     * 问题描述：集群部署时，不同节点的 Session 无法共享
     * 解决思路：将 Session 存储到 Redis，所有节点共享同一份 Session
     *
     * 【对比方案】
     * ❌ 方案一（Sticky Session）：Nginx 将同一用户请求路由到同一节点
     *    缺点：节点宕机后 Session 丢失，负载不均衡
     *
     * ❌ 方案二（Session 复制）：各节点之间同步 Session
     *    缺点：网络开销大，数据冗余
     *
     * ✅ 方案三（Redis 集中存储）：所有节点共享 Redis 中的 Session
     *    优点：无状态，水平扩展方便，Session 持久化
     *
     * 【面试追问】Spring Session 如何集成 Redis？
     * → 答：引入 spring-session-data-redis，加 @EnableRedisHttpSession 注解
     *
     * 适用场景：微服务集群 Session 共享、单点登录（SSO）
     */
    public void sessionDemo() {
        String sessionId = "sess:abc123def456";
        // 模拟用户信息 JSON
        String userInfoJson = "{\"userId\":1001,\"username\":\"张三\",\"role\":\"admin\",\"loginTime\":\"2024-01-01T10:00:00\"}";

        // 存储 Session（30分钟过期）
        redisTemplate.opsForValue().set(sessionId, userInfoJson, 30, TimeUnit.MINUTES);
        log.info("【分布式Session】Session已存储: sessionId={}", sessionId);

        // 读取 Session（任意节点都可以读取）
        String session = redisTemplate.opsForValue().get(sessionId);
        log.info("【分布式Session】Session读取成功: {}", session);

        // 续期（用户活跃时刷新过期时间）
        redisTemplate.expire(sessionId, 30, TimeUnit.MINUTES);
        log.info("【分布式Session】Session已续期30分钟");

        // 清理
        redisTemplate.delete(sessionId);
    }

    /**
     * 【面试考点】分布式 ID 生成 - INCR 实现
     *
     * 问题描述：分布式系统中如何生成全局唯一 ID？
     * 解决思路：利用 Redis INCR 的原子性，生成自增 ID
     *
     * 【对比方案】
     * ✅ 方案一（Redis INCR）：简单，有序，但有单点问题
     *    优点：实现简单，ID 有序，可读性好
     *    缺点：Redis 单点故障时无法生成 ID
     *
     * ✅ 方案二（Snowflake 雪花算法）：分布式，高性能，无单点
     *    优点：无中心化，高性能，趋势递增
     *    缺点：依赖机器时钟，时钟回拨会导致 ID 重复
     *
     * ✅ 方案三（UUID）：简单，无序
     *    优点：实现最简单，无依赖
     *    缺点：无序，不适合作为数据库主键（B+树频繁分裂）
     *
     * 【面试追问】为什么 UUID 不适合作为 MySQL 主键？
     * → 答：UUID 无序，插入时 B+树频繁分裂，性能差；且占用空间大（36字节 vs 8字节）
     *
     * 适用场景：订单号、流水号生成（配合业务前缀）
     */
    public void distributedIdDemo() {
        String idKey = "id:order";

        // 使用 INCR 生成自增 ID
        Long orderId = redisTemplate.opsForValue().increment(idKey);
        log.info("【分布式ID】生成订单ID: {}", orderId);

        // 实际场景：加上日期前缀，格式为 20240101000001
        String datePrefix = "20240101";
        String businessId = datePrefix + String.format("%06d", orderId);
        log.info("【分布式ID】业务订单号: {}", businessId);

        // 批量生成（INCRBY 一次增加多个）
        Long batchEnd = redisTemplate.opsForValue().increment(idKey, 100);
        Long batchStart = batchEnd - 100 + 1;
        log.info("【分布式ID】批量ID范围: {} ~ {}", batchStart, batchEnd);

        // 清理
        redisTemplate.delete(idKey);
    }
}
