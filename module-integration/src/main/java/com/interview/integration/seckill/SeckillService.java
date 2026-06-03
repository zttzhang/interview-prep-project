package com.interview.integration.seckill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

/**
 * 【面试考点】秒杀系统核心服务 - 完整链路演示
 * 
 * 秒杀流程：
 * ① 请求进入 → 检查Redis库存（原子扣减，Lua脚本）
 * ② 库存不足 → 直接返回失败（不走DB）
 * ③ 库存充足 → 发送Kafka消息（异步下单，快速响应用户）
 * ④ Consumer消费消息 → 写DB（幂等处理，防止重复下单）
 * ⑤ 失败 → 进入死信队列 → 告警 + 人工处理
 * 
 * 【面试追问】为什么要用 Kafka 而不是直接写 DB？
 * → 答：异步解耦，高并发下快速响应用户。同步写 DB 会阻塞请求
 * 
 * 【面试追问】如何保证不超卖？
 * → 答：Redis Lua 脚本保证原子性扣减，扣到0就不再减
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 【面试考点】Lua脚本实现原子库存扣减
     * 
     * Lua脚本内容（逐行解释）：
     */
    private static final String STOCK_LUA_SCRIPT = """
            -- 第一步：检查商品是否存在
            if redis.call('get', KEYS[1]) == nil then
                return -1  -- 商品不存在
            end
            -- 第二步：检查库存是否充足
            if tonumber(redis.call('get', KEYS[1])) <= 0 then
                return 0   -- 库存不足
            end
            -- 第三步：原子扣减库存
            redis.call('decr', KEYS[1])
            return 1       -- 扣减成功
            """;

    /**
     * 【面试考点】秒杀接口 - 快速响应用户
     * 
     * 设计思路：
     * 1. 用户请求先到 Controller
     * 2. Controller 调用 SeckillService
     * 3. Service 用 Lua 脚本原子扣减 Redis 库存
     * 4. 扣减成功，发送 Kafka 消息，立即返回"排队中"
     * 5. Consumer 异步消费，创建订单
     * 
     * 【面试追问】为什么要立即返回，不等订单创建完成？
     * → 答：高并发场景，同步等待会拖慢响应时间
     * → 答：用户只需要知道"请求成功"，订单状态可以通过其他方式查询
     */
    public SeckillResult seckill(Long userId, Long productId) {
        log.info("收到秒杀请求: userId={}, productId={}", userId, productId);
        
        // 第一步：检查用户是否已经购买过（防重复秒杀）
        String orderedKey = "seckill:ordered:" + productId + ":" + userId;
        Boolean alreadyOrdered = redisTemplate.hasKey(orderedKey);
        if (Boolean.TRUE.equals(alreadyOrdered)) {
            return SeckillResult.fail("您已购买过该商品");
        }
        
        // 第二步：原子扣减库存（Lua脚本保证原子性）
        String stockKey = "seckill:stock:" + productId;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(STOCK_LUA_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(stockKey));
        
        // 第三步：处理扣减结果
        if (result == null || result == -1) {
            // 商品不存在
            log.warn("商品不存在: productId={}", productId);
            return SeckillResult.fail("商品不存在");
        }
        
        if (result == 0) {
            // 库存不足
            log.info("库存不足: productId={}", productId);
            return SeckillResult.fail("库存不足");
        }
        
        // 第四步：扣减成功，标记用户已购买
        redisTemplate.opsForValue().set(orderedKey, "1");
        
        // 第五步：发送 Kafka 消息（异步下单）
        String orderNo = UUID.randomUUID().toString();
        SeckillMessage message = new SeckillMessage(orderNo, userId, productId);
        kafkaTemplate.send("seckill-topic", orderNo, message.toJson());
        
        log.info("秒杀成功，订单排队中: orderNo={}", orderNo);
        return SeckillResult.success(orderNo, "秒杀成功，订单正在处理中");
    }

    /**
     * 【面试考点】初始化秒杀库存
     * 
     * 场景：活动开始前，需要将 DB 中的库存同步到 Redis
     */
    public void initStock(Long productId, Integer stock) {
        String stockKey = "seckill:stock:" + productId;
        redisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
        log.info("初始化秒杀库存: productId={}, stock={}", productId, stock);
    }

    /**
     * 【面试考点】查询秒杀结果
     */
    public SeckillResult queryResult(String orderNo) {
        // 查询订单状态
        String orderKey = "seckill:order:" + orderNo;
        String status = redisTemplate.opsForValue().get(orderKey);
        
        if (status == null) {
            return SeckillResult.processing(orderNo, "订单处理中");
        }
        
        if ("SUCCESS".equals(status)) {
            return SeckillResult.success(orderNo, "下单成功");
        }
        
        return SeckillResult.fail("下单失败");
    }

    // ========== 内部类 ==========

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SeckillResult {
        private String orderNo;
        private String message;
        private boolean success;
        
        public static SeckillResult success(String orderNo, String message) {
            return new SeckillResult(orderNo, message, true);
        }
        
        public static SeckillResult fail(String message) {
            return new SeckillResult(null, message, false);
        }
        
        public static SeckillResult processing(String orderNo, String message) {
            return new SeckillResult(orderNo, message, true);
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SeckillMessage {
        private String orderNo;
        private Long userId;
        private Long productId;
        
        public String toJson() {
            return String.format("{\"orderNo\":\"%s\",\"userId\":%d,\"productId\":%d}", 
                    orderNo, userId, productId);
        }
    }
}