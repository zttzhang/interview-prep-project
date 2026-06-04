package com.interview.redis.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 【面试考点】布隆过滤器（Bloom Filter）详细演示
 *
 * 问题描述：如何用极小的内存判断一个元素是否存在于大量数据中？
 * 解决思路：布隆过滤器 - 多个 hash 函数映射到 bit 数组
 *
 * 【面试考点】布隆过滤器原理：
 * 1. 初始化一个 bit 数组（全0）
 * 2. 添加元素时：用 k 个 hash 函数计算 k 个位置，将这些位置置为 1
 * 3. 查询元素时：用同样的 k 个 hash 函数计算 k 个位置
 *    - 如果所有位置都是 1：元素"可能"存在（有误判率）
 *    - 如果有任何位置是 0：元素"一定"不存在（100% 准确）
 *
 * 【面试速记】布隆过滤器的两个核心特性：
 * - False Positive（误判存在）：可能发生，概率约 1%（可配置）
 * - False Negative（误判不存在）：不会发生，100% 准确
 * → 记忆口诀：说"不存在"一定准，说"存在"可能错
 *
 * 【面试追问】为什么布隆过滤器不支持删除？
 * → 答：多个元素可能共享同一个 bit 位
 * → 删除一个元素时，将其对应的 bit 位置为 0
 * → 可能影响其他元素的判断（其他元素也用了这个 bit 位）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterDemo {

    private final RedissonClient redissonClient;

    /**
     * 【面试考点】初始化布隆过滤器
     *
     * 问题描述：如何配置布隆过滤器的参数？
     * 解决思路：根据预期数据量和可接受的误判率来配置
     *
     * 参数说明：
     * - expectedInsertions：预期插入的元素数量（影响 bit 数组大小）
     * - falseProbability：误判率（越小，内存越大，hash 函数越多）
     *
     * 内存估算公式：
     * m = -n * ln(p) / (ln(2))^2
     * 其中 n=元素数量，p=误判率，m=bit 数组大小
     * 例：1亿元素，误判率1% → 约 120MB
     *
     * 【面试追问】误判率和内存的关系？
     * → 误判率 1%：每个元素约 9.6 bits
     * → 误判率 0.1%：每个元素约 14.4 bits
     * → 误判率越低，内存越大
     *
     * @param filterName         布隆过滤器名称
     * @param expectedInsertions 预期插入元素数量
     * @param falseProbability   误判率（0.0 ~ 1.0）
     */
    public void initBloomFilter(String filterName, long expectedInsertions, double falseProbability) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(filterName);

        // tryInit：如果已存在则不重新初始化（幂等）
        boolean initialized = bloomFilter.tryInit(expectedInsertions, falseProbability);

        if (initialized) {
            log.info("【布隆过滤器】初始化成功: name={}, expectedInsertions={}, falseProbability={}",
                    filterName, expectedInsertions, falseProbability);
        } else {
            log.info("【布隆过滤器】已存在，跳过初始化: name={}", filterName);
        }

        log.info("【布隆过滤器】当前元素数量: {}", bloomFilter.count());
    }

    /**
     * 【面试考点】向布隆过滤器添加元素
     *
     * 问题描述：如何向布隆过滤器添加元素？
     * 解决思路：add 方法，内部使用多个 hash 函数计算位置并置为 1
     *
     * 【面试追问】添加元素的时间复杂度？
     * → O(k)，k 是 hash 函数的数量（通常 k = 7~10）
     * → 与元素数量无关，始终是常数时间
     *
     * @param filterName 布隆过滤器名称
     * @param value      要添加的元素
     */
    public void addToBloomFilter(String filterName, String value) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(filterName);
        boolean added = bloomFilter.add(value);
        log.info("【布隆过滤器】添加元素: filterName={}, value={}, isNew={}", filterName, value, added);
    }

    /**
     * 【面试考点】判断元素是否可能存在
     *
     * 问题描述：如何判断一个元素是否在布隆过滤器中？
     * 解决思路：contains 方法，检查所有 hash 位置是否都为 1
     *
     * 返回值含义：
     * - true：元素"可能"存在（有误判率，可能实际不存在）
     * - false：元素"一定"不存在（100% 准确）
     *
     * 【面试追问】查询的时间复杂度？
     * → O(k)，k 是 hash 函数的数量
     * → 与元素数量无关，始终是常数时间
     *
     * @param filterName 布隆过滤器名称
     * @param value      要查询的元素
     * @return true=可能存在，false=一定不存在
     */
    public boolean mightContain(String filterName, String value) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(filterName);
        boolean result = bloomFilter.contains(value);
        log.info("【布隆过滤器】查询元素: filterName={}, value={}, mightContain={}", filterName, value, result);
        return result;
    }

    /**
     * 【面试考点】演示布隆过滤器的误判率
     *
     * 问题描述：布隆过滤器的误判率是多少？如何验证？
     * 解决思路：添加一批元素，然后查询未添加的元素，统计误判次数
     *
     * 【面试追问】布隆过滤器适用场景？
     * 1. 缓存穿透防护：拦截不存在的 key，避免打到 DB
     * 2. 垃圾邮件过滤：判断邮件地址是否在黑名单中
     * 3. URL 去重：爬虫判断 URL 是否已爬取
     * 4. 推荐系统去重：判断用户是否已看过某内容
     *
     * 【面试追问】布隆过滤器 vs Set 的区别？
     * → Set：精确，支持删除，内存占用大（O(n)）
     * → 布隆过滤器：有误判率，不支持删除，内存极小（固定大小）
     * → 1亿元素：Set 约需 1.6GB，布隆过滤器约需 120MB（误判率1%）
     */
    public void demonstrateBloomFilter() {
        String filterName = "demo:bloom:filter";

        // 初始化布隆过滤器（预期1000个元素，误判率1%）
        initBloomFilter(filterName, 1000, 0.01);

        // 添加 1000 个元素（ID 1-1000）
        log.info("【布隆过滤器演示】添加1000个元素（ID 1-1000）");
        for (int i = 1; i <= 1000; i++) {
            addToBloomFilter(filterName, "user:" + i);
        }

        // 验证已添加的元素（应该全部返回 true）
        int falseNegativeCount = 0;
        for (int i = 1; i <= 100; i++) {
            boolean result = mightContain(filterName, "user:" + i);
            if (!result) {
                falseNegativeCount++;
                log.error("【布隆过滤器演示】误判不存在（False Negative）: user:{}", i);
            }
        }
        log.info("【布隆过滤器演示】已添加元素的误判不存在次数: {}（应该为0）", falseNegativeCount);

        // 验证未添加的元素（应该大部分返回 false，少量误判为 true）
        int falsePositiveCount = 0;
        int testCount = 1000;
        for (int i = 1001; i <= 1000 + testCount; i++) {
            boolean result = mightContain(filterName, "user:" + i);
            if (result) {
                falsePositiveCount++;
            }
        }
        double actualFalsePositiveRate = (double) falsePositiveCount / testCount;
        log.info("【布隆过滤器演示】未添加元素的误判存在次数: {}/{}", falsePositiveCount, testCount);
        log.info("【布隆过滤器演示】实际误判率: {:.2f}%（配置误判率: 1%）", actualFalsePositiveRate * 100);

        // 清理
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(filterName);
        bloomFilter.delete();
        log.info("【布隆过滤器演示】演示完成，已清理");
    }
}
