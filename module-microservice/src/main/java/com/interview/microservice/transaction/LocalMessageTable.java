package com.interview.microservice.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 【面试考点】本地消息表实现分布式事务
 *
 * 核心思路：将分布式事务拆成本地事务 + 消息最终一致
 *
 * 流程：
 * ① 业务操作 + 插入消息记录（同一本地事务，原子性保证）
 * ② 定时任务扫描未发送消息 → 发送Kafka
 * ③ 消费方幂等处理 + 回调确认
 * ④ 消息标记为已完成
 *
 * 【对比Seata】
 * 本地消息表：侵入性小，性能好，适合最终一致场景
 * Seata AT：自动化程度高，适合强一致场景，但有性能开销
 *
 * 【面试追问】为什么不用Seata？
 * → 答：Seata AT模式需要解析SQL，对数据库有一定侵入性
 * → 答：本地消息表方案只需要一个消息表，代码改动小
 *
 * 【面试追问】消息发送失败了怎么办？
 * → 答：定时任务会不断重试，直到发送成功
 * → 答：可以配合死信队列处理永久失败的消息
 */
@Slf4j
@Component
public class LocalMessageTable {

    private final JdbcTemplate jdbcTemplate;

    public LocalMessageTable(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 【面试考点】第一步：在事务中执行业务并保存消息
     *
     * 关键点：业务操作和消息插入必须在同一个事务中
     * 这样保证：要么都成功，要么都失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void doBusinessAndSaveMessage(Long orderId, Long userId, Long productId, Integer quantity) {
        // ========== 业务操作 ==========
        // 1. 创建订单
        String insertOrder = """
            INSERT INTO orders (order_no, user_id, product_id, quantity, status, create_time)
            VALUES (?, ?, ?, ?, 0, ?)
            """;
        String orderNo = "ORD" + System.currentTimeMillis();
        jdbcTemplate.update(insertOrder, orderNo, userId, productId, quantity, LocalDateTime.now());
        log.info("【本地消息表】订单创建成功: {}", orderNo);

        // 2. 扣减库存（这里简化处理，实际应该有库存服务）
        // ... 库存扣减逻辑 ...

        // ========== 【关键】消息记录也在同一个事务中 ==========
        // 这样保证：订单创建成功，消息一定保存；订单失败，消息一定不保存
        saveMessage(orderNo, "order_created", String.format(
            "{\"orderNo\":\"%s\",\"userId\":%d,\"productId\":%d,\"quantity\":%d}",
            orderNo, userId, productId, quantity
        ));
    }

    /**
     * 【面试考点】保存消息到本地消息表
     */
    private void saveMessage(String bizId, String msgType, String msgContent) {
        String sql = """
            INSERT INTO local_transaction_messages 
            (biz_id, msg_type, msg_content, status, retry_count, create_time, update_time)
            VALUES (?, ?, ?, 0, 0, ?, ?)
            """;

        jdbcTemplate.update(sql, bizId, msgType, msgContent, LocalDateTime.now(), LocalDateTime.now());
        log.info("【本地消息表】消息已保存: bizId={}, type={}", bizId, msgType);
    }

    /**
     * 【面试考点】第二步：定时任务扫描未发送的消息
     *
     * 实现方式：
     * 1. 每隔几秒扫描一次（建议5-10秒）
     * 2. 查询 status=0 的消息（未发送）
     * 3. 发送到MQ
     * 4. 发送成功后更新状态
     *
     * 【面试追问】定时任务扫描的频率怎么定？
     * → 答：根据业务对延迟的容忍度调整
     * → 答：金融支付要求实时性高，可能需要秒级
     * → 答：普通消息几分钟也OK
     */
    public void scanAndSendMessages() {
        String querySql = """
            SELECT id, biz_id, msg_type, msg_content
            FROM local_transaction_messages
            WHERE status = 0 AND retry_count < 3
            ORDER BY create_time ASC
            LIMIT 100
            """;

        // 注意：这里只是演示，实际需要注入 KafkaTemplate
        // List<MessageRecord> messages = jdbcTemplate.query(...);

        // for (MessageRecord msg : messages) {
        //     try {
        //         kafkaTemplate.send("transaction-topic", msg.getBizId(), msg.getMsgContent());
        //         updateMessageStatus(msg.getId(), 1); // 标记已发送
        //         log.info("【本地消息表】消息发送成功: {}", msg.getBizId());
        //     } catch (Exception e) {
        //         incrementRetryCount(msg.getId());
        //         log.error("【本地消息表】消息发送失败: {}", msg.getBizId(), e);
        //     }
        // }
    }

    /**
     * 【面试考点】第三步：消费方处理并回调确认
     *
     * 消费方收到消息后的处理流程：
     * 1. 检查是否已处理（幂等）
     * 2. 执行业务逻辑
     * 3. 调用确认接口更新消息状态
     */
    // public void onMessageReceived(String msgContent) {
    //     // 1. 幂等检查
    //     if (hasProcessed(msgContent)) {
    //         log.info("消息已处理，跳过: {}", msgContent);
    //         return;
    //     }
    //
    //     // 2. 执行业务（发送短信、通知等）
    //     sendNotification(msgContent);
    //
    //     // 3. 回调确认（这里简化，实际应该调用HTTP回调或发送确认消息）
    //     confirmMessage(msgContent);
    // }

    /**
     * 【面试考点】消息状态枚举
     */
    public enum MessageStatus {
        PENDING(0, "待发送"),
        SENT(1, "已发送"),
        CONFIRMED(2, "已确认"),
        FAILED(3, "失败");

        private final int code;
        private final String desc;

        MessageStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
    }
}