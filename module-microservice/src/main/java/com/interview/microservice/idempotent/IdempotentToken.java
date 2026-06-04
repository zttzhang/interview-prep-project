package com.interview.microservice.idempotent;

import java.lang.annotation.*;

/**
 * 【面试考点】Token 幂等注解
 *
 * 标注此注解的方法将被 {@link IdempotentAspect} 拦截，
 * 要求请求头中携带 X-Idempotency-Token，并进行原子验证。
 *
 * 使用示例：
 * <pre>
 * {@code
 * @PostMapping("/order")
 * @IdempotentToken
 * public Result<String> createOrder(@RequestBody OrderRequest request) {
 *     // 业务逻辑
 * }
 * }
 * </pre>
 *
 * 前端调用流程：
 * 1. GET /api/token?userId=xxx  → 获取 Token
 * 2. POST /api/order            → 请求头携带 X-Idempotency-Token: {token}
 *
 * 【与 module-integration @IdempotentAnnotation 的区别】
 * → @IdempotentAnnotation：基于业务 key（SpEL），适合后端内部调用
 * → @IdempotentToken（本注解）：基于 Token，适合前端用户操作
 *
 * @author interview-prep
 * @see IdempotentAspect
 * @see TokenService
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentToken {
    // 无需参数：Token 从请求头 X-Idempotency-Token 自动获取
}
