package com.interview.microservice.circuit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】降级处理策略演示
 *
 * 问题描述：
 *   当下游服务不可用（超时、异常、熔断触发）时，如何保证上游服务仍能正常响应？
 *
 * 解决思路：
 *   降级（Fallback）：在服务不可用时，返回兜底数据，保证系统可用性。
 *   熔断（Circuit Breaker）：当失败率超过阈值，自动切断请求，防止故障蔓延。
 *   两者关系：熔断是触发降级的一种方式，降级是熔断后的处理策略。
 *
 * 【对比方案】
 * ❌ 方案一（不做降级）：
 *    → 下游超时 → 上游线程阻塞 → 线程池耗尽 → 雪崩
 * ✅ 方案二（返回默认值）：
 *    → 简单快速，适合非核心数据（如用户头像、推荐列表）
 * ✅ 方案三（返回缓存数据）：
 *    → 数据相对准确，适合读多写少场景（如商品详情）
 * ✅ 方案四（返回错误提示）：
 *    → 明确告知用户，适合核心操作（如支付、下单）
 *
 * 【面试追问】熔断和降级的区别？
 * → 熔断（Circuit Breaker）：是一种保护机制，当失败率超过阈值时，
 *   自动切断对下游服务的调用，防止故障蔓延（雪崩效应）。
 *   熔断器有三个状态：CLOSED → OPEN → HALF_OPEN
 * → 降级（Fallback）：是一种兜底策略，当服务不可用时，
 *   返回预设的默认值/缓存数据/错误提示，保证系统可用性。
 * → 关系：熔断是触发降级的一种方式，但降级不一定由熔断触发，
 *   也可以由超时、异常、手动开关触发。
 *
 * 【Sentinel vs Hystrix vs Resilience4j 对比】
 * ┌─────────────────┬──────────────┬──────────────┬──────────────────┐
 * │ 特性             │ Sentinel     │ Hystrix      │ Resilience4j     │
 * ├─────────────────┼──────────────┼──────────────┼──────────────────┤
 * │ 维护状态         │ 阿里活跃维护  │ Netflix停维  │ 活跃维护          │
 * │ 熔断策略         │ 多种（RT/异常）│ 异常比例     │ 多种              │
 * │ 限流             │ ✅ 支持       │ ❌ 不支持    │ ✅ 支持           │
 * │ 控制台           │ ✅ 实时监控   │ Hystrix Dashboard│ ❌ 无内置    │
 * │ Spring Cloud集成 │ ✅ 原生支持   │ ✅ 支持      │ ✅ 支持           │
 * │ 性能             │ 高           │ 中           │ 高                │
 * └─────────────────┴──────────────┴──────────────┴──────────────────┘
 * → 推荐：国内项目用 Sentinel（阿里系，文档丰富）；
 *         国际项目用 Resilience4j（Hystrix 官方推荐替代品）
 *
 * @author interview-prep
 * @see SentinelDemo
 */
@Slf4j
@Component
public class FallbackHandler {

    // ========== 熔断器三种状态说明 ==========
    // CLOSED（关闭/正常）：请求正常通过，统计失败率
    // OPEN（打开/熔断）：直接拒绝请求，不调用下游，立即返回降级结果
    // HALF_OPEN（半开/探测）：熔断一段时间后，放行少量请求探测下游是否恢复
    //   → 探测成功 → 回到 CLOSED 状态
    //   → 探测失败 → 继续 OPEN 状态，重置计时器

    // ========== 降级策略对比 ==========
    // ✅ 策略一（返回默认值）：最简单，适合非核心数据
    //    优点：无依赖，响应快；缺点：数据不准确
    // ✅ 策略二（返回缓存数据）：数据相对准确，适合读多写少
    //    优点：用户体验好；缺点：缓存可能过期，需要维护缓存
    // ✅ 策略三（返回错误提示）：明确告知用户，适合核心操作
    //    优点：语义清晰；缺点：用户体验差

