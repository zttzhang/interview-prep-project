package com.interview.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 【面试考点】Kafka 批量消费 - 高吞吐量消费模式
 *
 * 【面试速记】批量消费核心配置：
 * 1. max.poll.records=500：每次 poll 最多拉取 500 条
 * 2. fetch.min.bytes=1024：至少积累 1KB 数据才返回（减少空轮询）
 * 3. fetch.max.wait.ms=500：最多等待 500ms（即使不足 min.bytes 也返回）
 * 4. ContainerFactory 设置 batchListener=true
 *
 * 批量消费 vs 单条消费对比：
 * ┌──────────────┬──────────────────────────────┬──────────────────────────────┐
 * │   维度        │  单条消费                     │  批量消费                     │
 * ├──────────────┼──────────────────────────────┼──────────────────────────────┤
 * │ 吞吐量        │  低（每条消息单独处理）         │  高（批量处理，减少 IO 次数）  │
 * │ 延迟          │  低（消息到达立即处理）         │  高（等待凑够一批）            │
 * │ 失败处理      │  简单（单条重试）              │  复杂（部分失败如何处理）       │
 * │ 适用场景      │  实时性要求高                  │  吞吐量要求高（日志、埋点）     │
 * └──────────────┴──────────────────────────────┴──────────────────────────────┘
 *
 * 【面试追问】批量消费中某条消息处理失败怎么办？
 * → 方案一：整批重试（简单，但已成功的消息会重复处理，需要幂等）
 * → 方案二：记录失败消息到死信队列，其余消息正常提交
 * → 方案三：逐条处理，失败的单独处理（退化为单条消费，失去批量优势）
 */
@Slf4j
@Service
public class BatchConsumer {

    /**
     * 【面试考点】批量消费 - 一次处理多条消息
     *
     * 问题描述：单条消费吞吐量不足，如何提升消费性能？
     * 解决思路：批量消费，一次 poll 处理多条消息，减少处理开销
     *
     * 批量消费配置（在 ContainerFactory 中）：
     * factory.setBatchListener(true);  // 开启批量模式
     *
     * 批量消费配置（在 ConsumerConfig 中）：
     * max.poll.records=500        // 每次最多拉取 500 条
     * fetch.min.bytes=1024        // 至少 1KB 才返回（减少空轮询）
     * fetch.max.wait.ms=500       // 最多等 500ms
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（单条消费）：每条消息单独处理
     * //    问题：数据库写入频繁，网络 IO 多，吞吐量低
     * // ✅ 方案二（批量消费）：一批消息一起处理
     * //    优点：批量写数据库（INSERT ... VALUES(?,?),(?,?)...），减少 IO
     * //    缺点：延迟增加，失败处理复杂
     * // ==============================
     *
     * 【面试追问】批量消费如何保证幂等？
     * → 答：每条消息有唯一 ID，批量处理前先过滤已处理的消息
     * → 答：使用数据库 INSERT IGNORE 或 ON DUPLICATE KEY UPDATE
     * → 答：Redis SET NX 批量检查
     *
     * @param records 批量消息列表（一次 poll 的所有消息）
     */
    @KafkaListener(
            topics = "interview.batch",
            groupId = "batch-consumer-group",
            containerFactory = "batchContainerFactory"  // 需要配置批量工厂
    )
    public void consumeBatch(List<ConsumerRecord<String, String>> records) {
        log.info("【批量消费】收到 {} 条消息", records.size());

        // 统计信息
        int successCount = 0;
        int failCount = 0;
        List<String> failedMessages = new ArrayList<>();

        for (ConsumerRecord<String, String> record : records) {
            log.debug("处理消息: partition={}, offset={}, key={}, value={}",
                    record.partition(), record.offset(), record.key(), record.value());

            try {
                // 批量处理业务逻辑
                processRecord(record);
                successCount++;
            } catch (Exception e) {
                failCount++;
                failedMessages.add(record.key() + ":" + record.value());
                log.error("消息处理失败: offset={}, error={}", record.offset(), e.getMessage());
                // 【面试考点】批量消费失败处理策略：
                // 1. 记录失败消息，继续处理其他消息（at-least-once，需要幂等）
                // 2. 整批失败，抛出异常触发重试（简单但效率低）
                // 这里选择策略1：记录失败，继续处理
            }
        }

        log.info("【批量消费】处理完成: 成功={}, 失败={}", successCount, failCount);

        if (!failedMessages.isEmpty()) {
            log.warn("【批量消费】失败消息列表: {}", failedMessages);
            // 生产环境：将失败消息发送到死信队列或告警
        }

        // 注意：这里没有手动 ack，Spring 会在方法返回后自动提交
        // 如果需要手动提交，使用 consumeBatchWithManualCommit 方法
    }

