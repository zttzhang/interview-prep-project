package com.interview.microservice.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【面试考点】TCC（Try-Confirm-Cancel）分布式事务演示
 *
 * 问题描述：
 *   跨服务的分布式事务（如：扣减余额 + 创建订单 + 锁定库存）无法用本地事务保证原子性。
 *   如何在不引入重量级框架的情况下，实现分布式事务的一致性？
 *
 * 解决思路（TCC 模式）：
 *   将每个分布式操作拆分为三个阶段：
 *   ① Try（预留）：检查并预留资源（冻结余额、锁定库存），不真正执行
 *   ② Confirm（确认）：所有 Try 成功后，提交操作（扣减冻结余额、确认订单）
 *   ③ Cancel（取消）：任意 Try 失败后，回滚操作（解冻余额、释放库存）
 *
 * 【对比方案】TCC vs 2PC vs Saga vs 本地消息表
 * ┌──────────────┬──────────────────┬──────────────────┬──────────────────┐
 * │ 方案          │ 一致性            │ 性能              │ 业务侵入性        │
 * ├──────────────┼──────────────────┼──────────────────┼──────────────────┤
 * │ 2PC          │ 强一致            │ 低（锁等待）       │ 低（框架处理）    │
 * │ TCC          │ 强一致            │ 高（无长锁）       │ 高（需实现3接口） │
 * │ Saga         │ 最终一致          │ 高               │ 中（补偿逻辑）    │
 * │ 本地消息表    │ 最终一致          │ 高               │ 低（只加消息表）  │
 * └──────────────┴──────────────────┴──────────────────┴──────────────────┘
 *
 * TCC 优点：
 *   → 无长事务锁，性能好（Try 阶段只是预留，不加数据库锁）
 *   → 强一致性（Confirm/Cancel 保证最终状态一致）
 *   → 适合对一致性要求高的场景（支付、转账）
 *
 * TCC 缺点：
 *   → 业务侵入性强（每个服务都要实现 Try/Confirm/Cancel 三个接口）
 *   → 需要处理空回滚、幂等、悬挂三大问题
 *
 * 【面试追问】TCC 如何处理网络超时？
 * → Try 超时：TCC 框架认为 Try 失败，调用 Cancel 回滚
 * → Confirm 超时：TCC 框架重试 Confirm（所以 Confirm 必须幂等）
 * → Cancel 超时：TCC 框架重试 Cancel（所以 Cancel 必须幂等）
 * → 关键：Confirm/Cancel 必须是幂等的，可以重复调用
 *
 * @author interview-prep
 * @see LocalMessageTable
 */
@Slf4j
@Service
public class TccDemo {

    // ========== 模拟数据库（实际应该是真实的数据库操作）==========
    // 用户余额表：userId -> 余额
    private final Map<String, BigDecimal> balanceMap = new ConcurrentHashMap<>();
    // 冻结余额表：userId -> 冻结金额（Try 阶段写入，Confirm/Cancel 阶段清除）
    private final Map<String, BigDecimal> frozenBalanceMap = new ConcurrentHashMap<>();
    // 库存表：productId -> 库存数量
    private final Map<String, Integer> stockMap = new ConcurrentHashMap<>();
    // 锁定库存表：orderId -> 锁定数量（Try 阶段写入）
    private final Map<String, Integer> lockedStockMap = new ConcurrentHashMap<>();
    // 订单状态表：orderId -> 状态（TRYING/CONFIRMED/CANCELLED）
    private final Map<String, String> orderStatusMap = new ConcurrentHashMap<>();
    // 事务状态表：txId -> 状态（用于解决空回滚和悬挂问题）
    private final Map<String, String> txStatusMap = new ConcurrentHashMap<>();

    // ========== TCC 参与者接口 ==========

