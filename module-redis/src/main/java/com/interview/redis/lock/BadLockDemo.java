package com.interview.redis.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】分布式锁 - 错误实现演示（面试陷阱）
 * 
 * 本类演示分布式锁的3个经典错误，是面试中常见的"挖坑"题
 * 
 * 【面试速记】分布式锁3大核心要求：
 * 1. 互斥性：同一时刻只能有一个线程持有锁
 * 2. 可重入：同一线程可以多次获取同一把锁（Redisson支持）
 * 3. 锁续期：业务执行时间长时，防止锁自动过期被他人获取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BadLockDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * ========== 错误1：加锁和设置过期时间非原子 ==========
     * 
     * :x: 错误代码：
     *     redisTemplate.opsForValue().set(key, value);
     *     redisTemplate.expire(key, 30, TimeUnit.SECONDS);
     * 
     * 问题分析：
     * - 如果第一条执行成功，第二条执行前宕机
     * - key 永不过期（没有TTL）
     * - 其他线程永远无法获取锁 → 死锁！
     * 
     * 【面试追问】setNX 和 expire 是两条命令，如何保证原子性？
     * → 答：使用 SET key value NX EX seconds（一条命令完成）
     */
    public boolean badLock1(String key, String value, long timeout) {
        // 【错误实现】先加锁，再设置过期时间（不是原子操作）
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value);
        if (Boolean.TRUE.equals(success)) {
            // 如果这里宕机，锁永远不过期！
            redisTemplate.expire(key, timeout, TimeUnit.SECONDS);
        }
        return Boolean.TRUE.equals(success);
    }

    /**
     * ========== 错误2：释放锁时没有校验 owner ==========
     * 
     * :x: 错误代码：
     *     if (redisTemplate.delete(key)) { ... }
     * 
     * 问题分析：
     * 线程A 获取锁后业务执行超时，锁自动释放
     * 线程B 获取同一把锁
     * 线程A 业务执行完毕，执行 delete 删除锁
     * 结果：线程B 的锁被删除了！其他线程可以进入
     * 
     * 【面试追问】为什么不能直接删除锁？
     * → 答：锁可能已经不属于自己了，删除会影响到其他持有者
     */
    public void badUnlock1(String key) {
        // 【错误实现】直接删除，不校验 owner
        redisTemplate.delete(key);
    }

    /**
     * ========== 错误3：get + delete 非原子 ==========
     * 
     * :x: 错误代码：
     *     if (value.equals(redisTemplate.opsForValue().get(key))) {
     *         redisTemplate.delete(key);
     *     }
     * 
     * 问题分析：
     * - 线程A 执行 get，返回 "uuid-1"（是自己的）
     * - 线程B 正好此时获取了锁（锁已过期）
     * - 线程A 执行 delete，删除了线程B 的锁！
     * 
     * 【面试追问】这种场景下如何保证原子性？
     * → 答：使用 Lua 脚本，保证判断和删除在同一个原子操作中执行
     */
    public void badUnlock2(String key, String expectedValue) {
        // 【错误实现】先判断再删除，两步操作不是原子的
        String currentValue = redisTemplate.opsForValue().get(key);
        if (expectedValue.equals(currentValue)) {
            // 如果在 get 之后、delete 之前，锁被其他线程获取并释放
            // 那么这里会误删其他线程的锁
            redisTemplate.delete(key);
        }
    }

    // ========== 正确实现参考 ==========

    /**
     * 【面试考点】正确的加锁方式 - 使用 SET NX EX 原子命令
     * 
     * :white_check_mark: 正确实现：
     *     SET key value NX EX seconds
     * 
     * 原理：
     * - SET NX：保证互斥性，只有key不存在时才能设置成功
     * - EX seconds：设置过期时间，防止死锁
     * - NX + EX 是原子操作，不会出现"设值成功但过期时间设置失败"的情况
     */
    public boolean correctLock(String key, long timeout) {
        // 生成唯一标识，用于释放锁时校验
        String uuid = UUID.randomUUID().toString();
        
        // ========== 方案对比 ==========
        // :x: 错误：先 setNX 再 expire，两步操作非原子
        // redisTemplate.opsForValue().setIfAbsent(key, uuid);
        // redisTemplate.expire(key, timeout, TimeUnit.SECONDS);
        //
        // :white_check_mark: 正确：一条命令完成 SET NX EX
        // ========== ========== ========== ==========
        
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, uuid, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 【面试考点】正确的解锁方式 - 使用 Lua 脚本保证原子性
     * 
     * Lua 脚本内容：
     * if redis.call('get', KEYS[1]) == ARGV[1] then
     *     return redis.call('del', KEYS[1])
     * else
     *     return 0
     * end
     * 
     * 原理：
     * - 判断当前值是否等于传入的 value（证明是锁的持有者）
     * - 如果相等则删除，否则返回0
     * - 判断和删除在同一个原子操作中完成
     */
    public boolean correctUnlock(String key, String uuid) {
        // Lua 脚本：判断 + 删除 原子执行
        String luaScript = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";
        
        Long result = redisTemplate.execute(
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, Long.class),
            java.util.List.of(key),
            uuid
        );
        
        return result != null && result == 1;
    }
}