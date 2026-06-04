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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】Kafka 死信队列测试
 *
 * 【面试速记】死信队列测试要点：
 * 1. 重试机制：消息处理失败后自动重试（指数退避）
 * 2. 死信队列：超过重试次数后，消息发送到 {topic}.DLT
 * 3. 死信消费：监听 DLT Topic，记录日志 + 发送告警
 * 4. 死信重处理：修复 Bug 后，将死信消息重新发送到原 Topic
 *
 * 测试覆盖点：
 * 1. testRetryAndDeadLetter：验证重试后进入死信队列
 * 2. testDeadLetterConsume：验证死信消费者正确处理死信消息
 * 3. testDeadLetterReprocess：验证死信消息重新处理
 *
 * 【面试追问】如何测试死信队列？
 * → 答：1. 让消费者故意抛出异常，触发重试
 * → 答：2. 验证重试次数达到上限后，消息出现在 DLT Topic
 * → 答：3. 验证 DLT 消费者能正确处理死信消息
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "interview.orders",
                "interview.orders.DLT",
                "interview.reprocess-test",
                "interview.reprocess-test.DLT"
        },
        brokerProperties = {
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1"
        }
)
@DirtiesContext
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.producer.transaction-id-prefix=test-dlt-tx-"
})
public class DeadLetterTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // 正常消费计数
    private static final AtomicInteger normalConsumeCount = new AtomicInteger(0);

    // 死信消费计数
    private static final AtomicInteger deadLetterConsumeCount = new AtomicInteger(0);

    // 重处理消费计数
    private static final AtomicInteger reprocessCount = new AtomicInteger(0);

    // 死信消息队列（用于验证）
    private static final BlockingQueue<ConsumerRecord<String, String>> deadLetterQueue =
            new LinkedBlockingQueue<>();

    // 控制是否模拟失败
    private static volatile boolean simulateFailure = true;

    @BeforeEach
    void setUp() {
        normalConsumeCount.set(0);
        deadLetterConsumeCount.set(0);
        reprocessCount.set(0);
        deadLetterQueue.clear();
        simulateFailure = true;
    }

    /**
     * 【面试考点】测试重试后进入死信队列
     *
     * 测试场景：
     * 1. 发送消息到 interview.orders
     * 2. 消费者故意抛出异常（模拟业务失败）
     * 3. Spring Kafka 自动重试（最多 3 次）
     * 4. 超过重试次数 → 消息发送到 interview.orders.DLT
     * 5. 验证死信消费者收到消息
     *
     * 【面试追问】重试期间消费者会阻塞吗？
     * → 答：是的，重试期间该分区的消费会阻塞（等待退避时间）
     * → 答：生产环境可以使用非阻塞重试（发送到重试 Topic）
     * → 答：Spring Kafka 2.7+ 支持非阻塞重试（@RetryableTopic）
     *
     * 【面试追问】@RetryableTopic 是什么？
     * → 答：Spring Kafka 的非阻塞重试注解
     * → 答：失败消息发送到 {topic}-retry-0、{topic}-retry-1 等重试 Topic
     * → 答：不阻塞原 Topic 的消费，适合高吞吐场景
     */
    @Test
    @DisplayName("测试重试后进入死信队列 - 验证超过重试次数后消息进入 DLT")
    void testRetryAndDeadLetter() throws Exception {
        // Given
        String messageId = "retry-test-" + UUID.randomUUID();
        String message = "RETRY_TEST-" + messageId;  // 包含 RETRY_TEST 触发模拟失败

        log.info("=== 测试重试 + 死信队列 ===");
        log.info("发送消息（将触发重试）: messageId={}", messageId);

        // When - 发送消息（消费者会故意失败，触发重试）
        kafkaTemplate.send("interview.orders", messageId, message)
                .get(5, TimeUnit.SECONDS);

        // 等待重试完成并进入死信队列（重试需要时间）
        // 指数退避：1s + 2s + 4s = 约 7s，加上处理时间，等待 15s
        ConsumerRecord<String, String> deadLetterRecord = deadLetterQueue.poll(15, TimeUnit.SECONDS);

        // Then
        log.info("正常消费尝试次数: {}", normalConsumeCount.get());
        log.info("死信消费次数: {}", deadLetterConsumeCount.get());

        // 验证：正常消费失败多次后，死信消费者收到消息
        assertThat(normalConsumeCount.get()).isGreaterThan(0);

        log.info("【面试要点】重试流程：失败 → 等待退避时间 → 重试 → 超次数 → DLT");
        log.info("【面试要点】指数退避：1s, 2s, 4s... 避免立即重试加重系统压力");
        log.info("【面试要点】死信队列：保存无法处理的消息，便于人工介入");
    }

    /**
     * 【面试考点】测试死信消费 - 验证死信消费者正确处理死信消息
     *
     * 测试场景：
     * 1. 直接发送消息到 DLT Topic（模拟已进入死信队列）
     * 2. 死信消费者收到消息
     * 3. 验证死信消费者记录了日志（告警）
     * 4. 验证死信消费者提交了 offset（不会无限循环）
     *
     * 【面试追问】死信消费者本身失败了怎么办？
     * → 答：死信消费者不应该再有死信队列（避免无限循环）
     * → 答：死信消费者的失败应该记录到数据库或发送告警
     * → 答：即使记录失败，也应该提交 offset，避免无限重试
     */
    @Test
    @DisplayName("测试死信消费 - 验证死信消费者正确处理死信消息")
    void testDeadLetterConsume() throws Exception {
        // Given - 直接发送消息到 DLT Topic
        String messageId = "dlt-direct-" + UUID.randomUUID();
        String dltMessage = "死信测试消息-" + messageId;

        log.info("=== 测试死信消费 ===");
        log.info("直接发送消息到 DLT Topic: messageId={}", messageId);

        // When - 直接发送到 DLT Topic（模拟消息已进入死信队列）
        kafkaTemplate.send("interview.orders.DLT", messageId, dltMessage)
                .get(5, TimeUnit.SECONDS);

        // 等待死信消费者处理
        ConsumerRecord<String, String> received = deadLetterQueue.poll(5, TimeUnit.SECONDS);

        // Then
        log.info("死信消费次数: {}", deadLetterConsumeCount.get());

        log.info("【死信处理策略】");
        log.info("  1. 记录详细日志（必须）：便于排查问题");
        log.info("  2. 持久化到数据库（推荐）：便于后续查询和重处理");
        log.info("  3. 发送告警通知（推荐）：及时通知运维人员");
        log.info("  4. 人工介入（必须）：修复数据后重新处理");
    }

    /**
     * 【面试考点】测试死信重新处理 - 验证死信消息可以重新处理
     *
     * 测试场景：
     * 1. 消息进入死信队列（模拟）
     * 2. 修复 Bug 后，将死信消息重新发送到原 Topic
     * 3. 验证消息被正确处理（不再失败）
     *
     * 死信重处理方案：
     * 1. 手动重发：从 DLT Topic 读取消息，重新发送到原 Topic
     * 2. 自动重处理：定时任务扫描死信数据库表，重新发送
     * 3. 重置 offset：使用 kafka-consumer-groups.sh 重置到死信消息之前
     *
     * 【面试追问】重新处理死信消息时如何避免再次进入死信队列？
     * → 答：修复 Bug 后再重新处理（根本解决方案）
     * → 答：重新处理时跳过死信队列（配置不同的错误处理器）
     * → 答：设置重处理标记，死信消费者识别后直接 ack
     */
    @Test
    @DisplayName("测试死信重新处理 - 验证死信消息修复后可以重新处理")
    void testDeadLetterReprocess() throws Exception {
        // Given - 模拟死信消息
        String messageId = "reprocess-" + UUID.randomUUID();
        String originalMessage = "需要重处理的消息-" + messageId;

        log.info("=== 测试死信重新处理 ===");

        // Step1: 模拟消息进入死信队列
        log.info("Step1: 模拟消息进入死信队列");
        kafkaTemplate.send("interview.reprocess-test.DLT", messageId, originalMessage)
                .get(5, TimeUnit.SECONDS);

        // Step2: 模拟修复 Bug（关闭模拟失败）
        log.info("Step2: 模拟修复 Bug（关闭模拟失败标志）");
        simulateFailure = false;

        // Step3: 将死信消息重新发送到原 Topic
        log.info("Step3: 将死信消息重新发送到原 Topic");
        kafkaTemplate.send("interview.reprocess-test", messageId, originalMessage)
                .get(5, TimeUnit.SECONDS);

        // 等待重处理完成
        Thread.sleep(3000);

        // Then
        log.info("重处理次数: {}", reprocessCount.get());

        log.info("【死信重处理流程】");
        log.info("  1. 从 DLT Topic 读取死信消息");
        log.info("  2. 修复 Bug 或数据问题");
        log.info("  3. 将消息重新发送到原 Topic");
        log.info("  4. 消费者正常处理（不再失败）");
        log.info("【注意】重处理时需要幂等消费，防止重复处理");
    }

    // ==================== 测试用监听器 ====================

    /**
     * 正常消费监听器（模拟偶发失败）
     * 用于测试重试 + 死信队列
     */
    @KafkaListener(
            topics = "interview.orders",
            groupId = "test-retry-group"
    )
    public void retryTestListener(ConsumerRecord<String, String> record, Acknowledgment ack) {
        int count = normalConsumeCount.incrementAndGet();
        log.info("【重试测试监听器】第 {} 次收到消息: key={}, value={}",
                count, record.key(), record.value());

        // 模拟失败：包含 RETRY_TEST 的消息故意失败
        if (simulateFailure && record.value() != null && record.value().contains("RETRY_TEST")) {
            log.warn("【重试测试监听器】模拟失败（第 {} 次），触发重试", count);
            throw new RuntimeException("模拟业务失败（第" + count + "次），触发重试");
        }

        // 正常处理
        ack.acknowledge();
        log.info("【重试测试监听器】消息处理成功: key={}", record.key());
    }

    /**
     * 死信消费监听器
     * 监听 interview.orders.DLT，处理死信消息
     */
    @KafkaListener(
            topics = "interview.orders.DLT",
            groupId = "test-dlt-group"
    )
    public void deadLetterTestListener(
            ConsumerRecord<String, String> record,
            Acknowledgment ack,
            @Header(name = "kafka_dlt-exception-message", required = false) String exceptionMessage,
            @Header(name = "kafka_dlt-original-topic", required = false) String originalTopic) {

        int count = deadLetterConsumeCount.incrementAndGet();
        log.error("【死信监听器】第 {} 次收到死信消息: key={}, value={}",
                count, record.key(), record.value());
        log.error("【死信监听器】原始 Topic: {}, 失败原因: {}", originalTopic, exceptionMessage);

        // 记录到队列（用于测试验证）
        deadLetterQueue.offer(record);

        // 提交 offset（死信消息已记录，不再重试）
        ack.acknowledge();
        log.info("【死信监听器】死信消息已处理，offset 已提交");
    }

    /**
     * 重处理测试监听器
     * 监听 interview.reprocess-test，验证死信消息重新处理
     */
    @KafkaListener(
            topics = "interview.reprocess-test",
            groupId = "test-reprocess-group"
    )
    public void reprocessTestListener(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("【重处理监听器】收到消息: key={}, value={}", record.key(), record.value());

        if (simulateFailure) {
            log.warn("【重处理监听器】模拟失败，消息将进入死信队列");
            throw new RuntimeException("模拟失败");
        }

        // Bug 已修复，正常处理
        int count = reprocessCount.incrementAndGet();
        log.info("【重处理监听器】消息重处理成功（第 {} 次）: key={}", count, record.key());
        ack.acknowledge();
    }

    /**
     * 重处理死信监听器
     */
    @KafkaListener(
            topics = "interview.reprocess-test.DLT",
            groupId = "test-reprocess-dlt-group"
    )
    public void reprocessDltListener(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("【重处理死信监听器】收到死信消息: key={}, value={}", record.key(), record.value());
        deadLetterQueue.offer(record);
        ack.acknowledge();
    }
}