    /**
     * 【面试考点】用户服务降级 - 返回默认用户信息
     *
     * 问题描述：用户服务不可用时，如何保证依赖用户信息的功能不崩溃？
     *
     * 解决思路：
     *   返回一个"默认用户"对象，包含最基本的信息，
     *   让调用方能够继续运行（降级处理，而非抛出异常）。
     *
     * 【对比方案】
     * ❌ 方案一（直接抛异常）：throw new ServiceUnavailableException()
     *    → 问题：调用方需要处理异常，且用户体验差
     * ✅ 方案二（返回默认值，本方案）：return defaultUser
     *    → 优点：调用方无感知，系统继续运行
     *    → 适用：非核心用户信息（如头像、昵称）
     * ✅ 方案三（返回缓存数据）：return cache.get("user:" + userId)
     *    → 优点：数据更准确；缺点：需要维护缓存
     *
     * 【面试追问】什么情况下不能返回默认值？
     * → 涉及权限校验时（不能用默认用户绕过权限）
     * → 涉及金融操作时（不能用默认余额）
     * → 此时应该返回明确的错误提示，让用户重试
     *
     * @param userId 用户ID
     * @param t      触发降级的异常（可能是超时、熔断等）
     * @return 默认用户信息 Map
     */
    public Map<String, Object> fallbackForUserService(Long userId, Throwable t) {
        log.warn("【降级】用户服务不可用，返回默认用户信息. userId={}, cause={}",
                userId, t != null ? t.getMessage() : "unknown");

        // ========== 降级策略：返回默认值 ==========
        Map<String, Object> defaultUser = new HashMap<>();
        defaultUser.put("userId", userId);
        defaultUser.put("username", "游客_" + userId);
        defaultUser.put("avatar", "https://default-avatar.example.com/default.png");
        defaultUser.put("status", "DEGRADED");  // 标记为降级数据，调用方可据此判断
        defaultUser.put("message", "用户服务暂时不可用，显示默认信息");

        // 【关键】记录降级原因，便于监控告警
        if (t != null) {
            log.error("【降级原因】", t);
        }

        return defaultUser;
    }

    /**
     * 【面试考点】订单服务降级 - 返回空订单
     *
     * 问题描述：订单服务不可用时，查询订单列表应该返回什么？
     *
     * 解决思路：
     *   返回空集合而非 null，避免调用方 NullPointerException。
     *   同时在响应中标记降级状态，让前端可以展示友好提示。
     *
     * 【对比方案】
     * ❌ 方案一（返回 null）：
     *    → 问题：调用方遍历时 NullPointerException
     * ✅ 方案二（返回空集合，本方案）：
     *    → 优点：安全，调用方无需判空
     * ✅ 方案三（返回缓存的订单列表）：
     *    → 优点：用户体验好；缺点：数据可能不是最新的
     *
     * 【面试追问】空集合和 null 的区别在哪里？
     * → null 表示"没有这个对象"，调用方必须判空
     * → 空集合表示"有这个对象，但里面没有数据"，调用方可以直接遍历
     * → 最佳实践：集合类型永远不要返回 null，返回空集合
     *
     * @param orderId 订单ID
     * @param t       触发降级的异常
     * @return 空订单信息 Map
     */
    public Map<String, Object> fallbackForOrderService(Long orderId, Throwable t) {
        log.warn("【降级】订单服务不可用，返回空订单. orderId={}, cause={}",
                orderId, t != null ? t.getMessage() : "unknown");

        // ========== 降级策略：返回空订单（而非 null）==========
        Map<String, Object> emptyOrder = new HashMap<>();
        emptyOrder.put("orderId", orderId);
        emptyOrder.put("items", Collections.emptyList());  // 空集合，非 null
        emptyOrder.put("totalAmount", 0);
        emptyOrder.put("status", "UNKNOWN");
        emptyOrder.put("degraded", true);  // 标记为降级数据
        emptyOrder.put("message", "订单服务暂时不可用，请稍后重试");

        return emptyOrder;
    }

    /**
     * 【面试考点】支付服务降级 - 返回"处理中"状态
     *
     * 问题描述：支付服务不可用时，如何处理？
     *   支付是核心操作，不能随意返回默认值（可能导致重复支付或漏支付）。
     *
     * 解决思路：
     *   返回"处理中"状态，让用户等待，后续通过异步查询确认结果。
     *   这是金融场景的标准做法：宁可让用户等待，也不能给出错误的结果。
     *
     * 【对比方案】
     * ❌ 方案一（返回支付成功）：
     *    → 问题：实际可能未支付，导致资损
     * ❌ 方案二（返回支付失败）：
     *    → 问题：实际可能已支付，导致重复支付
     * ✅ 方案三（返回处理中，本方案）：
     *    → 优点：安全，让用户等待异步结果
     *    → 适用：支付、转账等金融核心操作
     *
     * 【面试追问】支付场景下如何保证最终一致性？
     * → 支付结果通过异步回调（Webhook）通知
     * → 前端轮询查询支付状态
     * → 超时后通过对账系统核对
     *
     * @param orderId 订单号
     * @param t       触发降级的异常
     * @return 处理中状态 Map
     */
    public Map<String, Object> fallbackForPaymentService(String orderId, Throwable t) {
        log.warn("【降级】支付服务不可用，返回处理中状态. orderId={}, cause={}",
                orderId, t != null ? t.getMessage() : "unknown");

        // ========== 降级策略：返回"处理中"（金融场景标准做法）==========
        Map<String, Object> processingResult = new HashMap<>();
        processingResult.put("orderId", orderId);
        processingResult.put("paymentStatus", "PROCESSING");  // 处理中，非成功/失败
        processingResult.put("degraded", true);
        processingResult.put("message", "支付处理中，请勿重复提交，稍后查看支付结果");
        processingResult.put("queryUrl", "/api/payment/query?orderId=" + orderId);  // 提供查询地址

        // 【关键】记录降级日志，便于后续对账
        log.error("【支付降级告警】orderId={} 支付服务不可用，需要人工核查", orderId, t);

        return processingResult;
    }

