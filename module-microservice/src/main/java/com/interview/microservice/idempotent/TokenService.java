package com.interview.microservice.idempotent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 【面试考点】Token 幂等方案实现
 *
 * 问题描述：
 *   用户重复点击"提交订单"按钮，或网络超时后前端自动重试，
 *   会导致同一操作被执行多次（重复下单、重复扣款）。
 *   如何防止这种重复提交？
 *
 * 解决思路（Token 方案）：
 *   ① 前端提交前，先调用 /api/token 接口获取唯一 Token
 *   ② 后端生成 UUID 作为 Token，存入 Redis（TTL=5分钟）
 *   ③ 前端提交时，将 Token 放在请求头 X-Idempotency-Token
 *   ④ 后端 AOP 拦截，原子操作验证并删除 Token（Lua 脚本）
 *   ⑤ Token 存在 → 执行业务逻辑（Token 已被消费，不可重用）
 *   ⑥ Token 不存在 → 拒绝请求（重复提交）
 *
 * 【Token 方案流程图】
 *   前端                    后端
 *    │                       │
 *    │── GET /api/token ────>│ 生成 UUID，存 Redis，返回 token
 *    │<── token ─────────────│
 *    │                       │
 *    │── POST /api/order ───>│ 从请求头取 token
 *    │   Header: X-Idempotency-Token: {token}
 *    │                       │── Lua 脚本：GET + DEL（原子）
 *    │                       │   token 存在 → 执行业务
 *    │                       │   token 不存在 → 返回"重复请求"
 *    │<── 响应 ───────────────│
 *
 * 【对比方案】
 * ❌ 方案一（数据库唯一索引）：
 *    INSERT INTO orders (order_no, ...) VALUES (?, ...)
 *    → 优点：简单可靠，数据库保证唯一性
 *    → 缺点：依赖 DB，高并发下有性能问题；需要设计唯一业务键
 *    → 适用：并发量不高，有天然唯一业务键的场景
 *
 * ❌ 方案二（Redis SET NX，基于业务 key）：
 *    SET idempotent:{userId}:{orderId} 1 EX 60 NX
 *    → 优点：高性能，无需前端配合
 *    → 缺点：需要设计业务 key，不同业务 key 设计不同
 *    → 适用：后端内部调用、MQ 消费者等场景
 *    → 参考：module-integration 中的 IdempotentAspect
 *
 * ✅ 方案三（Redis Token，本方案）：
 *    → 优点：高性能，通用性强，不依赖业务 key 设计
 *    → 缺点：需要前端配合（先获取 token，再提交）
 *    → 适用：支付、下单等不可重复的用户操作
 *
 * 【与 module-integration IdempotentAspect 的区别】
 * → module-integration：基于业务 key（SpEL 表达式），适合后端内部调用
 * → module-microservice（本方案）：基于 Token，适合前端用户操作
 *
 * 【面试追问】Token 丢失怎么办？
 * → 用户可以重新调用 /api/token 接口获取新 Token
 * → 旧 Token 会在 TTL（5分钟）后自动过期，不会造成资源泄漏
 * → 注意：重新获取 Token 后，之前的 Token 立即失效（可选实现）
 *
 * @author interview-prep
 * @see IdempotentAspect
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final StringRedisTemplate stringRedisTemplate;

    // ========== 常量配置 ==========
    private static final String TOKEN_KEY_PREFIX = "idempotency:token:";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);  // Token 有效期 5 分钟

    // ========== Lua 脚本：原子性 GET + DEL ==========
    // 【关键】为什么必须用 Lua 脚本？
    // 如果用两步操作：GET → DEL，在并发场景下：
    //   线程A: GET → 存在（准备执行业务）
    //   线程B: GET → 存在（准备执行业务）
    //   线程A: DEL → 删除
    //   线程B: DEL → 删除（已经删了，但线程B已经通过了验证！）
    // 结果：两个线程都通过了验证，重复执行业务！
    //
    // Lua 脚本在 Redis 中是原子执行的，不会被其他命令打断：
    //   GET + DEL 作为一个原子操作，要么都执行，要么都不执行
    private static final String VALIDATE_AND_CONSUME_SCRIPT = """
            local token = redis.call('GET', KEYS[1])
            if token then
                redis.call('DEL', KEYS[1])
                return 1
            else
                return 0
            end
            """;

    private static final DefaultRedisScript<Long> VALIDATE_AND_CONSUME_REDIS_SCRIPT;

    static {
        VALIDATE_AND_CONSUME_REDIS_SCRIPT = new DefaultRedisScript<>();
        VALIDATE_AND_CONSUME_REDIS_SCRIPT.setScriptText(VALIDATE_AND_CONSUME_SCRIPT);
        VALIDATE_AND_CONSUME_REDIS_SCRIPT.setResultType(Long.class);
    }

    /**
     * 【面试考点】生成幂等 Token
     *
     * 问题描述：如何生成一个全局唯一的 Token？
     *
     * 解决思路：
     *   使用 UUID 作为 Token，存入 Redis 并设置 TTL。
     *   UUID 的碰撞概率极低（2^122 种可能），可以认为是全局唯一的。
     *
     * 【对比方案】
     * ❌ 方案一（自增 ID）：
     *    → 问题：需要数据库或 Redis 计数器，有性能瓶颈
     * ❌ 方案二（时间戳 + 随机数）：
     *    → 问题：高并发下可能重复（同一毫秒内多个请求）
     * ✅ 方案三（UUID，本方案）：
     *    → 优点：无需中心化生成，碰撞概率极低
     *    → 缺点：不可读，无法从 Token 中提取信息
     * ✅ 方案四（Snowflake ID）：
     *    → 优点：有序，可以提取时间戳
     *    → 缺点：需要配置机器 ID，有时钟回拨问题
     *
     * 【面试追问】Token 的 TTL 设置多长合适？
     * → 太短：用户操作慢时 Token 过期，需要重新获取
     * → 太长：Token 占用 Redis 内存，且过期前无法重新提交
     * → 建议：根据业务场景设置，一般 5-30 分钟
     * → 支付场景：通常 15-30 分钟（与支付超时时间一致）
     *
     * @param userId 用户ID（用于构建 Redis key，便于按用户管理）
     * @return 生成的 Token（UUID 格式）
     */
    public String generateToken(String userId) {
        // ========== 生成 UUID Token ==========
        String token = UUID.randomUUID().toString().replace("-", "");
        String redisKey = buildTokenKey(token);

        // ========== 存入 Redis，设置 TTL ==========
        // 【关键】SET key value EX ttl（原子操作，不能先 SET 再 EXPIRE）
        stringRedisTemplate.opsForValue().set(redisKey, userId, TOKEN_TTL);

        log.info("【Token】生成幂等 Token. userId={}, token={}, ttl={}min",
                userId, token, TOKEN_TTL.toMinutes());

        return token;
    }

    /**
     * 【面试考点】验证并消费 Token（原子操作）
     *
     * 问题描述：如何保证 Token 验证和删除的原子性？
     *
     * 解决思路：
     *   使用 Lua 脚本在 Redis 中原子执行 GET + DEL。
     *   Lua 脚本在 Redis 中是单线程执行的，不会被其他命令打断。
     *
     * 【并发安全分析】
     * 场景：用户快速点击两次提交按钮
     *   请求A: Lua(GET→存在, DEL) → 返回 1（成功）→ 执行业务
     *   请求B: Lua(GET→不存在)    → 返回 0（失败）→ 拒绝请求
     * 结果：只有一个请求执行了业务，幂等性保证！
     *
     * 【对比方案】
     * ❌ 方案一（GET + DEL 两步操作）：
     *    → 问题：非原子，并发时两个请求都可能通过验证
     * ❌ 方案二（先 DEL 再判断）：
     *    → 问题：DEL 返回删除数量，但 DEL 本身不是原子的 GET+DEL
     * ✅ 方案三（Lua 脚本，本方案）：
     *    → 优点：原子操作，并发安全
     *    → 原理：Redis 单线程 + Lua 脚本原子性
     *
     * 【面试追问】除了 Lua 脚本，还有什么方式保证原子性？
     * → Redis 事务（MULTI/EXEC）：不推荐，不支持条件判断
     * → Redis 6.0+ 的 GETDEL 命令：原子的 GET + DEL，但无法判断是否存在
     * → 分布式锁：过重，不适合这个场景
     *
     * @param token 待验证的 Token
     * @return true=Token 有效（已消费），false=Token 无效（重复请求）
     */
    public boolean validateAndConsumeToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("【Token】Token 为空，拒绝请求");
            return false;
        }

        String redisKey = buildTokenKey(token);

        // ========== 原子操作：GET + DEL（Lua 脚本）==========
        Long result = stringRedisTemplate.execute(
                VALIDATE_AND_CONSUME_REDIS_SCRIPT,
                Collections.singletonList(redisKey)
        );

        boolean valid = Long.valueOf(1L).equals(result);

        if (valid) {
            log.info("【Token】Token 验证成功，已消费. token={}", maskToken(token));
        } else {
            log.warn("【Token】Token 无效或已消费（重复请求）. token={}", maskToken(token));
        }

        return valid;
    }

    /**
     * 【面试考点】检查 Token 是否有效（不消费）
     *
     * 问题描述：有时需要检查 Token 是否有效，但不消费它（如预检查）。
     *
     * 解决思路：
     *   直接 GET Redis key，存在则有效，不存在则无效。
     *   注意：此方法不消费 Token，Token 仍然可以被后续请求使用。
     *
     * 【使用场景】
     * → 前端轮询检查 Token 是否还有效
     * → 后端预检查（在执行耗时操作前先验证 Token）
     *
     * 【注意事项】
     * → 此方法不是幂等保证的核心，只是辅助查询
     * → 真正的幂等保证必须使用 validateAndConsumeToken（原子操作）
     * → 不要用此方法替代 validateAndConsumeToken，否则有并发问题
     *
     * @param token 待检查的 Token
     * @return true=Token 有效，false=Token 无效或已过期
     */
    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String redisKey = buildTokenKey(token);
        Boolean exists = stringRedisTemplate.hasKey(redisKey);
        boolean valid = Boolean.TRUE.equals(exists);

        log.debug("【Token】检查 Token 有效性（不消费）. token={}, valid={}", maskToken(token), valid);
        return valid;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 构建 Redis key
     *
     * key 格式：idempotency:token:{token}
     * 使用前缀便于：
     * 1. 按前缀批量查询/删除（运维操作）
     * 2. 避免与其他 key 冲突
     * 3. 便于监控（统计 idempotency:token:* 的数量）
     *
     * @param token Token 值
     * @return Redis key
     */
    private String buildTokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    /**
     * 脱敏 Token（日志中只显示前8位和后4位）
     *
     * 安全考虑：Token 是敏感信息，不应该完整打印到日志中。
     * 脱敏后仍然可以用于问题排查（前8位+后4位足够定位问题）。
     *
     * @param token 原始 Token
     * @return 脱敏后的 Token
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 8) + "****" + token.substring(token.length() - 4);
    }
}
