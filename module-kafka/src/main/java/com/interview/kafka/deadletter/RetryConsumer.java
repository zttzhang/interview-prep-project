package com.interview.kafka.deadletter;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【面试考点】Kafka 重试消费者 + 死信消费者
 *
 * 【面试速记】重试 + 死信队列完整流程：
 * 1. 正常消费：consume() 处理消息
 * 2. 处理失败：Spring Kafka 自动重试（指数退避）
 * 3. 超过重试次数：DeadLetterPublishingRecoverer 发送到 {topic}.DLT
 * 4. 死信消费：consumeDeadLetter() 处理死信消息
 * 5. 死信处理：记录日志 + 发送告警 + 人工介入
 *
 * 【面试追问】如何防止死信消息被重复处理？
 * → 答：死信消费者也需要实现幂等（消息ID去重）
 * → 答：死信消息头中包含原始 offset，可以作为幂等 key
 * → 答：使用 Redis SET NX 或数据库唯一索引去重
 */
@Slf4j
@Service
public class RetryConsumer {

    // 【面试考点】幂等消费：使用内存 Set 记录已处理的消息 ID
    // 生产环境应使用 Redis 或数据库（内存重启后丢失）
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    // 模拟失败计数器（用于演示重试机制）
    private final AtomicInteger failureCounter = new AtomicInteger(0);

