package com.interview.integration.idempotent;

import java.lang.annotation.*;

/**
 * 【面试考点】接口幂等性注解 - 基于 Redis 的 Token 方案
 *
 * 问题描述：
 *   用户重复点击提交按钮、网络重试、消息重复投递等场景，
 *   会导致同一操作被执行多次，产生脏数据（如重复下单、重复扣款）。
 *
 * 解决思路（Token 方案）：
 *   ① 前端请求接口前，先调用 /token 接口获取唯一 token
 *   ② 前端提交时，将 token 放在请求头（X-Idempotent-Token）
 *   ③ 后端 AOP 拦截，原子操作验证并删除 token（SET NX + DEL）
 *   ④ token 存在 → 执行业务；token 不存在 → 拒绝（重复提交）
 *
 * 【对比方案】
 * ❌ 方案一（数据库唯一索引）：
 *    → 优点：简单可靠；缺点：依赖 DB，高并发下有性能问题
 * ❌ 方案二（状态机）：
 *    → 优点：业务语义清晰；缺点：需要设计状态流转，复杂度高
 * ✅ 方案三（Redis Token，本方案）：
 *    → 优点：高性能，无侵入，通用性强
 *    → 缺点：依赖 Redis，需要前端配合
 * ✅ 方案四（AOP + SpEL key，本注解）：
 *    → 优点：基于业务 key（如 userId+orderId），不需要前端获取 token
 *    → 适用：后端内部调用、MQ 消费者等场景
 *
 * 【面试追问】
 * Q: 幂等 vs 防重复提交的区别？
 * A: 幂等（Idempotent）：同一操作执行多次，结果与执行一次相同（关注结果）
 *    防重复提交（Anti-Duplicate）：阻止同一请求被处理多次（关注过程）
 *    区别：幂等允许重复执行但结果一致；防重复提交直接拒绝重复请求
 *    例子：GET 请求天然幂等；POST 下单需要防重复提交
 *
 * Q: 如何用 SpEL 表达式指定幂等 key？
 * A: 支持以下格式：
 *    - "#userId"：取方法参数 userId 的值
 *    - "#order.id"：取参数 order 对象的 id 属性
 *    - "#p0"：取第一个参数
 *    - "'prefix:' + #userId"：字符串拼接
 *
 * @author interview-prep
 * @see IdempotentAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentAnnotation {

    /**
     * 幂等 key（支持 SpEL 表达式）
     *
     * 示例：
     *   key = "#userId"                    → 以 userId 为幂等 key
     *   key = "#order.id"                  → 以 order.id 为幂等 key
     *   key = "'createOrder:' + #userId"   → 带前缀的幂等 key
     *
     * 如果为空，则使用 "类名:方法名" 作为 key（适用于无参数的场景）
     */
    String key() default "";

    /**
     * 幂等 key 的过期时间（秒）
     *
     * 【面试考点】过期时间如何设置？
     * 答：根据业务场景决定：
     *   - 表单提交防重：5~60 秒（用户操作间隔）
     *   - 订单创建：5~10 分钟（支付超时时间）
     *   - 消息消费：24 小时（消息重试窗口）
     *
     * 过期时间过短：可能在业务执行期间过期，导致重复执行
     * 过期时间过长：占用 Redis 内存，且失败后无法重试
     */
    long expireSeconds() default 60;

    /**
     * 重复提交时的提示信息
     */
    String message() default "请勿重复提交";
}
