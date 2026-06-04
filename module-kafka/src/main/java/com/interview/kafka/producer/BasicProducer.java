package com.interview.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 【面试考点】Kafka 基础生产者 - 同步/异步发送对比
 *
 * 【面试速记】发送方式三选一：
 * 1. 同步发送（.get()阻塞）  - 可靠，但吞吐量低
 * 2. 异步发送（回调）        - 高吞吐，失败需回调处理
 * 3. 发后即忘（fire-and-forget）- 最高吞吐，可能丢消息
 *
 * 【面试追问】Kafka 如何保证消息顺序？
 * → 答：同一分区内消息有序（FIFO）
 * → 答：相同 key 的消息路由到同一分区，保证同一业务实体的消息有序
 * → 答：跨分区无法保证顺序，需要业务层处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasicProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 【面试考点】同步发送 - 阻塞等待 Broker 确认
     *
     * 问题描述：如何确保消息一定发送成功？
     * 解决思路：调用 .get() 阻塞当前线程，等待 Broker 返回 ACK
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（发后即忘）：kafkaTemplate.send(topic, key, message);
     * //    问题：不知道是否成功，可能丢消息
     * // ✅ 方案二（同步等待）：kafkaTemplate.send(...).get();
     * //    优点：确保发送成功，失败立即抛异常
     * //    缺点：阻塞线程，吞吐量低（每条消息都要等待网络往返）
     * // ==============================
     *
     * 【面试追问】同步发送的性能瓶颈在哪里？
     * → 答：每次发送都要等待网络 RTT（往返时延），无法利用批量发送优化
     * → 答：生产环境中，高吞吐场景应使用异步发送
     *
     * @param topic   目标 Topic
     * @param key     消息 Key（相同 key 路由到同一分区，保证顺序）
     * @param message 消息内容
     * @return 发送结果元数据（分区、offset 等）
     */
    public RecordMetadata sendSync(String topic, String key, String message) {
        log.info("同步发送消息: topic={}, key={}, message={}", topic, key, message);
        try {
            // .get() 阻塞等待，超时时间 5 秒
            // 【面试考点】key 的作用：
            //   1. 路由：相同 key 的消息发到同一分区（保证顺序）
            //   2. 压缩：相同 key 的消息可以被日志压缩（Log Compaction）
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, key, message);

            SendResult<String, String> result = future.get(5, TimeUnit.SECONDS);
            RecordMetadata metadata = result.getRecordMetadata();

            log.info("同步发送成功: topic={}, partition={}, offset={}",
                    metadata.topic(), metadata.partition(), metadata.offset());
            return metadata;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("同步发送被中断: topic={}, key={}", topic, key, e);
            throw new RuntimeException("消息发送被中断", e);
        } catch (ExecutionException e) {
            // 【面试考点】发送失败处理策略：
            // 1. 重试：配置 retries 参数自动重试
            // 2. 死信队列：超过重试次数后发送到 DLT
            // 3. 本地存储：写入本地文件，后续补偿
            log.error("同步发送失败: topic={}, key={}", topic, key, e);
            throw new RuntimeException("消息发送失败", e);
        } catch (TimeoutException e) {
            log.error("同步发送超时: topic={}, key={}", topic, key, e);
            throw new RuntimeException("消息发送超时", e);
        }
    }

    /**
     * 【面试考点】异步发送 - 回调处理结果，不阻塞主线程
     *
     * 问题描述：同步发送吞吐量低，如何提升发送性能？
     * 解决思路：异步发送，通过回调（CompletableFuture）处理成功/失败
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（同步）：future.get() 阻塞，吞吐量低
     * // ✅ 方案二（异步回调）：whenComplete() 非阻塞，高吞吐
     * //    优点：主线程不阻塞，可以继续处理其他请求
     * //    缺点：失败处理逻辑在回调中，代码复杂度增加
     * // ==============================
     *
     * 【面试追问】异步发送失败了怎么办？
     * → 答：在 whenComplete 回调中处理失败，可以重试或写入补偿队列
     * → 答：结合 retries 配置，Kafka 会自动重试网络抖动等临时错误
     *
     * @param topic   目标 Topic
     * @param key     消息 Key
     * @param message 消息内容
     */
    public void sendAsync(String topic, String key, String message) {
        log.info("异步发送消息: topic={}, key={}, message={}", topic, key, message);

        kafkaTemplate.send(topic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        // 发送成功
                        RecordMetadata metadata = result.getRecordMetadata();
                        log.info("异步发送成功: topic={}, partition={}, offset={}",
                                metadata.topic(), metadata.partition(), metadata.offset());
                    } else {
                        // 发送失败 - 生产环境应该有告警 + 补偿机制
                        log.error("异步发送失败: topic={}, key={}, error={}",
                                topic, key, ex.getMessage(), ex);
                        // TODO: 生产环境处理方案：
                        // 1. 写入本地数据库，定时任务补偿重发
                        // 2. 发送告警通知
                        // 3. 写入备用消息队列
                        handleSendFailure(topic, key, message, ex);
                    }
                });
    }

    /**
     * 【面试考点】带回调的发送 - 使用 ProducerRecord 精细控制
     *
     * 问题描述：如何在发送时指定分区、时间戳等高级参数？
     * 解决思路：使用 ProducerRecord 构造完整的消息对象
     *
     * ProducerRecord 参数说明：
     * - topic：目标 Topic
     * - partition：指定分区（null = 由分区器决定）
     * - timestamp：消息时间戳（null = 当前时间）
     * - key：消息 Key（影响分区路由）
     * - value：消息内容
     *
     * 【面试追问】如何自定义分区策略？
     * → 答：实现 Partitioner 接口，在 partition() 方法中自定义路由逻辑
     * → 答：例如：按用户ID取模，保证同一用户的消息在同一分区
     *
     * @param topic   目标 Topic
     * @param message 消息内容
     */
    public void sendWithCallback(String topic, String message) {
        // 构造 ProducerRecord，可以精细控制消息属性
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                null,                           // partition: null = 自动分配
                System.currentTimeMillis(),     // timestamp: 当前时间
                "callback-key-" + System.currentTimeMillis(), // key
                message                         // value
        );

        log.info("带回调发送消息: topic={}, message={}", topic, message);

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        RecordMetadata metadata = result.getRecordMetadata();
                        log.info("带回调发送成功: partition={}, offset={}, timestamp={}",
                                metadata.partition(), metadata.offset(), metadata.timestamp());
                    } else {
                        log.error("带回调发送失败: {}", ex.getMessage(), ex);
                    }
                });
    }

    /**
     * 发送失败的补偿处理
     * 生产环境中应该有完善的失败处理机制
     *
     * 【面试考点】消息可靠性保障：
     * 1. at-most-once（最多一次）：发后即忘，可能丢消息
     * 2. at-least-once（至少一次）：失败重试，可能重复
     * 3. exactly-once（精确一次）：幂等生产者 + 事务 + 幂等消费者
     */
    private void handleSendFailure(String topic, String key, String message, Throwable ex) {
        // 生产环境实现：
        // 1. 记录到失败日志表
        // 2. 触发告警（钉钉/邮件）
        // 3. 写入本地补偿队列，定时重试
        log.error("消息发送失败，需要人工介入: topic={}, key={}, message={}", topic, key, message);
    }
}
