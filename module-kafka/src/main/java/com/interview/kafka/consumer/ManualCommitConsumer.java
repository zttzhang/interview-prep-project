package com.interview.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 【面试考点】Kafka 手动提交 Offset - 防止消息丢失
 *
 * 【面试速记】Offset 提交方式对比：
 * ┌──────────────────┬──────────────────────────────────────────────────┐
 * │   提交方式        │  特点                                            │
 * ├──────────────────┼──────────────────────────────────────────────────┤
 * │ 自动提交          │ 定期提交，消费中宕机可能丢消息（先提交后处理）       │
 * │ 手动同步提交      │ 处理完再提交，可靠但性能低（阻塞等待）              │
 * │ 手动异步提交      │ 处理完再提交，性能好但失败不重试                   │
 * │ 批量手动提交      │ 批量处理完统一提交，高吞吐                         │
 * └──────────────────┴──────────────────────────────────────────────────┘
 *
 * 【面试追问】手动提交失败怎么办？
 * → 答：重试提交（Spring Kafka 默认会重试）
 * → 答：如果一直失败，消费者会 Rebalance，消息会被重新消费
 * → 答：所以消费端必须实现幂等，防止重复处理
 */
@Slf4j
@Service
public class ManualCommitConsumer {

    /**
     * 【面试考点】演示自动提交的问题 - 消费中宕机会丢消息
     *
     * 问题描述：自动提交 offset 为什么会丢消息？
     * 解决思路：理解自动提交的时机，改用手动提交
     *
     * 自动提交问题场景：
     * 1. 消费者拉取消息（offset=100）
     * 2. 自动提交触发，提交 offset=101（表示100已消费）
     * 3. 业务处理中，消费者宕机
     * 4. 重启后，从 offset=101 开始消费，offset=100 的消息永久丢失！
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（自动提交）：enable.auto.commit=true
     * //    问题：先提交 offset，后处理业务，宕机丢消息
     * //    适用：允许少量丢消息的场景（如：日志收集）
     * // ✅ 方案二（手动提交）：enable.auto.commit=false + ack.acknowledge()
     * //    优点：业务处理成功后再提交，不丢消息
     * //    缺点：需要实现幂等消费（重启后可能重复消费）
     * // ==============================
     *
     * 【注意】此方法使用默认容器工厂（AckMode=BATCH），
     * 方法正常返回后 Spring 自动提交，模拟自动提交行为
     *
     * @param record 消息记录
     */
    @KafkaListener(
            topics = "interview.auto-commit-demo",
            groupId = "auto-commit-group"
            // 使用默认工厂，AckMode=BATCH（批次处理完自动提交）
    )
    public void consumeWithAutoCommit(ConsumerRecord<String, String> record) {
        log.info("【自动提交演示】收到消息: offset={}, value={}", record.offset(), record.value());

        // 【面试考点】自动提交的问题演示：
        // 如果在这里抛出异常，Spring Kafka 不会提交 offset，消息会重试
        // 但如果是 enable.auto.commit=true（原生 Kafka），offset 已经提交了！

        // 模拟业务处理（假设这里宕机）
        try {
            processBusinessLogic(record.value());
            log.info("【自动提交演示】业务处理完成，Spring 将自动提交 offset={}", record.offset());
        } catch (Exception e) {
            log.error("【自动提交演示】业务处理失败，offset={} 不会提交，消息将重试", record.offset());
            throw e; // 重新抛出，触发重试
        }
    }

