package com.interview.integration;

import com.interview.integration.seckill.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】秒杀并发测试 - 验证原子性和超卖防护
 *
 * 测试目标：
 *   验证在高并发场景下，Redis Lua 脚本能正确防止超卖。
 *   100个线程同时秒杀，库存10，最终只有10个成功。
 *
 * 测试原理：
 *   使用 CountDownLatch 模拟"同时"发起请求：
 *   ① 创建100个线程，每个线程持有一个 CountDownLatch
 *   ② 所有线程准备好后，同时释放（countDown），模拟并发
 *   ③ 统计成功和失败数量，验证结果
 *
 * 【面试追问】
 * Q: 并发测试的关键点是什么？
 * A: ① 原子性验证：确保库存扣减是原子操作，不会出现竞态条件
 *    ② 边界条件：库存恰好为0时，不能再扣减
 *    ③ 幂等性：同一用户不能重复秒杀
 *    ④ 结果一致性：成功数 + 失败数 = 总请求数
 *
 * Q: CountDownLatch 和 CyclicBarrier 的区别？
 * A: CountDownLatch：一次性，计数到0后不能重置，适合"等待所有线程完成"
 *    CyclicBarrier：可重复使用，所有线程到达屏障后同时继续，适合"多轮并发测试"
 *    本测试用 CountDownLatch 模拟"同时发起请求"更合适
 *
 * Q: AtomicInteger 为什么是线程安全的？
 * A: 底层使用 CAS（Compare-And-Swap）操作，无锁实现线程安全。
 *    CAS：比较内存值与期望值，相等则更新，不等则重试。
 *    比 synchronized 性能更好（无锁竞争）。
 *
 * @author interview-prep
 */
@Slf4j
@SpringBootTest
class SeckillConcurrencyTest {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 测试商品ID（使用固定值，避免与其他测试冲突）
     */
    private static final Long TEST_PRODUCT_ID = 9999L;

    /**
     * 初始库存
     */
    private static final int INITIAL_STOCK = 10;

    /**
     * 并发线程数（模拟100个用户同时秒杀）
     */
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        // 每次测试前重置库存
        seckillService.initStock(TEST_PRODUCT_ID, INITIAL_STOCK);

        // 清除已购买标记（避免测试间干扰）
        // 实际项目中可以用 @DirtiesContext 或 TestContainers 隔离
        for (long userId = 1; userId <= THREAD_COUNT; userId++) {
            redisTemplate.delete("seckill:ordered:" + TEST_PRODUCT_ID + ":" + userId);
        }

