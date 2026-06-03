package com.interview.kafka.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】Kafka 生产者配置 - 可靠性配置
 * 
 * 【面试速记】消息可靠性3大配置：
 * 1. acks=all - 所有ISR副本确认，不丢消息
 * 2. retries=3 - 失败重试（需开启幂等）
 * 3. enable.idempotence=true - 精确一次语义
 */
@Slf4j
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // ========== 基础配置 ==========
        // Kafka 服务地址
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // 序列化器
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // ========== 【面试考点】可靠性核心配置 ==========
        
        /**
         * acks 配置（最重要！）
         * - acks=0：生产者不等Leader确认，可能丢消息（性能最高）
         * - acks=1：Leader确认即成功，可能丢消息（Leader宕机）
         * - acks=all：所有ISR副本确认，不丢消息（性能最低，可靠性最高）
         * 
         * 【面试追问】acks=all 性能很差怎么办？
         * → 答：可以适当调整 min.insync.replicas 参数，在可靠性和性能间平衡
         */
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        
        /**
         * retries 重试次数
         * 网络抖动、Broker暂时不可用时会自动重试
         * 注意：需要开启幂等才能保证不重复
         */
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        
        /**
         * enable.idempotence 幂等发送
         * 开启后，Kafka会自动去除重复消息，实现精确一次语义
         * 
         * 【面试追问】幂等发送的原理？
         * → 答：每个生产者有个唯一的 PID，消息有递增的 sequenceNumber
         * → 答：Broker 会记录每个 PID 发送的消息序列号，重复的会被忽略
         */
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        // ========== 性能优化配置 ==========
        
        /**
         * batch.size 批量发送大小
         * 攒够这个大小的消息才发送，减少网络请求次数
         * 默认 16KB，可以适当调大（如 32KB 或 64KB）
         */
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768); // 32KB
        
        /**
         * linger.ms 等待时间
         * 即使 batch.size 没攒够，等待这个时间也会发送
         * 0 = 有消息就发（低延迟），>0 = 批量发送（高吞吐）
         */
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        /**
         * compression.type 压缩类型
         * 可选：none, gzip, snappy, lz4, zstd
         * snappy：压缩率不错，CPU开销适中，推荐！
         * 
         * 【面试追问】压缩会消耗CPU，为什么还要用？
         * → 答：网络带宽是稀缺资源，压缩可以减少网络传输，提升整体吞吐量
         */
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        /**
         * buffer.memory 发送缓冲区
         * 如果发送速度跟不上，消息会先缓存在这里
         * 满了会阻塞或抛异常
         */
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB
        
        /**
         * max.in.flight.requests.per.connection
         * 单个连接最多有多少个请求在飞
         * 开启幂等后，这个值必须 <= 5
         */
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        
        log.info("Kafka Producer 配置加载: acks=all, retries=3, idempotence=true, compression=snappy");
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}