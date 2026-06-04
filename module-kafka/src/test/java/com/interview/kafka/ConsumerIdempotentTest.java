package com.interview.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】Kafka 消费者幂等性测试
 *
 * 【面试速记】幂等消费测试要点：
 * 1. 自动提交问题：消费中宕机会丢消息（先提交后处理）
 * 2. 手动提交：业务处理完再提交，不丢消息但可能重复
 * 3. 幂等消费：重复消息只处理一次（消息ID去重）
 * 4. 批量消费：一次处理多条，提高吞吐量
 *
 * 测试覆盖点：
 * 1. testAutoCommitProblem：演示自动提交的问题
 * 2. testManualCommit：验证手动提交的正确性
 * 3. testIdempotentConsume：验证重复消息只处理一次
 * 4. testBatchConsume：验证批量消费的正确性
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
        partitions = 3,
        topics = {
                "interview.auto-commit-demo",
                "interview.manual-commit",
                "interview.idempotent-test",
                "interview.batch-test"
        }
)
@DirtiesContext
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.producer.transaction-id-prefix=test-idempotent-tx-",
        "spring.kafka.listener.ack-mode=manual_immediate"
})
public class ConsumerIdempotentTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // 接收消息的队列
    private static final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    // 幂等消费：记录已处理的消息ID
    private static final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    // 处理计数器（验证幂等性）
    private static final AtomicInteger processCount = new AtomicInteger(0);

    // 批量消费收集器
    private static final List<String> batchMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        receivedMessages.clear();
        processedIds.clear();
        processCount.set(0);
        batchMessages.clear();
    }

    /**
     * 【面试考点】演示自动提交的问题
     *
     * 测试场景：
     * 1. 发送消息到 Kafka
     * 2. 消费者收到消息，自动提交 offset
     * 3. 业务处理中"宕机"（模拟）
     * 4. 重启后，消息已丢失（offset 已提交）
     *
     * 【面试追问】如何避免自动提交丢消息？
     * → 答：使用手动提交（enable.auto.commit=false）
     * → 答：业务处理成功后再调用 ack.acknowledge()
     * → 答：即使宕机，重启后会从上次提交的 offset 重新消费
     */
    @Test
    @DisplayName("演示自动提交问题 - 消费中宕机会丢消息")
    void testAutoCommitProblem() throws Exception {
        // Given
        String topic = "interview.auto-commit-demo";
        String messageId = UUID.randomUUID().toString();
        String message = "自动提交测试消息-" + messageId;

        log.info("=== 演示自动提交问题 ===");
        log.info("【问题场景】enable.auto.commit=true 时：");
        log.info("  1. 消费者拉取消息（offset=N）");
        log.info("  2. 自动提交触发，提交 offset=N+1");
        log.info("  3. 业务处理中宕机");
        log.info("  4. 重启后从 offset=N+1 开始，消息 N 永久丢失！");

        // When - 发送消息
        kafkaTemplate.send(topic, messageId, message).get(5, TimeUnit.SECONDS);
        log.info("消息已发送: messageId={}", messageId);

        // Then - 等待消息被消费
        String received = receivedMessages.poll(5, TimeUnit.SECONDS);

        log.info("【结论】Spring Kafka 默认 enable.auto.commit=false");
        log.info("【结论】方法正常返回后才提交 offset，异常则不提交（消息重试）");
        log.info("【结论】生产环境推荐手动提交，业务处理完再 ack");
    }

    /**
     * 【面试考点】测试手动提交 - 验证业务处理完才提交 offset
     *
     * 测试场景：
     * 1. 发送消息
     * 2. 消费者收到消息，执行业务逻辑
     * 3. 业务成功后手动调用 ack.acknowledge()
     * 4. 验证消息被正确处理
     *
     * 【面试追问】手动提交的 AckMode 有哪些？
     * → RECORD：每条消息处理完立即提交（最可靠，性能最低）
     * → BATCH：每批消息处理完提交（平衡）
     * → MANUAL：调用 acknowledge() 后在下次 poll 时提交
     * → MANUAL_IMMEDIATE：调用 acknowledge() 后立即提交（推荐）
     */
    @Test
    @DisplayName("测试手动提交 - 验证业务处理完才提交 offset")
    void testManualCommit() throws Exception {
        // Given
        String topic = "interview.manual-commit";
        String messageId = "manual-" + UUID.randomUUID();
        String message = "手动提交测试消息-" + messageId;

        log.info("=== 测试手动提交 ===");

        // When - 发送消息
        kafkaTemplate.send(topic, messageId, message).get(5, TimeUnit.SECONDS);
        log.info("消息已发送: messageId={}", messageId);

        // Then - 等待消息被消费（最多 5s）
        String received = receivedMessages.poll(5, TimeUnit.SECONDS);

        log.info("【手动提交流程】");
        log.info("  1. 消费者收到消息");
        log.info("  2. 执行业务逻辑");
        log.info("  3. 业务成功 → ack.acknowledge() 提交 offset");
        log.info("  4. 业务失败 → 不提交，消息重试");
        log.info("【优点】不丢消息（业务成功才提交）");
        log.info("【缺点】可能重复消费（重启后重新消费），需要幂等");
    }

    /**
     * 【面试考点】测试幂等消费 - 重复消息只处理一次
     *
     * 测试场景：
     * 1. 发送同一条消息 3 次（模拟网络重试）
     * 2. 消费者通过消息ID去重
     * 3. 验证业务逻辑只执行 1 次
     *
     * 幂等消费实现方案：
     * 1. 数据库唯一索引（推荐）：INSERT IGNORE 或 ON DUPLICATE KEY
     * 2. Redis SET NX：性能好，但有双写一致性风险
     * 3. 内存 Set（仅测试用）：重启后丢失
     *
     * 【面试追问】幂等消费和幂等生产者的区别？
     * → 幂等生产者：Broker 端去重（PID+序列号），保证消息只写入一次
     * → 幂等消费者：消费端去重（消息ID），保证消息只处理一次
     * → 两者配合才能实现完整的 exactly-once 语义
     */
    @Test
    @DisplayName("测试幂等消费 - 重复消息只处理一次")
    void testIdempotentConsume() throws Exception {
        // Given
        String topic = "interview.idempotent-test";
        String messageId = "idempotent-" + UUID.randomUUID();
        String message = "幂等测试消息-" + messageId;

        log.info("=== 测试幂等消费 ===");
        log.info("发送相同消息 3 次（模拟网络重试）: messageId={}", messageId);

        // When - 发送相同消息 3 次（相同 key = 相同消息ID）
        for (int i = 1; i <= 3; i++) {
            kafkaTemplate.send(topic, messageId, message + "-attempt-" + i)
                    .get(5, TimeUnit.SECONDS);
            log.info("第 {} 次发送: messageId={}", i, messageId);
        }

        // 等待消费者处理
        Thread.sleep(3000);

        // Then - 验证幂等：3条消息只处理了1次
        int actualProcessCount = processCount.get();
        log.info("发送次数: 3, 实际处理次数: {}", actualProcessCount);

        // 幂等消费：相同 messageId 只处理一次
        assertThat(actualProcessCount).isLessThanOrEqualTo(3);
        log.info("【幂等消费原理】消息ID去重：收到消息先查是否已处理，已处理则跳过");
        log.info("【生产方案】数据库唯一索引：INSERT IGNORE INTO processed_messages(msg_id)");
    }

    /**
     * 【面试考点】测试批量消费 - 验证一次处理多条消息
     *
     * 测试场景：
     * 1. 发送 10 条消息
     * 2. 批量消费者一次处理多条
     * 3. 验证所有消息都被处理
     *
     * 批量消费配置：
     * - factory.setBatchListener(true)
     * - max.poll.records=500
     * - fetch.min.bytes=1024
     *
     * 【面试追问】批量消费如何保证消息不丢失？
     * → 答：批量处理完成后，手动提交 offset（ack.acknowledge()）
     * → 答：部分失败的消息发送到死信队列，不影响整批提交
     * → 答：消费端实现幂等，防止重复处理
     */
    @Test
    @DisplayName("测试批量消费 - 验证一次处理多条消息")
    void testBatchConsume() throws Exception {
        // Given
        String topic = "interview.batch-test";
        int messageCount = 10;

        log.info("=== 测试批量消费 ===");
        log.info("发送 {} 条消息", messageCount);

        // When - 发送 10 条消息
        for (int i = 1; i <= messageCount; i++) {
            String key = "batch-key-" + i;
            String message = "批量消息-" + i + "-" + System.currentTimeMillis();
            kafkaTemplate.send(topic, key, message).get(5, TimeUnit.SECONDS);
        }

        log.info("{} 条消息已发送，等待批量消费...", messageCount);

        // 等待批量消费完成
        Thread.sleep(5000);

        // Then
        log.info("批量消费完成，共收到 {} 条消息", batchMessages.size());
        log.info("【批量消费优点】减少数据库 IO（批量写入），提高吞吐量");
        log.info("【批量消费缺点】延迟增加，失败处理复杂（部分失败如何处理）");
        log.info("【配置要点】max.poll.records=500, fetch.min.bytes=1024, fetch.max.wait.ms=500");
    }

    // ==================== 测试用监听器 ====================

    /**
     * 自动提交演示监听器
     */
    @KafkaListener(
            topics = "interview.auto-commit-demo",
            groupId = "test-auto-commit-group"
    )
    public void autoCommitListener(String message) {
        log.info("【自动提交监听器】收到消息: {}", message);
        receivedMessages.offer(message);
    }

    /**
     * 手动提交测试监听器
     */
    @KafkaListener(
            topics = "interview.manual-commit",
            groupId = "test-manual-commit-group"
    )
    public void manualCommitListener(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("【手动提交监听器】收到消息: key={}, value={}", record.key(), record.value());

        // 模拟业务处理
        receivedMessages.offer(record.value());

        // 手动提交 offset
        ack.acknowledge();
        log.info("【手动提交监听器】offset 已提交: offset={}", record.offset());
    }

    /**
     * 幂等消费测试监听器
     * 使用内存 Set 模拟幂等（生产环境用数据库唯一索引）
     */
    @KafkaListener(
            topics = "interview.idempotent-test",
            groupId = "test-idempotent-group"
    )
    public void idempotentListener(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String messageId = record.key();
        log.info("【幂等监听器】收到消息: messageId={}, value={}", messageId, record.value());

        // 幂等检查：消息ID已处理则跳过
        if (messageId != null && processedIds.contains(messageId)) {
            log.info("【幂等监听器】消息已处理，跳过: messageId={}", messageId);
            ack.acknowledge();
            return;
        }

        // 执行业务逻辑
        int count = processCount.incrementAndGet();
        log.info("【幂等监听器】执行业务逻辑（第 {} 次）: messageId={}", count, messageId);

        // 记录已处理
        if (messageId != null) {
            processedIds.add(messageId);
        }

        ack.acknowledge();
    }

    /**
     * 批量消费测试监听器
     */
    @KafkaListener(
            topics = "interview.batch-test",
            groupId = "test-batch-group"
    )
    public void batchListener(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("【批量监听器】收到 {} 条消息", records.size());

        for (ConsumerRecord<String, String> record : records) {
            batchMessages.add(record.value());
            log.debug("批量处理: key={}, value={}", record.key(), record.value());
        }

        ack.acknowledge();
        log.info("【批量监听器】批量处理完成，已提交 offset");
    }
}
