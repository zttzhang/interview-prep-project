package com.interview.integration.delayqueue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 【面试考点】Redis ZSet 实现延迟队列
 *
 * 问题描述：
 *   业务中常见延迟任务场景：
 *   - 订单30分钟未支付自动取消
 *   - 用户注册后5分钟发送欢迎邮件
 *   - 优惠券到期提醒
 *   需要一种机制：在指定时间后执行某个任务
 *
 * 解决思路（ZSet 延迟队列原理）：
 *   ZSet 的 score 字段存储任务的执行时间戳（Unix 时间戳，秒）
 *   轮询时，ZRANGEBYSCORE 0 {当前时间戳} 获取所有到期任务
 *   处理完后，ZREM 删除任务
 *
 * 数据结构：
 *   Key:   delay:queue
 *   Value: taskId（任务唯一标识）
 *   Score: 执行时间戳（当前时间 + 延迟秒数）
 *
 * 【对比方案】
 * ❌ 方案一（JDK DelayQueue）：
 *    → 优点：简单，无外部依赖
 *    → 缺点：内存存储，重启丢失；单机，无法分布式
 * ❌ 方案二（Redis ZSet，本方案）：
 *    → 优点：持久化，分布式；缺点：需要轮询，有延迟误差（取决于轮询间隔）
 * ✅ 方案三（RocketMQ 延迟消息）：
 *    → 优点：精确延迟，高可靠；缺点：依赖 RocketMQ，延迟级别固定（18个级别）
 * ✅ 方案四（Kafka + 时间轮）：
 *    → 优点：高吞吐；缺点：原生不支持延迟，需要自己实现时间轮
 *
 * 【面试追问】
 * Q: ZSet 延迟队列 vs Kafka 延迟队列 vs RocketMQ 延迟队列对比？
 * A: ZSet：实现简单，适合小规模；精度取决于轮询间隔；需要分布式锁防多实例重复处理
 *    Kafka：原生不支持延迟，需要多 topic 模拟（延迟级别有限）；高吞吐
 *    RocketMQ：原生支持18个延迟级别（1s~2h）；精确可靠；生产推荐
 *    选型建议：任务量小用 ZSet；需要精确延迟用 RocketMQ；已有 Kafka 可用时间轮
 *
 * Q: ZRANGEBYSCORE + ZREM 为什么不是原子操作？
 * A: 两个命令之间可能有其他客户端也执行了 ZRANGEBYSCORE，
 *    导致同一任务被多个实例同时获取并处理（重复执行）。
 *    解决方案：Lua 脚本将两个命令合并为原子操作。
 *
 * @author interview-prep
 * @see DelayQueueScheduler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DelayQueueService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 延迟队列的 Redis Key
     * 使用 ZSet 存储，score = 执行时间戳
     */
    public static final String DELAY_QUEUE_KEY = "delay:queue";

    /**
     * 任务数据存储 Key 前缀（存储任务的详细数据）
     * 格式：delay:data:{taskId}
     */
    private static final String TASK_DATA_KEY_PREFIX = "delay:data:";

    // ========== 方案对比：原子性问题 ==========
    // ❌ 方案一（非原子，有竞态条件）：
    //    Set<String> tasks = zrangebyscore(0, now);  // 步骤1
    //    // 此时另一个实例也执行了步骤1，获取到相同的 tasks
    //    for (String task : tasks) {
    //        zrem(task);  // 步骤2：两个实例都会执行，导致重复处理
    //        process(task);
    //    }
    // ✅ 方案二（Lua 脚本，原子操作）：
    //    local tasks = redis.call('ZRANGEBYSCORE', key, 0, now, 'LIMIT', 0, 10)
    //    for _, task in ipairs(tasks) do
    //        redis.call('ZREM', key, task)  // 原子：获取和删除在同一脚本中
    //    end
    //    return tasks
    // ==============================

    /**
     * 原子轮询 Lua 脚本
     *
     * 脚本逻辑：
     * 1. ZRANGEBYSCORE 获取 score <= 当前时间戳的任务（最多10个）
     * 2. 对每个任务执行 ZREM 删除（原子操作，防止重复处理）
     * 3. 返回获取到的任务列表
     *
     * 【面试考点】为什么 Lua 脚本能保证原子性？
     * 答：Redis 执行 Lua 脚本时，脚本中的所有命令作为一个整体执行，
     *     不会被其他客户端的命令打断（Redis 单线程模型）。
     */
    private static final String POLL_LUA_SCRIPT = """
            -- KEYS[1]: 延迟队列 key
            -- ARGV[1]: 当前时间戳（秒）
            -- ARGV[2]: 每次最多获取的任务数
            local tasks = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
            if #tasks == 0 then
                return {}
            end
            -- 原子删除已获取的任务（防止其他实例重复获取）
            for _, task in ipairs(tasks) do
                redis.call('ZREM', KEYS[1], task)
            end
            return tasks
            """;

    /**
     * 【面试考点】添加延迟任务
     *
     * 问题描述：
     *   将任务加入延迟队列，在指定延迟时间后执行
     *
     * 解决思路：
     *   ZADD delay:queue {执行时间戳} {taskId}
     *   执行时间戳 = 当前时间戳 + 延迟秒数
     *
     * 【面试追问】
     * Q: 如果同一个 taskId 被添加两次怎么办？
     * A: ZSet 的 member 是唯一的，重复添加会更新 score（执行时间）。
     *    这可能是期望行为（更新延迟时间），也可能是 bug，需要根据业务决定。
     *
     * @param taskId       任务唯一标识（用于幂等和查询）
     * @param taskData     任务数据（JSON 格式，存储业务参数）
     * @param delaySeconds 延迟秒数
     */
    public void addTask(String taskId, String taskData, long delaySeconds) {
        long executeAt = Instant.now().getEpochSecond() + delaySeconds;

        // 存储任务数据（ZSet 中只存 taskId，详细数据单独存储）
        String dataKey = TASK_DATA_KEY_PREFIX + taskId;
        redisTemplate.opsForValue().set(dataKey, taskData);

        // 将 taskId 加入延迟队列，score = 执行时间戳
        redisTemplate.opsForZSet().add(DELAY_QUEUE_KEY, taskId, executeAt);

        log.info("添加延迟任务: taskId={}, executeAt={}, delaySeconds={}", taskId, executeAt, delaySeconds);
    }

    /**
     * 【面试考点】轮询到期任务（非原子版本，用于演示问题）
     *
     * 问题描述：
     *   ZRANGEBYSCORE 和 ZREM 是两个独立命令，不是原子操作。
     *   多实例部署时，可能导致同一任务被多个实例同时获取。
     *
     * 解决思路：
     *   ① 先 ZRANGEBYSCORE 获取到期任务
     *   ② 再逐个 ZREM 删除（谁删除成功谁处理）
     *   → 问题：步骤①和②之间有时间窗口，可能重复处理
     *   → 推荐使用 pollDueTasksAtomic() 方法
     *
     * @return 到期任务的 taskId 列表
     */
    public List<String> pollDueTasks() {
        long now = Instant.now().getEpochSecond();

        // 获取 score 在 [0, now] 范围内的任务（即执行时间 <= 当前时间）
        Set<ZSetOperations.TypedTuple<String>> tasks = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(DELAY_QUEUE_KEY, 0, now);

        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        // 逐个删除并收集（非原子，多实例下可能重复处理）
        return tasks.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .filter(taskId -> {
                    // ZREM 返回删除的元素数量，1 表示删除成功（该实例获得处理权）
                    Long removed = redisTemplate.opsForZSet().remove(DELAY_QUEUE_KEY, taskId);
                    return removed != null && removed > 0;
                })
                .toList();
    }

    /**
     * 【面试考点】原子轮询到期任务（Lua 脚本版本，推荐）
     *
     * 问题描述：
     *   非原子版本在多实例部署时可能重复处理任务。
     *
     * 解决思路：
     *   使用 Lua 脚本将 ZRANGEBYSCORE + ZREM 合并为原子操作。
     *   Redis 执行 Lua 脚本时不会被打断，保证只有一个实例能获取到任务。
     *
     * 【面试追问】
     * Q: Lua 脚本能完全解决多实例重复处理问题吗？
     * A: 能解决"获取"阶段的重复，但"处理"阶段仍可能失败。
     *    完整方案：Lua 原子获取 + 处理失败时重新入队（带重试次数限制）
     *
     * @return 到期任务的 taskId 列表
     */
    @SuppressWarnings("unchecked")
    public List<String> pollDueTasksAtomic() {
        long now = Instant.now().getEpochSecond();

        DefaultRedisScript<List> script = new DefaultRedisScript<>(POLL_LUA_SCRIPT, List.class);
        List<String> tasks = redisTemplate.execute(
                script,
                Collections.singletonList(DELAY_QUEUE_KEY),
                String.valueOf(now),
                "10"  // 每次最多获取10个任务
        );

        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        log.debug("原子轮询到期任务: count={}, now={}", tasks.size(), now);
        return tasks;
    }

    /**
     * 【面试考点】获取任务数据
     *
     * @param taskId 任务ID
     * @return 任务数据（JSON 字符串）
     */
    public String getTaskData(String taskId) {
        return redisTemplate.opsForValue().get(TASK_DATA_KEY_PREFIX + taskId);
    }

    /**
     * 【面试考点】删除延迟任务（取消任务）
     *
     * 场景：用户支付成功后，取消"30分钟未支付自动取消"的延迟任务
     *
     * @param taskId 任务唯一标识
     */
    public void removeTask(String taskId) {
        // 从延迟队列中删除
        redisTemplate.opsForZSet().remove(DELAY_QUEUE_KEY, taskId);
        // 删除任务数据
        redisTemplate.delete(TASK_DATA_KEY_PREFIX + taskId);
        log.info("删除延迟任务: taskId={}", taskId);
    }

    /**
     * 查询队列中的任务数量（用于监控）
     *
     * @return 队列中的任务总数
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForZSet().size(DELAY_QUEUE_KEY);
        return size != null ? size : 0;
    }
}
