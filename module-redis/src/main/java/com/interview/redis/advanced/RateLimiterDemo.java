package com.interview.redis.advanced;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】Redis 限流三种方案对比
 *
 * 问题描述：如何防止接口被恶意刷取或高并发压垮？
 * 解决思路：限流 - 控制单位时间内的请求数量
 *
 * ========== 三种限流方案对比 ==========
 * | 方案       | 精确度 | 突发流量 | 实现复杂度 | 临界问题 |
 * | 固定窗口   | 低     | 不支持   | 低         | 有       |
 * | 滑动窗口   | 高     | 不支持   | 中         | 无       |
 * | 令牌桶     | 高     | 支持     | 低（Redisson）| 无    |
 * =====================================
 *
 * 【面试速记】选型建议：
 * - 简单场景：固定窗口（INCR + EXPIRE）
 * - 精确限流：滑动窗口（ZSet）
 * - 支持突发：令牌桶（Redisson RRateLimiter）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterDemo {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    /**
     * 【面试考点】方案1：固定窗口限流（INCR + EXPIRE）
     *
     * 问题描述：最简单的限流实现，但有临界问题
     * 解决思路：INCR 计数，EXPIRE 设置窗口时间
     *
     * ========== 固定窗口缺点：临界问题 ==========
     * 场景：限制每分钟100次请求
     * 窗口1（0:00-1:00）：最后1秒（0:59）发送100次请求 → 通过
     * 窗口2（1:00-2:00）：第1秒（1:00）发送100次请求 → 通过
     * 结果：2秒内通过了200次请求，实际超过了限制！
     *
     * 解决方案：滑动窗口（见 slidingWindowRateLimit）
     * ==========================================
     *
     * 【面试追问】固定窗口适用什么场景？
     * → 答：对精确度要求不高，实现简单的场景
     * → 如：每天最多发送10条短信（天级别窗口，临界问题影响小）
     *
     * @param key           限流 key（如 rate:limit:user:1001:api）
     * @param limit         窗口内最大请求数
     * @param windowSeconds 窗口时间（秒）
     * @return true=允许通过，false=被限流
     */
    public boolean fixedWindowRateLimit(String key, int limit, int windowSeconds) {
        // INCR：原子自增计数
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            return false;
        }

        if (count == 1) {
            // 第一次请求，设置窗口过期时间
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        boolean allowed = count <= limit;
        log.info("【固定窗口限流】key={}, count={}/{}, allowed={}", key, count, limit, allowed);

        if (!allowed) {
            log.warn("【固定窗口限流】请求被限流: key={}", key);
        }

        return allowed;
    }

    /**
     * 【面试考点】方案2：滑动窗口限流（ZSet 实现）
     *
     * 问题描述：固定窗口有临界问题，如何实现精确的滑动窗口限流？
     * 解决思路：ZSet score=时间戳，ZREMRANGEBYSCORE 清理过期请求，ZCARD 计数
     *
     * ========== 滑动窗口实现步骤 ==========
     * 1. ZADD key timestamp timestamp（score和member都是时间戳，保证唯一性）
     * 2. ZREMRANGEBYSCORE key 0 (now - windowSize)（清理窗口外的过期请求）
     * 3. ZCARD key（统计窗口内的请求数）
     * 4. 如果 ZCARD > limit，拒绝请求
     * ======================================
     *
     * 【面试追问】滑动窗口的内存如何优化？
     * → 答：每个请求都要存储时间戳，内存占用 O(N)
     * → 优化：使用 Lua 脚本将4步操作合并为原子操作，减少网络往返
     * → 或者使用 Redisson RRateLimiter（令牌桶，内存更小）
     *
     * @param key           限流 key
     * @param limit         窗口内最大请求数
     * @param windowSeconds 窗口时间（秒）
     * @return true=允许通过，false=被限流
     */
    public boolean slidingWindowRateLimit(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        // 使用 Lua 脚本保证原子性（4步操作合并）
        String script =
                // 步骤1：添加当前请求（score=时间戳，member=时间戳）
                "redis.call('zadd', KEYS[1], ARGV[1], ARGV[1]) " +
                // 步骤2：清理窗口外的过期请求
                "redis.call('zremrangebyscore', KEYS[1], 0, ARGV[2]) " +
                // 步骤3：设置 key 过期时间（避免内存泄漏）
                "redis.call('expire', KEYS[1], ARGV[3]) " +
                // 步骤4：统计窗口内的请求数
                "local count = redis.call('zcard', KEYS[1]) " +
                // 步骤5：判断是否超限
                "if count > tonumber(ARGV[4]) then " +
                "    return 0 " +
                "else " +
                "    return 1 " +
                "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(now),          // ARGV[1]: 当前时间戳（member和score）
                String.valueOf(windowStart),  // ARGV[2]: 窗口开始时间（清理过期请求）
                String.valueOf(windowSeconds * 2), // ARGV[3]: key 过期时间
                String.valueOf(limit)         // ARGV[4]: 限制次数
        );

        boolean allowed = Long.valueOf(1L).equals(result);
        log.info("【滑动窗口限流】key={}, limit={}/{}s, allowed={}", key, limit, windowSeconds, allowed);
        return allowed;
    }

    /**
     * 【面试考点】方案3：令牌桶限流（Redisson RRateLimiter）
     *
     * 问题描述：如何支持突发流量（如秒杀开始时的瞬间高并发）？
     * 解决思路：令牌桶算法 - 以固定速率生成令牌，请求消耗令牌
     *
     * ========== 令牌桶原理 ==========
     * 1. 桶的容量：capacity（最大令牌数，支持突发流量）
     * 2. 令牌生成速率：refillRate（每秒生成的令牌数）
     * 3. 请求到来时：消耗1个令牌，令牌不足则拒绝
     *
     * 例：capacity=100，refillRate=10/s
     * → 平均速率：10 QPS
     * → 突发流量：最多允许100个请求同时通过（消耗桶中所有令牌）
     * ================================
     *
     * 【对比方案】
     * ❌ 固定窗口：有临界问题，不支持突发
     * ❌ 滑动窗口：无临界问题，不支持突发，内存占用大
     * ✅ 令牌桶：无临界问题，支持突发，Redisson 内置实现
     * ✅ 漏桶：无临界问题，不支持突发，平滑输出（适合保护下游）
     *
     * 【面试追问】令牌桶和漏桶的区别？
     * → 令牌桶：允许突发流量（桶满时可以一次性消耗所有令牌）
     * → 漏桶：不允许突发，以固定速率输出（保护下游服务）
     *
     * @param key        限流 key（RRateLimiter 的名称）
     * @param capacity   桶的容量（最大令牌数）
     * @param refillRate 令牌生成速率（每秒生成的令牌数）
     * @return true=允许通过，false=被限流
     */
    public boolean tokenBucketRateLimit(String key, int capacity, int refillRate) {
        // 获取 Redisson RRateLimiter（令牌桶）
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);

        // 初始化令牌桶（幂等，已存在则不重新初始化）
        // RateType.OVERALL：所有客户端共享同一个令牌桶（分布式限流）
        // RateType.PER_CLIENT：每个客户端独立的令牌桶（本地限流）
        rateLimiter.trySetRate(RateType.OVERALL, refillRate, 1, RateIntervalUnit.SECONDS);

        // 尝试获取1个令牌（非阻塞）
        boolean allowed = rateLimiter.tryAcquire(1);
        log.info("【令牌桶限流】key={}, capacity={}, refillRate={}/s, allowed={}",
                key, capacity, refillRate, allowed);

        if (!allowed) {
            log.warn("【令牌桶限流】请求被限流（令牌不足）: key={}", key);
        }

        return allowed;
    }
}
