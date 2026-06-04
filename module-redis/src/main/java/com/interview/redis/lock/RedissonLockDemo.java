package com.interview.redis.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】分布式锁演进 - 第四代：Redisson（生产级别推荐方案）
 *
 * 问题描述：Lua 脚本锁不支持可重入、不支持自动续期
 * 解决思路：Redisson 提供了完整的分布式锁实现，解决所有问题
 *
 * ========== 分布式锁方案对比 ==========
 * | 方案           | 原子性 | 续期 | 可重入 | 复杂度 |
 * | SETNX          | ❌     | ❌   | ❌     | 低     |
 * | SETNX+UUID     | ❌释放 | ❌   | ❌     | 低     |
 * | Lua脚本        | ✅     | ❌   | ❌     | 中     |
 * | Redisson       | ✅     | ✅   | ✅     | 低     |
 * =====================================
 *
 * 【面试考点】Redisson 锁的底层实现：
 * 1. 数据结构：Hash（不是 String）
 *    key: 锁名称
 *    field: 线程ID（格式：UUID:threadId）
 *    value: 重入次数
 *
 * 2. 加锁 Lua 脚本（伪代码）：
 *    if (not exists key) then
 *        hset key threadId 1
 *        pexpire key 30000
 *        return nil  -- 加锁成功
 *    end
 *    if (hget key == threadId) then  -- 可重入判断
 *        hincrby key threadId 1
 *        pexpire key 30000
 *        return nil  -- 重入成功
 *    end
 *    return pttl key  -- 返回剩余过期时间（加锁失败）
 *
 * 3. watchdog 续期机制：
 *    - 加锁成功后，启动一个定时任务（每 10秒 执行一次）
 *    - 定时任务检查锁是否还被当前线程持有
 *    - 如果持有，续期到 30秒（leaseTime）
 *    - 如果线程宕机，watchdog 停止，30秒后锁自动释放
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedissonLockDemo {

    private final RedissonClient redissonClient;

    /**
     * 【面试考点】基础可重入锁 - lock/unlock
     *
     * 问题描述：如何使用 Redisson 实现基础分布式锁？
     * 解决思路：getRLock 获取锁对象，lock 加锁，unlock 释放锁
     *
     * 【面试追问】lock() 和 tryLock() 的区别？
     * → lock()：阻塞等待，直到获取锁（可能永久等待）
     * → tryLock()：尝试获取锁，可以设置等待超时时间
     * → 生产环境建议使用 tryLock()，避免死等
     *
     * 【面试追问】为什么要在 finally 中释放锁？
     * → 答：业务异常时也要释放锁，防止死锁
     * → 但 Redisson 有 watchdog，即使不手动释放，30秒后也会自动释放
     */
    public void basicLockDemo() {
        String lockName = "redisson:basic:lock";
        RLock lock = redissonClient.getLock(lockName);

        log.info("【基础锁】尝试获取锁: {}", lockName);

        // lock()：阻塞等待获取锁（watchdog 自动续期）
        lock.lock();
        try {
            log.info("【基础锁】获取锁成功，执行业务逻辑");
            // 模拟业务执行
            Thread.sleep(100);
            log.info("【基础锁】业务执行完毕");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 释放锁（必须在 finally 中）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("【基础锁】释放锁成功");
            }
        }
    }

    /**
     * 【面试考点】tryLock - 带超时的锁获取（推荐使用）
     *
     * 问题描述：如何避免无限等待锁？
     * 解决思路：tryLock 设置等待超时时间，超时后返回 false
     *
     * 参数说明：
     * - waitTime：等待获取锁的最长时间（超过则放弃）
     * - leaseTime：持有锁的最长时间（-1 表示使用 watchdog 自动续期）
     *
     * ========== 方案对比 ==========
     * ✅ tryLock(waitTime, leaseTime, unit)：
     *    - 指定 leaseTime：锁在 leaseTime 后自动释放，watchdog 不生效
     *    - leaseTime = -1：使用 watchdog 自动续期（推荐）
     *
     * ✅ tryLock(waitTime, unit)：
     *    - 等价于 tryLock(waitTime, -1, unit)
     *    - watchdog 自动续期
     * ==============================
     *
     * 【面试追问】leaseTime 设置多少合适？
     * → 答：建议不设置 leaseTime（使用 watchdog），让 Redisson 自动管理
     * → 如果必须设置，要比业务执行时间长，留有余量
     */
    public void tryLockDemo() {
        String lockName = "redisson:trylock:demo";
        RLock lock = redissonClient.getLock(lockName);

        try {
            // tryLock：等待最多3秒，持有锁最多10秒
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);

            if (acquired) {
                try {
                    log.info("【tryLock】获取锁成功，执行业务逻辑");
                    Thread.sleep(100);
                    log.info("【tryLock】业务执行完毕");
                } finally {
                    lock.unlock();
                    log.info("【tryLock】释放锁成功");
                }
            } else {
                log.warn("【tryLock】等待3秒后仍未获取到锁，执行降级逻辑");
                // 降级处理：返回缓存数据、提示用户稍后重试等
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("【tryLock】等待锁时被中断");
        }
    }

    /**
     * 【面试考点】可重入锁演示 - 同一线程多次 lock
     *
     * 问题描述：同一线程调用嵌套方法时，如何避免死锁？
     * 解决思路：Redisson 支持可重入，同一线程多次 lock 不会死锁
     *
     * 【面试考点】可重入锁底层实现：
     * - Redisson 使用 Hash 结构存储锁：{threadId: 重入次数}
     * - 同一线程再次 lock：重入次数 +1
     * - unlock：重入次数 -1，减到 0 时真正释放锁
     *
     * 【面试追问】可重入锁和不可重入锁的区别？
     * → 可重入：同一线程可以多次获取同一把锁（Java synchronized 也是可重入的）
     * → 不可重入：同一线程再次获取同一把锁会死锁
     * → SETNX 实现的锁是不可重入的（同一线程再次 SETNX 会失败）
     */
    public void reentrantLockDemo() {
        String lockName = "redisson:reentrant:lock";
        RLock lock = redissonClient.getLock(lockName);

        log.info("【可重入锁】演示同一线程多次获取锁");

        // 第一次加锁
        lock.lock();
        log.info("【可重入锁】第1次加锁成功，重入次数: 1");

        try {
            // 第二次加锁（可重入，不会死锁）
            lock.lock();
            log.info("【可重入锁】第2次加锁成功（可重入），重入次数: 2");

            try {
                // 第三次加锁（可重入）
                lock.lock();
                log.info("【可重入锁】第3次加锁成功（可重入），重入次数: 3");

                try {
                    log.info("【可重入锁】执行业务逻辑");
                } finally {
                    lock.unlock();
                    log.info("【可重入锁】第3次解锁，重入次数: 2");
                }
            } finally {
                lock.unlock();
                log.info("【可重入锁】第2次解锁，重入次数: 1");
            }
        } finally {
            lock.unlock();
            log.info("【可重入锁】第1次解锁，重入次数: 0，锁已释放");
        }
    }

    /**
     * 【面试考点】watchdog 自动续期演示
     *
     * 问题描述：业务执行时间超过锁过期时间，锁自动释放导致并发安全问题
     * 解决思路：不设置 leaseTime，Redisson watchdog 每 10秒 自动续期到 30秒
     *
     * 【面试考点】watchdog 工作原理：
     * 1. 调用 lock()（不指定 leaseTime）时，Redisson 启动 watchdog
     * 2. watchdog 是一个定时任务，每隔 leaseTime/3（默认10秒）执行一次
     * 3. 定时任务检查锁是否还被当前线程持有
     * 4. 如果持有，将锁的过期时间续期到 leaseTime（默认30秒）
     * 5. 如果线程宕机，watchdog 停止，锁在 30秒后自动释放
     *
     * ========== 方案对比 ==========
     * ❌ 手动设置 leaseTime（watchdog 不生效）：
     *    lock.lock(10, TimeUnit.SECONDS);  // 10秒后自动释放，不续期
     *    问题：业务执行超过10秒，锁自动释放，其他线程可以获取锁
     *
     * ✅ 不设置 leaseTime（watchdog 自动续期）：
     *    lock.lock();  // watchdog 自动续期，业务执行多久都不会自动释放
     *    优点：业务执行时间不受限制
     *    注意：必须在 finally 中手动 unlock，否则锁永远不释放
     * ==============================
     *
     * 【面试追问】watchdog 的默认超时时间如何修改？
     * → 答：在 RedissonConfig 中设置 config.setLockWatchdogTimeout(60000)（毫秒）
     */
    public void watchdogDemo() {
        String lockName = "redisson:watchdog:demo";
        RLock lock = redissonClient.getLock(lockName);

        log.info("【watchdog演示】不设置leaseTime，watchdog自动续期");
        log.info("【watchdog演示】默认leaseTime=30秒，每10秒续期一次");

        // 不设置 leaseTime，watchdog 自动续期
        lock.lock();
        try {
            log.info("【watchdog演示】加锁成功，watchdog已启动");
            log.info("【watchdog演示】即使业务执行超过30秒，watchdog也会自动续期");

            // 模拟业务执行（实际场景中可能超过30秒）
            Thread.sleep(200);

            log.info("【watchdog演示】业务执行完毕，手动释放锁");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 必须手动释放锁！
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("【watchdog演示】锁已手动释放，watchdog停止");
            }
        }

        log.info("【总结】Redisson 锁的优势：");
        log.info("  1. ✅ 原子性：Lua 脚本保证加锁/释放的原子性");
        log.info("  2. ✅ 续期：watchdog 自动续期，解决锁过期问题");
        log.info("  3. ✅ 可重入：Hash 结构记录重入次数");
        log.info("  4. ✅ 简单：开箱即用，无需手写 Lua 脚本");
    }
}