    /**
     * 【面试考点】手动提交 - 业务处理完再提交 offset
     *
     * 问题描述：如何保证消息处理完才提交 offset？
     * 解决思路：使用 AcknowledgingMessageListener，手动调用 ack.acknowledge()
     *
     * 手动提交流程：
     * 1. 消费者拉取消息
     * 2. 执行业务逻辑
     * 3. 业务成功 → ack.acknowledge() 提交 offset
     * 4. 业务失败 → 不调用 ack，消息会重试（需要幂等消费）
     *
     * 【面试追问】ack.acknowledge() 是立即提交还是异步提交？
     * → 答：取决于 AckMode 配置：
     * → MANUAL：调用 acknowledge() 后立即提交（同步）
     * → MANUAL_IMMEDIATE：调用后立即提交（同步，推荐）
     * → MANUAL（默认）：在下次 poll 时批量提交
     *
     * 需要在 ContainerFactory 中配置：
     * factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
     *
     * @param record 消息记录
     * @param ack    手动提交确认对象
     */
    @KafkaListener(
            topics = "interview.manual-commit",
            groupId = "manual-commit-group",
            containerFactory = "manualAckContainerFactory"  // 需要配置手动提交工厂
    )
    public void consumeWithManualCommit(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("【手动提交】收到消息: offset={}, key={}, value={}",
                record.offset(), record.key(), record.value());

        try {
            // Step1: 执行业务逻辑
            processBusinessLogic(record.value());

            // Step2: 业务处理成功，手动提交 offset
            // 【面试考点】只有在这里调用 ack.acknowledge()，offset 才会提交
            // 如果业务失败抛异常，不会执行到这里，offset 不提交，消息重试
            ack.acknowledge();
            log.info("【手动提交】offset 提交成功: offset={}", record.offset());

        } catch (Exception e) {
            // 业务处理失败，不提交 offset
            // 消息会被重新投递（需要幂等消费防止重复处理）
            log.error("【手动提交】业务处理失败，offset={} 不提交，消息将重试: {}",
                    record.offset(), e.getMessage());
            // 注意：这里不调用 ack.acknowledge()，消息会重试
            // 生产环境应该设置重试次数上限，超过后发送到死信队列
            throw new RuntimeException("消息处理失败，触发重试", e);
        }
    }

    /**
     * 【面试考点】批量手动提交 - 减少提交频率，提高吞吐量
     *
     * 问题描述：每条消息都手动提交，提交频率太高，影响性能？
     * 解决思路：批量消费，处理完一批后统一提交 offset
     *
     * 批量提交优化：
     * - 每次 poll 拉取 N 条消息（max.poll.records=100）
     * - 处理完这 N 条消息后，调用一次 ack.acknowledge()
     * - 减少提交次数，提高吞吐量
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（逐条提交）：每条消息处理完立即提交
     * //    问题：提交频率高，网络开销大，吞吐量低
     * // ✅ 方案二（批量提交）：一批消息处理完统一提交
     * //    优点：减少提交次数，提高吞吐量
     * //    缺点：批次中某条失败，整批重试（需要幂等消费）
     * // ==============================
     *
     * 【面试追问】批量提交中某条消息处理失败怎么办？
     * → 答：整批消息会重试（因为 offset 没有提交）
     * → 答：需要幂等消费，防止已成功的消息被重复处理
     * → 答：或者记录失败消息到死信队列，其余消息正常提交
     *
     * @param record 消息记录（单条，但 offset 代表批次位置）
     * @param ack    手动提交确认对象
     */
    @KafkaListener(
            topics = "interview.batch-manual-commit",
            groupId = "batch-manual-commit-group",
            containerFactory = "manualAckContainerFactory"
    )
    public void consumeWithBatchManualCommit(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("【批量手动提交】收到消息: offset={}, value={}", record.offset(), record.value());

        try {
            // 处理单条消息（实际批量场景见 BatchConsumer）
            processBusinessLogic(record.value());

            // 批量提交优化：
            // 在实际批量消费中，可以积累多条消息后统一提交
            // 这里演示单条手动提交的基本用法
            ack.acknowledge();
            log.info("【批量手动提交】offset 提交成功: offset={}", record.offset());

        } catch (Exception e) {
            log.error("【批量手动提交】处理失败: offset={}, error={}", record.offset(), e.getMessage());
            // 不提交 offset，触发重试
            // 生产环境：记录失败消息，发送告警
        }
    }

    /**
     * 模拟业务处理逻辑
     */
    private void processBusinessLogic(String message) {
        log.debug("执行业务逻辑: {}", message);
        // 模拟偶发失败（用于演示重试机制）
        if (message != null && message.contains("FAIL")) {
            throw new RuntimeException("模拟业务处理失败: " + message);
        }
    }
}
