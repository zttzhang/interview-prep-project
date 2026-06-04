package com.interview.microservice.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【面试考点】Saga 补偿事务演示（编排模式）
 *
 * 问题描述：
 *   跨多个微服务的长事务（如：创建订单 → 扣减库存 → 扣减余额 → 发送通知），
 *   如何在某个步骤失败时，回滚已执行的步骤？
 *
 * 解决思路（Saga 模式）：
 *   将长事务拆分为一系列本地事务，每个本地事务都有对应的补偿操作。
 *   如果某个步骤失败，按逆序执行已成功步骤的补偿操作。
 *
 * 【Saga 两种模式对比】
 *
 * ✅ 编排模式（Orchestration，本演示）：
 *    → 有一个中央协调者（Orchestrator）统一管理事务流程
 *    → 协调者按顺序调用各服务，并在失败时触发补偿
 *    → 优点：流程清晰，易于监控和调试
 *    → 缺点：协调者是单点，可能成为瓶颈
 *    → 适用：复杂业务流程，需要集中管控
 *
 * ❌ 协同模式（Choreography）：
 *    → 没有中央协调者，各服务通过事件（MQ）相互触发
 *    → 服务A完成后发布事件，服务B监听事件并执行
 *    → 优点：去中心化，服务解耦
 *    → 缺点：流程分散，难以追踪整体状态，调试困难
 *    → 适用：简单业务流程，服务间松耦合
 *
 * 【Saga vs TCC 对比】
 * ┌──────────────┬──────────────────────┬──────────────────────┐
 * │ 特性          │ Saga                 │ TCC                  │
 * ├──────────────┼──────────────────────┼──────────────────────┤
 * │ 一致性        │ 最终一致性            │ 强一致性              │
 * │ 隔离性        │ 差（中间状态可见）     │ 好（冻结资源不可见）   │
 * │ 业务侵入性    │ 中（需实现补偿逻辑）   │ 高（需实现3个接口）    │
 * │ 性能          │ 高                   │ 高                   │
 * │ 适用场景      │ 长流程、最终一致       │ 短流程、强一致         │
 * └──────────────┴──────────────────────┴──────────────────────┘
 *
 * 【Saga 的问题：隔离性差】
 * → 中间状态可见：订单已创建但余额还未扣减，其他服务可能读到不一致的数据
 * → 解决方案：语义锁（Semantic Lock）—— 在中间状态加标记，其他服务检查标记
 *
 * 【面试追问】Saga 如何保证补偿操作一定执行？
 * → 本地消息表 + 重试：将补偿操作记录到本地消息表，定时任务重试
 * → 幂等补偿：补偿操作必须是幂等的，重试不会产生副作用
 * → 死信队列：多次重试失败后，进入死信队列，人工处理
 *
 * @author interview-prep
 * @see TccDemo
 * @see LocalMessageTable
 */
@Slf4j
@Service
public class SagaDemo {

    // ========== 模拟数据库 ==========
    private final Map<String, String> orderStatusMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> stockMap = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> balanceMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> notificationMap = new ConcurrentHashMap<>();

    // ========== Saga 步骤接口 ==========

    /**
     * 【面试考点】Saga 步骤接口
     *
     * 每个步骤包含两个操作：
     * - execute()：正向操作（业务逻辑）
     * - compensate()：补偿操作（回滚逻辑）
     *
     * 【关键要求】
     * 1. 补偿操作必须是幂等的（可能被重复调用）
     * 2. 补偿操作必须最终成功（不能有不可重试的错误）
     * 3. 补偿操作的语义是"撤销"，而非"反向操作"
     *    例如：扣减余额的补偿是"退款"，而非"加回余额"（语义不同）
     *
     * 【面试追问】补偿操作和反向操作的区别？
     * → 反向操作：数学上的逆操作（加法的逆是减法）
     * → 补偿操作：业务语义上的撤销（下单的补偿是取消订单，而非删除订单记录）
     * → 区别：补偿操作需要保留操作记录（用于审计），反向操作可能直接删除
     */
    public interface SagaStep {
        /**
         * 执行正向操作
         *
         * @return true=成功，false=失败（触发补偿）
         */
        boolean execute();

