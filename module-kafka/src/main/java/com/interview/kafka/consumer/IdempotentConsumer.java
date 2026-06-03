package com.interview.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 【面试考点】Kafka 幂等消费 - 数据库唯一索引方案
 * 
 * 【面试速记】幂等消费3种方案：
 * 1. 数据库唯一索引（推荐）- 简单可靠
 * 2. Redis Setnx - 性能好，但有双写一致性风险
 * 3. 消息表 + 状态机 - 最可靠，但实现复杂
 * 
 * 【面试追问】为什么不用 Redis 做幂等？
 * → 答：Redis 可以做，但要考虑 Redis 和 DB 的双写一致性问题
 * → 答：DB 唯一索引更简单可靠，天然保证一致性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentConsumer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 【面试考点】幂等消费实现 - 数据库唯一索引
     * 
     * 流程：
     * 1. 消息带全局唯一ID（msgId）
     * 2. 消费前查 DB 是否已处理
     * 3. 处理完插入消费记录（唯一索引防并发重复）
     * 4. 重复消息 catch DuplicateKeyException 直接 ack，不抛出
     * 
     * 【面试追问】为什么先查后插入，而不是直接插入？
     * → 答：先查可以减少异常处理，而且查询比插入代价小
     * → 答：而且有些场景需要根据是否已处理来做不同逻辑
     */
    @KafkaListener(topics = "idempotent-test-topic", groupId = "idempotent-consumer-group")
    public void consumeIdempotent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String msgId = record.key(); // 消息的唯一标识
        String msgContent = record.value();
        
        log.info("收到消息: msgId={}, content={}", msgId, msgContent);
        
        try {
            // 第一步：检查是否已处理
            if (isProcessed(msgId)) {
                log.info("消息已处理，跳过: msgId={}", msgId);
                ack.acknowledge();
                return;
            }
            
            // 第二步：执行业务逻辑
            processMessage(msgContent);
            
            // 第三步：记录消费记录（使用唯一索引防止重复）
            saveConsumeRecord(msgId, msgContent);
            
            // 第四步：手动提交 offset
            ack.acknowledge();
            log.info("消息处理成功: msgId={}", msgId);
            
        } catch (DataIntegrityViolationException e) {
            // 【面试考点】捕获唯一索引冲突 = 重复消息，直接ACK
            // 说明其他消费者已经处理过了，我们直接跳过
            log.info("消息重复消费，已跳过: msgId={}", msgId);
            ack.acknowledge();
            
        } catch (Exception e) {
            log.error("消息处理失败: msgId={}", msgId, e);
            // 处理失败，不ack，消息会重试
            // 实际生产中应该设置重试次数上限
            throw e;
        }
    }

    /**
     * 检查消息是否已处理
     */
    private boolean isProcessed(String msgId) {
        String sql = "SELECT COUNT(*) FROM message_consume_records WHERE msg_id = ? AND status = 1";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, msgId);
        return count != null && count > 0;
    }

    /**
     * 保存消费记录
     * 使用 INSERT ... ON CONFLICT 实现 upsert
     */
    private void saveConsumeRecord(String msgId, String msgContent) {
        // PostgreSQL 的 upsert 语法
        String sql = """
            INSERT INTO message_consume_records (msg_id, msg_content, status, create_time, update_time)
            VALUES (?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (msg_id) DO UPDATE SET update_time = CURRENT_TIMESTAMP
            """;
        
        // 或者使用 MySQL 的语法：
        // String sql = "INSERT INTO message_consume_records (msg_id, msg_content, status) " +
        //              "VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE update_time = CURRENT_TIMESTAMP";
        
        jdbcTemplate.update(sql, msgId, msgContent);
    }

    /**
     * 业务处理逻辑
     */
    private void processMessage(String msgContent) {
        // 模拟业务处理
        log.info("执行业务逻辑: {}", msgContent);
        
        // 模拟处理时间
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========== 测试方法 ==========

    /**
     * 模拟发送重复消息
     * 测试幂等性
     */
    public static String generateMsgId() {
        return UUID.randomUUID().toString();
    }
}