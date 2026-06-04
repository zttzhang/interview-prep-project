package com.interview.microservice.idempotent;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】基于 Token 的幂等切面
 *
 * 问题描述：
 *   如果在每个 Controller 方法中手动写 Token 验证逻辑，代码重复且侵入性强。
 *   如何用 AOP 将幂等逻辑从业务代码中抽离？
 *
 * 解决思路：
 *   ① @Around 拦截带有 @IdempotentToken 注解的方法
 *   ② 从请求头 X-Idempotency-Token 获取 Token
 *   ③ 调用 TokenService.validateAndConsumeToken() 原子验证
 *   ④ Token 无效 → 返回"重复请求"错误响应
 *   ⑤ Token 有效 → 执行业务逻辑
 *
 * 【与 module-integration IdempotentAspect 的区别】
 *
 * module-integration/IdempotentAspect（业务 key 方案）：
 *   → 基于 SpEL 表达式计算业务 key（如 #userId + #orderId）
 *   → 使用 Redis SET NX EX 原子操作
 *   → 不需要前端配合，后端自动生成 key
 *   → 适用场景：后端内部调用、MQ 消费者、查询幂等、更新幂等
 *   → 示例：@IdempotentAnnotation(key = "#userId + ':' + #orderId", expireSeconds = 60)
 *
 * module-microservice/IdempotentAspect（Token 方案，本类）：
 *   → 基于前端传入的 Token（UUID）
 *   → 使用 Lua 脚本原子执行 GET + DEL
 *   → 需要前端先获取 Token，再提交时携带
 *   → 适用场景：支付、下单等不可重复的用户操作
 *   → 示例：@IdempotentToken（无需参数，Token 从请求头获取）
 *
 * 【两种方案对比】
 * ┌──────────────────┬──────────────────────┬──────────────────────┐
 * │ 特性              │ 业务 key 方案         │ Token 方案（本类）    │
 * ├──────────────────┼──────────────────────┼──────────────────────┤
 * │ 前端配合          │ 不需要               │ 需要（先获取 Token）  │
 * │ key 设计          │ 需要设计业务 key      │ 无需（UUID 自动生成） │
 * │ 适用场景          │ 后端调用、MQ 消费     │ 用户操作（支付/下单） │
 * │ Token 可重用      │ 否（TTL 内不可重复）  │ 否（消费后立即失效）  │
 * │ 并发安全          │ SET NX（原子）        │ Lua GET+DEL（原子）  │
 * └──────────────────┴──────────────────────┴──────────────────────┘
 *
 * 【面试追问】如何防止 Token 被盗用？
 * → 方案一：绑定用户 ID
 *   生成 Token 时，将 userId 存入 Redis value
 *   验证时，检查 Token 对应的 userId 是否与当前登录用户一致
 * → 方案二：绑定 IP 地址
 *   生成 Token 时，记录客户端 IP
 *   验证时，检查请求 IP 是否与生成时的 IP 一致
 * → 方案三：HTTPS + Token 短有效期
 *   使用 HTTPS 防止 Token 在传输中被截获
 *   Token 有效期设置较短（5分钟），减少被盗用的窗口
 * → 方案四：Token 绑定设备指纹
 *   生成 Token 时，记录设备指纹（User-Agent + 屏幕分辨率等）
 *   验证时，检查设备指纹是否一致
 *
 * 【面试追问】@Around vs @Before 的区别？
 * → @Before：只能在方法执行前拦截，无法阻止方法执行
 * → @Around：可以决定是否调用 joinPoint.proceed()，更灵活
 * → 幂等场景需要"拦截并阻止执行"，必须用 @Around
 *
 * @author interview-prep
 * @see TokenService
 * @see IdempotentToken
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final TokenService tokenService;

    // ========== 请求头名称 ==========
    // 使用标准的幂等性请求头名称（参考 RFC 草案）
    private static final String IDEMPOTENCY_TOKEN_HEADER = "X-Idempotency-Token";

    // ========== 切点定义 ==========

    /**
     * 【面试考点】切点定义：拦截带有 @IdempotentToken 注解的方法
     *
     * 切点表达式说明：
     *   @annotation(com.interview.microservice.idempotent.IdempotentToken)
     *   → 拦截所有标注了 @IdempotentToken 注解的方法
     *
     * 【对比方案】
     * ❌ 方案一（拦截所有 POST 请求）：
     *    execution(* com.interview..*.*(..)) && @annotation(org.springframework.web.bind.annotation.PostMapping)
     *    → 问题：过于宽泛，会拦截不需要幂等的 POST 请求
     * ✅ 方案二（自定义注解，本方案）：
     *    @annotation(IdempotentToken)
     *    → 优点：精确控制，只拦截需要幂等的方法
     *    → 使用方式：在需要幂等的方法上加 @IdempotentToken 注解
     *
     * 【面试追问】切点表达式有哪些类型？
     * → execution：匹配方法执行（最常用）
     * → @annotation：匹配带有指定注解的方法
     * → @within：匹配带有指定注解的类中的所有方法
     * → within：匹配指定包/类中的所有方法
     * → args：匹配参数类型
     */
    @Pointcut("@annotation(com.interview.microservice.idempotent.IdempotentToken)")
    public void idempotentTokenPointcut() {
        // 切点定义，方法体为空
    }

    /**
     * 【面试考点】@Around 通知：Token 幂等核心逻辑
     *
     * 问题描述：如何在不修改业务代码的情况下，为方法添加幂等保护？
     *
     * 解决思路：
     *   ① 从 HTTP 请求头获取 X-Idempotency-Token
     *   ② 调用 TokenService.validateAndConsumeToken() 原子验证
     *   ③ Token 无效 → 直接返回错误响应，不执行业务逻辑
     *   ④ Token 有效 → 调用 joinPoint.proceed() 执行业务逻辑
     *
     * 【关键设计决策】
     *
     * 1. Token 验证失败时，返回什么？
     *    → 返回 Map（模拟 HTTP 响应体），实际项目中应该返回统一响应对象
     *    → HTTP 状态码建议：409 Conflict（表示请求与当前状态冲突）
     *
     * 2. Token 不存在时（请求头为空），如何处理？
     *    → 方案A：拒绝请求（严格模式，适合支付等核心操作）
     *    → 方案B：放行请求（宽松模式，适合非核心操作）
     *    → 本实现采用方案A（严格模式）
     *
     * 3. 业务执行失败时，Token 是否应该恢复？
     *    → 方案A：不恢复（Token 已消费，用户需要重新获取）
     *    → 方案B：恢复 Token（允许用户重试）
     *    → 本实现采用方案A，因为 Token 消费是原子的，恢复会引入并发问题
     *    → 如果需要允许重试，应该在业务层面处理（如订单状态机）
     *
     * 【面试追问】如果业务执行中抛出异常，Token 已经被消费了怎么办？
     * → Token 已消费，用户需要重新获取 Token 才能重试
     * → 这是 Token 方案的特点：一次性使用，保证不重复执行
     * → 如果需要支持重试，可以在 catch 块中重新生成 Token 并返回给前端
     *
     * @param joinPoint 切入点（被拦截的方法）
     * @return 业务方法的返回值，或错误响应
     * @throws Throwable 业务方法抛出的异常
     */
    @Around("idempotentTokenPointcut()")
    public Object aroundIdempotentToken(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        log.info("【幂等切面】拦截方法: {}", methodName);

        // ========== 第一步：从请求头获取 Token ==========
        String token = extractTokenFromRequest();

        if (token == null || token.isBlank()) {
            log.warn("【幂等切面】请求头 {} 为空，拒绝请求. method={}", IDEMPOTENCY_TOKEN_HEADER, methodName);
            return buildErrorResponse(
                    "MISSING_TOKEN",
                    "请求头 " + IDEMPOTENCY_TOKEN_HEADER + " 不能为空，请先调用 /api/token 获取幂等 Token"
            );
        }

        // ========== 第二步：原子验证并消费 Token ==========
        // 【关键】validateAndConsumeToken 内部使用 Lua 脚本，保证原子性
        boolean tokenValid = tokenService.validateAndConsumeToken(token);

        if (!tokenValid) {
            log.warn("【幂等切面】Token 无效或已消费（重复请求）. method={}, token={}",
                    methodName, maskToken(token));
            return buildErrorResponse(
                    "DUPLICATE_REQUEST",
                    "重复请求：Token 已被使用或已过期，请重新获取 Token"
            );
        }

        // ========== 第三步：Token 有效，执行业务逻辑 ==========
        log.info("【幂等切面】Token 验证通过，执行业务逻辑. method={}", methodName);

        try {
            Object result = joinPoint.proceed();
            log.info("【幂等切面】业务执行成功. method={}", methodName);
            return result;
        } catch (Throwable e) {
            // 业务执行失败，Token 已消费，用户需要重新获取 Token 才能重试
            // 【设计决策】不恢复 Token，因为：
            //   1. 恢复 Token 需要再次写 Redis，引入额外的并发问题
            //   2. 业务失败的原因可能是数据问题，重试也会失败
            //   3. 让用户重新获取 Token，可以触发前端重新检查状态
            log.error("【幂等切面】业务执行失败，Token 已消费（不恢复）. method={}", methodName, e);
            throw e;
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 从 HTTP 请求头中提取 Token
     *
     * 使用 Spring 的 RequestContextHolder 获取当前请求，
     * 无需在方法参数中注入 HttpServletRequest。
     *
     * 【面试追问】RequestContextHolder 的原理？
     * → 使用 ThreadLocal 存储当前线程的请求上下文
     * → Spring MVC 在请求进入时，将 HttpServletRequest 存入 ThreadLocal
     * → 在同一线程的任何地方都可以通过 RequestContextHolder 获取
     * → 注意：异步线程中无法获取（ThreadLocal 不跨线程）
     *
     * @return Token 字符串，如果请求头不存在则返回 null
     */
    private String extractTokenFromRequest() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes == null) {
                log.warn("【幂等切面】无法获取 HttpServletRequest（可能是非 Web 环境调用）");
                return null;
            }

            HttpServletRequest request = attributes.getRequest();
            String token = request.getHeader(IDEMPOTENCY_TOKEN_HEADER);

            log.debug("【幂等切面】从请求头获取 Token. header={}, token={}",
                    IDEMPOTENCY_TOKEN_HEADER, token != null ? maskToken(token) : "null");

            return token;
        } catch (Exception e) {
            log.error("【幂等切面】获取请求头失败", e);
            return null;
        }
    }

    /**
     * 构建错误响应
     *
     * 实际项目中，应该返回统一的响应对象（如 Result<T>）。
     * 这里使用 Map 简化演示，便于理解核心逻辑。
     *
     * 【面试追问】AOP 切面如何返回自定义响应？
     * → @Around 通知可以返回任意对象，替代原方法的返回值
     * → 关键：不调用 joinPoint.proceed()，直接返回自定义对象
     * → 注意：返回类型必须与原方法兼容（或使用 Object 类型）
     *
     * @param code    错误码
     * @param message 错误信息
     * @return 错误响应 Map
     */
    private Map<String, Object> buildErrorResponse(String code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    /**
     * Token 脱敏（日志安全）
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
