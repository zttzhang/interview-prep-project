package com.interview.kafka.deadletter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】Kafka 死信队列配置 - 消息重试与失败处理
 *
 * 【面试速记】死信队列（DLT）核心流程：
 * 1. 消费者处理消息失败 → 触发重试（指数退避）
 * 2. 超过最大重试次数 → 消息发送到 {topic}.DLT
 * 3. 死信消费者监听 DLT → 记录日志、发送告警、人工处理
 *
 * 死信队列命名规则（Spring Kafka 默认）：
 * - 原 Topic：interview.orders
 * - 死信 Topic：interview.orders.DLT
 *
 * 【面试追问】如何监控死信队列？
 * → 答：1. 消费 DLT Topic，发送告警（钉钉/邮件/PagerDuty）
 * → 答：2. Kafka 监控工具（Kafka Manager、Prometheus + Grafana）
 * → 答：3. 监控 DLT Topic 的消息积压（Consumer Lag）
 * → 答：4. 设置告警阈值，积压超过 N 条触发告警
 *
 * 【面试追问】死信队列的消息如何重新处理？
 * → 答：1. 人工修复数据后，将消息重新发送到原 Topic
 * → 答：2. 编写补偿程序，批量重新处理死信消息
 * → 答：3. 使用 Kafka 的 kafka-consumer-groups.sh 重置 offset
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DeadLetterConfig {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * 【面试考点】死信发布恢复器 - 消息发送到 DLT
     *
     * 问题描述：消息处理失败超过重试次数后，如何保存失败消息？
     * 解决思路：DeadLetterPublishingRecoverer 自动将失败消息发送到 DLT Topic
     *
     * DeadLetterPublishingRecoverer 行为：
     * 1. 默认发送到 {originalTopic}.DLT
     * 2. 消息头中包含失败原因（exception class、message、stack trace）
     * 3. 可以自定义目标 Topic（通过 BiFunction 参数）
     *
     * 消息头信息（面试加分项）：
     * - kafka_dlt-exception-fqcn：异常类全名
     * - kafka_dlt-exception-message：异常消息
     * - kafka_dlt-original-topic：原始 Topic
     * - kafka_dlt-original-partition：原始分区
     * - kafka_dlt-original-offset：原始 Offset
     *
     * @return DeadLetterPublishingRecoverer Bean
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer() {
        // ========== 方案对比 ==========
        // ❌ 方案一（默认）：发送到 {topic}.DLT，使用相同分区
        //    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        // ✅ 方案二（自定义目标）：可以自定义死信 Topic 和分区
        //    优点：灵活控制死信消息的路由
        // ==============================
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> {
                    // 自定义死信 Topic：原 Topic + ".DLT"
                    String dltTopic = record.topic() + ".DLT";
                    log.warn("消息发送到死信队列: originalTopic={}, dltTopic={}, key={}, error={}",
                            record.topic(), dltTopic, record.key(), exception.getMessage());
                    // 返回 TopicPartition，null 表示使用默认分区策略
                    return new org.apache.kafka.common.TopicPartition(dltTopic, record.partition());
                });
    }

    /**
     * 【面试考点】默认错误处理器 - 重试 + 指数退避
     *
     * 问题描述：消息处理失败后，如何避免立即重试加重系统压力？
     * 解决思路：指数退避策略（Exponential Backoff）
     *
     * 指数退避原理：
     * - 第1次重试：等待 1s
     * - 第2次重试：等待 2s
     * - 第3次重试：等待 4s
     * - 超过最大次数：发送到死信队列
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（固定间隔重试）：FixedBackOff(1000, 3)
     * //    问题：立即重试可能加重下游系统压力（如：DB 宕机时）
     * // ✅ 方案二（指数退避）：ExponentialBackOff
     * //    优点：随着重试次数增加，等待时间指数增长，给系统恢复时间
     * //    适用：下游系统临时不可用（网络抖动、DB 短暂宕机）
     * // ==============================
     *
     * 【面试追问】指数退避的最大等待时间如何设置？
     * → 答：根据业务 SLA 决定，通常设置 maxInterval=30s 或 60s
     * → 答：超过最大等待时间后，等待时间不再增加（避免等待过长）
     *
     * @return DefaultErrorHandler Bean
     */
    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        // 指数退避策略：初始间隔 1s，倍数 2，最大间隔 30s
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30000L);  // 最大等待 30s
        backOff.setMaxElapsedTime(120000L);  // 最大总等待时间 120s（约3次重试）

        // 创建错误处理器，配置重试策略和死信恢复器
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterPublishingRecoverer(),
                backOff
        );

        // 【面试考点】不可重试异常配置
        // 某些异常不应该重试（如：数据格式错误，重试也没用）
        // 直接发送到死信队列，不浪费重试次数
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,  // 参数错误，重试无意义
                ClassCastException.class         // 类型转换错误，重试无意义
        );

        // 重试日志
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("消息重试: topic={}, partition={}, offset={}, attempt={}, error={}",
                        record.topic(), record.partition(), record.offset(),
                        deliveryAttempt, ex.getMessage())
        );

        return errorHandler;
    }

    /**
     * 【面试考点】固定间隔重试的错误处理器（对比用）
     *
     * 适用场景：重试次数少，对延迟不敏感的场景
     * 配置：重试 3 次，每次间隔 1s
     *
     * @return 固定间隔错误处理器
     */
    @Bean("fixedBackOffErrorHandler")
    public DefaultErrorHandler fixedBackOffErrorHandler() {
        // FixedBackOff(间隔ms, 最大重试次数)
        FixedBackOff fixedBackOff = new FixedBackOff(1000L, 3L);

        return new DefaultErrorHandler(
                deadLetterPublishingRecoverer(),
                fixedBackOff
        );
    }

    /**
     * 【面试考点】死信队列专用消费者容器工厂
     *
     * 问题描述：如何将错误处理器应用到消费者？
     * 解决思路：创建专用的 ContainerFactory，设置错误处理器
     *
     * ContainerFactory 的作用：
     * 1. 创建 KafkaMessageListenerContainer 实例
     * 2. 配置 AckMode（自动/手动提交）
     * 3. 配置错误处理器（重试、死信队列）
     * 4. 配置并发度（concurrency）
     *
     * 【面试追问】为什么需要多个 ContainerFactory？
     * → 答：不同消费者可能需要不同的配置（手动提交、批量消费、不同错误处理）
     * → 答：通过 @KafkaListener(containerFactory="xxx") 指定使用哪个工厂
     *
     * @return ConcurrentKafkaListenerContainerFactory Bean
     */
    @Bean("deadLetterContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> deadLetterContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(deadLetterConsumerFactory());

        // 设置错误处理器（包含重试 + 死信队列）
        factory.setCommonErrorHandler(defaultErrorHandler());

        // 手动提交 offset（配合错误处理器）
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // 并发度：3个消费者线程
        factory.setConcurrency(3);

        return factory;
    }

    /**
     * 死信队列专用消费者工厂
     * 配置 isolation.level=read_committed，只消费已提交的事务消息
     */
    @Bean
    public ConsumerFactory<String, String> deadLetterConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dead-letter-consumer-group");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // 【面试考点】read_committed：只消费已提交的事务消息
        // 防止消费到事务回滚的消息（僵尸消息）
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        return new DefaultKafkaConsumerFactory<>(props);
    }
}
