package com.interview.redis.datatype;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 【面试考点】Redis Hash 类型核心操作演示
 *
 * 问题描述：Hash 是 Redis 中存储对象的最佳数据结构之一
 * 解决思路：掌握 Hash 的典型使用场景，理解 Hash vs String 的选型依据
 *
 * 【面试速记】Hash 底层编码：
 * - 元素数量 <= 128 且每个 value <= 64字节：listpack（紧凑存储，内存效率高）
 * - 超过阈值：hashtable（O(1) 查找）
 *
 * 【面试追问】Hash 和 String 存对象有什么区别？
 * → 见 {@link #hashVsStringComparison()} 方法
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HashOpsDemo {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 【面试考点】购物车场景 - Hash 存储购物车
     *
     * 问题描述：购物车需要频繁增删改查商品，如何高效存储？
     * 解决思路：Hash key=userId, field=productId, value=quantity
     *
     * ========== 方案对比 ==========
     * ❌ 方案一（String存JSON）：
     *    key: cart:1001
     *    value: {"101":2, "102":1, "103":3}
     *    缺点：修改单个商品数量需要读取整个JSON，反序列化后修改，再序列化写回
     *          并发修改时容易产生数据覆盖问题
     *
     * ✅ 方案二（Hash存购物车）：
     *    key: cart:1001
     *    field: productId, value: quantity
     *    优点：HSET 直接修改单个字段，无需读取整个对象
     *          HDEL 直接删除单个商品，操作粒度细
     * ==============================
     *
     * 【面试追问】购物车数据量大时如何优化？
     * → 答：Hash 元素超过 128 个时，底层从 listpack 转为 hashtable，内存占用增加
     * → 可以考虑分片存储，或者将不活跃购物车数据持久化到 DB
     *
     * 适用场景：购物车、用户配置、商品属性
     */
    public void shoppingCartDemo() {
        String cartKey = "cart:user:1001";

        // HSET：添加商品到购物车（field=商品ID, value=数量）
        redisTemplate.opsForHash().put(cartKey, "product:101", 2);
        redisTemplate.opsForHash().put(cartKey, "product:102", 1);
        redisTemplate.opsForHash().put(cartKey, "product:103", 3);
        log.info("【购物车】添加商品完成");

        // HGET：获取单个商品数量
        Object quantity = redisTemplate.opsForHash().get(cartKey, "product:101");
        log.info("【购物车】商品101数量: {}", quantity);

        // HGETALL：获取整个购物车
        Map<Object, Object> cart = redisTemplate.opsForHash().entries(cartKey);
        log.info("【购物车】购物车内容: {}", cart);

        // HINCRBY：修改商品数量（原子操作）
        redisTemplate.opsForHash().increment(cartKey, "product:101", 1);
        log.info("【购物车】商品101数量+1后: {}", redisTemplate.opsForHash().get(cartKey, "product:101"));

        // HDEL：删除商品
        redisTemplate.opsForHash().delete(cartKey, "product:102");
        log.info("【购物车】删除商品102后，购物车: {}", redisTemplate.opsForHash().entries(cartKey));

        // HLEN：获取购物车商品种类数
        Long size = redisTemplate.opsForHash().size(cartKey);
        log.info("【购物车】购物车商品种类数: {}", size);

        // 清理
        redisTemplate.delete(cartKey);
    }

    /**
     * 【面试考点】用户信息存储 - Hash 支持部分字段更新
     *
     * 问题描述：用户信息有多个字段，如何只更新某个字段而不影响其他字段？
     * 解决思路：Hash 的 HSET 可以只更新指定字段，不影响其他字段
     *
     * 【对比方案】
     * ❌ String 存 JSON：更新昵称需要读取整个 JSON → 修改 → 写回，有并发覆盖风险
     * ✅ Hash 存字段：HSET user:1001 nickname "新昵称"，只更新 nickname 字段
     *
     * 【面试追问】Hash 存用户信息 vs 关系型数据库有什么区别？
     * → Redis Hash：读写速度快，但不支持复杂查询（如按年龄范围查询）
     * → MySQL：支持复杂查询，但读写速度慢
     * → 实际场景：MySQL 存储，Redis Hash 作为缓存层
     *
     * 适用场景：用户Profile缓存、商品详情缓存
     */
    public void userProfileDemo() {
        String userKey = "user:profile:1001";

        // 批量设置用户信息（HMSET）
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", 1001);
        userInfo.put("username", "zhangsan");
        userInfo.put("nickname", "张三");
        userInfo.put("age", 25);
        userInfo.put("email", "zhangsan@example.com");
        userInfo.put("phone", "13800138000");
        redisTemplate.opsForHash().putAll(userKey, userInfo);
        log.info("【用户信息】初始化用户信息完成");

        // HGET：获取单个字段（只读取需要的字段，节省带宽）
        Object nickname = redisTemplate.opsForHash().get(userKey, "nickname");
        log.info("【用户信息】用户昵称: {}", nickname);

        // HSET：只更新昵称（不影响其他字段）
        redisTemplate.opsForHash().put(userKey, "nickname", "张三丰");
        log.info("【用户信息】昵称更新为: {}", redisTemplate.opsForHash().get(userKey, "nickname"));

        // HMGET：批量获取多个字段
        java.util.List<Object> fields = redisTemplate.opsForHash().multiGet(userKey,
                java.util.Arrays.asList("username", "nickname", "age"));
        log.info("【用户信息】批量获取字段: username={}, nickname={}, age={}", fields.get(0), fields.get(1), fields.get(2));

        // HEXISTS：判断字段是否存在
        Boolean hasEmail = redisTemplate.opsForHash().hasKey(userKey, "email");
        log.info("【用户信息】是否有email字段: {}", hasEmail);

        // 清理
        redisTemplate.delete(userKey);
    }

    /**
     * 【面试考点】Hash vs String 存储对象的对比分析
     *
     * 问题描述：存储一个用户对象，应该用 Hash 还是 String（JSON）？
     * 解决思路：根据访问模式选择合适的数据结构
     *
     * ========== 方案对比 ==========
     * ✅ Hash 存对象：
     *    优点：
     *      1. 可以只更新某个字段（HSET），无需读取整个对象
     *      2. 可以只读取某个字段（HGET），节省带宽
     *      3. 支持字段级别的原子操作（HINCRBY）
     *    缺点：
     *      1. 不支持嵌套对象（value 只能是字符串）
     *      2. 序列化/反序列化需要手动处理
     *      3. 内存占用比 String 略高（存储了 field 名称）
     *
     * ✅ String 存 JSON：
     *    优点：
     *      1. 支持嵌套对象，结构灵活
     *      2. 序列化/反序列化方便（Jackson/Gson）
     *      3. 整体读取性能好（一次 GET）
     *    缺点：
     *      1. 更新单个字段需要读取整个 JSON（读-改-写）
     *      2. 并发修改时容易产生数据覆盖
     *      3. 大对象序列化/反序列化有性能开销
     * ==============================
     *
     * 【面试追问】实际项目中如何选择？
     * → 频繁更新单个字段 → Hash（如购物车、用户积分）
     * → 整体读取，很少更新 → String JSON（如商品详情、文章内容）
     * → 有嵌套结构 → String JSON（Hash 不支持嵌套）
     */
    public void hashVsStringComparison() {
        log.info("========== Hash vs String 存储对象对比 ==========");

        // ===== Hash 方式 =====
        String hashKey = "user:hash:1001";
        redisTemplate.opsForHash().put(hashKey, "name", "张三");
        redisTemplate.opsForHash().put(hashKey, "age", 25);
        redisTemplate.opsForHash().put(hashKey, "score", 100);

        // 只更新 score 字段（不影响其他字段）
        redisTemplate.opsForHash().increment(hashKey, "score", 10);
        log.info("【Hash方式】更新score后: {}", redisTemplate.opsForHash().get(hashKey, "score"));

        // ===== String JSON 方式 =====
        String stringKey = "user:string:1001";
        String userJson = "{\"name\":\"张三\",\"age\":25,\"score\":100}";
        redisTemplate.opsForValue().set(stringKey, userJson);

        // 更新 score 需要：GET → 反序列化 → 修改 → 序列化 → SET（非原子）
        String currentJson = (String) redisTemplate.opsForValue().get(stringKey);
        log.info("【String方式】更新score需要读取整个JSON: {}", currentJson);
        // 实际项目中这里需要 JSON 解析，有并发覆盖风险

        log.info("【结论】频繁更新单字段用Hash，整体读取用String JSON");

        // 清理
        redisTemplate.delete(hashKey);
        redisTemplate.delete(stringKey);
    }
}
