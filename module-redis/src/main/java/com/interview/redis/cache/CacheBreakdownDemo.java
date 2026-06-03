package com.interview.redis.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 【面试考点】缓存击穿解决方案 - 互斥锁方案 + 逻辑过期方案
 * 
 * 问题描述：
 * 热点key过期瞬间，大量请求同时打到DB，造成数据库压力激增
 * 
 * 场景：双十一商品详情页，热点商品缓存过期
 * 
 * 【面试速记】缓存击穿 vs 穿透 vs 雪崩
 * - 穿透：key不存在，大量请求打到DB
 * - 击穿：key过期，大量请求同时打到DB
 * - 雪崩：大量key同时过期
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheBreakdownDemo {

    private final StringRedisTemplate redisTemplate;
    private final ReentrantLock localLock = new ReentrantLock();

    /**
     * 【面试考点】方案一：互斥锁方案
     * 
     * 原理：
     * 1. 查缓存 miss 后，不直接查DB
     * 2. 先抢分布式锁
     * 3. 抢到锁的查DB并重建缓存
     * 4. 未抢到锁的等待重试（或返回降级数据）
     * 
     * 优点：数据强一致
     * 缺点：有等待延迟，高并发时性能较差
     * 
     * 【面试追问】如果缓存重建很慢，等待线程太多怎么办？
     * → 答：限制等待次数，超过阈值直接返回降级数据（兜底）
     */
    public <T> T queryWithMutex(String keyPrefix, Long id, Class<T> type,
                                  Function<Long, T> dbFallback, long expireSeconds) {
        String key = keyPrefix + id;
        
        // 第一步：查缓存
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("【互斥锁方案】缓存命中: key={}", key);
            return parseValue(cached, type);
        }
        
        // 第二步：缓存未命中，尝试获取分布式锁
        String lockKey = "lock:" + key;
        String lockValue = Thread.currentThread().getName();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
        
        if (Boolean.TRUE.equals(acquired)) {
            try {
                // 第三步：抢到锁，再查一次缓存（防止其他线程已重建）
                cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return parseValue(cached, type);
                }
                
                // 第四步：查DB重建缓存
                log.info("【互斥锁方案】抢到锁，查DB重建缓存: key={}", key);
                T result = dbFallback.apply(id);
                if (result != null) {
                    redisTemplate.opsForValue().set(key, serializeValue(result), expireSeconds, TimeUnit.SECONDS);
                }
                return result;
            } finally {
                // 第五步：释放锁
                redisTemplate.delete(lockKey);
            }
        } else {
            // 第六步：未抢到锁，等待后重试（或返回降级数据）
            log.info("【互斥锁方案】未抢到锁，等待重试: key={}", key);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 重试查缓存
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return parseValue(cached, type);
            }
            
            // 超过等待次数，返回降级数据或null
            log.warn("【互斥锁方案】重试后仍未命中，返回null: key={}", key);
            return null;
        }
    }

    /**
     * 【面试考点】方案二：逻辑过期方案（推荐）
     * 
     * 原理：
     * 1. 缓存永不过期，但在value中存储"逻辑过期时间"
     * 2. 查询时检查逻辑过期时间
     * 3. 如果逻辑过期，触发异步重建（使用单独线程，不阻塞）
     * 4. 旧数据继续返回，保证性能
     * 
     * 优点：性能好，无等待延迟
     * 缺点：短暂返回旧数据（数据弱一致）
     * 
     * 【面试追问】逻辑过期方案如何避免缓存击穿？
     * → 答：缓存永不过期，只有"逻辑过期"，所以不会有击穿问题
     * 
     * 【对比方案】互斥锁方案见 {@link #queryWithMutex}
     *   互斥锁：数据强一致，但有等待延迟
     *   逻辑过期：性能更好，但会短暂返回旧数据
     * 
     * 【面试场景】实际选型建议：
     * - 数据一致性要求高（如价格、库存）→ 互斥锁
     * - 数据一致性要求低（如商品详情、评论）→ 逻辑过期
     */
    public <T> T queryWithLogicalExpire(String keyPrefix, Long id, Class<T> type,
                                         Function<Long, T> dbFallback, long logicalExpireSeconds) {
        String key = keyPrefix + id;
        
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            log.info("【逻辑过期方案】缓存不存在，查DB: key={}", key);
            T result = dbFallback.apply(id);
            if (result != null) {
                // 存入缓存，永不过期
                String value = buildLogicalExpireValue(result, logicalExpireSeconds);
                redisTemplate.opsForValue().set(key, value);
            }
            return result;
        }
        
        // 解析缓存数据
        LogicalExpireData<T> data = parseLogicalExpireValue(cached, type);
        
        // 检查逻辑过期时间
        if (data.isExpired()) {
            log.info("【逻辑过期方案】数据逻辑过期，异步重建: key={}", key);
            
            // 异步重建缓存（这里简化处理，实际用线程池）
            new Thread(() -> {
                try {
                    T newData = dbFallback.apply(id);
                    if (newData != null) {
                        String value = buildLogicalExpireValue(newData, logicalExpireSeconds);
                        redisTemplate.opsForValue().set(key, value);
                    }
                } catch (Exception e) {
                    log.error("异步重建缓存失败", e);
                }
            }).start();
        }
        
        // 返回旧数据（可能已过期，但保证可用）
        return data.getData();
    }

    // ========== 辅助方法 ==========

    private <T> String serializeValue(T value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private <T> T parseValue(String json, Class<T> type) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }

    private <T> String buildLogicalExpireValue(T data, long expireSeconds) {
        try {
            var obj = new java.util.HashMap<String, Object>();
            obj.put("data", data);
            obj.put("expireTime", System.currentTimeMillis() + expireSeconds * 1000);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private <T> LogicalExpireData<T> parseLogicalExpireValue(String json, Class<T> type) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            T data = mapper.readValue(node.get("data").toString(), type);
            long expireTime = node.get("expireTime").asLong();
            return new LogicalExpireData<>(data, expireTime);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }

    // 逻辑过期数据包装类
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class LogicalExpireData<T> {
        private T data;
        private long expireTime;
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}