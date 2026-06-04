package com.interview.integration.seckill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 【面试考点】Kafka 消费者幂等性设计 - 秒杀订单消费
 *
 * 问题描述：
 *   Kafka 保证 at-least-once 投递，即消息可能被重复消费。
 *   在秒杀场景中，重复消费会导致：
 *   1. 重复创建订单（用户被扣多次款）
 *   2. 库存被多次扣减（超卖）
 *
 * 解决思路（消费者幂等性三步法）：
 *   ① 消费前：检查 Redis 中是否已处理过该 orderId（SET NX）
 *   ② 消费中：执行业务逻辑（写 DB）
 *   ③ 消费后：提交 offset（手动提交，确保业务成功后才提交）
 *
 * 【对比方案】
 * ❌ 方案一（自动提交 offset）：
 *    → 问题：消息拉取后立即提交 offset，业务失败时消息丢失
 * ✅ 方案二（手动提交 offset + Redis 幂等）：
 *    → 优点：业务成功才提交，失败可重试；Redis 防止重复处理
 *
 * 【面试追问】
 * Q: 如何保证消息不丢失且不重复？
 * A: 不丢失：at-least-once（手动提交 offset，失败重试）
 *    不重复：幂等消费（Redis SET NX 记录已处理消息ID）
 *    两者结合 = 最终一致性（exactly-once 语义）
 *
 * Q: Redis 中的幂等 key 过期了怎么办？
 * A: 设置合理的过期时间（如 24h），超过后允许重新处理。
 *    同时在 DB 层加唯一索引兜底（order_no 唯一）
 *
 * Q: 消费失败如何处理？
 * A: 重试策略：
 *    ① Spring Kafka 内置重试（RetryTemplate，默认3次）
 *    ② 超过重试次数 → 发送到死信队列（DLQ）
 *    ③ 死信队列消费者 → 告警 + 人工处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillConsumer {

    private final StringRedisTemplate redisTemplate;

    /**
     * 幂等 key 前缀：记录已处理的订单ID
     * 格式：seckill:processed:{orderId}
     * 过期时间：24小时（超过后允许重新处理，但 DB 唯一索引会兜底）
     */
    private static final String PROCESSED_KEY_PREFIX = "seckill:processed:";
    private static final long PROCESSED_EXPIRE_HOURS = 24;

    // ========== 方案对比：幂等实现 ==========
    // ❌ 方案一（错误示范）：直接判断 DB 是否存在订单
    //    if (orderRepository.existsByOrderNo(orderNo)) { return; }
    //    → 问题：高并发下，两个线程同时判断"不存在"，都去插入，导致重复
    // ✅ 方案二（正确）：Redis SET NX（原子操作）
    //    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", 24h)
    //    → 优点：原子操作，天然防并发；DB 唯一索引作为最后兜底
    // ==============================

    /**
     * 【面试考点】消费秒杀订单消息
     *
     * 问题描述：
     *   消息格式：{"orderNo":"xxx","userId":1,"productId":100}
     *   需要幂等处理，防止重复消费导致重复下单
     *
     * 解决思路：
     *   1. 解析消息，提取 orderNo 作为幂等 key
     *   2. Redis SET NX 尝试占位（原子操作）
     *   3. 占位成功 → 执行业务逻辑（写 DB）
     *   4. 占位失败 → 已处理过，直接跳过
     *   5. 业务失败 → 删除 Redis key，允许重试
     *
     * 【面试追问】
     * Q: @KafkaListener 的 groupId 有什么作用？
     * A: 同一 groupId 的消费者组成消费者组，一个分区只能被组内一个消费者消费。
     *    不同 groupId 的消费者互不影响，都能收到全量消息。
     *
     * Q: containerFactory = "kafkaListenerContainerFactory" 是什么？
     * A: 指定消费者容器工厂，控制并发数、提交方式、错误处理等配置。
     *    手动提交需要配置 AckMode.MANUAL_IMMEDIATE
     *
     * @param record  Kafka 消息记录（包含 key、value、offset、partition 等）
     * @param ack     手动提交 offset 的回调（需配置 AckMode.MANUAL）
     */
    @KafkaListener(
            topics = "seckill-orders",
            groupId = "seckill-consumer-group",
            // containerFactory = "manualAckContainerFactory"  // 生产环境启用手动提交
            concurrency = "3"  // 3个并发消费者线程，对应 topic 的分区数
    )
    public void consumeSeckillOrder(ConsumerRecord<String, String> record
            // , Acknowledgment ack  // 生产环境启用手动提交
    ) {
        String orderNo = record.key();
        String messageJson = record.value();

        log.info("收到秒杀订单消息: orderNo={}, partition={}, offset={}",
                orderNo, record.partition(), record.offset());

        // ========== 第一步：幂等检查（Redis SET NX）==========
        String idempotentKey = PROCESSED_KEY_PREFIX + orderNo;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "processing", Duration.ofHours(PROCESSED_EXPIRE_HOURS));

        if (!Boolean.TRUE.equals(isNew)) {
            // 已处理过，直接跳过（幂等）
            log.warn("重复消息，跳过处理: orderNo={}", orderNo);
            // ack.acknowledge();  // 生产环境：手动提交 offset
            return;
        }

        // ========== 第二步：执行业务逻辑（写 DB）==========
        try {
            processOrder(orderNo, messageJson);

            // 更新幂等 key 状态为"已完成"
            redisTemplate.opsForValue()
                    .set(idempotentKey, "done", Duration.ofHours(PROCESSED_EXPIRE_HOURS));

            // 更新订单状态到 Redis（供前端轮询查询）
            String orderStatusKey = "seckill:order:" + orderNo;
            redisTemplate.opsForValue().set(orderStatusKey, "SUCCESS", Duration.ofHours(1));

            log.info("秒杀订单处理成功: orderNo={}", orderNo);
            // ack.acknowledge();  // 生产环境：手动提交 offset（业务成功后才提交）

        } catch (Exception e) {
            log.error("秒杀订单处理失败: orderNo={}, error={}", orderNo, e.getMessage(), e);

            // ========== 第三步：业务失败，删除幂等 key，允许重试 ==========
            // 【面试考点】为什么要删除 key？
            // 答：业务失败时，删除 Redis key，让消息可以被重新处理（重试）
            // 注意：如果是不可重试的错误（如数据格式错误），不应删除 key，直接进死信队列
            redisTemplate.delete(idempotentKey);

            // 抛出异常，触发 Spring Kafka 重试机制
            // 超过重试次数后，消息会被发送到死信队列（需配置 DeadLetterPublishingRecoverer）
            throw new RuntimeException("秒杀订单处理失败: " + orderNo, e);
        }
    }

    /**
     * 【面试考点】实际业务处理（写 DB）
     *
     * 问题描述：
     *   这里模拟写 DB 操作，实际项目中需要：
     *   1. 创建订单记录（order 表）
     *   2. 扣减 DB 库存（product 表，乐观锁）
     *   3. 创建支付记录（payment 表）
     *   以上三步需要在同一个事务中执行
     *
     * 解决思路：
     *   @Transactional 保证原子性
     *   DB 层唯一索引（order_no）作为最后兜底，防止重复插入
     *
     * 【面试追问】
     * Q: Kafka 消费者中能用 @Transactional 吗？
     * A: 可以，但要注意：
     *    ① DB 事务 和 Kafka offset 提交 是两个独立的事务
     *    ② DB 事务成功但 offset 未提交 → 消息重复消费（需幂等处理）
     *    ③ 真正的 exactly-once 需要 Kafka 事务 + DB 事务协调（复杂度高）
     *
     * @param orderNo     订单号
     * @param messageJson 消息 JSON
     */
    private void processOrder(String orderNo, String messageJson) {
        // 模拟解析消息
        log.info("开始处理订单: orderNo={}, message={}", orderNo, messageJson);

        // 模拟 DB 写入（实际项目中调用 OrderService.createOrder()）
        // orderService.createOrder(orderNo, userId, productId);

        // 模拟耗时操作（DB 写入通常需要几毫秒到几十毫秒）
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("订单写入 DB 成功（模拟）: orderNo={}", orderNo);

        // 模拟偶发失败（用于演示重试机制）
        // if (Math.random() < 0.1) {
        //     throw new RuntimeException("模拟 DB 写入失败");
        // }
    }
}
