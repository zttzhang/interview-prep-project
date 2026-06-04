package com.interview.redis.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】分布式锁演进 - 第三代：Lua 脚本保证释放锁的原子性
 *
 * 问题描述：SetnxLockDemo 中 GET + DEL 不是原子操作，存在并发安全问题
 * 解决思路：使用 Lua 脚本，将 GET + DEL 合并为一个原子操作
 *
 * 【面试考点】为什么 Lua 脚本能保证原子性？
 * → Redis 是单线程处理命令的
 * → Lua 脚本在 Redis 中是原子执行的（执行期间不会处理其他命令）
 * → 相当于把多条命令打包成一个"超级命令"
 *
 * 【面试追问】Lua 脚本执行期间 Redis 会阻塞吗？
 * → 答：会！Lua 脚本执行期间 Redis 不处理其他命令
 * → 所以 Lua 脚本要尽量简短，避免复杂计算
 * → Redis 6.0 引入了多线程 I/O，但命令执行仍是单线程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LuaScriptLockDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * 释放锁的 Lua 脚本（原子操作）
     *
     * 脚本逻辑逐行解析：
     * 第1行：if redis.call('get', KEYS[1]) == ARGV[1] then
     *   → 获取锁的当前 value，判断是否等于传入的 value（UUID）
     *   → KEYS[1]：锁的 key（通过 KEYS 数组传入，支持集群路由）
     *   → ARGV[1]：当前线程的 UUID（通过 ARGV 数组传入）
     *
     * 第2行：return redis.call('del', KEYS[1])
     *   → 如果 value 匹配（是自己的锁），执行 DEL 删除锁
     *   → 返回 1 表示删除成功
     *
     * 第3行：else return 0 end
     *   → 如果 value 不匹配（不是自己的锁），返回 0
     *   → 不执行删除，防止误删其他线程的锁
     *
     * 【面试追问】KEYS 和 ARGV 的区别？
     * → KEYS：Redis key 列表，用于集群模式下的 key 路由（必须是 key）
     * → ARGV：普通参数列表，不参与路由（可以是任意值）
     * → 规范：所有 Redis key 都应该通过 KEYS 传入，其他参数通过 ARGV 传入
     */
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    /**
     * 【面试考点】加锁 - SET NX EX（与 SetnxLockDemo 相同）
     *
     * 加锁部分与第二代相同，使用 SET key value NX EX seconds
     * 改进在于释放锁使用 Lua 脚本保证原子性
     *
     * @param lockKey      锁的 key
     * @param lockValue    锁的 value（UUID）
     * @param expireSeconds 锁的过期时间（秒）
     * @return true=加锁成功
     */
    public boolean tryLock(String lockKey, String lockValue, long expireSeconds) {
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, expireSeconds, TimeUnit.SECONDS);

        boolean locked = Boolean.TRUE.equals(success);
        log.info("【Lua锁】加锁{}: key={}", locked ? "成功" : "失败", lockKey);
        return locked;
    }

    /**
     * 【面试考点】释放锁 - Lua 脚本原子释放（核心改进）
     *
     * 问题描述：SetnxLockDemo 的 GET + DEL 非原子，存在并发安全问题
     * 解决思路：Lua 脚本将 GET + DEL 合并为原子操作
     *
     * ========== 方案对比 ==========
     * ❌ 方案一（非原子，SetnxLockDemo）：
     *    String value = redisTemplate.opsForValue().get(key);  // GET
     *    if (myValue.equals(value)) {
     *        redisTemplate.delete(key);  // DEL（GET和DEL之间可能被其他线程抢占）
     *    }
     *
     * ✅ 方案二（Lua脚本，原子操作）：
     *    redisTemplate.execute(script, keys, args);
     *    // GET + DEL 在同一个 Lua 脚本中执行，原子性保证
     * ==============================
     *
     * 【面试追问】eval 和 evalsha 的区别？
     * → eval：每次发送完整的 Lua 脚本（网络开销大）
     * → evalsha：发送脚本的 SHA1 哈希值（脚本需要先用 SCRIPT LOAD 加载）
     * → 生产环境建议使用 evalsha，减少网络传输
     *
     * @param lockKey   锁的 key
     * @param lockValue 当前线程持有的锁 value（UUID）
     * @return true=释放成功，false=释放失败（锁已不属于自己）
     */
    public boolean releaseLockWithLua(String lockKey, String lockValue) {
        // 创建 Lua 脚本对象（返回值类型为 Long）
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RELEASE_LOCK_SCRIPT);
        script.setResultType(Long.class);

        // 执行 Lua 脚本（原子操作）
        // KEYS[1] = lockKey, ARGV[1] = lockValue
        Long result = redisTemplate.execute(
                script,
                Collections.singletonList(lockKey),  // KEYS
                lockValue                              // ARGV
        );

        boolean released = Long.valueOf(1L).equals(result);
        if (released) {
            log.info("【Lua锁】释放锁成功: key={}", lockKey);
        } else {
            log.warn("【Lua锁】释放锁失败（锁已不属于自己）: key={}", lockKey);
        }
        return released;
    }

    /**
     * 【面试考点】完整的加锁/释放锁演示
     *
     * 演示 Lua 脚本锁的正确使用方式
     *
     * ⚠️ 仍存在的问题：锁续期问题
     * 场景：业务执行时间 > 锁过期时间，锁自动过期
     * 解决方案：Redisson watchdog 自动续期 → RedissonLockDemo
     *
     * 【面试追问】Lua 脚本锁和 Redisson 锁的区别？
     * → Lua 脚本锁：手动实现，不支持可重入，不支持自动续期
     * → Redisson 锁：开箱即用，支持可重入，支持 watchdog 自动续期
     */
    public void demonstrateLuaLock() {
        String lockKey = "lua:lock:demo";
        String lockValue = UUID.randomUUID().toString();

        log.info("========== Lua 脚本锁演示 ==========");

        boolean locked = tryLock(lockKey, lockValue, 30);
        if (!locked) {
            log.warn("加锁失败");
            return;
        }

        try {
            log.info("【Lua锁】加锁成功，执行业务逻辑");
            // 模拟业务执行
            Thread.sleep(100);
            log.info("【Lua锁】业务执行完毕");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 使用 Lua 脚本原子释放锁
            boolean released = releaseLockWithLua(lockKey, lockValue);
            log.info("【Lua锁】释放锁: {}", released ? "成功" : "失败");
        }

        log.info("【总结】Lua 脚本解决了 GET+DEL 非原子问题");
        log.info("【遗留问题】锁续期问题仍未解决 → 使用 Redisson watchdog");
    }
}
