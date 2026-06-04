package com.interview.integration;

import com.interview.integration.delayqueue.DelayQueueService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】延迟队列测试 - 验证 ZSet 延迟队列的核心功能
 *
 * 测试目标：
 *   1. 验证任务能正确添加到延迟队列
 *   2. 验证到期任务能被正确轮询
 *   3. 验证未到期任务不会被提前轮询
 *   4. 验证原子性轮询的正确性
 *
 * 测试设计原则：
 *   ① 使用短延迟（1~2秒）避免测试耗时过长
 *   ② 每个测试前后清理 Redis 数据，避免测试间干扰
 *   ③ 使用唯一 taskId（UUID），避免并发测试冲突
 *
 * 【面试追问】
 * Q: 如何测试时间相关的功能？
 * A: ① 使用短延迟（秒级）而不是分钟级，减少测试等待时间
 *    ② 使用 Thread.sleep() 等待任务到期（简单但不精确）
 *    ③ 生产代码中注入 Clock 接口，测试时用 MockClock 控制时间（推荐）
 *    ④ 使用 Awaitility 库：await().atMost(5, SECONDS).until(() -> condition)
 *
 * Q: @BeforeEach 和 @AfterEach 的作用？
 * A: @BeforeEach：每个测试方法执行前运行，用于初始化测试数据
 *    @AfterEach：每个测试方法执行后运行，用于清理测试数据
 *    保证测试隔离性（每个测试独立，互不影响）
 *
 * @author interview-prep
 */
@Slf4j
@SpringBootTest
class DelayQueueTest {

    @Autowired
    private DelayQueueService delayQueueService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 测试用的 taskId 前缀（用于清理）
     */
    private static final String TEST_TASK_PREFIX = "test:task:";

    @BeforeEach
    void setUp() {
        // 清理测试数据（避免上次测试残留影响）
        cleanupTestData();
        log.info("测试初始化完成");
    }

    @AfterEach
    void tearDown() {
        // 测试后清理（保持 Redis 干净）
        cleanupTestData();
        log.info("测试清理完成");
    }

    /**
     * 【面试考点】基础功能测试：添加任务并验证到期后被轮询
     *
     * 测试步骤：
     * ① 添加一个延迟1秒的任务
     * ② 立即轮询（任务未到期，应该获取不到）
     * ③ 等待2秒（任务到期）
     * ④ 再次轮询（应该获取到任务）
     *
     * 验证点：
     * - 任务添加后在队列中存在
     * - 到期前不会被轮询
     * - 到期后能被正确轮询
     */
    @Test
    @DisplayName("基础功能：添加延迟任务，到期后被轮询")
    void testAddAndPollTask() throws InterruptedException {
        // ========== 准备阶段 ==========
        String taskId = TEST_TASK_PREFIX + UUID.randomUUID();
        String taskData = "{\"type\":\"ORDER_CANCEL\",\"orderId\":\"" + taskId + "\"}";
        long delaySeconds = 1L;  // 1秒后到期

        // ========== 执行阶段：添加任务 ==========
        delayQueueService.addTask(taskId, taskData, delaySeconds);
        log.info("添加延迟任务: taskId={}, delaySeconds={}", taskId, delaySeconds);

        // 验证任务已加入队列
        long queueSize = delayQueueService.getQueueSize();
        assertThat(queueSize).as("任务应该在队列中").isGreaterThanOrEqualTo(1);

        // 验证任务数据已存储
        String storedData = delayQueueService.getTaskData(taskId);
        assertThat(storedData).as("任务数据应该被存储").isEqualTo(taskData);

        // ========== 验证：任务未到期，不应被轮询 ==========
        List<String> tasksBeforeExpiry = delayQueueService.pollDueTasksAtomic();
        boolean containsTestTask = tasksBeforeExpiry.contains(taskId);
        log.info("到期前轮询结果: 包含测试任务={}", containsTestTask);
        assertThat(containsTestTask).as("任务未到期，不应被轮询").isFalse();

        // ========== 等待任务到期 ==========
        log.info("等待任务到期（{}秒）...", delaySeconds + 1);
        Thread.sleep((delaySeconds + 1) * 1000);  // 多等1秒确保到期

        // ========== 验证：任务到期，应被轮询 ==========
        List<String> tasksAfterExpiry = delayQueueService.pollDueTasksAtomic();
        log.info("到期后轮询结果: tasks={}", tasksAfterExpiry);

        assertThat(tasksAfterExpiry)
                .as("到期后应该能轮询到任务")
                .contains(taskId);

        log.info("测试通过：延迟任务在到期后被正确轮询");
    }

    /**
     * 【面试考点】边界条件测试：未到期任务不会被提前轮询
     *
     * 测试步骤：
     * ① 添加一个延迟60秒的任务（不会在测试期间到期）
     * ② 立即轮询
     * ③ 验证：轮询结果中不包含该任务
     *
     * 验证点：
     * - ZRANGEBYSCORE 的 score 过滤正确
     * - 未到期任务不会被误处理
     */
    @Test
    @DisplayName("边界条件：未到期任务不会被提前轮询")
    void testTaskNotPolledBeforeDelay() {
        // ========== 准备阶段 ==========
        String taskId = TEST_TASK_PREFIX + UUID.randomUUID();
        String taskData = "{\"type\":\"FUTURE_TASK\",\"taskId\":\"" + taskId + "\"}";
        long delaySeconds = 60L;  // 60秒后到期（测试期间不会到期）

        // ========== 执行阶段：添加任务 ==========
        delayQueueService.addTask(taskId, taskData, delaySeconds);
        log.info("添加未来任务: taskId={}, delaySeconds={}", taskId, delaySeconds);

        // ========== 验证：立即轮询，不应获取到该任务 ==========
        List<String> tasks = delayQueueService.pollDueTasksAtomic();
        log.info("立即轮询结果: tasks={}", tasks);

        assertThat(tasks)
                .as("未到期任务不应被轮询")
                .doesNotContain(taskId);

        // 验证任务仍在队列中（未被删除）
        String storedData = delayQueueService.getTaskData(taskId);
        assertThat(storedData)
                .as("未到期任务的数据应该仍然存在")
                .isEqualTo(taskData);

        log.info("测试通过：未到期任务不会被提前轮询");
    }

