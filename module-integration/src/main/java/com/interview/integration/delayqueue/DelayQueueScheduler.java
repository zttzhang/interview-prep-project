package com.interview.integration.delayqueue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【面试考点】延迟队列调度器 - 定时轮询到期任务
 *
 * 问题描述：
 *   延迟队列中的任务需要在到期后被执行，
 *   需要一个定时任务不断检查是否有到期的任务。
 *
 * 解决思路：
 *   使用 @Scheduled 注解创建定时任务，每秒轮询一次延迟队列，
 *   获取到期任务并执行业务逻辑。
 *
 * 【对比方案】
 * ❌ 方案一（fixedRate = 1000）：
 *    → 每隔1秒执行一次，不管上次是否执行完
 *    → 问题：如果处理耗时超过1秒，会有多个任务同时执行（线程堆积）
 * ✅ 方案二（fixedDelay = 1000，本方案）：
 *    → 上次执行完成后，等待1秒再执行
 *    → 优点：不会堆积，但延迟精度略低（最大误差 = 处理时间 + 1秒）
 * ✅ 方案三（cron = "* * * * * ?"）：
 *    → 每秒执行一次（cron 最小粒度是秒）
 *    → 适合需要精确时间点执行的场景（如每天凌晨2点）
 *
 * @Scheduled 参数对比：
 * | 参数        | 含义                           | 示例                    |
 * |------------|-------------------------------|------------------------|
 * | fixedDelay | 上次完成后等待N毫秒再执行          | fixedDelay = 1000      |
 * | fixedRate  | 每隔N毫秒执行一次（不等上次完成）   | fixedRate = 1000       |
 * | cron       | Cron 表达式                    | cron = "0 0 2 * * ?"   |
 * | initialDelay | 首次执行前等待N毫秒             | initialDelay = 5000    |
 *
 * 【面试追问】
 * Q: 如何保证延迟任务只被一个实例处理？
 * A: 分布式锁方案（推荐 Redisson tryLock）：
 *    ① tryLock（非阻塞）：获取锁成功才执行，失败直接跳过
 *    ② 不用 lock（阻塞）：避免多实例排队等待，浪费资源
 *    ③ 锁的粒度：可以是整个轮询周期，也可以是单个任务
 *    代码示例：
 *    RLock lock = redissonClient.getLock("delay:queue:lock");
 *    if (lock.tryLock(0, 5, TimeUnit.SECONDS)) {
 *        try { pollAndProcess(); } finally { lock.unlock(); }
 *    }
 *
 * Q: @Scheduled 在分布式环境下有什么问题？
 * A: 每个实例都会执行定时任务，导致：
 *    ① 任务重复执行（如发送重复邮件）
 *    ② 资源浪费（多个实例同时轮询 Redis）
 *    解决方案：
 *    ① 分布式锁（Redisson）：简单，适合大多数场景
 *    ② ShedLock：专门的分布式调度锁框架
 *    ③ XXL-JOB / Elastic-Job：分布式任务调度框架，功能完整
 *
 * Q: 如果轮询间隔是1秒，任务的延迟精度是多少？
 * A: 最大误差约为1秒（轮询间隔）+ 处理时间。
 *    如果需要毫秒级精度，应使用 RocketMQ 延迟消息或 Netty 时间轮。
 *
 * @author interview-prep
 * @see DelayQueueService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayQueueScheduler {

    private final DelayQueueService delayQueueService;

    // 生产环境注入 Redisson 分布式锁
    // private final RedissonClient redissonClient;

    /**
     * 【面试考点】定时轮询延迟队列
     *
     * 问题描述：
     *   每秒检查一次延迟队列，处理所有到期任务。
     *
     * 解决思路：
     *   ① 使用 Lua 脚本原子获取到期任务（防止多实例重复处理）
     *   ② 遍历任务列表，逐个处理
     *   ③ 处理失败时记录日志（可扩展为重新入队）
     *
     * 【面试追问】
     * Q: fixedDelay 和 fixedRate 哪个更适合延迟队列？
     * A: fixedDelay 更安全。
     *    fixedRate 在处理耗时超过间隔时会堆积任务，可能导致 OOM。
     *    fixedDelay 保证上次完成后才开始下次，不会堆积。
     *    延迟队列场景推荐 fixedDelay，精度要求不高时完全够用。
     */
    @Scheduled(fixedDelay = 1000)  // 上次执行完成后等待1秒再执行
    public void pollAndProcessDueTasks() {
        // ========== 生产环境：分布式锁防止多实例重复处理 ==========
        // RLock lock = redissonClient.getLock("delay:queue:scheduler:lock");
        // boolean locked = false;
        // try {
        //     locked = lock.tryLock(0, 5, TimeUnit.SECONDS);  // 非阻塞，获取失败直接跳过
        //     if (!locked) {
        //         log.debug("未获取到分布式锁，跳过本次轮询");
        //         return;
        //     }
        //     doPollAndProcess();
        // } catch (InterruptedException e) {
        //     Thread.currentThread().interrupt();
        // } finally {
        //     if (locked) { lock.unlock(); }
        // }

        // 单机环境：直接执行（使用 Lua 脚本原子操作防止重复）
        doPollAndProcess();
    }

    /**
     * 实际的轮询和处理逻辑
     *
     * 使用 pollDueTasksAtomic() 而不是 pollDueTasks()，
     * 原因：Lua 脚本原子操作，即使多实例同时轮询，每个任务也只会被一个实例获取。
     */
    private void doPollAndProcess() {
        // 使用原子版本（Lua 脚本），防止多实例重复处理
        List<String> dueTasks = delayQueueService.pollDueTasksAtomic();

        if (dueTasks.isEmpty()) {
            return;  // 没有到期任务，静默返回（不打日志，避免日志刷屏）
        }

        log.info("轮询到 {} 个到期任务", dueTasks.size());

        for (String taskId : dueTasks) {
            processTask(taskId);
        }
    }

    /**
     * 【面试考点】处理单个到期任务
     *
     * 问题描述：
     *   任务处理可能失败，需要考虑：
     *   1. 失败重试（重新入队）
     *   2. 最大重试次数（防止无限重试）
     *   3. 失败告警
     *
     * 解决思路：
     *   ① 捕获异常，记录日志
     *   ② 可扩展：失败时重新入队（带重试次数）
     *   ③ 超过最大重试次数 → 发送告警
     *
     * @param taskId 任务ID
     */
    private void processTask(String taskId) {
        try {
            // 获取任务数据
            String taskData = delayQueueService.getTaskData(taskId);
            log.info("处理到期任务: taskId={}, data={}", taskId, taskData);

            // 执行业务逻辑（根据任务类型分发）
            doProcessTask(taskId, taskData);

            // 处理成功，删除任务数据（ZSet 中的 taskId 已在 pollDueTasksAtomic 中删除）
            delayQueueService.removeTask(taskId);
            log.info("任务处理成功: taskId={}", taskId);

        } catch (Exception e) {
            log.error("任务处理失败: taskId={}, error={}", taskId, e.getMessage(), e);

            // 可扩展：失败重试（重新入队，延迟5秒后重试）
            // String taskData = delayQueueService.getTaskData(taskId);
            // delayQueueService.addTask(taskId + ":retry", taskData, 5);
        }
    }

    /**
     * 实际业务处理（根据任务类型分发）
     *
     * 生产环境中，taskData 通常是 JSON，包含任务类型和业务参数：
     * {"type": "ORDER_CANCEL", "orderId": "xxx", "userId": 1}
     *
     * @param taskId   任务ID
     * @param taskData 任务数据（JSON）
     */
    private void doProcessTask(String taskId, String taskData) {
        // 模拟业务处理（实际项目中根据 taskData 中的 type 字段分发）
        log.info("执行延迟任务业务逻辑（模拟）: taskId={}, data={}", taskId, taskData);

        // 示例：订单超时取消
        // if (taskData.contains("ORDER_CANCEL")) {
        //     orderService.cancelOrder(orderId);
        // }

        // 示例：发送提醒邮件
        // if (taskData.contains("SEND_EMAIL")) {
        //     emailService.sendReminder(userId);
        // }
    }
}
