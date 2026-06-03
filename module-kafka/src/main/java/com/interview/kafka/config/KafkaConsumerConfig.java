package com.interview.kafka.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】Kafka 消费者配置
 * 
 * 【面试速记】消费者核心配置：
 * 1. group.id - 消费者组，同组内消息负载均衡
 * 2. enable.auto.commit vs manual commit - 自动/手动提交 offset
 * 3. isolation.level - 事务隔离级别
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // ========== 基础配置 ==========
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // ========== 【面试考点】消费者组配置 ==========
        
        /**
         * group.id 消费者组
         * 同一消费者组内的消费者会负载均衡消费消息
         * 不同消费者组会各自消费一遍（广播模式）
         * 
         * 【面试追问】消费者组内消费者数量过多会怎样？
         * → 答：分区数是上限，超过分区数的消费者会闲置
         */
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "interview-consumer-group");
        
        /**
         * enable.auto.commit 自动提交 offset
         * true：消费后自动提交（可能丢消息，Broker处理）
         * false：手动提交（推荐，业务处理完再提交）
         * 
         * 【面试追问】自动提交有什么问题？
         * → 答：如果消费中宕机，offset已提交但消息未处理完，会丢消息
         */
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // ========== 可靠性配置 ==========
        
        /**
         * auto.offset.reset 消费位点
         * earliest：从头开始消费（可能重复）
         * latest：从最新消息开始（可能丢消息）
         * 
         * 【面试场景】选型建议：
         * - 需要全量数据：earliest
         * - 只关心最新：latest
         */
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        /**
         * max.poll.records 每次拉取消息数量
         * 调大可以提高吞吐量，但会增加单次处理时间
         */
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        
        /**
         * session.timeout.ms 心跳超时
         * 超过这个时间没心跳，Broker会认为消费者挂了
         * 消费者处理慢时要注意调大
         */
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        
        /**
         * heartbeat.interval.ms 心跳间隔
         * 必须小于 session.timeout.ms 的 1/3
         */
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        
        /**
         * isolation.level 事务隔离级别
         * read_uncommitted：未提交的消息也能读到
         * read_committed：只读取已提交的消息
         * 
         * 【面试追问】read_committed 会影响性能吗？
         * → 答：会，因为要等事务提交后才能消费
         */
        props.put("isolation.level", "read_committed");
        
        log.info("Kafka Consumer 配置加载: auto.commit=false, auto.offset.reset=earliest");
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * 【面试考点】手动提交 offset 的容器工厂
     * 
     * 使用手动提交时，需要在监听器中调用 ack.acknowledge()
     * 
     * 【面试场景】为什么需要手动提交？
     * → 答：保证消息处理成功后再提交，避免丢消息
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        
        // 设置为手动提交模式
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // 并发消费者数量（建议与分区数一致）
        factory.setConcurrency(3);
        
        // 批量消费模式（可选）
        // factory.setBatchListener(true);
        
        log.info("Kafka Listener Container 工厂配置: AckMode=MANUAL, concurrency=3");
        
        return factory;
    }
}