    /**
     * 【面试考点】TCC 参与者接口
     *
     * 每个参与分布式事务的服务都必须实现这三个方法。
     * 这是 TCC 业务侵入性强的体现——每个服务都要改造。
     *
     * 【面试追问】为什么要定义这个接口？
     * → 规范化：确保每个参与者都实现了完整的 TCC 语义
     * → 框架集成：TCC 框架（如 ByteTCC、Hmily）通过接口扫描参与者
     */
    public interface TccParticipant {
        /**
         * Try 阶段：检查并预留资源
         * 要求：必须是幂等的（网络重试时可能被调用多次）
         */
        boolean try_(String txId, Object... args);

        /**
         * Confirm 阶段：提交操作
         * 要求：必须是幂等的（框架重试时可能被调用多次）
         * 注意：Confirm 不允许失败（如果失败，框架会一直重试）
         */
        boolean confirm(String txId, Object... args);

        /**
         * Cancel 阶段：回滚操作
         * 要求：必须是幂等的（框架重试时可能被调用多次）
         * 注意：必须处理空回滚（Try 未执行但 Cancel 被调用的情况）
         */
        boolean cancel(String txId, Object... args);
    }

    // ========== 余额服务 TCC 实现 ==========

    /**
     * 【面试考点】Try 阶段：冻结余额
     *
     * 问题描述：
     *   直接扣减余额会导致：如果后续步骤失败，需要回滚余额，
     *   但此时余额已经被扣减，其他并发请求可能已经读到了错误的余额。
     *
     * 解决思路：
     *   Try 阶段只"冻结"余额（从可用余额移到冻结余额），不真正扣减。
     *   这样其他请求看到的可用余额是准确的（已减去冻结部分）。
     *
     * 【对比方案】
     * ❌ 方案一（直接扣减）：balance -= amount
     *    → 问题：后续步骤失败时，需要补回余额，存在并发问题
     * ✅ 方案二（冻结余额，本方案）：
     *    available -= amount; frozen += amount
     *    → 优点：可用余额准确，回滚只需解冻，无并发问题
     *
     * 【面试追问】冻结余额和扣减余额的区别？
     * → 冻结：余额从"可用"变为"冻结"，总余额不变，可用余额减少
     * → 扣减：余额真正减少，总余额减少
     * → TCC 的 Try 阶段做冻结，Confirm 阶段做真正扣减（清除冻结）
     *
     * @param userId 用户ID
     * @param amount 冻结金额
     * @return true=冻结成功，false=余额不足
     */
    public boolean tryDeductBalance(String userId, BigDecimal amount) {
        log.info("【TCC-Try】冻结余额. userId={}, amount={}", userId, amount);

        // ========== 幂等处理：检查是否已经执行过 Try ==========
        // 实际项目中，txId 由 TCC 框架传入，这里简化处理
        String txId = "tx_" + userId + "_" + amount;
        if ("TRYING".equals(txStatusMap.get(txId))) {
            log.info("【TCC-Try】幂等：已执行过 Try，直接返回成功");
            return true;
        }

        // 初始化余额（模拟数据库查询）
        balanceMap.putIfAbsent(userId, new BigDecimal("1000.00"));
        BigDecimal currentBalance = balanceMap.get(userId);

        // 检查余额是否充足
        if (currentBalance.compareTo(amount) < 0) {
            log.warn("【TCC-Try】余额不足. userId={}, balance={}, required={}", userId, currentBalance, amount);
            return false;
        }

        // ========== 核心操作：冻结余额 ==========
        // 从可用余额扣除，加入冻结余额
        balanceMap.put(userId, currentBalance.subtract(amount));
        frozenBalanceMap.merge(userId, amount, BigDecimal::add);

        // 记录事务状态（用于幂等和空回滚判断）
        txStatusMap.put(txId, "TRYING");

        log.info("【TCC-Try】冻结成功. userId={}, 可用余额={}, 冻结余额={}",
                userId, balanceMap.get(userId), frozenBalanceMap.get(userId));
        return true;
    }