    /**
     * 【面试考点】原子性测试：验证 Lua 脚本原子轮询
     *
     * 测试步骤：
     * ① 添加3个立即到期的任务（delaySeconds=0）
     * ② 调用原子轮询方法
     * ③ 验证：3个任务都被获取到，且每个任务只被获取一次
     * ④ 再次轮询，验证队列为空（任务已被删除）
     *
     * 验证点：
     * - Lua 脚本正确获取所有到期任务
     * - 获取后任务从队列中删除（不会重复获取）
     * - 原子操作保证一致性
     */
    @Test
    @DisplayName("原子性测试：Lua 脚本原子轮询，任务不重复获取")
    void testAtomicPoll() throws InterruptedException {
        // ========== 准备阶段：添加3个立即到期的任务 ==========
        String taskId1 = TEST_TASK_PREFIX + "atomic-1-" + UUID.randomUUID();
        String taskId2 = TEST_TASK_PREFIX + "atomic-2-" + UUID.randomUUID();
        String taskId3 = TEST_TASK_PREFIX + "atomic-3-" + UUID.randomUUID();

        // delaySeconds=0：立即到期（score = 当前时间戳）
        delayQueueService.addTask(taskId1, "{\"seq\":1}", 0);
        delayQueueService.addTask(taskId2, "{\"seq\":2}", 0);
        delayQueueService.addTask(taskId3, "{\"seq\":3}", 0);

        log.info("添加3个立即到期任务: {}, {}, {}", taskId1, taskId2, taskId3);

        // 等待一小段时间确保任务的 score <= 当前时间戳
        Thread.sleep(100);

        // ========== 第一次原子轮询 ==========
        List<String> firstPoll = delayQueueService.pollDueTasksAtomic();
        log.info("第一次原子轮询结果: count={}, tasks={}", firstPoll.size(), firstPoll);

        // 验证：3个任务都被获取到
        assertThat(firstPoll)
                .as("第一次轮询应该获取到所有3个到期任务")
                .contains(taskId1, taskId2, taskId3);

        // ========== 第二次原子轮询（验证任务已被删除）==========
        List<String> secondPoll = delayQueueService.pollDueTasksAtomic();
        log.info("第二次原子轮询结果: count={}, tasks={}", secondPoll.size(), secondPoll);

        // 验证：第二次轮询不应获取到已处理的任务（原子删除保证）
        assertThat(secondPoll)
                .as("第二次轮询不应获取到已处理的任务")
                .doesNotContain(taskId1, taskId2, taskId3);

        log.info("测试通过：Lua 脚本原子轮询，任务不重复获取");
    }

    /**
     * 【面试考点】取消任务测试
     *
     * 测试步骤：
     * ① 添加一个延迟任务
     * ② 取消该任务
     * ③ 等待任务到期
     * ④ 验证：轮询结果中不包含已取消的任务
     *
     * 场景：用户支付成功后，取消"30分钟未支付自动取消"的延迟任务
     */
    @Test
    @DisplayName("取消任务：任务取消后不会被轮询")
    void testRemoveTask() throws InterruptedException {
        // ========== 准备阶段 ==========
        String taskId = TEST_TASK_PREFIX + "cancel-" + UUID.randomUUID();
        String taskData = "{\"type\":\"ORDER_CANCEL\",\"orderId\":\"" + taskId + "\"}";

        // 添加1秒后到期的任务
        delayQueueService.addTask(taskId, taskData, 1);
        log.info("添加待取消任务: taskId={}", taskId);

        // 验证任务已添加
        assertThat(delayQueueService.getTaskData(taskId))
                .as("任务数据应该存在")
                .isNotNull();

        // ========== 取消任务 ==========
        delayQueueService.removeTask(taskId);
        log.info("取消任务: taskId={}", taskId);

        // 验证任务数据已删除
        assertThat(delayQueueService.getTaskData(taskId))
                .as("取消后任务数据应该被删除")
                .isNull();

        // ========== 等待任务"到期"（虽然已取消）==========
        Thread.sleep(2000);

        // ========== 验证：已取消的任务不会被轮询 ==========
        List<String> tasks = delayQueueService.pollDueTasksAtomic();
        assertThat(tasks)
                .as("已取消的任务不应被轮询")
                .doesNotContain(taskId);

        log.info("测试通过：取消任务后不会被轮询");
    }

    /**
     * 清理测试数据
     */
    private void cleanupTestData() {
        // 清理延迟队列中的测试任务
        // 注意：生产环境不要这样做，这里只是测试清理
        Set<String> testTasks = redisTemplate.opsForZSet()
                .rangeByScore(DelayQueueService.DELAY_QUEUE_KEY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        if (testTasks != null) {
            testTasks.stream()
                    .filter(taskId -> taskId != null && taskId.startsWith(TEST_TASK_PREFIX))
                    .forEach(taskId -> {
                        redisTemplate.opsForZSet().remove(DelayQueueService.DELAY_QUEUE_KEY, taskId);
                        redisTemplate.delete("delay:data:" + taskId);
                    });
        }
    }
}
