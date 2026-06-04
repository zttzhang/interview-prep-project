package com.interview.integration.idempotent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 【面试考点】AOP 实现接口幂等性 - 切面核心逻辑
 *
 * 问题描述：
 *   如果在每个需要幂等的方法中手动写 Redis 判断逻辑，代码重复且侵入性强。
 *
 * 解决思路：
 *   AOP（面向切面编程）将幂等逻辑从业务代码中抽离：
 *   ① @Around 拦截所有标注 @IdempotentAnnotation 的方法
 *   ② 解析 SpEL 表达式，动态计算幂等 key
 *   ③ Redis SET NX EX 原子操作（判断 + 设置一步完成）
 *   ④ key 已存在 → 抛出异常（重复提交）
 *   ⑤ key 不存在 → 执行业务逻辑
 *   ⑥ 业务失败 → 删除 key（允许重试）
 *
 * 【对比方案】
 * ❌ 方案一（手动在每个方法写判断）：
 *    String key = "idempotent:" + userId;
 *    if (!redisTemplate.opsForValue().setIfAbsent(key, "1", 60, SECONDS)) {
 *        throw new RuntimeException("重复提交");
 *    }
 *    → 问题：代码重复，业务代码被污染，难以统一修改
 * ✅ 方案二（AOP + 注解，本方案）：
 *    @IdempotentAnnotation(key = "#userId", expireSeconds = 60)
 *    public void createOrder(Long userId) { ... }
 *    → 优点：业务代码零侵入，统一管理，易于扩展
 *
 * 【面试追问】
 * Q: 如果业务执行中宕机，key 没有删除怎么办？
 * A: 设置合理的过期时间（expireSeconds），key 会自动过期。
 *    这就是为什么 SET NX EX 必须同时设置过期时间，而不是先 SET NX 再 EXPIRE。
 *    先 SET NX 再 EXPIRE 不是原子操作，宕机后 key 永不过期！
 *
 * Q: SpEL 解析方法参数的原理？
 * A: 通过 ParameterNameDiscoverer 获取参数名，
 *    将参数名和值绑定到 EvaluationContext，
 *    SpEL 解析器从 Context 中取值。
 *    需要编译时保留参数名（-parameters 编译选项）
 *
 * Q: @Around vs @Before 的区别？
 * A: @Before 只能在方法执行前拦截，无法控制方法是否执行。
 *    @Around 可以决定是否调用 joinPoint.proceed()，更灵活。
 *    幂等场景需要"拦截并阻止执行"，必须用 @Around。
 *
 * @author interview-prep
 * @see IdempotentAnnotation
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate redisTemplate;

    /**
     * SpEL 表达式解析器（线程安全，可复用）
     */
    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();

    /**
     * 参数名发现器（用于将参数名绑定到 SpEL 上下文）
     */
    private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /**
     * 幂等 key 前缀
     */
    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:";

    /**
     * 【面试考点】@Around 通知 - 幂等核心逻辑
     *
     * 切点表达式：拦截所有标注 @IdempotentAnnotation 的方法
     *
     * 执行流程：
     * ① 获取注解配置（key 表达式、过期时间、提示信息）
     * ② 解析 SpEL 表达式，计算实际的幂等 key
     * ③ Redis SET NX EX（原子操作：不存在则设置，同时设置过期时间）
     * ④ 设置成功（isNew=true）→ 执行业务逻辑
     * ⑤ 设置失败（isNew=false）→ 抛出异常（重复提交）
     * ⑥ 业务异常 → 删除 key（允许重试）
     *
     * @param joinPoint 连接点（包含方法信息和参数）
     * @return 业务方法的返回值
     * @throws Throwable 业务异常或重复提交异常
     */
    @Around("@annotation(com.interview.integration.idempotent.IdempotentAnnotation)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // ========== 第一步：获取注解配置 ==========
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        IdempotentAnnotation annotation = method.getAnnotation(IdempotentAnnotation.class);

        // ========== 第二步：解析幂等 key ==========
        String idempotentKey = resolveKey(annotation, joinPoint, method);
        log.debug("幂等检查: key={}, expireSeconds={}", idempotentKey, annotation.expireSeconds());

        // ========== 第三步：Redis SET NX EX（原子操作）==========
        // ❌ 错误示范：先 GET 再 SET（非原子，高并发下有竞态条件）
        //    String existing = redisTemplate.opsForValue().get(idempotentKey);
        //    if (existing != null) { throw new RuntimeException("重复提交"); }
        //    redisTemplate.opsForValue().set(idempotentKey, "1");  // 两步不原子！
        //
        // ✅ 正确做法：setIfAbsent = SET NX EX（原子操作）
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(
                idempotentKey,
                "processing",
                Duration.ofSeconds(annotation.expireSeconds())
        );

        // ========== 第四步：判断是否重复提交 ==========
        if (!Boolean.TRUE.equals(isNew)) {
            log.warn("重复提交被拦截: key={}, method={}", idempotentKey, method.getName());
            throw new IllegalStateException(annotation.message());
        }

        // ========== 第五步：执行业务逻辑 ==========
        try {
            Object result = joinPoint.proceed();
            // 业务成功，key 保留（在过期时间内防止重复提交）
            log.debug("业务执行成功，幂等 key 保留: key={}", idempotentKey);
            return result;

        } catch (Throwable e) {
            // ========== 第六步：业务失败，删除 key（允许重试）==========
            // 【面试考点】为什么业务失败要删除 key？
            // 答：业务失败说明操作未完成，用户应该可以重试。
            //     如果不删除 key，用户在过期时间内无法重试，体验差。
            // 注意：如果是"不可重试"的错误（如参数错误），可以不删除 key。
            log.warn("业务执行失败，删除幂等 key 允许重试: key={}, error={}", idempotentKey, e.getMessage());
            redisTemplate.delete(idempotentKey);
            throw e;
        }
    }

    /**
     * 【面试考点】SpEL 表达式解析方法参数
     *
     * 问题描述：
     *   注解中的 key = "#userId" 是 SpEL 表达式，需要在运行时解析为实际值。
     *
     * 解决思路：
     *   ① 获取方法参数名（通过 ParameterNameDiscoverer）
     *   ② 将参数名和值绑定到 EvaluationContext
     *   ③ SpEL 解析器从 Context 中取值
     *
     * 支持的 SpEL 表达式示例：
     *   "#userId"                    → 参数 userId 的值
     *   "#order.id"                  → 参数 order 对象的 id 属性
     *   "#p0"                        → 第一个参数的值
     *   "'createOrder:' + #userId"   → 字符串拼接
     *
     * @param annotation 幂等注解
     * @param joinPoint  连接点
     * @param method     目标方法
     * @return 解析后的幂等 key（带前缀）
     */
    private String resolveKey(IdempotentAnnotation annotation,
                               ProceedingJoinPoint joinPoint,
                               Method method) {
        String keyExpression = annotation.key();

        // 如果没有指定 key，使用 "类名:方法名" 作为默认 key
        if (keyExpression == null || keyExpression.isEmpty()) {
            String className = joinPoint.getTarget().getClass().getSimpleName();
            String methodName = method.getName();
            return IDEMPOTENT_KEY_PREFIX + className + ":" + methodName;
        }

        // 如果不是 SpEL 表达式（不含 # 或 '），直接使用
        if (!keyExpression.contains("#") && !keyExpression.contains("'")) {
            return IDEMPOTENT_KEY_PREFIX + keyExpression;
        }

        // 解析 SpEL 表达式
        try {
            // 创建 SpEL 上下文，绑定方法参数
            EvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(),  // 目标对象（this）
                    method,
                    joinPoint.getArgs(),    // 方法参数值
                    PARAM_NAME_DISCOVERER   // 参数名发现器
            );

            // 解析表达式
            Object keyValue = SPEL_PARSER.parseExpression(keyExpression).getValue(context);
            if (keyValue == null) {
                log.warn("SpEL 表达式解析结果为 null，使用表达式字符串作为 key: {}", keyExpression);
                return IDEMPOTENT_KEY_PREFIX + keyExpression;
            }

            return IDEMPOTENT_KEY_PREFIX + keyValue;

        } catch (Exception e) {
            log.error("SpEL 表达式解析失败: expression={}, error={}", keyExpression, e.getMessage());
            // 解析失败时，使用原始表达式字符串作为 key（降级处理）
            return IDEMPOTENT_KEY_PREFIX + keyExpression;
        }
    }
}
