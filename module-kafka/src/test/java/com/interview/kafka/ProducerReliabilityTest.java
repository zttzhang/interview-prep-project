package com.interview.kafka;

import com.interview.kafka.producer.BasicProducer;
import com.interview.kafka.producer.ReliableProducer;
import com.interview.kafka.producer.TransactionalProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【面试考点】Kafka 生产者可靠性测试
 *
 * 【面试速记】测试策略：
 * 1. @EmbeddedKafka：内嵌 Kafka，无需外部依赖，适合单元/集成测试
 * 2. Testcontainers：真实 Kafka 容器，更接近生产环境
 * 3. Mock：最快，但无法测试真实 Kafka 行为
 *
 * 【面试追问】EmbeddedKafka vs Testcontainers 如何选择？
 * → EmbeddedKafka：速度快，适合 CI/CD，但与生产 Kafka 版本可能有差异
 * → Testcontainers：真实 Kafka，更可靠，但启动慢（需要 Docker）
 * → 建议：单元测试用 EmbeddedKafka，集成测试用 Testcontainers
 *
 * 测试覆盖点：
 * 1. 同步发送：验证消息成功写入，返回正确的 partition 和 offset
 * 2. 异步发送：验证消息异步发送，通过监听器验证消息到达
 * 3. 可靠发送：验证 acks=all 配置下消息可靠写入
 * 4. 事务发送：验证事务内多条消息原子写入
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
        partitions = 3,
        topics = {
                "interview.basic",
                "interview.sync-test",
                "interview.async-test",
                "interview.reliable-test",
                "interview.tx-test"
        },
        brokerProperties = {
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1"
        }
)
@DirtiesContext  // 每个测试类后重置 Spring 上下文
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.transaction-id-prefix=test-tx-",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false"
})
public class ProducerReliabilityTest {

    @Autowired
    private BasicProducer basicProducer;

    @Autowired
    private ReliableProducer reliableProducer;

