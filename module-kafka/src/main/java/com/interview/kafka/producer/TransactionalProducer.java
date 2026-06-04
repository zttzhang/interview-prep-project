package com.interview.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【面试考点】Kafka 事务生产者 - Exactly-Once 语义实现
 *
 * 【面试速记】Kafka 事务核心概念：
 * 1. 事务ID（transactional.id）：跨会话唯一标识，保证 Producer 重启后事务连续性
 * 2. 两阶段提交（2PC）：Kafka 内部用 2PC 协调事务，TransactionCoordinator 是协调者
 * 3. Exactly-Once：幂等生产者（PID+序列号）+ 事务（原子写多分区）
 *
 * 【面试追问】Kafka 事务 vs 数据库事务的区别？
 * → 数据库事务：ACID，支持回滚，强一致性，单机或分布式（XA）
 * → Kafka 事务：只保证消息原子写入多个分区，不支持跨系统（DB+Kafka）的原子性
 * → 跨系统原子性需要：本地消息表 + 事务消息（RocketMQ方案）或 Saga 模式
 *
 * 【注意】使用事务需要在 KafkaProducerConfig 中配置：
 * - spring.kafka.producer.transaction-id-prefix=tx-
 * - enable.idempotence=true（事务自动开启幂等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionalProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 【面试考点】事务消息发送 - 原子写入多个分区
     *
     * 问题描述：如何保证向多个 Topic/分区发送消息的原子性？
     * 解决思路：使用 Kafka 事务，要么全部成功，要么全部失败
     *
     * Kafka 事务两阶段提交流程：
     * 1. beginTransaction()：向 TransactionCoordinator 注册事务
     * 2. send()：发送消息（此时消费者看不到，因为 isolation.level=read_committed）
     * 3. commitTransaction()：提交事务，消费者才能看到消息
     * 4. abortTransaction()：回滚，消费者永远看不到这些消息
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（非事务）：多条消息分别发送，部分成功部分失败
     * //    问题：消息不一致，消费者可能只收到部分消息
     * // ✅ 方案二（事务）：executeInTransaction() 包裹，原子性保证
     * //    优点：要么全部成功，要么全部失败
     * //    缺点：性能开销约 20%，吞吐量下降
     * // ==============================
     *
     * 【面试追问】事务的性能开销来自哪里？
     * → 答：1. 事务协调器的网络交互（beginTransaction/commitTransaction）
     * → 答：2. 消费者需要过滤未提交消息（read_committed 隔离级别）
     * → 答：3. 事务标记（Transaction Marker）写入所有分区
     *
     * @param topic    目标 Topic
     * @param messages 消息列表（原子发送）
     */
    public void sendInTransaction(String topic, List<String> messages) {
        log.info("开始事务发送: topic={}, messageCount={}", topic, messages.size());

        // ========== 方案对比 ==========
        // ❌ 方案一（手动管理事务）：
        //    kafkaTemplate.beginTransaction();
        //    try { ... kafkaTemplate.commitTransaction(); }
        //    catch { kafkaTemplate.abortTransaction(); }
        //    问题：代码冗余，容易忘记 abort
        // ✅ 方案二（executeInTransaction 模板方法）：
        //    自动管理 begin/commit/abort，代码简洁
        // ==============================
        kafkaTemplate.executeInTransaction(operations -> {
            for (int i = 0; i < messages.size(); i++) {
                String key = "tx-key-" + i;
                String message = messages.get(i);

                operations.send(topic, key, message);
                log.info("事务中发送消息: key={}, message={}", key, message);
            }

            // 【面试考点】事务内可以向多个 Topic 发送
            // 所有发送要么全部提交，要么全部回滚
            operations.send(topic + "-audit", "audit-key",
                    "事务批次审计日志: count=" + messages.size());

            return true; // 返回 true 触发 commit，返回 false 或抛异常触发 abort
        });

        log.info("事务发送完成: topic={}, messageCount={}", topic, messages.size());
    }

    /**
     * 【面试考点】结合数据库事务 - 演示 Exactly-Once 语义的局限性
     *
     * 问题描述：如何保证"写数据库"和"发 Kafka 消息"的原子性？
     * 解决思路：本地消息表方案（Kafka 事务无法跨系统）
     *
     * 【重要】Kafka 事务 ≠ 跨系统事务！
     * Kafka 事务只能保证：消息原子写入 Kafka 的多个分区
     * 无法保证：数据库操作 + Kafka 发送 的原子性
     *
     * 跨系统原子性解决方案：
     * 1. 本地消息表（推荐）：
     *    - 业务操作 + 写消息表 在同一个 DB 事务中
     *    - 定时任务扫描消息表，发送到 Kafka
     *    - 发送成功后更新消息表状态
     * 2. Saga 模式：
     *    - 拆分为多个本地事务，通过补偿事务回滚
     * 3. TCC（Try-Confirm-Cancel）：
     *    - 预留资源 → 确认 → 取消，实现最终一致性
     *
     * 【面试追问】为什么不用 XA 分布式事务？
     * → 答：XA 性能差（同步阻塞），Kafka 不支持 XA 协议
     * → 答：实际生产中用本地消息表或 Saga 模式
     *
     * @param topic   目标 Topic
     * @param message 消息内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendWithDatabaseTransaction(String topic, String message) {
        log.info("开始数据库+Kafka联合操作: topic={}", topic);

        // ========== 方案对比 ==========
        // ❌ 错误方案：直接在 @Transactional 方法中发送 Kafka
        //    问题：DB 事务提交成功，但 Kafka 发送失败 → 数据不一致
        //    问题：Kafka 发送成功，但 DB 事务回滚 → 消息已发出无法撤回
        // ✅ 正确方案：本地消息表
        //    Step1: DB 操作 + 写消息表（同一事务）
        //    Step2: 定时任务扫描消息表，发送 Kafka
        //    Step3: 发送成功，更新消息表状态为"已发送"
        // ==============================

        // 演示：模拟数据库操作（实际应注入 Repository/JdbcTemplate）
        log.info("Step1: 执行数据库业务操作（模拟）");
        // jdbcTemplate.update("INSERT INTO orders ...", ...);

        // 演示：在同一事务中写入本地消息表
        log.info("Step2: 写入本地消息表（与业务操作同一DB事务）");
        // jdbcTemplate.update("INSERT INTO local_messages(topic, content, status) VALUES(?,?,?)",
        //         topic, message, "PENDING");

        // 【注意】这里直接发送 Kafka 只是演示，生产环境应该用本地消息表
        // 直接发送存在的问题：DB 回滚后 Kafka 消息已发出，无法撤回
        try {
            kafkaTemplate.executeInTransaction(operations -> {
                operations.send(topic, "db-tx-key", message);
                log.info("Kafka 事务消息已发送（演示）: topic={}", topic);
                return true;
            });
        } catch (Exception e) {
            log.error("Kafka 发送失败，DB 事务将回滚: {}", e.getMessage());
            throw new RuntimeException("消息发送失败，触发事务回滚", e);
        }

        log.info("数据库+Kafka操作完成（演示）");
    }

    /**
     * 【面试考点】事务 ID 的作用 - 跨会话事务连续性
     *
     * 事务 ID（transactional.id）的作用：
     * 1. 唯一标识一个 Producer 实例的事务
     * 2. Producer 重启后，Kafka 能识别是同一个 Producer
     * 3. 防止"僵尸 Producer"（旧 Producer 重启后继续发送）
     *
     * 【面试追问】什么是僵尸 Producer？
     * → 答：Producer 宕机重启后，旧实例可能还在发送消息
     * → 答：Kafka 通过 epoch（纪元）机制解决：新 Producer 的 epoch 更大
     * → 答：旧 Producer 发送时，Broker 发现 epoch 过期，拒绝接收
     */
    public void demonstrateTransactionId() {
        log.info("演示事务ID的作用");
        log.info("配置：spring.kafka.producer.transaction-id-prefix=tx-interview-");
        log.info("实际事务ID = prefix + 线程ID，保证唯一性");

        // 事务 ID 配置示例（在 application.yml 中）：
        // spring:
        //   kafka:
        //     producer:
        //       transaction-id-prefix: tx-interview-
        //       acks: all
        //       enable-idempotence: true
    }
}