    /**
     * 【面试考点】Confirm 阶段：扣减冻结余额
     *
     * 问题描述：所有 Try 都成功后，如何提交操作？
     *
     * 解决思路：
     *   Confirm 阶段将冻结余额清零（真正扣减）。
     *   此时冻结余额已经不在可用余额中，所以只需清除冻结记录即可。
     *
     * 【关键】Confirm 必须是幂等的！
     *   原因：网络超时时，TCC 框架会重试 Confirm
     *   实现：检查冻结余额是否存在，不存在则说明已经 Confirm 过了
     *
     * 【面试追问】Confirm 失败了怎么办？
     * → TCC 框架会一直重试 Confirm，直到成功
     * → 所以 Confirm 的逻辑必须保证最终能成功（不能有不可重试的错误）
     * → 如果 Confirm 永远失败，需要人工介入（这是 TCC 的局限性）
     *
     * @param userId 用户ID
     * @param amount 扣减金额
     * @return true=扣减成功
     */
    public boolean confirmDeductBalance(String userId, BigDecimal amount) {
        log.info("【TCC-Confirm】扣减冻结余额. userId={}, amount={}", userId, amount);

        // ========== 幂等处理：检查冻结余额是否存在 ==========
        BigDecimal frozenAmount = frozenBalanceMap.get(userId);
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.info("【TCC-Confirm】幂等：冻结余额已清零，说明已经 Confirm 过了");
            return true;
        }

        // ========== 核心操作：清除冻结余额（真正扣减）==========
        frozenBalanceMap.put(userId, frozenAmount.subtract(amount));
        if (frozenBalanceMap.get(userId).compareTo(BigDecimal.ZERO) <= 0) {
            frozenBalanceMap.remove(userId);
        }

