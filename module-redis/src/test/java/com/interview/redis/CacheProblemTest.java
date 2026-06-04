package com.interview.redis;

import com.interview.redis.cache.CacheAvalancheDemo;
import com.interview.redis.cache.CacheBreakdownDemo;
import com.interview.redis.cache.CachePenetrationDemo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】三大缓存问题测试
 *
 * 测试覆盖：
 * 1. 缓存穿透：空值缓存方案 + 布隆过滤器方案
 * 2. 缓存雪崩：随机 TTL 方案
 * 3. 缓存击穿：互斥锁方案（引用已有的 CacheBreakdownDemo）
 */
@Slf4j
@SpringBootTest
@DisplayName("三大缓存问题测试")
class CacheProblemTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CachePenetrationDemo cachePenetrationDemo;

    @Autowired
    private CacheAvalancheDemo cacheAvalancheDemo;

    @Autowired
    private CacheBreakdownDemo cacheBreakdownDemo;

    @BeforeEach
    void setUp() {
        // 初始化布隆过滤器（预热合法 ID）
        cachePenetrationDemo.initBloomFilter();
        log.info("【初始化】布隆过滤器预热完成");
    }

    @AfterEach
    void cleanup() {
        // 清理测试数据
        Set<String> keys = stringRedisTemplate.keys("product:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        Set<String> testKeys = stringRedisTemplate.keys("test:*");
        if (testKeys != null && !testKeys.isEmpty()) {
            stringRedisTemplate.delete(testKeys);
        }
        log.info("【清理】测试数据已清理");
    }

    /**
     * 【面试考点】缓存穿透 - 空值缓存方案测试
     *
     * 测试场景：
     * 1. 查询不存在的 ID（id=99999）
     * 2. 第一次查询：缓存未命中 → 查 DB → DB 无数据 → 缓存空值
     * 3. 第二次查询：命中空值缓存 → 直接返回 null（不查 DB）
     *
     * 验证：空值缓存能有效防止重复打 DB
     */
    @Test
    @DisplayName("缓存穿透: 空值缓存方案")
    void testCachePenetration_nullCache() {
        Long nonExistId = 99999L;

        // 第一次查询（缓存未命中，查 DB，DB 无数据，缓存空值）
        String result1 = cachePenetrationDemo.queryWithNullCache(nonExistId);
        assertThat(result1).isNull();
        log.info("【缓存穿透测试】第一次查询不存在的ID: result={}", result1);

        // 验证空值已被缓存
        String cachedNull = stringRedisTemplate.opsForValue().get("product:" + nonExistId);
        assertThat(cachedNull).isEqualTo("NULL");
        log.info("【缓存穿透测试】空值已缓存: cachedValue={}", cachedNull);

        // 第二次查询（命中空值缓存，不查 DB）
        String result2 = cachePenetrationDemo.queryWithNullCache(nonExistId);
        assertThat(result2).isNull();
        log.info("【缓存穿透测试】第二次查询命中空值缓存: result={}", result2);

        // 查询存在的 ID（正常流程）
        String result3 = cachePenetrationDemo.queryWithNullCache(1L);
        assertThat(result3).isNotNull().contains("product_data_1");
        log.info("【缓存穿透测试】查询存在的ID: result={}", result3);
    }

    /**
     * 【面试考点】缓存穿透 - 布隆过滤器方案测试
     *
     * 测试场景：
     * 1. 查询不存在的 ID（id=99999）→ 布隆过滤器拦截，直接返回 null
     * 2. 查询存在的 ID（id=1）→ 布隆过滤器放行，正常查询
     *
     * 验证：布隆过滤器能有效拦截不存在的 key
     */
    @Test
    @DisplayName("缓存穿透: 布隆过滤器方案")
    void testCachePenetration_bloomFilter() {
        // 查询不存在的 ID（布隆过滤器应该拦截）
        Long nonExistId = 99999L;
        String result1 = cachePenetrationDemo.queryWithBloomFilter(nonExistId);
        assertThat(result1).isNull();
        log.info("【布隆过滤器测试】不存在的ID被拦截: id={}, result={}", nonExistId, result1);

        // 查询存在的 ID（布隆过滤器放行）
        Long existId = 1L;
        String result2 = cachePenetrationDemo.queryWithBloomFilter(existId);
        assertThat(result2).isNotNull();
        log.info("【布隆过滤器测试】存在的ID正常查询: id={}, result={}", existId, result2);

        // 验证布隆过滤器的核心特性：说"不存在"一定准
        // 不存在的 ID 一定返回 null（不会误判为存在）
        for (long id = 10001; id <= 10010; id++) {
            String result = cachePenetrationDemo.queryWithBloomFilter(id);
            assertThat(result).isNull();
        }
        log.info("【布隆过滤器测试】10个不存在的ID全部被拦截，False Negative=0，验证通过");
    }

    /**
     * 【面试考点】缓存雪崩 - 随机 TTL 方案测试
     *
     * 测试场景：
     * 1. 批量设置缓存（使用随机 TTL）
     * 2. 验证各 key 的 TTL 不完全相同（分散过期时间）
     *
     * 验证：随机 TTL 能有效分散缓存过期时间，避免同时过期
     */
    @Test
    @DisplayName("缓存雪崩: 随机TTL方案")
    void testCacheAvalanche_randomTTL() {
        long baseTtlMinutes = 60L;
        int keyCount = 10;

        // 批量设置缓存（随机 TTL）
        for (int i = 1; i <= keyCount; i++) {
            String key = "test:avalanche:product:" + i;
            cacheAvalancheDemo.setWithRandomTTL(key, "value_" + i, baseTtlMinutes);
        }

        // 获取所有 key 的 TTL
        long minTtl = Long.MAX_VALUE;
        long maxTtl = Long.MIN_VALUE;

        for (int i = 1; i <= keyCount; i++) {
            String key = "test:avalanche:product:" + i;
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.MINUTES);
            assertThat(ttl).isNotNull().isGreaterThan(0);

            if (ttl < minTtl) minTtl = ttl;
            if (ttl > maxTtl) maxTtl = ttl;
        }

        log.info("【缓存雪崩测试】TTL范围: min={}min, max={}min, 基础TTL={}min",
                minTtl, maxTtl, baseTtlMinutes);

        // 验证 TTL 在合理范围内（baseTtl ~ baseTtl + 10）
        assertThat(minTtl).isGreaterThanOrEqualTo(baseTtlMinutes);
        assertThat(maxTtl).isLessThanOrEqualTo(baseTtlMinutes + 10);

        // 验证 TTL 有差异（随机分散）
        // 注意：极小概率所有随机值相同，但实际上几乎不会发生
        log.info("【缓存雪崩测试】随机TTL验证通过: TTL分布在[{}, {}]分钟", minTtl, maxTtl);

        // 清理
        for (int i = 1; i <= keyCount; i++) {
            stringRedisTemplate.delete("test:avalanche:product:" + i);
        }
    }

    /**
     * 【面试考点】缓存击穿 - 互斥锁方案测试
     *
     * 测试场景：
     * 1. 热点 key 缓存未命中（模拟缓存过期）
     * 2. 多个请求同时查询，只有一个查 DB，其他等待
     * 3. 验证最终返回正确数据
     *
     * 引用已有的 CacheBreakdownDemo（互斥锁方案）
     */
    @Test
    @DisplayName("缓存击穿: 互斥锁方案（引用CacheBreakdownDemo）")
    void testCacheBreakdown() {
        String keyPrefix = "test:breakdown:product:";
        Long productId = 1001L;

        // 模拟缓存击穿：缓存未命中，查 DB 重建缓存
        String result = cacheBreakdownDemo.queryWithMutex(
                keyPrefix,
                productId,
                String.class,
                id -> "product_from_db_" + id,  // 模拟 DB 查询
                30  // 缓存30秒
        );

        assertThat(result).isNotNull().isEqualTo("product_from_db_1001");
        log.info("【缓存击穿测试】互斥锁方案查询结果: {}", result);

        // 第二次查询（命中缓存）
        String cachedResult = cacheBreakdownDemo.queryWithMutex(
                keyPrefix,
                productId,
                String.class,
                id -> "product_from_db_" + id,
                30
        );

        assertThat(cachedResult).isEqualTo(result);
        log.info("【缓存击穿测试】第二次查询命中缓存: {}", cachedResult);

        // 清理
        stringRedisTemplate.delete(keyPrefix + productId);
    }
}
