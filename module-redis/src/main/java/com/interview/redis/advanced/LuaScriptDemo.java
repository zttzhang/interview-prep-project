package com.interview.redis.advanced;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;

/**
 * 【面试考点】Redis Lua 脚本高级应用
 *
 * 问题描述：多个 Redis 命令需要原子执行时，如何保证原子性？
 * 解决思路：Lua 脚本在 Redis 中原子执行，相当于"超级命令"
 *
 * 【面试考点】Lua 脚本原子性原理：
 * 1. Redis 是单线程处理命令的
 * 2. Lua 脚本执行期间，Redis 不处理其他命令
 * 3. 因此 Lua 脚本中的所有命令是原子执行的
 *
 * 【面试速记】KEYS 和 ARGV 的区别：
 * - KEYS：Redis key 列表，用于集群模式下的 key 路由（必须是 key）
 * - ARGV：普通参数列表，不参与路由（可以是任意值）
 * - 规范：所有 Redis key 都应该通过 KEYS 传入，其他参数通过 ARGV 传入
 *
 * 【面试追问】eval 和 evalsha 的区别？
 * → eval：每次发送完整的 Lua 脚本（网络开销大）
 * → evalsha：发送脚本的 SHA1 哈希值（脚本需要先用 SCRIPT LOAD 加载到服务器）
 * → 生产环境建议使用 evalsha，减少网络传输
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LuaScriptDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * 【面试考点】原子性自增并检查上限（限流场景）
     *
     * 问题描述：限流时需要原子地执行"自增 + 检查是否超限"
     * 解决思路：Lua 脚本将 INCR + 比较 合并为原子操作
     *
     * Lua 脚本逐行解析：
     * local current = redis.call('incr', KEYS[1])
     *   → 对 KEYS[1] 执行 INCR，返回自增后的值
     *
     * if current == 1 then
     *   → 如果是第一次自增（值为1），说明 key 刚创建
     *
     *     redis.call('expire', KEYS[1], ARGV[2])
     *   → 设置过期时间（窗口时间），避免 key 永不过期
     *
     * end
     *
     * if current > tonumber(ARGV[1]) then
     *   → 如果当前值超过限制（ARGV[1]）
     *
     *     return 0
     *   → 返回 0 表示超限（被限流）
     *
     * else
     *     return 1
     *   → 返回 1 表示未超限（允许通过）
     *
     * 【面试追问】为什么不用 GET + INCR 两条命令？
     * → 答：GET 和 INCR 之间可能被其他命令插入，导致并发计数不准确
     * → Lua 脚本保证原子性，计数准确
     *
     * @param key   限流 key（如 rate:limit:user:1001）
     * @param limit 限制次数
     * @return true=允许通过，false=被限流
     */
    public boolean atomicIncrWithLimit(String key, long limit) {
        // Lua 脚本：原子自增并检查上限
        String script =
                "local current = redis.call('incr', KEYS[1]) " +
                "if current == 1 then " +
                "    redis.call('expire', KEYS[1], ARGV[2]) " +
                "end " +
                "if current > tonumber(ARGV[1]) then " +
                "    return 0 " +
                "else " +
                "    return 1 " +
                "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        // KEYS[1]=key, ARGV[1]=limit, ARGV[2]=窗口时间(秒)
        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(limit),
                "60"  // 60秒窗口
        );

        boolean allowed = Long.valueOf(1L).equals(result);
        log.info("【Lua限流】key={}, limit={}, allowed={}", key, limit, allowed);
        return allowed;
    }

    /**
     * 【面试考点】CAS（Compare And Set）原子操作
     *
     * 问题描述：如何原子地执行"比较并设置"（乐观锁场景）？
     * 解决思路：Lua 脚本将 GET + 比较 + SET 合并为原子操作
     *
     * Lua 脚本逐行解析：
     * local current = redis.call('get', KEYS[1])
     *   → 获取当前值
     *
     * if current == ARGV[1] then
     *   → 如果当前值等于期望值（ARGV[1]）
     *
     *     redis.call('set', KEYS[1], ARGV[2])
     *   → 设置新值（ARGV[2]）
     *
     *     return 1
     *   → 返回 1 表示 CAS 成功
     *
     * else
     *     return 0
     *   → 返回 0 表示 CAS 失败（当前值已被其他线程修改）
     *
     * 【面试追问】CAS 和分布式锁的区别？
     * → CAS：乐观锁，不阻塞，失败后重试
     * → 分布式锁：悲观锁，阻塞等待，保证互斥
     * → CAS 适合冲突少的场景，分布式锁适合冲突多的场景
     *
     * @param key           Redis key
     * @param expectedValue 期望的当前值
     * @param newValue      要设置的新值
     * @return true=CAS 成功，false=CAS 失败（值已被修改）
     */
    public boolean atomicCheckAndSet(String key, String expectedValue, String newValue) {
        // Lua 脚本：CAS 操作
        String script =
                "local current = redis.call('get', KEYS[1]) " +
                "if current == ARGV[1] then " +
                "    redis.call('set', KEYS[1], ARGV[2]) " +
                "    return 1 " +
                "else " +
                "    return 0 " +
                "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        // KEYS[1]=key, ARGV[1]=expectedValue, ARGV[2]=newValue
        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(key),
                expectedValue,
                newValue
        );

        boolean success = Long.valueOf(1L).equals(result);
        log.info("【Lua CAS】key={}, expected={}, new={}, success={}", key, expectedValue, newValue, success);
        return success;
    }

    /**
     * 【面试考点】库存扣减（原子性保证，防止超卖）
     *
     * 问题描述：秒杀场景下，如何原子地扣减库存，防止超卖？
     * 解决思路：Lua 脚本将"查库存 + 判断 + 扣减"合并为原子操作
     *
     * Lua 脚本逐行解析：
     * local stock = tonumber(redis.call('get', KEYS[1]))
     *   → 获取当前库存，tonumber 转为数字类型
     *
     * if stock == nil then
     *   → 如果库存 key 不存在
     *
     *     return -1
     *   → 返回 -1 表示库存未初始化
     *
     * end
     *
     * if stock < tonumber(ARGV[1]) then
     *   → 如果库存不足（小于要扣减的数量）
     *
     *     return 0
     *   → 返回 0 表示库存不足
     *
     * end
     *
     * redis.call('decrby', KEYS[1], ARGV[1])
     *   → 扣减库存（原子操作）
     *
     * return 1
     *   → 返回 1 表示扣减成功
     *
     * 【面试追问】为什么不用 GET + DECRBY 两条命令？
     * → 答：GET 和 DECRBY 之间可能被其他线程插入，导致超卖
     * → 场景：库存=1，线程A GET=1（判断够），线程B GET=1（判断够）
     *         线程A DECRBY 1，库存=0；线程B DECRBY 1，库存=-1（超卖！）
     * → Lua 脚本保证原子性，彻底防止超卖
     *
     * @param stockKey 库存 key
     * @param quantity 要扣减的数量
     * @return 1=扣减成功，0=库存不足，-1=库存未初始化
     */
    public long stockDeductWithLua(String stockKey, int quantity) {
        // Lua 脚本：原子库存扣减
        String script =
                "local stock = tonumber(redis.call('get', KEYS[1])) " +
                "if stock == nil then " +
                "    return -1 " +
                "end " +
                "if stock < tonumber(ARGV[1]) then " +
                "    return 0 " +
                "end " +
                "redis.call('decrby', KEYS[1], ARGV[1]) " +
                "return 1";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        // KEYS[1]=stockKey, ARGV[1]=quantity
        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(stockKey),
                String.valueOf(quantity)
        );

        long resultValue = result != null ? result : -1L;
        switch ((int) resultValue) {
            case 1:
                log.info("【Lua库存扣减】扣减成功: key={}, quantity={}, 剩余库存={}",
                        stockKey, quantity, redisTemplate.opsForValue().get(stockKey));
                break;
            case 0:
                log.warn("【Lua库存扣减】库存不足: key={}, quantity={}", stockKey, quantity);
                break;
            case -1:
                log.error("【Lua库存扣减】库存未初始化: key={}", stockKey);
                break;
            default:
                log.error("【Lua库存扣减】未知结果: {}", resultValue);
        }

        return resultValue;
    }
}