    /**
     * 【面试考点】正常消费 - 模拟偶发失败触发重试
     *
     * 问题描述：消息处理偶发失败，如何自动重试？
     * 解决思路：Spring Kafka 的 DefaultErrorHandler 自动重试，超次数发送 DLT
     *
     * 重试机制工作原理：
     * 1. 消费者抛出异常
     * 2. DefaultErrorHandler 捕获异常
     * 3. 根据 BackOff 策略等待后重试
     * 4. 超过最大重试次数 → DeadLetterPublishingRecoverer 发送到 DLT
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（无重试）：消息处理失败直接丢弃
     * //    问题：临时故障（网络抖动、DB 短暂不可用）导致消息永久丢失
     * // ✅ 方案二（重试 + 死信）：自动重试，超次数进死信
     * //    优点：临时故障自动恢复，永久故障人工处理
     * //    缺点：需要幂等消费（重试可能重复处理）
     * // ==============================
     *
     * 【面试追问】重试期间消费者会阻塞吗？
     * → 答：是的！重试期间该分区的消费会阻塞（等待退避时间）
     * → 答：其他分区不受影响（不同线程）
     * → 答：如果不能接受阻塞，可以使用非阻塞重试（发送到重试 Topic）
     *
     * @param record 消息记录
     * @param ack    手动提交确认
     */
    @KafkaListener(
            topics = "interview.orders",
            groupId = "retry-consumer-group",
            containerFactory = "deadLetterContainerFactory"  // 使用配置了重试的工厂
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String messageId = record.key();
        String message = record.value();

        log.info("【正常消费】收到消息: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), messageId, message);

        // 【面试考点】幂等消费：先检查是否已处理
        if (messageId != null && processedMessageIds.contains(messageId)) {
            log.info("【幂等消费】消息已处理，跳过: messageId={}", messageId);
            ack.acknowledge();
            return;
        }

        try {
            // 模拟偶发失败（前3次失败，第4次成功）
            // 用于演示重试机制
            int failCount = failureCounter.incrementAndGet();
            if (failCount % 4 != 0 && message != null && message.contains("RETRY_TEST")) {
                log.warn("【模拟失败】第 {} 次处理失败，将触发重试: messageId={}", failCount, messageId);
                throw new RuntimeException("模拟业务处理失败（第" + failCount + "次）");
            }

            // 正常业务处理
            processOrder(message);

            // 记录已处理（幂等）
            if (messageId != null) {
                processedMessageIds.add(messageId);
            }

            // 手动提交 offset
            ack.acknowledge();
            log.info("【正常消费】消息处理成功: messageId={}", messageId);

        } catch (Exception e) {
            log.error("【正常消费】消息处理失败，将触发重试: messageId={}, error={}",
                    messageId, e.getMessage());
            // 不调用 ack.acknowledge()，Spring Kafka 会根据 DefaultErrorHandler 重试
            // 超过重试次数后，DeadLetterPublishingRecoverer 将消息发送到 DLT
            throw e;
        }
    }

    /**
     * 【面试考点】死信消费者 - 处理无法正常消费的消息
     *
     * 问题描述：进入死信队列的消息如何处理？
     * 解决思路：记录详细日志 + 发送告警 + 人工介入
     *
     * 死信消息头信息（Spring Kafka 自动添加）：
     * - kafka_dlt-exception-fqcn：异常类全名
     * - kafka_dlt-exception-message：异常消息
     * - kafka_dlt-exception-stacktrace：异常堆栈
     * - kafka_dlt-original-topic：原始 Topic
     * - kafka_dlt-original-partition：原始分区
     * - kafka_dlt-original-offset：原始 Offset
     * - kafka_dlt-original-timestamp：原始时间戳
     *
     * 死信处理策略：
     * 1. 记录日志（必须）：便于排查问题
     * 2. 发送告警（推荐）：及时通知运维人员
     * 3. 存储到数据库（可选）：便于后续查询和重处理
     * 4. 人工介入（必须）：修复数据后重新处理
     *
     * 【面试追问】死信消息如何重新处理？
     * → 答：1. 修复 Bug 后，将死信消息重新发送到原 Topic
     * → 答：2. 编写补偿程序，从死信 Topic 读取并重新处理
     * → 答：3. 使用 kafka-consumer-groups.sh 重置 offset 到死信消息之前
     *
     * @param record            死信消息记录
     * @param ack               手动提交确认
     * @param exceptionMessage  异常消息（从消息头获取）
     * @param originalTopic     原始 Topic（从消息头获取）
     * @param originalOffset    原始 Offset（从消息头获取）
     */
    @KafkaListener(
            topics = "interview.orders.DLT",
            groupId = "dead-letter-consumer-group"
    )
    public void consumeDeadLetter(
            ConsumerRecord<String, String> record,
            Acknowledgment ack,
            @Header(name = "kafka_dlt-exception-message", required = false) String exceptionMessage,
            @Header(name = "kafka_dlt-original-topic", required = false) String originalTopic,
            @Header(name = "kafka_dlt-original-offset", required = false) Long originalOffset) {

        log.error("========== 死信消息 ==========");
        log.error("死信消息内容: key={}, value={}", record.key(), record.value());
        log.error("原始 Topic: {}", originalTopic);
        log.error("原始 Offset: {}", originalOffset);
        log.error("失败原因: {}", exceptionMessage);
        log.error("死信 Topic: {}, Partition: {}, Offset: {}",
                record.topic(), record.partition(), record.offset());
        log.error("==============================");

        try {
            // Step1: 持久化死信消息（便于后续查询和重处理）
            saveDeadLetterMessage(record, exceptionMessage, originalTopic, originalOffset);

            // Step2: 发送告警通知
            sendAlert(record.key(), record.value(), exceptionMessage);

            // Step3: 提交 offset（死信消息已记录，不需要重试）
            ack.acknowledge();
            log.info("死信消息已记录，等待人工处理: key={}", record.key());

        } catch (Exception e) {
            log.error("死信消息处理失败（记录日志失败）: key={}, error={}", record.key(), e.getMessage());
            // 即使记录失败，也提交 offset，避免死信消费者无限循环
            // 此时需要依赖其他监控手段（如：Kafka 监控告警）
            ack.acknowledge();
        }
    }

    /**
     * 处理订单业务逻辑
     */
    private void processOrder(String orderMessage) {
        log.info("处理订单: {}", orderMessage);
        // 实际业务：解析订单、写数据库、调用库存服务等
    }

    /**
     * 持久化死信消息到数据库
     * 便于后续查询、统计和重处理
     */
    private void saveDeadLetterMessage(ConsumerRecord<String, String> record,
                                        String exceptionMessage,
                                        String originalTopic,
                                        Long originalOffset) {
        // 实际实现：
        // jdbcTemplate.update(
        //     "INSERT INTO dead_letter_messages(msg_key, msg_value, exception, original_topic, original_offset, created_at) VALUES(?,?,?,?,?,?)",
        //     record.key(), record.value(), exceptionMessage, originalTopic, originalOffset, LocalDateTime.now()
        // );
        log.info("死信消息已持久化（演示）: key={}, originalTopic={}, originalOffset={}",
                record.key(), originalTopic, originalOffset);
    }

    /**
     * 发送告警通知
     * 生产环境：钉钉机器人、企业微信、邮件、PagerDuty 等
     */
    private void sendAlert(String key, String value, String exceptionMessage) {
        // 实际实现：
        // alertService.sendDingTalk("死信消息告警: key=" + key + ", error=" + exceptionMessage);
        log.warn("【告警】死信消息需要人工处理: key={}, error={}", key, exceptionMessage);
    }
}
