package com.interview.redis.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】分布式锁演进 - 第二代：SETNX + UUID（解决误删问题）
 *
 * 问题描述：BadLockDemo 中直接删除锁会误删其他线程的锁
 * 解决思路：value 使用 UUID，释放锁时先判断 value 是否是自己的
 *
 * 【面试速记】分布式锁演进路线：
 * 第1代：SETNX + EXPIRE（非原子，有死锁风险）→ BadLockDemo
 * 第2代：SET NX EX + UUID（原子加锁，但释放非原子）→ 本类
 * 第3代：SET NX EX + UUID + Lua脚本（原子释放）→ LuaScriptLockDemo
 * 第4代：Redisson（watchdog续期，可重入）→ RedissonLockDemo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetnxLockDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * 【面试考点】加锁 - SET key value NX EX seconds（原子操作）
     *
     * 问题描述：如何用一条命令完成加锁+设置过期时间？
     * 解决思路：SET key value NX EX seconds（Redis 2.6.12 支持）
     *
     * ========== 方案对比 ==========
     * ❌ 方案一（非原子，有死锁风险）：
     *    redisTemplate.opsForValue().setIfAbsent(key, value);  // SETNX
     *    redisTemplate.expire(key, expire, TimeUnit.SECONDS);  // EXPIRE
     *    问题：两条命令之间宕机，锁永不过期 → 死锁
     *
     * ✅ 方案二（原子操作）：
     *    redisTemplate.opsForValue().setIfAbsent(key, value, expire, TimeUnit.SECONDS);
     *    等价于：SET key value NX EX seconds
     *    优点：原子操作，不会出现死锁
     * ==============================
     *
     * 【面试追问】value 为什么要用 UUID？
     * → 答：防止误删其他线程的锁
     * → 场景：线程A的锁过期，线程B获取了锁，线程A执行完后不能删除线程B的锁
     * → UUID 作为 value，释放时先判断 value 是否是自己的
     *
     * @param lockKey      锁的 key
     * @param lockValue    锁的 value（建议使用 UUID）
     * @param expireSeconds 锁的过期时间（秒）
     * @return true=加锁成功，false=加锁失败（锁已被占用）
     */
    public boolean tryLock(String lockKey, String lockValue, long expireSeconds) {
        // SET key value NX EX seconds（原子操作）
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, expireSeconds, TimeUnit.SECONDS);

        boolean locked = Boolean.TRUE.equals(success);
        log.info("【SETNX锁】加锁{}: key={}, value={}", locked ? "成功" : "失败", lockKey, lockValue);
        return locked;
    }

    /**
     * 【面试考点】释放锁 - 先判断 value 再删除（防误删）
     *
     * 问题描述：如何防止释放其他线程的锁？
     * 解决思路：释放前先 GET 判断 value 是否是自己的，再 DEL
     *
     * ⚠️ 仍存在的问题：GET 和 DEL 不是原子操作！
     * 场景：
     *   1. 线程A GET，返回自己的 UUID（判断通过）
     *   2. 此时锁恰好过期，线程B 获取了锁
     *   3. 线程A 执行 DEL，删除了线程B 的锁！
     *
     * 解决方案：使用 Lua 脚本保证 GET + DEL 的原子性 → LuaScriptLockDemo
     *
     * 【面试追问】为什么不用 WATCH + MULTI + EXEC（事务）？
     * → 答：Redis 事务不支持回滚，且 WATCH 在集群模式下有限制
     * → Lua 脚本更简单，且保证原子性
     *
     * @param lockKey   锁的 key
     * @param lockValue 当前线程持有的锁 value（UUID）
     */
    public void releaseLock(String lockKey, String lockValue) {
        // 先判断 value 是否是自己的（防误删）
        String currentValue = redisTemplate.opsForValue().get(lockKey);

        if (lockValue.equals(currentValue)) {
            // ⚠️ 注意：这里 GET 和 DEL 不是原子操作，仍有并发问题！
            // 解决方案：使用 Lua 脚本 → LuaScriptLockDemo
            redisTemplate.delete(lockKey);
            log.info("【SETNX锁】释放锁成功: key={}", lockKey);
        } else {
            log.warn("【SETNX锁】释放锁失败（锁已不属于自己）: key={}, expected={}, actual={}",
                    lockKey, lockValue, currentValue);
        }
    }

    /**
     * 【面试考点】演示 SETNX 锁仍存在的问题
     *
     * 问题1：GET + DEL 非原子（见 releaseLock 注释）
     * 问题2：锁续期问题（业务执行时间 > 锁过期时间）
     *
     * 场景演示：
     * 1. 线程A 加锁，设置过期时间 5秒
     * 2. 业务执行需要 10秒（超过锁过期时间）
     * 3. 5秒后锁自动过期，线程B 获取了锁
     * 4. 线程A 和线程B 同时执行业务 → 并发安全问题！
     *
     * 解决方案：Redisson watchdog 自动续期 → RedissonLockDemo
     */
    public void demonstrateProblem() {
        String lockKey = "demo:lock:problem";
        String lockValue = UUID.randomUUID().toString();

        log.info("========== 演示 SETNX 锁的问题 ==========");

        // 加锁，设置 2秒过期（模拟短过期时间）
        boolean locked = tryLock(lockKey, lockValue, 2);
        if (!locked) {
            log.warn("加锁失败，演示结束");
            return;
        }

        try {
            log.info("【问题演示】加锁成功，开始执行业务（模拟耗时3秒，超过锁过期时间2秒）");

            // 模拟业务执行（耗时超过锁过期时间）
            Thread.sleep(3000);

            // 此时锁已经过期！其他线程可能已经获取了锁
            log.warn("【问题演示】业务执行完毕，但锁可能已经过期！");
            log.warn("【问题演示】如果其他线程在锁过期后获取了锁，现在会有并发安全问题！");
            log.warn("【问题演示】解决方案：使用 Redisson watchdog 自动续期");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 释放锁（此时锁可能已经不是自己的了）
            releaseLock(lockKey, lockValue);
        }

        log.info("========== 演示结束 ==========");
        log.info("【总结】SETNX 锁的两个核心问题：");
        log.info("  1. GET + DEL 非原子 → 使用 Lua 脚本解决");
        log.info("  2. 锁续期问题 → 使用 Redisson watchdog 解决");
    }
}
