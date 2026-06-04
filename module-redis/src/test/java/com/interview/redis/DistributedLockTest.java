package com.interview.redis;

import com.interview.redis.lock.LuaScriptLockDemo;
import com.interview.redis.lock.RedissonLockDemo;
import com.interview.redis.lock.SetnxLockDemo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】分布式锁演进测试
 *
 * 测试覆盖：SETNX锁 → Lua脚本锁 → Redisson锁 → 可重入性 → 并发测试
 * 每个测试方法对应分布式锁演进的一个阶段
 */
@Slf4j
@SpringBootTest
@DisplayName("分布式锁演进测试")
class DistributedLockTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SetnxLockDemo setnxLockDemo;

    @Autowired
    private LuaScriptLockDemo luaScriptLockDemo;

    @Autowired
    private RedissonLockDemo redissonLockDemo;

    @AfterEach
    void cleanup() {
        // 清理测试锁
        stringRedisTemplate.delete("test:lock:setnx");
        stringRedisTemplate.delete("test:lock:lua");
        log.info("【清理】测试锁已清理");
    }

    /**
     * 【面试考点】测试 SETNX 锁的基本功能
     *
     * 验证：
     * 1. 加锁成功
     * 2. 重复加锁失败（互斥性）
     * 3. 释放锁后可以重新加锁
     */
    @Test
    @DisplayName("SETNX锁: 基本加锁/释放/互斥测试")
    void testSetnxLock() {
        String lockKey = "test:lock:setnx";
        String lockValue1 = UUID.randomUUID().toString();
        String lockValue2 = UUID.randomUUID().toString();

        // 第一次加锁成功
        boolean locked1 = setnxLockDemo.tryLock(lockKey, lockValue1, 30);
        assertThat(locked1).isTrue();
        log.info("【SETNX锁测试】第一次加锁: {}", locked1);

        // 第二次加锁失败（互斥性）
        boolean locked2 = setnxLockDemo.tryLock(lockKey, lockValue2, 30);
        assertThat(locked2).isFalse();
        log.info("【SETNX锁测试】第二次加锁（应失败）: {}", locked2);

        // 释放锁
        setnxLockDemo.releaseLock(lockKey, lockValue1);

        // 释放后可以重新加锁
        boolean locked3 = setnxLockDemo.tryLock(lockKey, lockValue2, 30);
        assertThat(locked3).isTrue();
        log.info("【SETNX锁测试】释放后重新加锁: {}", locked3);

        // 清理
        setnxLockDemo.releaseLock(lockKey, lockValue2);
    }

    /**
     * 【面试考点】测试 Lua 脚本锁的原子释放
     *
     * 验证：
     * 1. 加锁成功
     * 2. Lua 脚本原子释放（正确 value 才能释放）
     * 3. 错误 value 无法释放（防误删）
     */
    @Test
    @DisplayName("Lua脚本锁: 原子释放/防误删测试")
    void testLuaScriptLock() {
        String lockKey = "test:lock:lua";
        String correctValue = UUID.randomUUID().toString();
        String wrongValue = UUID.randomUUID().toString();

        // 加锁
        boolean locked = luaScriptLockDemo.tryLock(lockKey, correctValue, 30);
        assertThat(locked).isTrue();
        log.info("【Lua锁测试】加锁成功");

        // 用错误的 value 尝试释放（应该失败，防误删）
        boolean wrongRelease = luaScriptLockDemo.releaseLockWithLua(lockKey, wrongValue);
        assertThat(wrongRelease).isFalse();
        log.info("【Lua锁测试】错误value释放（应失败）: {}", wrongRelease);

        // 验证锁还在（错误 value 没有删除锁）
        String currentValue = stringRedisTemplate.opsForValue().get(lockKey);
        assertThat(currentValue).isEqualTo(correctValue);
        log.info("【Lua锁测试】锁仍然存在，防误删验证通过");

        // 用正确的 value 释放（应该成功）
        boolean correctRelease = luaScriptLockDemo.releaseLockWithLua(lockKey, correctValue);
        assertThat(correctRelease).isTrue();
        log.info("【Lua锁测试】正确value释放成功: {}", correctRelease);

        // 验证锁已删除
        String afterRelease = stringRedisTemplate.opsForValue().get(lockKey);
        assertThat(afterRelease).isNull();
        log.info("【Lua锁测试】锁已删除，释放验证通过");
    }

    /**
     * 【面试考点】测试 Redisson 锁的基本功能
     *
     * 验证：
     * 1. tryLock 成功获取锁
     * 2. 锁被占用时 tryLock 失败
     * 3. 释放后可以重新获取
     */
    @Test
    @DisplayName("Redisson锁: tryLock/unlock 基本测试")
    void testRedissonLock() throws InterruptedException {
        String lockName = "test:redisson:basic";
        RLock lock = redissonClient.getLock(lockName);

        // tryLock：等待1秒，持有10秒
        boolean locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
        assertThat(locked).isTrue();
        log.info("【Redisson锁测试】tryLock成功");

        try {
            // 验证锁被持有
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            log.info("【Redisson锁测试】isHeldByCurrentThread=true，验证通过");
        } finally {
            lock.unlock();
            log.info("【Redisson锁测试】unlock成功");
        }

        // 释放后验证
        assertThat(lock.isHeldByCurrentThread()).isFalse();
        log.info("【Redisson锁测试】释放后isHeldByCurrentThread=false，验证通过");
    }

    /**
     * 【面试考点】测试 Redisson 可重入锁
     *
     * 验证：
     * 1. 同一线程可以多次 lock（可重入）
     * 2. 重入次数正确（每次 lock +1，每次 unlock -1）
     * 3. 最后一次 unlock 才真正释放锁
     */
    @Test
    @DisplayName("Redisson锁: 可重入性测试")
    void testReentrantLock() {
        String lockName = "test:redisson:reentrant";
        RLock lock = redissonClient.getLock(lockName);

        // 第一次加锁
        lock.lock();
        assertThat(lock.isHeldByCurrentThread()).isTrue();
        log.info("【可重入测试】第1次加锁，重入次数=1");

        // 第二次加锁（可重入，不会死锁）
        lock.lock();
        assertThat(lock.isHeldByCurrentThread()).isTrue();
        log.info("【可重入测试】第2次加锁（可重入），重入次数=2");

        // 第一次解锁（重入次数 -1，锁还在）
        lock.unlock();
        assertThat(lock.isHeldByCurrentThread()).isTrue();
        log.info("【可重入测试】第1次解锁，重入次数=1，锁仍持有");

        // 第二次解锁（重入次数 -1 = 0，锁真正释放）
        lock.unlock();
        assertThat(lock.isHeldByCurrentThread()).isFalse();
        log.info("【可重入测试】第2次解锁，重入次数=0，锁已释放");
    }

    /**
     * 【面试考点】并发测试 - 多线程同时抢锁
     *
     * 验证：
     * 1. 10个线程同时抢锁，只有1个能成功
     * 2. 持有锁的线程执行完后，其他线程才能获取
     * 3. 最终计数器值正确（无并发安全问题）
     *
     * 这是面试中最常考的并发测试场景
     */
    @Test
    @DisplayName("Redisson锁: 并发抢锁测试（10线程）")
    void testConcurrentLock() throws InterruptedException {
        String lockName = "test:redisson:concurrent";
        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 10个线程同时抢锁
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程就绪后同时开始

                    RLock lock = redissonClient.getLock(lockName);
                    boolean locked = lock.tryLock(5, 10, TimeUnit.SECONDS);

                    if (locked) {
                        try {
                            successCount.incrementAndGet();
                            // 模拟业务执行（原子自增）
                            int current = counter.incrementAndGet();
                            log.info("【并发测试】线程{} 获取锁成功，counter={}", threadId, current);
                            Thread.sleep(50); // 模拟业务耗时
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        log.info("【并发测试】线程{} 获取锁失败（超时）", threadId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 所有线程就绪，同时开始
        startLatch.countDown();

        // 等待所有线程完成
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();

        // 验证：所有线程都成功获取了锁（串行执行）
        log.info("【并发测试】成功获取锁的线程数: {}/{}", successCount.get(), threadCount);
        log.info("【并发测试】最终counter值: {}", counter.get());

        // 所有线程都应该成功获取锁（等待5秒足够）
        assertThat(successCount.get()).isEqualTo(threadCount);
        // counter 值应该等于线程数（无并发安全问题）
        assertThat(counter.get()).isEqualTo(threadCount);

        log.info("【并发测试】验证通过：{}个线程串行执行，counter={}", threadCount, counter.get());
    }
}