        /**
         * 执行补偿操作（回滚）
         * 必须是幂等的，可以被重复调用
         */
        void compensate();

        /**
         * 步骤名称（用于日志）
         */
        String getName();
    }

    // ========== 步骤一：创建订单 ==========

    /**
     * 【面试考点】步骤1正向操作：创建订单
     *
     * 问题描述：创建订单后，如果后续步骤失败，如何撤销订单？
     *
     * 解决思路：
     *   创建订单时，将订单状态设为 PENDING（待确认），
     *   而非直接设为 CONFIRMED（已确认）。
     *   这样即使后续步骤失败，补偿操作只需将状态改为 CANCELLED。
     *
     * 【Saga 隔离性问题】
     *   订单创建后（PENDING 状态），其他服务可能查询到这个订单。
     *   这就是 Saga 隔离性差的体现——中间状态对外可见。
     *   解决方案：语义锁（订单状态为 PENDING 时，其他操作拒绝处理）
     *
     * @param orderId 订单ID
     * @return true=创建成功
     */
    public boolean createOrder(String orderId) {
        log.info("【Saga-Step1】创建订单. orderId={}", orderId);

        // 幂等处理
        if (orderStatusMap.containsKey(orderId)) {
            log.info("【Saga-Step1】幂等：订单已存在");
            return true;
        }

        // ========== 核心操作：创建订单（PENDING 状态）==========
        // 注意：使用 PENDING 而非 CONFIRMED，体现 Saga 的中间状态
        orderStatusMap.put(orderId, "PENDING");
        log.info("【Saga-Step1】订单创建成功（PENDING 状态）. orderId={}", orderId);
        return true;
    }

    /**
     * 【面试考点】步骤1补偿操作：取消订单
     *
     * 补偿操作将订单状态改为 CANCELLED，而非删除订单记录。
     * 原因：保留记录用于审计和问题排查。
     *
     * 【幂等实现】
     *   检查订单状态，如果已经是 CANCELLED，直接返回（幂等）。
     *
     * @param orderId 订单ID
     */
    public void cancelOrder(String orderId) {
        log.info("【Saga-Compensate1】取消订单. orderId={}", orderId);

        // 幂等处理
        if ("CANCELLED".equals(orderStatusMap.get(orderId))) {
            log.info("【Saga-Compensate1】幂等：订单已取消");
            return;
        }

        // 空补偿处理（订单不存在，说明正向操作未执行）
        if (!orderStatusMap.containsKey(orderId)) {
            log.info("【Saga-Compensate1】空补偿：订单不存在，无需取消");
            return;
        }

        orderStatusMap.put(orderId, "CANCELLED");
        log.info("【Saga-Compensate1】订单已取消. orderId={}", orderId);
    }

    // ========== 步骤二：扣减库存 ==========

    /**
     * 【面试考点】步骤2正向操作：扣减库存
     *
     * @param productId 商品ID
     * @param qty       扣减数量
     * @return true=扣减成功，false=库存不足
     */
    public boolean deductStock(String productId, int qty) {
        log.info("【Saga-Step2】扣减库存. productId={}, qty={}", productId, qty);

        stockMap.putIfAbsent(productId, 100);
        int currentStock = stockMap.get(productId);

        if (currentStock < qty) {
            log.warn("【Saga-Step2】库存不足. productId={}, stock={}, required={}", productId, currentStock, qty);
            return false;
        }

        stockMap.put(productId, currentStock - qty);
        log.info("【Saga-Step2】库存扣减成功. productId={}, 剩余库存={}", productId, stockMap.get(productId));
        return true;
    }