        log.info("【TCC-Confirm】扣减成功. userId={}, 剩余冻结余额={}",
                userId, frozenBalanceMap.getOrDefault(userId, BigDecimal.ZERO));
        return true;
    }

    /**
     * 【面试考点】Cancel 阶段：解冻余额
     *
     * 问题描述：任意 Try 失败后，如何回滚已冻结的余额？
     *
     * 解决思路：
     *   Cancel 阶段将冻结余额归还到可用余额。
     *
     * 【三大问题】
     *
     * 1. 空回滚（Empty Rollback）：
     *    场景：Try 请求超时，TCC 框架调用 Cancel，但 Try 实际上还没执行
     *    解决：Cancel 时检查事务状态，如果没有 Try 记录，直接返回成功（空回滚）
     *
     * 2. 幂等（Idempotent）：
     *    场景：Cancel 请求超时，TCC 框架重试 Cancel
     *    解决：Cancel 时检查冻结余额是否存在，不存在则说明已经 Cancel 过了
     *
     * 3. 悬挂（Suspension）：
     *    场景：Cancel 先于 Try 执行（网络乱序）
     *    解决：Cancel 时记录"已取消"状态，Try 执行时检查此状态，若已取消则拒绝 Try
     *
     * 【面试追问】空回滚和悬挂的区别？
     * → 空回滚：Try 未执行，Cancel 被调用（Try 请求丢失）
     * → 悬挂：Cancel 先于 Try 执行（网络乱序，Cancel 先到达）
     * → 两者都需要通过事务状态表来解决
     *
     * @param userId 用户ID
     * @param amount 解冻金额
     * @return true=解冻成功
     */
    public boolean cancelDeductBalance(String userId, BigDecimal amount) {
        log.info("【TCC-Cancel】解冻余额. userId={}, amount={}", userId, amount);

        String txId = "tx_" + userId + "_" + amount;

        // ========== 空回滚处理：Try 未执行，直接返回成功 ==========
        if (!txStatusMap.containsKey(txId)) {
            log.info("【TCC-Cancel】空回滚：Try 未执行，直接返回成功（记录空回滚状态防止悬挂）");
            // 【关键】记录空回滚状态，防止后续 Try 请求到达时执行（悬挂问题）
            txStatusMap.put(txId, "CANCELLED");
            return true;
        }

        // ========== 幂等处理：已经 Cancel 过了 ==========
        if ("CANCELLED".equals(txStatusMap.get(txId))) {
            log.info("【TCC-Cancel】幂等：已执行过 Cancel，直接返回成功");
            return true;
        }

        // ========== 核心操作：解冻余额 ==========
        BigDecimal frozenAmount = frozenBalanceMap.getOrDefault(userId, BigDecimal.ZERO);
        if (frozenAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 将冻结余额归还到可用余额
            balanceMap.merge(userId, amount, BigDecimal::add);
            frozenBalanceMap.put(userId, frozenAmount.subtract(amount));
            if (frozenBalanceMap.get(userId).compareTo(BigDecimal.ZERO) <= 0) {
                frozenBalanceMap.remove(userId);
            }
        }

        // 更新事务状态
        txStatusMap.put(txId, "CANCELLED");

        log.info("【TCC-Cancel】解冻成功. userId={}, 可用余额={}", userId, balanceMap.get(userId));
        return true;
    }

    // ========== 订单服务 TCC 实现 ==========

    /**
     * 【面试考点】Try 阶段：锁定库存
     *
     * 问题描述：如何防止超卖？
     *
     * 解决思路：
     *   Try 阶段锁定库存（从可用库存移到锁定库存），不真正扣减。
     *   这样可以防止超卖，同时保证回滚时能准确释放库存。
     *
     * @param orderId   订单ID
     * @param productId 商品ID
     * @param quantity  锁定数量
     * @return true=锁定成功，false=库存不足
     */
    public boolean tryCreateOrder(String orderId, String productId, int quantity) {
        log.info("【TCC-Try】锁定库存. orderId={}, productId={}, quantity={}", orderId, productId, quantity);

        // 幂等处理
        if (orderStatusMap.containsKey(orderId)) {
            log.info("【TCC-Try】幂等：订单已存在，直接返回成功");
            return true;
        }

        // 初始化库存（模拟数据库查询）
        stockMap.putIfAbsent(productId, 100);
        int currentStock = stockMap.get(productId);

        // 检查库存是否充足
        if (currentStock < quantity) {
            log.warn("【TCC-Try】库存不足. productId={}, stock={}, required={}", productId, currentStock, quantity);
            return false;
        }

        // ========== 核心操作：锁定库存 ==========
        stockMap.put(productId, currentStock - quantity);
        lockedStockMap.put(orderId, quantity);
        orderStatusMap.put(orderId, "TRYING");

        log.info("【TCC-Try】锁定成功. productId={}, 可用库存={}, 锁定库存={}",
                productId, stockMap.get(productId), quantity);
        return true;
    }

    /**
     * 【面试考点】Confirm 阶段：确认订单
     *
     * Confirm 阶段将订单状态从 TRYING 改为 CONFIRMED，
     * 并清除锁定库存记录（库存已经在 Try 阶段扣减了）。
     *
     * @param orderId 订单ID
     * @return true=确认成功
     */
    public boolean confirmCreateOrder(String orderId) {
        log.info("【TCC-Confirm】确认订单. orderId={}", orderId);

        // 幂等处理
        if ("CONFIRMED".equals(orderStatusMap.get(orderId))) {
            log.info("【TCC-Confirm】幂等：订单已确认");
            return true;
        }

        // ========== 核心操作：确认订单 ==========
        orderStatusMap.put(orderId, "CONFIRMED");
        lockedStockMap.remove(orderId);  // 清除锁定记录（库存已真正扣减）

        log.info("【TCC-Confirm】订单确认成功. orderId={}", orderId);
        return true;
    }

    /**
     * 【面试考点】Cancel 阶段：释放库存
     *
     * Cancel 阶段将锁定的库存归还到可用库存，
     * 并将订单状态改为 CANCELLED。
     *
     * @param orderId 订单ID
     * @return true=释放成功
     */
    public boolean cancelCreateOrder(String orderId) {
        log.info("【TCC-Cancel】释放库存. orderId={}", orderId);

        // 空回滚处理
        if (!orderStatusMap.containsKey(orderId)) {
            log.info("【TCC-Cancel】空回滚：订单不存在，直接返回成功");
            orderStatusMap.put(orderId, "CANCELLED");
            return true;
        }

        // 幂等处理
        if ("CANCELLED".equals(orderStatusMap.get(orderId))) {
            log.info("【TCC-Cancel】幂等：订单已取消");
            return true;
        }

        // ========== 核心操作：释放锁定库存 ==========
        Integer lockedQty = lockedStockMap.remove(orderId);
        if (lockedQty != null) {
            // 从订单状态中找到 productId（实际项目中应该从数据库查询）
            // 这里简化处理，假设 productId = "P001"
            stockMap.merge("P001", lockedQty, Integer::sum);
            log.info("【TCC-Cancel】释放库存成功. orderId={}, 释放数量={}", orderId, lockedQty);
        }

        orderStatusMap.put(orderId, "CANCELLED");
        return true;
    }

    // ========== 完整 TCC 流程演示 ==========

    /**
     * 【面试考点】演示完整 TCC 事务流程
     *
     * 问题描述：如何协调多个服务的 TCC 操作？
     *
     * 解决思路：
     *   由 TCC 协调者（Coordinator）统一管理事务状态：
     *   ① 调用所有参与者的 Try 方法
     *   ② 所有 Try 成功 → 调用所有参与者的 Confirm 方法
     *   ③ 任意 Try 失败 → 调用所有已成功 Try 的参与者的 Cancel 方法
     *
     * 【面试追问】TCC 框架（如 Hmily、ByteTCC）做了什么？
     * → 自动生成全局事务 ID（txId）
     * → 记录每个参与者的 Try/Confirm/Cancel 状态到数据库
     * → 网络超时时自动重试 Confirm/Cancel
     * → 提供注解（@TccTransaction）简化开发
     */
    public void executeTccTransaction() {
        log.info("========== TCC 事务演示开始 ==========");

        String userId = "user_001";
        String orderId = "order_" + System.currentTimeMillis();
        String productId = "P001";
        BigDecimal amount = new BigDecimal("100.00");
        int quantity = 2;

        // ========== 场景一：成功场景 ==========
        log.info("--- 场景一：成功场景 ---");
        boolean balanceTryResult = tryDeductBalance(userId, amount);
        boolean orderTryResult = tryCreateOrder(orderId, productId, quantity);

        if (balanceTryResult && orderTryResult) {
            // 所有 Try 成功 → 执行 Confirm
            log.info("【TCC】所有 Try 成功，执行 Confirm 阶段");
            confirmDeductBalance(userId, amount);
            confirmCreateOrder(orderId);
            log.info("【TCC】事务提交成功！");
        } else {
            // 任意 Try 失败 → 执行 Cancel
            log.info("【TCC】Try 失败，执行 Cancel 阶段");
            if (balanceTryResult) {
                cancelDeductBalance(userId, amount);  // 只回滚已成功的 Try
            }
            if (orderTryResult) {
                cancelCreateOrder(orderId);
            }
            log.info("【TCC】事务回滚完成！");
        }

        // ========== 场景二：失败回滚场景 ==========
        log.info("--- 场景二：失败回滚场景（余额不足）---");
        String orderId2 = "order_" + (System.currentTimeMillis() + 1);
        BigDecimal largeAmount = new BigDecimal("99999.00");  // 超过余额

        boolean balanceTry2 = tryDeductBalance(userId, largeAmount);  // 会失败
        boolean orderTry2 = tryCreateOrder(orderId2, productId, quantity);

        if (balanceTry2 && orderTry2) {
            confirmDeductBalance(userId, largeAmount);
            confirmCreateOrder(orderId2);
        } else {
            log.info("【TCC】Try 失败（余额不足），执行 Cancel 回滚");
            if (balanceTry2) cancelDeductBalance(userId, largeAmount);
            if (orderTry2) cancelCreateOrder(orderId2);
            log.info("【TCC】回滚完成，资源已释放");
        }

        log.info("========== TCC 事务演示结束 ==========");
        log.info("");
        log.info("【TCC 三大问题总结】");
        log.info("1. 空回滚：Try 未执行，Cancel 被调用 → 检查事务状态表，直接返回成功");
        log.info("2. 幂等：Confirm/Cancel 被重复调用 → 检查操作是否已执行，已执行则直接返回");
        log.info("3. 悬挂：Cancel 先于 Try 执行 → 空回滚时记录状态，Try 执行时检查状态");
    }
}
