package com.interview.redis.datatype;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】Redis ZSet（有序集合）类型核心操作演示
 *
 * 问题描述：ZSet 是 Redis 中的有序集合，每个元素关联一个 score，按 score 排序
 * 解决思路：掌握 ZSet 的典型使用场景（排行榜、延迟队列、滑动窗口限流）
 *
 * 【面试速记】ZSet 底层编码：
 * - 元素数量 <= 128 且每个元素 <= 64字节：listpack（紧凑存储）
 * - 超过阈值：skiplist + hashtable（跳表支持范围查询，hashtable支持O(1)查找）
 *
 * 【面试追问】ZSet 为什么用跳表而不用红黑树？
 * → 跳表：实现简单，范围查询效率高（O(logN)），支持并发修改
 * → 红黑树：实现复杂，范围查询需要中序遍历
 * → 跳表的范围查询（ZRANGEBYSCORE）更自然，代码更简洁
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZSetOpsDemo {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 【面试考点】排行榜场景 - ZADD + ZREVRANGE + ZRANK
     *
     * 问题描述：如何实现实时更新的游戏积分排行榜？
     * 解决思路：ZSet score=积分，member=用户ID，ZREVRANGE 获取 Top N
     *
     * 【对比方案】
     * ❌ 方案一（数据库 ORDER BY）：
     *    SELECT user_id, score FROM game_scores ORDER BY score DESC LIMIT 10
     *    缺点：每次查询都需要全表扫描+排序，高并发下性能差
     *
     * ✅ 方案二（Redis ZSet）：
     *    ZADD leaderboard score userId
     *    ZREVRANGE leaderboard 0 9 WITHSCORES
     *    优点：O(logN) 插入，O(logN+M) 范围查询，实时更新
     * ==============================
     *
     * 【面试追问】排行榜数据如何与数据库同步？
     * → 答：写操作同时写 DB 和 Redis（双写）
     * → 或者定时任务从 DB 同步到 Redis（允许短暂不一致）
     *
     * 适用场景：游戏排行榜、销售排行榜、热搜榜
     */
    public void leaderboardDemo() {
        String leaderboardKey = "game:leaderboard:2024";

        // ZADD：添加/更新用户积分
        redisTemplate.opsForZSet().add(leaderboardKey, "player:Alice", 9500);
        redisTemplate.opsForZSet().add(leaderboardKey, "player:Bob", 8800);
        redisTemplate.opsForZSet().add(leaderboardKey, "player:Charlie", 9200);
        redisTemplate.opsForZSet().add(leaderboardKey, "player:David", 7600);
        redisTemplate.opsForZSet().add(leaderboardKey, "player:Eve", 9800);
        log.info("【排行榜】初始化5名玩家积分");

        // ZREVRANGE：获取 Top 3（从高到低）
        Set<Object> top3 = redisTemplate.opsForZSet().reverseRange(leaderboardKey, 0, 2);
        log.info("【排行榜】Top 3: {}", top3);

        // ZREVRANGE WITHSCORES：获取 Top 3 及其积分
        Set<ZSetOperations.TypedTuple<Object>> top3WithScores =
                redisTemplate.opsForZSet().reverseRangeWithScores(leaderboardKey, 0, 2);
        top3WithScores.forEach(tuple ->
                log.info("【排行榜】玩家: {}, 积分: {}", tuple.getValue(), tuple.getScore()));

        // ZINCRBY：增加积分（原子操作）
        Double newScore = redisTemplate.opsForZSet().incrementScore(leaderboardKey, "player:Bob", 500);
        log.info("【排行榜】Bob积分+500后: {}", newScore);

        // ZRANK：获取排名（从0开始，从低到高）
        Long rank = redisTemplate.opsForZSet().reverseRank(leaderboardKey, "player:Bob");
        log.info("【排行榜】Bob的排名（从1开始）: {}", rank != null ? rank + 1 : "未上榜");

        // ZSCORE：获取某玩家的积分
        Double score = redisTemplate.opsForZSet().score(leaderboardKey, "player:Alice");
        log.info("【排行榜】Alice的积分: {}", score);

        // 清理
        redisTemplate.delete(leaderboardKey);
    }

    /**
     * 【面试考点】延迟队列场景 - score = 执行时间戳
     *
     * 问题描述：如何实现延迟任务（如订单30分钟未支付自动取消）？
     * 解决思路：ZSet score=执行时间戳，定时轮询 ZRANGEBYSCORE 获取到期任务
     *
     * ========== 方案对比 ==========
     * ✅ Redis ZSet 延迟队列：
     *    优点：实现简单，支持任意延迟时间，可以取消任务（ZREM）
     *    缺点：需要轮询，有延迟误差（取决于轮询间隔）
     *
     * ✅ RabbitMQ 死信队列：
     *    优点：精确延迟，不需要轮询
     *    缺点：需要额外部署 RabbitMQ，配置复杂
     *
     * ✅ Kafka + 时间轮：
     *    优点：高吞吐量，精确延迟
     *    缺点：实现复杂
     * ==============================
     *
     * 【面试追问】ZSet 延迟队列如何保证任务不重复执行？
     * → 答：使用 ZRANGEBYSCORE + ZREM 的 Lua 脚本，原子性地获取并删除任务
     *
     * 适用场景：订单超时取消、定时提醒、延迟通知
     */
    public void delayQueueDemo() {
        String delayQueueKey = "delay:queue:orders";

        long now = Instant.now().getEpochSecond();

        // ZADD：添加延迟任务（score = 执行时间戳）
        // 订单1：5秒后执行
        redisTemplate.opsForZSet().add(delayQueueKey, "order:1001:cancel", now + 5);
        // 订单2：10秒后执行
        redisTemplate.opsForZSet().add(delayQueueKey, "order:1002:cancel", now + 10);
        // 订单3：已到期（立即执行）
        redisTemplate.opsForZSet().add(delayQueueKey, "order:1003:cancel", now - 1);
        log.info("【延迟队列】添加3个延迟任务");

        // ZRANGEBYSCORE：获取所有已到期的任务（score <= 当前时间戳）
        Set<Object> dueTasks = redisTemplate.opsForZSet()
                .rangeByScore(delayQueueKey, 0, now);
        log.info("【延迟队列】当前到期的任务: {}", dueTasks);

        // 处理到期任务（实际场景中需要用 Lua 脚本保证原子性）
        if (dueTasks != null) {
            for (Object task : dueTasks) {
                // 处理任务
                log.info("【延迟队列】处理任务: {}", task);
                // 处理完成后删除
                redisTemplate.opsForZSet().remove(delayQueueKey, task);
            }
        }

        // 清理
        redisTemplate.delete(delayQueueKey);
    }

    /**
     * 【面试考点】滑动窗口限流 - ZADD + ZREMRANGEBYSCORE + ZCARD
     *
     * 问题描述：如何实现精确的滑动窗口限流？
     * 解决思路：ZSet score=请求时间戳，ZREMRANGEBYSCORE 清理过期请求，ZCARD 计数
     *
     * ========== 方案对比 ==========
     * ❌ 固定窗口限流（INCR + EXPIRE）：
     *    缺点：临界问题！窗口切换时可能通过 2倍 的请求
     *    例：窗口1最后1秒通过100个请求，窗口2第1秒又通过100个请求
     *        实际上1秒内通过了200个请求，超过了限制
     *
     * ✅ 滑动窗口限流（ZSet）：
     *    优点：精确，无临界问题
     *    缺点：内存占用较大（每个请求都要存储时间戳）
     *    步骤：
     *      1. ZADD key timestamp timestamp（score和member都是时间戳）
     *      2. ZREMRANGEBYSCORE key 0 (now - windowSize)（清理过期请求）
     *      3. ZCARD key（统计窗口内请求数）
     *      4. 如果 ZCARD > limit，拒绝请求
     * ==============================
     *
     * 【面试追问】滑动窗口限流的内存如何优化？
     * → 答：可以用时间戳的毫秒值作为 score，减少精度损失
     * → 或者使用 Redis 的 RRateLimiter（Redisson 提供，令牌桶算法）
     *
     * 适用场景：API 限流、防刷、接口保护
     */
    public void slidingWindowRateLimitDemo() {
        String rateLimitKey = "rate:limit:user:1001:api";
        int limit = 10; // 每分钟最多10次请求
        long windowSizeMs = 60 * 1000L; // 60秒窗口

        log.info("【滑动窗口限流】模拟用户请求，限制: {}次/分钟", limit);

        // 模拟多次请求
        for (int i = 1; i <= 12; i++) {
            long now = System.currentTimeMillis();

            // 步骤1：添加当前请求（score=时间戳，member=时间戳，保证唯一性）
            redisTemplate.opsForZSet().add(rateLimitKey, String.valueOf(now + i), now + i);

            // 步骤2：清理窗口外的过期请求
            redisTemplate.opsForZSet().removeRangeByScore(rateLimitKey, 0, now - windowSizeMs);

            // 步骤3：统计窗口内的请求数
            Long count = redisTemplate.opsForZSet().size(rateLimitKey);

            // 步骤4：判断是否超过限制
            if (count != null && count > limit) {
                log.warn("【滑动窗口限流】请求{}被限流！当前窗口请求数: {}", i, count);
            } else {
                log.info("【滑动窗口限流】请求{}通过，当前窗口请求数: {}", i, count);
            }
        }

        // 设置 key 过期时间，避免内存泄漏
        redisTemplate.expire(rateLimitKey, 2, TimeUnit.MINUTES);

        // 清理
        redisTemplate.delete(rateLimitKey);
    }
}