    /**
     * 【面试考点】步骤2补偿操作：恢复库存
     *
     * 【幂等实现】
     *   库存恢复操作本身是幂等的（加法操作），
     *   但需要防止重复恢复（多次补偿导致库存超出原始值）。
     *   解决方案：记录补偿状态，已补偿则跳过。
     *
     * 【面试追问】库存恢复和库存增加的区别？
     * → 库存恢复：是补偿操作，恢复到扣减前的状态
     * → 库存增加：是正常业务操作（如采购入库）
     * → 区别：补偿操作需要幂等，且只能恢复本次扣减的数量
     *
     * @param productId 商品ID
     * @param qty       恢复数量
     */
    public void restoreStock(String productId, int qty) {
        log.info("【Saga-Compensate2】恢复库存. productId={}, qty={}", productId, qty);

        stockMap.merge(productId, qty, Integer::sum);
        log.info("【Saga-Compensate2】库存恢复成功. productId={}, 当前库存={}", productId, stockMap.get(productId));
    }

    // ========== 步骤三：扣减余额 ==========

    /**
     * 【面试考点】步骤3正向操作：扣减余额
     *
     * @param userId 用户ID
     * @param amount 扣减金额
     * @return true=扣减成功，false=余额不足
     */
    public boolean deductBalance(String userId, BigDecimal amount) {
        log.info("【Saga-Step3】扣减余额. userId={}, amount={}", userId, amount);

        balanceMap.putIfAbsent(userId, new BigDecimal("500.00"));
        BigDecimal currentBalance = balanceMap.get(userId);

        if (currentBalance.compareTo(amount) < 0) {
            log.warn("【Saga-Step3】余额不足. userId={}, balance={}, required={}", userId, currentBalance, amount);
            return false;
        }

        balanceMap.put(userId, currentBalance.subtract(amount));
        log.info("【Saga-Step3】余额扣减成功. userId={}, 剩余余额={}", userId, balanceMap.get(userId));
        return true;
    }

    /**
     * 【面试考点】步骤3补偿操作：退款
     *
     * 注意：补偿操作是"退款"，而非简单的"加回余额"。
     * 语义上，退款需要记录退款流水，便于用户查询和审计。
     * 这里简化处理，实际项目中应该插入退款记录。
     *
     * @param userId 用户ID
     * @param amount 退款金额
     */
    public void refundBalance(String userId, BigDecimal amount) {
        log.info("【Saga-Compensate3】退款. userId={}, amount={}", userId, amount);

        // ========== 核心操作：退款（补偿）==========
        balanceMap.merge(userId, amount, BigDecimal::add);

        // 实际项目中还需要：
        // 1. 插入退款流水记录
        // 2. 发送退款通知
        // 3. 更新订单退款状态
        log.info("【Saga-Compensate3】退款成功. userId={}, 当前余额={}", userId, balanceMap.get(userId));
    }

    // ========== 步骤四：发送通知 ==========

    /**
     * 【面试考点】步骤4正向操作：发送通知
     *
     * 通知是最后一步，通常不需要补偿（通知已发出无法撤回）。
     * 但如果通知发送失败，可以选择：
     * 1. 忽略（通知不是核心操作）
     * 2. 重试（通知服务不可用时）
     * 3. 记录失败，后续补发
     *
     * @param userId 用户ID
     * @return true=发送成功
     */
    public boolean sendNotification(String userId) {
        log.info("【Saga-Step4】发送通知. userId={}", userId);

        // 模拟通知发送（实际是调用通知服务）
        notificationMap.put(userId, true);
        log.info("【Saga-Step4】通知发送成功. userId={}", userId);
        return true;
    }

    /**
     * 【面试考点】步骤4补偿操作：取消通知
     *
     * 通知已发出，无法真正撤回。
     * 补偿操作可以发送一条"取消通知"，告知用户操作已取消。
     *
     * 【面试追问】如果通知无法撤回怎么办？
     * → 发送补偿通知（"您的订单已取消"）
     * → 这是 Saga 补偿的常见模式：无法撤销时，发送反向通知
     *
     * @param userId 用户ID
     */
    public void cancelNotification(String userId) {
        log.info("【Saga-Compensate4】发送取消通知. userId={}", userId);

        // 发送取消通知（而非撤回原通知）
        notificationMap.put(userId + "_cancel", true);
        log.info("【Saga-Compensate4】取消通知已发送. userId={}", userId);
    }

    // ========== Saga 执行引擎 ==========