    /**
     * 【面试考点】批量消费 + 手动提交 - 最可靠的批量消费方式
     *
     * 问题描述：批量消费如何保证消息不丢失？
     * 解决思路：批量处理完成后，手动提交 offset
     *
     * 手动提交时机：
     * 1. 整批处理成功 → 调用 ack.acknowledge() 提交
     * 2. 部分失败 → 记录失败消息到死信队列，然后提交（at-least-once）
     * 3. 整批失败 → 不提交，触发重试（需要幂等消费）
     *
     * 【面试追问】批量消费中 offset 是如何提交的？
     * → 答：调用 ack.acknowledge() 提交的是这批消息中最大的 offset
     * → 答：Kafka 的 offset 是连续的，提交最大 offset 表示之前所有消息都已消费
     * → 答：所以批量消费中，即使部分消息失败，提交 offset 后这些消息不会重试
     * → 答：失败消息需要通过死信队列或其他机制处理
     *
     * @param records 批量消息列表
     * @param ack     手动提交确认对象
     */
    @KafkaListener(
            topics = "interview.batch-manual",
            groupId = "batch-manual-group",
            containerFactory = "batchManualAckContainerFactory"  // 批量+手动提交工厂
    )
    public void consumeBatchWithManualCommit(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment ack) {

        log.info("【批量手动提交】收到 {} 条消息", records.size());

        if (records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        // 记录批次信息（用于日志追踪）
        ConsumerRecord<String, String> firstRecord = records.get(0);
        ConsumerRecord<String, String> lastRecord = records.get(records.size() - 1);
        log.info("批次范围: partition={}, offset {} ~ {}",
                firstRecord.partition(), firstRecord.offset(), lastRecord.offset());

        // 批量处理
        List<String> deadLetterMessages = new ArrayList<>();
        int successCount = 0;

        for (ConsumerRecord<String, String> record : records) {
            try {
                processRecord(record);
                successCount++;
            } catch (Exception e) {
                log.error("消息处理失败，发送到死信队列: offset={}, key={}",
                        record.offset(), record.key(), e);
                // 失败消息记录到死信队列（不影响整批提交）
                deadLetterMessages.add(record.value());
            }
        }

        // 处理死信消息（发送到 DLT Topic）
        if (!deadLetterMessages.isEmpty()) {
            log.warn("【批量手动提交】{} 条消息处理失败，已记录到死信队列", deadLetterMessages.size());
            sendToDeadLetterQueue(deadLetterMessages);
        }

        // 【面试考点】无论成功失败，都提交 offset
        // 失败消息已经发送到死信队列，不需要重试
        // 这是 at-least-once 语义（配合幂等消费）
        ack.acknowledge();
        log.info("【批量手动提交】offset 提交成功: 成功={}, 死信={}",
                successCount, deadLetterMessages.size());
    }

    /**
     * 处理单条消息的业务逻辑
     */
    private void processRecord(ConsumerRecord<String, String> record) {
        String value = record.value();
        log.debug("处理消息: key={}, value={}", record.key(), value);

        // 模拟偶发失败
        if (value != null && value.contains("ERROR")) {
            throw new RuntimeException("模拟消息处理失败: " + value);
        }

        // 实际业务处理（如：写数据库、调用服务等）
    }

    /**
     * 发送到死信队列
     * 实际生产中应该注入 KafkaTemplate 发送到 DLT Topic
     */
    private void sendToDeadLetterQueue(List<String> messages) {
        // 实际实现：
        // messages.forEach(msg -> kafkaTemplate.send("interview.batch.DLT", msg));
        log.warn("死信消息（演示）: {}", messages);
    }
}