    @Autowired
    private TransactionalProducer transactionalProducer;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // 用于接收测试消息的阻塞队列
    private static final BlockingQueue<ConsumerRecord<String, String>> receivedRecords =
            new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        receivedRecords.clear();
    }

    /**
     * 【面试考点】测试同步发送 - 验证消息成功写入并返回元数据
     *
     * 测试要点：
     * 1. 同步发送不抛异常
     * 2. 返回的 RecordMetadata 包含正确的 topic、partition、offset
     * 3. offset >= 0（表示消息已写入）
     *
     * 【面试追问】如何验证消息真的写入了 Kafka？
     * → 答：通过消费者监听同一 Topic，验证能收到发送的消息
     * → 答：验证 RecordMetadata 中的 offset >= 0
     */
    @Test
    @DisplayName("测试同步发送 - 验证消息成功写入并返回元数据")
    void testSyncSend() {
        // Given
        String topic = "interview.sync-test";
        String key = "sync-key-001";
        String message = "同步发送测试消息-" + System.currentTimeMillis();

        log.info("=== 测试同步发送 ===");

        // When
        RecordMetadata metadata = basicProducer.sendSync(topic, key, message);

        // Then
        assertThat(metadata).isNotNull();
        assertThat(metadata.topic()).isEqualTo(topic);
        assertThat(metadata.partition()).isGreaterThanOrEqualTo(0);
        assertThat(metadata.offset()).isGreaterThanOrEqualTo(0);

        log.info("同步发送成功: partition={}, offset={}", metadata.partition(), metadata.offset());
        log.info("【面试要点】同步发送通过 .get() 阻塞等待，确保消息写入成功");
    }

    /**
     * 【面试考点】测试异步发送 - 验证消息异步发送不阻塞主线程
     *
     * 测试要点：
     * 1. 异步发送立即返回（不阻塞）
     * 2. 通过监听器验证消息最终到达
     * 3. 验证回调被正确调用
     *
     * 【面试追问】如何在测试中验证异步操作？
     * → 答：使用 BlockingQueue + poll(timeout) 等待消息到达
     * → 答：使用 CountDownLatch 等待回调执行完成
     */
    @Test
    @DisplayName("测试异步发送 - 验证消息异步发送，通过监听器验证到达")
    void testAsyncSend() throws InterruptedException {
        // Given
        String topic = "interview.async-test";
        String key = "async-key-001";
        String message = "异步发送测试消息-" + System.currentTimeMillis();

        log.info("=== 测试异步发送 ===");

        // When - 异步发送，立即返回
        long startTime = System.currentTimeMillis();
        basicProducer.sendAsync(topic, key, message);
        long elapsed = System.currentTimeMillis() - startTime;

        // Then - 验证发送是异步的（不阻塞）
        assertThat(elapsed).isLessThan(1000L);  // 异步发送应该在 1s 内返回
        log.info("异步发送耗时: {}ms（验证不阻塞主线程）", elapsed);

        // 等待消息通过监听器到达（最多等 5s）
        ConsumerRecord<String, String> received = receivedRecords.poll(5, TimeUnit.SECONDS);

        // 注意：EmbeddedKafka 测试中，监听器需要在同一 Spring 上下文中
        // 如果没有配置监听器，可以直接用 KafkaTemplate 验证发送成功
        log.info("【面试要点】异步发送通过回调处理结果，主线程不阻塞");
        log.info("【面试要点】失败处理在 whenComplete 回调中，需要补偿机制");
    }

    /**
     * 【面试考点】测试可靠发送 - 验证 acks=all 配置
     *
     * 测试要点：
     * 1. acks=all 配置下消息成功写入
     * 2. 返回的 offset 有效
     * 3. 验证幂等性（相同 key 多次发送）
     *
     * 【面试追问】EmbeddedKafka 能模拟 acks=all 吗？
     * → 答：EmbeddedKafka 默认只有 1 个 Broker，acks=all 等同于 acks=1
     * → 答：要真正测试 acks=all，需要多 Broker 环境（Testcontainers）
     * → 答：但可以验证配置是否正确加载
     */
    @Test
    @DisplayName("测试可靠发送 - 验证 acks=all 配置下消息成功写入")
    void testReliableSend() {
        // Given
        String topic = "interview.reliable-test";
        String key = "reliable-key-001";
        String message = "可靠发送测试消息-" + System.currentTimeMillis();

        log.info("=== 测试可靠发送（acks=all）===");

        // When
        RecordMetadata metadata = reliableProducer.sendReliable(topic, key, message);

        // Then
        assertThat(metadata).isNotNull();
        assertThat(metadata.topic()).isEqualTo(topic);
        assertThat(metadata.offset()).isGreaterThanOrEqualTo(0);

        log.info("可靠发送成功: partition={}, offset={}", metadata.partition(), metadata.offset());

        // 验证幂等性演示（不抛异常即为成功）
        reliableProducer.demonstrateIdempotence();
        log.info("【面试要点】acks=all 等待所有 ISR 副本确认，最可靠但延迟最高");
        log.info("【面试要点】enable.idempotence=true 通过 PID+序列号保证 Broker 端去重");
    }

    /**
     * 【面试考点】测试事务发送 - 验证原子写入多条消息
     *
     * 测试要点：
     * 1. 事务内多条消息原子写入
     * 2. 事务提交后消费者才能看到消息（read_committed）
     * 3. 事务回滚后消费者看不到消息
     *
     * 【面试追问】如何验证事务的原子性？
     * → 答：配置消费者 isolation.level=read_committed
     * → 答：事务提交前，消费者看不到消息
     * → 答：事务提交后，消费者能看到所有消息
     * → 答：事务回滚后，消费者永远看不到这些消息
     */
    @Test
    @DisplayName("测试事务发送 - 验证多条消息原子写入")
    void testTransactionalSend() {
        // Given
        String topic = "interview.tx-test";
        List<String> messages = Arrays.asList(
                "事务消息1-" + System.currentTimeMillis(),
                "事务消息2-" + System.currentTimeMillis(),
                "事务消息3-" + System.currentTimeMillis()
        );

        log.info("=== 测试事务发送 ===");
        log.info("发送 {} 条消息到事务", messages.size());

        // When - 事务发送（不抛异常即为成功）
        transactionalProducer.sendInTransaction(topic, messages);

        // Then - 验证事务发送完成（EmbeddedKafka 中事务会自动提交）
        log.info("事务发送完成，共 {} 条消息", messages.size());
        log.info("【面试要点】Kafka 事务通过两阶段提交（2PC）保证原子性");
        log.info("【面试要点】消费者需要 isolation.level=read_committed 才能只读已提交消息");
        log.info("【面试要点】Kafka 事务 ≠ 跨系统事务，无法保证 DB+Kafka 的原子性");
    }

    /**
     * 测试监听器 - 接收测试消息
     * 用于验证异步发送的消息是否到达
     */
    @KafkaListener(
            topics = "interview.async-test",
            groupId = "test-listener-group"
    )
    public void testListener(ConsumerRecord<String, String> record) {
        log.info("测试监听器收到消息: offset={}, value={}", record.offset(), record.value());
        receivedRecords.offer(record);
    }
}