    /**
     * 【面试考点】执行 Saga 事务，失败时逆序补偿
     *
     * 问题描述：如何协调多个步骤，并在失败时正确回滚？
     *
     * 解决思路：
     *   ① 按顺序执行每个步骤
     *   ② 记录已成功执行的步骤
     *   ③ 某步骤失败时，按逆序执行已成功步骤的补偿操作
     *
     * 【关键设计】逆序补偿
     *   为什么要逆序？因为后面的步骤可能依赖前面步骤的结果，
     *   逆序补偿可以保证依赖关系正确处理。
     *   例如：步骤3（扣余额）依赖步骤2（扣库存），
     *   补偿时先补偿步骤3（退款），再补偿步骤2（恢复库存）。
     *
     * 【面试追问】Saga 如何保证补偿操作一定执行？
     * → 方案一：本地消息表 + 定时重试
     *   将补偿操作记录到本地消息表，定时任务扫描并重试失败的补偿
     * → 方案二：MQ 死信队列
     *   补偿操作通过 MQ 发送，失败后进入死信队列，人工处理
     * → 方案三：Saga 框架（如 Seata Saga）
     *   框架自动管理补偿操作的重试和状态
     *
     * 【面试追问】Saga 的隔离性问题如何解决？
     * → 语义锁（Semantic Lock）：在中间状态加标记（如 PENDING），
     *   其他服务读到 PENDING 状态时，拒绝处理或等待
     * → 乐观锁：使用版本号，补偿时检查版本号是否一致
     * → 业务设计：尽量减少中间状态的暴露时间
     *
     * @param steps Saga 步骤列表（按执行顺序）
     */
    public void executeSaga(List<SagaStep> steps) {
        log.info("========== Saga 事务开始，共 {} 个步骤 ==========", steps.size());

        // 记录已成功执行的步骤（用于失败时逆序补偿）
        List<SagaStep> executedSteps = new ArrayList<>();

        // ========== 正向执行阶段 ==========
        for (SagaStep step : steps) {
            log.info("【Saga】执行步骤: {}", step.getName());
            try {
                boolean success = step.execute();
                if (success) {
                    executedSteps.add(step);
                    log.info("【Saga】步骤 {} 执行成功", step.getName());
                } else {
                    // 步骤执行失败，触发补偿
                    log.warn("【Saga】步骤 {} 执行失败，开始逆序补偿", step.getName());
                    compensate(executedSteps);
                    return;
                }
            } catch (Exception e) {
                log.error("【Saga】步骤 {} 执行异常，开始逆序补偿", step.getName(), e);
                compensate(executedSteps);
                return;
            }
        }

        log.info("【Saga】所有步骤执行成功，事务提交完成！");
    }

    /**
     * 【面试考点】逆序执行补偿操作
     *
     * 逆序补偿的原因：
     *   步骤 A → B → C，C 失败时，先补偿 B，再补偿 A。
     *   因为 B 可能依赖 A 的结果，先补偿 B 可以保证 A 的补偿不受影响。
     *
     * 【补偿失败处理】
     *   补偿操作本身也可能失败（如网络超时）。
     *   处理方式：
     *   1. 重试（补偿操作必须是幂等的）
     *   2. 记录到本地消息表，定时重试
     *   3. 告警 + 人工介入
     *
     * @param executedSteps 已成功执行的步骤列表
     */
    private void compensate(List<SagaStep> executedSteps) {
        log.info("【Saga】开始逆序补偿，共 {} 个步骤需要补偿", executedSteps.size());

        // ========== 逆序补偿 ==========
        for (int i = executedSteps.size() - 1; i >= 0; i--) {
            SagaStep step = executedSteps.get(i);
            log.info("【Saga】补偿步骤: {}", step.getName());
            try {
                step.compensate();
                log.info("【Saga】步骤 {} 补偿成功", step.getName());
            } catch (Exception e) {
                // 补偿失败：记录日志，实际项目中应该写入本地消息表重试
                log.error("【Saga】步骤 {} 补偿失败！需要人工介入或重试", step.getName(), e);
                // 继续补偿其他步骤（不能因为一个补偿失败就停止）
            }
        }

        log.info("【Saga】逆序补偿完成");
    }

