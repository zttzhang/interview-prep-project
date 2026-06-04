package com.interview.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 【面试考点】Kafka 可靠生产者 - 消息可靠性三个层次
 *
 * 消息可靠性三个层次（面试必背）：
 * ┌─────────────────┬──────────────┬──────────────┬──────────────┐
 * │    语义          │  可能丢消息   │  可能重复     │  性能        │
 * ├─────────────────┼──────────────┼──────────────┼──────────────┤
 * │ at-most-once    │     是       │     否       │   最高       │
 * │ at-least-once   │     否       │     是       │   中等       │
 * │ exactly-once    │     否       │     否       │   最低       │
 * └─────────────────┴──────────────┴──────────────┴──────────────┘
 *
 * 如何实现 exactly-once：
 * 1. 幂等生产者（enable.idempotence=true）：PID + 序列号去重
 * 2. 事务（transactional.id）：原子写入多分区
 * 3. 幂等消费者：消费端去重（数据库唯一索引等）
 *
 * 【面试追问】acks=all 就能保证不丢消息吗？
 * → 答：acks=all 保证 Leader + 所有 ISR 副本都写入成功
 * → 答：但如果 min.insync.replicas=1，ISR 只有 Leader，等同于 acks=1
 * → 答：生产环境建议：acks=all + min.insync.replicas=2 + replication.factor=3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReliableProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    // ========== 可靠性配置说明（通过 application.yml 配置）==========
    // spring.kafka.producer.acks=all
    //   → acks=0：不等确认，最高吞吐，可能丢消息
    //   → acks=1：Leader 确认，Leader 宕机可能丢消息
    //   → acks=all：所有 ISR 副本确认，最可靠（推荐生产环境）
    //
    // spring.kafka.producer.retries=3
    //   → 网络抖动时自动重试，最多 3 次
    //   → 注意：重试可能导致消息重复，需要幂等消费
    //   → 开启幂等（enable.idempotence=true）后，重试不会重复
    //
    // spring.kafka.producer.properties.enable.idempotence=true
    //   → 幂等生产者：PID（Producer ID）+ 序列号（Sequence Number）
    //   → Broker 记录每个 PID 的最大序列号，重复消息直接丢弃
    //   → 保证单分区内 exactly-once（注意：只是单分区！）
    //
    // spring.kafka.producer.compression-type=snappy
    //   → 压缩类型对比：
    //   → none：不压缩，CPU 最低，网络带宽最高
    //   → gzip：压缩率最高（~70%），CPU 开销大，适合冷数据
    //   → snappy：压缩率中等（~50%），CPU 开销小，推荐！
    //   → lz4：压缩/解压最快，压缩率略低于 snappy
    //   → zstd：新一代压缩算法，压缩率高且速度快（Kafka 2.1+）
    // ================================================================

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * 【面试考点】可靠发送 - acks=all + 幂等 + 重试
     *
     * 问题描述：如何在生产环境中保证消息不丢失？
     * 解决思路：三重保障 = acks=all（Broker端）+ 幂等（去重）+ 重试（容错）
     *
     * 【对比方案】
     * // ========== 方案对比 ==========
     * // ❌ 方案一（默认配置）：acks=1，可能丢消息
     * //    场景：Leader 确认后宕机，Follower 未同步，消息丢失
     * // ✅ 方案二（acks=all）：所有 ISR 副本确认
     * //    优点：即使 Leader 宕机，Follower 也有完整数据
     * //    缺点：延迟增加（需等待所有 ISR 确认）
     * // ==============================
     *
     * 【面试追问】ISR 是什么？
     * → 答：In-Sync Replicas，与 Leader 保持同步的副本集合
     * → 答：落后太多的副本会被踢出 ISR（由 replica.lag.time.max.ms 控制）
     * → 答：acks=all 只需要 ISR 中的副本确认，不是所有副本
     *
     * @param topic   目标 Topic
     * @param key     消息 Key（相同 key 路由到同一分区）
     * @param message 消息内容
     * @return 发送结果元数据
     */
    public RecordMetadata sendReliable(String topic, String key, String message) {
        log.info("可靠发送消息: topic={}, key={}, message={}", topic, key, message);

        try {
            // 同步等待，确保消息发送成功
            // 配置了 acks=all，会等待所有 ISR 副本确认
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, key, message);

            SendResult<String, String> result = future.get(10, TimeUnit.SECONDS);
            RecordMetadata metadata = result.getRecordMetadata();

            log.info("可靠发送成功: topic={}, partition={}, offset={}, timestamp={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp());

            return metadata;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("可靠发送被中断: topic={}, key={}", topic, key, e);
            throw new RuntimeException("消息发送被中断", e);
        } catch (ExecutionException e) {
            // 【面试考点】发送失败的处理策略
            // 1. 记录到本地数据库（消息表），定时任务补偿重发
            // 2. 发送告警通知运维人员
            // 3. 如果是业务消息，可以写入死信队列人工处理
            log.error("可靠发送失败（已重试{}次）: topic={}, key={}", 3, topic, key, e);
            throw new RuntimeException("消息发送失败，已超过重试次数", e);
        } catch (TimeoutException e) {
            log.error("可靠发送超时: topic={}, key={}", topic, key, e);
            throw new RuntimeException("消息发送超时", e);
        }
    }

    /**
     * 【面试考点】演示幂等性 - 重复发送相同消息只写入一次
     *
     * 问题描述：网络重试导致消息重复，如何在 Broker 端去重？
     * 解决思路：幂等生产者 = PID（Producer ID）+ 序列号（Sequence Number）
     *
     * 幂等原理详解：
     * 1. 每个 Producer 启动时，Broker 分配唯一 PID
     * 2. 每条消息携带 <PID, 分区, 序列号>
     * 3. Broker 记录每个 <PID, 分区> 的最大序列号
     * 4. 收到序列号 ≤ 已记录最大值的消息，直接丢弃（幂等）
     * 5. 序列号不连续（跳跃），Broker 拒绝并报错
     *
     * 【面试追问】幂等生产者能保证跨分区的 exactly-once 吗？
     * → 答：不能！幂等只保证单分区内的 exactly-once
     * → 答：跨分区需要事务（transactional.id）
     * → 答：幂等 + 事务 = 完整的 exactly-once 语义
     *
     * 【面试追问】Producer 重启后 PID 会变吗？
     * → 答：会变！PID 是运行时分配的，重启后 PID 不同
     * → 答：所以幂等只保证单次运行期间的去重
     * → 答：跨会话去重需要事务 ID（transactional.id）
     */
    public void demonstrateIdempotence() {
        String topic = "idempotence-demo-topic";
        String key = "same-key-001";
        String message = "这条消息会被重复发送，但 Broker 只写入一次";

        log.info("=== 演示幂等性：重复发送相同消息 ===");
        log.info("配置：enable.idempotence=true");
        log.info("原理：PID={} + 序列号，Broker 自动去重", "由Broker分配，运行时可见");

        // 模拟网络重试：发送 3 次相同的消息
        // 开启幂等后，Broker 只会写入 1 次
        for (int i = 1; i <= 3; i++) {
            log.info("第 {} 次发送（模拟重试）: key={}", i, key);
            try {
                // 注意：真正的幂等去重发生在 Broker 端
                // 这里只是演示概念，实际重试由 Kafka 内部机制触发
                CompletableFuture<SendResult<String, String>> future =
                        kafkaTemplate.send(topic, key, message + "-attempt-" + i);
                SendResult<String, String> result = future.get(5, TimeUnit.SECONDS);
                log.info("发送成功: partition={}, offset={}",
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } catch (Exception e) {
                log.error("发送失败: attempt={}", i, e);
            }
        }

        log.info("=== 幂等演示完成 ===");
        log.info("结论：开启幂等后，即使网络重试，Broker 保证每条消息只写入一次");

        // ========== 方案对比 ==========
        // ❌ 方案一（不开启幂等）：
        //    网络抖动 → 重试 → Broker 收到重复消息 → 写入多次
        //    消费者会收到重复消息，需要消费端去重
        // ✅ 方案二（开启幂等）：
        //    网络抖动 → 重试 → Broker 检测到重复序列号 → 丢弃重复消息
        //    消费者不会收到重复消息（单分区内）
        // ==============================
    }

    /**
     * 【面试考点】压缩配置对比演示
     *
     * 压缩类型选型建议：
     * - 日志/文本数据：snappy（压缩率好，CPU 开销小）
     * - 归档/冷数据：gzip（压缩率最高，节省存储）
     * - 实时流处理：lz4（速度最快，延迟最低）
     * - 新项目（Kafka 2.1+）：zstd（综合最优）
     *
     * 【面试追问】压缩在哪里解压？
     * → 答：Producer 压缩 → Broker 存储（不解压）→ Consumer 解压
     * → 答：Broker 只在需要消息格式转换时才解压（尽量避免）
     */
    public void demonstrateCompression() {
        log.info("=== 压缩配置说明 ===");
        log.info("当前配置：compression.type=snappy");
        log.info("压缩对比：");
        log.info("  none  : 不压缩，CPU=0，带宽=100%");
        log.info("  gzip  : 压缩率~70%，CPU=高，适合冷数据");
        log.info("  snappy: 压缩率~50%，CPU=中，推荐！");
        log.info("  lz4   : 压缩率~45%，CPU=低，速度最快");
        log.info("  zstd  : 压缩率~60%，CPU=中，新一代推荐");
    }
}