        log.info("测试初始化完成: productId={}, stock={}", TEST_PRODUCT_ID, INITIAL_STOCK);
    }

    /**
     * 【面试考点】核心并发测试：100线程抢10个库存
     *
     * 测试步骤：
     * ① 初始化库存为10
     * ② 创建100个线程，每个线程代表一个用户
     * ③ 使用 CountDownLatch 让所有线程同时发起秒杀请求
     * ④ 等待所有线程完成
     * ⑤ 验证：成功数 = 10，失败数 = 90
     *
     * 验证点：
     * - 成功数不超过库存（防超卖）
     * - 成功数 + 失败数 = 总请求数（无请求丢失）
     * - Redis 中剩余库存 = 0（库存被正确扣减）
     */
    @Test
    @DisplayName("并发秒杀测试：100线程抢10个库存，验证不超卖")
    void testConcurrentSeckill() throws InterruptedException {
        // ========== 准备阶段 ==========
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // CountDownLatch：让所有线程同时开始（模拟并发）
        CountDownLatch startLatch = new CountDownLatch(1);
        // CountDownLatch：等待所有线程完成
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // ========== 提交任务阶段 ==========
        for (int i = 0; i < THREAD_COUNT; i++) {
            final long userId = i + 1;  // userId 从1开始
            executor.submit(() -> {
                try {
                    // 等待发令枪（所有线程准备好后同时开始）
                    startLatch.await();

                    // 发起秒杀请求
                    SeckillService.SeckillResult result = seckillService.seckill(userId, TEST_PRODUCT_ID);

                    if (result.isSuccess()) {
                        successCount.incrementAndGet();
                        log.debug("秒杀成功: userId={}, orderNo={}", userId, result.getOrderNo());
                    } else {
                        failCount.incrementAndGet();
                        log.debug("秒杀失败: userId={}, reason={}", userId, result.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("秒杀异常: userId={}, error={}", userId, e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();  // 通知主线程该线程已完成
                }
            });
        }

        // ========== 执行阶段 ==========
        log.info("发令枪开始！{} 个线程同时发起秒杀...", THREAD_COUNT);
        startLatch.countDown();  // 发令枪：所有线程同时开始

        // 等待所有线程完成（最多等待30秒）
        doneLatch.await();
        executor.shutdown();

        // ========== 验证阶段 ==========
        log.info("========== 秒杀并发测试结果 ==========");
        log.info("总请求数: {}", THREAD_COUNT);
        log.info("成功数:   {}", successCount.get());
        log.info("失败数:   {}", failCount.get());

        // 验证剩余库存
        String stockKey = "seckill:stock:" + TEST_PRODUCT_ID;
        String remainingStock = redisTemplate.opsForValue().get(stockKey);
        log.info("剩余库存: {}", remainingStock);
        log.info("=======================================");

        // 核心断言：成功数不超过初始库存（防超卖）
        assertThat(successCount.get())
                .as("成功数不能超过库存")
                .isLessThanOrEqualTo(INITIAL_STOCK);

        // 核心断言：成功数等于初始库存（库存被完全消耗）
        assertThat(successCount.get())
                .as("库存应该被完全消耗（成功数 = 初始库存）")
                .isEqualTo(INITIAL_STOCK);

        // 核心断言：成功数 + 失败数 = 总请求数（无请求丢失）
        assertThat(successCount.get() + failCount.get())
                .as("成功数 + 失败数 = 总请求数")
                .isEqualTo(THREAD_COUNT);

        // 核心断言：Redis 中剩余库存为0
        assertThat(remainingStock)
                .as("Redis 中剩余库存应为0")
                .isEqualTo("0");
    }

    /**
     * 【面试考点】幂等性测试：同一用户不能重复秒杀
     *
     * 测试步骤：
     * ① 初始化库存为10
     * ② 同一用户（userId=1）发起2次秒杀请求
     * ③ 验证：第一次成功，第二次失败（幂等）
     * ④ 验证：库存只扣减了1次
     */
    @Test
    @DisplayName("幂等性测试：同一用户重复秒杀，只有第一次成功")
    void testIdempotentSeckill() {
        Long userId = 1L;

        // 第一次秒杀
        SeckillService.SeckillResult firstResult = seckillService.seckill(userId, TEST_PRODUCT_ID);
        log.info("第一次秒杀结果: success={}, message={}", firstResult.isSuccess(), firstResult.getMessage());

        // 第二次秒杀（同一用户）
        SeckillService.SeckillResult secondResult = seckillService.seckill(userId, TEST_PRODUCT_ID);
        log.info("第二次秒杀结果: success={}, message={}", secondResult.isSuccess(), secondResult.getMessage());

        // 验证：第一次成功
        assertThat(firstResult.isSuccess()).as("第一次秒杀应该成功").isTrue();

        // 验证：第二次失败（幂等）
        assertThat(secondResult.isSuccess()).as("第二次秒杀应该失败（幂等）").isFalse();
        assertThat(secondResult.getMessage()).as("失败原因应包含'已购买'").contains("已购买");

        // 验证：库存只扣减了1次（从10变为9）
        String stockKey = "seckill:stock:" + TEST_PRODUCT_ID;
        String remainingStock = redisTemplate.opsForValue().get(stockKey);
        assertThat(remainingStock)
                .as("库存应该只扣减1次（剩余9）")
                .isEqualTo(String.valueOf(INITIAL_STOCK - 1));
    }
}