    // ========== 完整 Saga 流程演示 ==========

    /**
     * 【面试考点】演示完整 Saga 事务流程（编排模式）
     *
     * 业务场景：用户下单
     *   步骤1：创建订单
     *   步骤2：扣减库存
     *   步骤3：扣减余额
     *   步骤4：发送通知
     *
     * 演示两个场景：
     *   场景一：全部成功
     *   场景二：步骤3（扣减余额）失败，触发逆序补偿
     */
    public void demonstrateSaga() {
        log.info("========== Saga 编排模式演示 ==========");

        String orderId = "order_" + System.currentTimeMillis();
        String productId = "P001";
        String userId = "user_001";
        BigDecimal amount = new BigDecimal("100.00");
        int qty = 2;

        // ========== 场景一：全部成功 ==========
        log.info("--- 场景一：全部成功 ---");
        List<SagaStep> successSteps = buildOrderSagaSteps(orderId, productId, userId, amount, qty);
        executeSaga(successSteps);

        // ========== 场景二：余额不足，触发补偿 ==========
        log.info("--- 场景二：余额不足，触发逆序补偿 ---");
        String orderId2 = "order_" + (System.currentTimeMillis() + 1);
        BigDecimal largeAmount = new BigDecimal("99999.00");  // 超过余额，触发失败

        List<SagaStep> failSteps = buildOrderSagaSteps(orderId2, productId, userId, largeAmount, qty);
        executeSaga(failSteps);

        log.info("========== Saga 演示结束 ==========");
        log.info("");
        log.info("【Saga 核心要点总结】");
        log.info("1. 最终一致性：通过补偿操作保证最终状态一致");
        log.info("2. 逆序补偿：失败时按逆序执行补偿，保证依赖关系正确");
        log.info("3. 幂等补偿：补偿操作必须幂等，支持重试");
        log.info("4. 隔离性差：中间状态对外可见，需要语义锁解决");
        log.info("5. 补偿保证：本地消息表 + 重试，保证补偿一定执行");
    }

    /**
     * 构建订单 Saga 步骤列表
     *
     * 使用匿名内部类实现 SagaStep 接口，
     * 将业务参数通过闭包捕获，避免参数传递的复杂性。
     *
     * @param orderId   订单ID
     * @param productId 商品ID
     * @param userId    用户ID
     * @param amount    金额
     * @param qty       数量
     * @return Saga 步骤列表
     */
    private List<SagaStep> buildOrderSagaSteps(
            String orderId, String productId, String userId, BigDecimal amount, int qty) {

        List<SagaStep> steps = new ArrayList<>();

        // 步骤1：创建订单
        steps.add(new SagaStep() {
            @Override
            public boolean execute() {
                return createOrder(orderId);
            }

            @Override
            public void compensate() {
                cancelOrder(orderId);
            }

            @Override
            public String getName() {
                return "创建订单(" + orderId + ")";
            }
        });

        // 步骤2：扣减库存
        steps.add(new SagaStep() {
            @Override
            public boolean execute() {
                return deductStock(productId, qty);
            }

            @Override
            public void compensate() {
                restoreStock(productId, qty);
            }

            @Override
            public String getName() {
                return "扣减库存(" + productId + " x" + qty + ")";
            }
        });

        // 步骤3：扣减余额
        steps.add(new SagaStep() {
            @Override
            public boolean execute() {
                return deductBalance(userId, amount);
            }

            @Override
            public void compensate() {
                refundBalance(userId, amount);
            }

            @Override
            public String getName() {
                return "扣减余额(" + userId + " ¥" + amount + ")";
            }
        });

        // 步骤4：发送通知
        steps.add(new SagaStep() {
            @Override
            public boolean execute() {
                return sendNotification(userId);
            }

            @Override
            public void compensate() {
                cancelNotification(userId);
            }

            @Override
            public String getName() {
                return "发送通知(" + userId + ")";
            }
        });

        return steps;
    }
}