    /**
     * 【面试考点】熔断器状态机演示
     *
     * 问题描述：熔断器的三个状态是如何流转的？
     *
     * 解决思路：
     *   熔断器是一个有限状态机（FSM），有三个状态：
     *   CLOSED → OPEN → HALF_OPEN → CLOSED（恢复）
     *                            → OPEN（继续熔断）
     *
     * 状态流转规则：
     *   CLOSED → OPEN：失败率超过阈值（如 50%）或失败次数超过阈值
     *   OPEN → HALF_OPEN：熔断超时后（如 60秒），自动进入半开状态
     *   HALF_OPEN → CLOSED：探测请求成功，恢复正常
     *   HALF_OPEN → OPEN：探测请求失败，继续熔断
     *
     * 【对比方案】
     * ❌ 方案一（简单重试）：
     *    → 问题：下游已经崩溃，重试只会加重负担（雪崩效应）
     * ✅ 方案二（熔断器，本方案）：
     *    → 优点：快速失败，保护下游；定期探测，自动恢复
     *
     * 【面试追问】为什么需要 HALF_OPEN 状态？
     * → 如果没有 HALF_OPEN，熔断后永远不会自动恢复，需要人工干预
     * → HALF_OPEN 允许少量请求探测下游是否恢复，实现自动恢复
     * → 探测时只放行一个请求（而非全部），避免下游刚恢复就被大量请求压垮
     *
     * 【面试追问】熔断阈值如何设置？
     * → 失败率阈值：通常 50%（一半请求失败就熔断）
     * → 最小请求数：至少 N 个请求后才统计失败率（避免样本太少误判）
     * → 熔断时间：通常 30-60 秒（给下游足够的恢复时间）
     */
    public void circuitBreakerDemo() {
        log.info("========== 熔断器状态机演示 ==========");

        // ========== 状态一：CLOSED（正常状态）==========
        log.info("【CLOSED 状态】熔断器关闭，请求正常通过");
        log.info("  → 统计最近 10 次请求的失败率");
        log.info("  → 失败率 < 50%：继续 CLOSED 状态");
        log.info("  → 失败率 >= 50%：触发熔断，进入 OPEN 状态");

        // 模拟失败率超过阈值
        simulateHighFailureRate();

        // ========== 状态二：OPEN（熔断状态）==========
        log.info("【OPEN 状态】熔断器打开，直接拒绝请求");
        log.info("  → 所有请求立即返回降级结果（不调用下游）");
        log.info("  → 等待 60 秒后，进入 HALF_OPEN 状态");
        log.info("  → 优点：快速失败，保护下游服务，释放线程资源");

        // 模拟等待熔断超时
        log.info("  → 等待熔断超时（60秒）...");

        // ========== 状态三：HALF_OPEN（半开探测状态）==========
        log.info("【HALF_OPEN 状态】熔断器半开，放行一个探测请求");
        log.info("  → 放行 1 个请求，调用下游服务");
        log.info("  → 探测成功 → 恢复 CLOSED 状态，恢复正常流量");
        log.info("  → 探测失败 → 回到 OPEN 状态，重置熔断计时器");

        // 模拟探测成功
        boolean probeSuccess = simulateProbeRequest();
        if (probeSuccess) {
            log.info("  → 探测成功！熔断器恢复 CLOSED 状态");
        } else {
            log.info("  → 探测失败！熔断器继续 OPEN 状态");
        }

        log.info("========== 状态机演示结束 ==========");
        log.info("");
        log.info("【总结】熔断器状态流转：");
        log.info("  CLOSED ──(失败率超阈值)──> OPEN");
        log.info("  OPEN   ──(超时60s)──────> HALF_OPEN");
        log.info("  HALF_OPEN ──(探测成功)──> CLOSED");
        log.info("  HALF_OPEN ──(探测失败)──> OPEN");
    }

    // ========== 私有辅助方法 ==========

    /**
     * 模拟高失败率场景
     */
    private void simulateHighFailureRate() {
        log.info("  → 模拟：连续 6 次请求失败（失败率 60% > 阈值 50%）");
        log.info("  → 触发熔断！进入 OPEN 状态");
    }

    /**
     * 模拟探测请求
     *
     * @return true=探测成功，false=探测失败
     */
    private boolean simulateProbeRequest() {
        // 模拟下游服务已恢复
        log.info("  → 发送探测请求到下游服务...");
        return true; // 假设探测成功
    }
}
