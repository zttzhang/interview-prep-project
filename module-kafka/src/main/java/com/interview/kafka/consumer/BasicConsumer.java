package com.interview.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * 【面试考点】Kafka 基础消费者 - @KafkaListener 核心用法
 *
 * 【面试速记】消费者核心概念：
 * 1. 消费者组（Consumer Group）：同组内负载均衡，不同组各自消费（广播）
 * 2. 分区分配：一个分区只能被同组内一个消费者消费
 * 3. Offset 管理：自动提交（可能丢消息）vs 手动提交（推荐）
 *
 * 【面试追问】Kafka 消费者组的作用？
 * → 负载均衡：多个消费者分摊分区，提高消费吞吐量
 * → 容错：某个消费者宕机，其分区会 Rebalance 给其他消费者
 * → 广播：不同消费者组各自消费全量消息（如：订单服务 + 库存服务都消费订单消息）
 *
 * 【面试追问】消费者数量超过分区数会怎样？
 * → 答：多余的消费者会闲置，无法分配到分区
 * → 答：分区数是消费并行度的上限
 */
@Slf4j
@Service
public class BasicConsumer {

    /**
     * 【面试考点】基础消费 - @KafkaListener 参数详解
     *
     * 问题描述：如何消费 Kafka 消息？
     * 解决思路：使用 @KafkaListener 注解，Spring 自动创建消费者容器
     *
     * @KafkaListener 核心参数：
     * - topics：监听的 Topic 列表（支持 SpEL 表达式）
     * - groupId：消费者组 ID（覆盖全局配置）
     * - containerFactory：指定监听容器工厂（用于批量消费、手动提交等）
     * - concurrency：并发消费线程数（每个线程对应一个消费者实例）
     * - topicPartitions：精确指定 Topic + 分区 + 初始 Offset
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（直接接收 String）：
     * //    @KafkaListener(topics = "xxx")
     * //    public void consume(String message) { ... }
     * //    问题：丢失消息元数据（分区、offset、key、时间戳）
     * // ✅ 方案二（接收 ConsumerRecord）：
     * //    public void consume(ConsumerRecord<String, String> record) { ... }
     * //    优点：可以获取完整的消息元数据，用于日志追踪、幂等判断
     * // ==============================
     *
     * 【面试追问】自动提交 offset 有什么问题？
     * → 答：enable.auto.commit=true 时，消费者定期自动提交 offset
     * → 答：如果消费中宕机，offset 已提交但消息未处理完 → 丢消息
     * → 答：生产环境推荐手动提交（处理完业务再提交）
     *
     * @param record Kafka 消息记录（包含 topic、partition、offset、key、value）
     */
    @KafkaListener(
            topics = "interview.basic",
            groupId = "basic-consumer-group",
            // containerFactory = "kafkaListenerContainerFactory"  // 使用默认工厂
            concurrency = "3"  // 3个并发消费者，最多消费3个分区
    )
    public void consume(ConsumerRecord<String, String> record) {
        log.info("收到消息: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value());

        // 【面试考点】自动提交 offset 的问题：
        // 当前配置 enable.auto.commit=false（在 KafkaConsumerConfig 中）
        // 但这里没有手动 ack，Spring Kafka 会在方法正常返回后自动提交
        // 如果方法抛出异常，offset 不会提交，消息会重试

        // 模拟业务处理
        processMessage(record.key(), record.value());

        log.info("消息处理完成: offset={}", record.offset());
    }

    /**
     * 【面试考点】带过滤器的消费 - 使用 @Header 获取消息头
     *
     * 问题描述：如何只消费满足条件的消息？
     * 解决思路：
     * 1. 方案一：在消费方法内过滤（消息已拉取，只是不处理）
     * 2. 方案二：配置 RecordFilterStrategy（在容器工厂中设置，消息被过滤不进入方法）
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（方法内过滤）：消息已拉取到本地，只是不处理
     * //    问题：浪费网络带宽和内存，offset 仍然推进
     * // ✅ 方案二（RecordFilterStrategy）：在容器层过滤
     * //    优点：被过滤的消息直接跳过，不进入业务方法
     * //    配置：factory.setRecordFilterStrategy(record -> !record.value().startsWith("SKIP"))
     * // ==============================
     *
     * 【面试追问】@Header 注解的作用？
     * → 答：从 Kafka 消息头（Headers）中提取指定字段
     * → 答：常用于传递追踪 ID、消息类型、版本号等元数据
     *
     * @param message   消息内容（@Payload 提取消息体）
     * @param topic     消息来源 Topic
     * @param partition 消息所在分区
     * @param offset    消息 Offset
     */
    @KafkaListener(
            topics = "interview.filtered",
            groupId = "filtered-consumer-group"
    )
    public void consumeWithFilter(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("收到消息（带过滤）: topic={}, partition={}, offset={}, message={}",
                topic, partition, offset, message);

        // ========== 方案对比 ==========
        // ❌ 方案一（方法内过滤）：
        if (message == null || message.startsWith("SKIP")) {
            log.info("消息被过滤，跳过处理: message={}", message);
            return; // 直接返回，但 offset 仍然推进
        }
        // ✅ 方案二（推荐）：在 ContainerFactory 中配置 RecordFilterStrategy
        //    factory.setRecordFilterStrategy(record -> record.value().startsWith("SKIP"));
        //    这样被过滤的消息不会进入此方法
        // ==============================

        // 正常业务处理
        log.info("处理消息: {}", message);
        processMessage(null, message);
    }

    /**
     * 模拟业务处理逻辑
     */
    private void processMessage(String key, String value) {
        // 模拟业务处理耗时
        log.debug("执行业务逻辑: key={}, value={}", key, value);
        // 实际业务：调用 Service、写数据库等
    }
}
