package com.interview.redis;

import com.interview.redis.datatype.HashOpsDemo;
import com.interview.redis.datatype.ListOpsDemo;
import com.interview.redis.datatype.SetOpsDemo;
import com.interview.redis.datatype.StringOpsDemo;
import com.interview.redis.datatype.ZSetOpsDemo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】Redis 五种数据类型测试
 *
 * 测试覆盖：String、Hash、List、Set、ZSet 的核心操作
 * 每种类型至少2个测试方法，验证面试中常考的操作
 */
@Slf4j
@SpringBootTest
@DisplayName("Redis 数据类型测试")
class DataTypeTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringOpsDemo stringOpsDemo;

    @Autowired
    private HashOpsDemo hashOpsDemo;

    @Autowired
    private ListOpsDemo listOpsDemo;

    @Autowired
    private SetOpsDemo setOpsDemo;

    @Autowired
    private ZSetOpsDemo zSetOpsDemo;

    @AfterEach
    void cleanup() {
        // 清理测试数据（删除所有测试 key）
        Set<String> keys = stringRedisTemplate.keys("test:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("【清理】删除测试key: {}个", keys.size());
        }
    }

    // ==================== String 类型测试 ====================

    /**
     * 【面试考点】String SET/GET/EXPIRE 基础操作
     *
     * 测试要点：
     * 1. SET key value EX seconds（原子操作）
     * 2. GET 获取值
     * 3. TTL 验证过期时间
     */
    @Test
    @DisplayName("String: SET/GET/EXPIRE 基础操作")
    void testStringSetGetExpire() {
        String key = "test:string:basic";
        String value = "hello_redis";

        // SET with TTL（原子操作）
        stringRedisTemplate.opsForValue().set(key, value, 60, TimeUnit.SECONDS);

        // GET 验证
        String result = stringRedisTemplate.opsForValue().get(key);
        assertThat(result).isEqualTo(value);
        log.info("【String测试】SET/GET验证通过: key={}, value={}", key, result);

        // TTL 验证
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);
        log.info("【String测试】TTL验证通过: ttl={}s", ttl);

        // 清理
        stringRedisTemplate.delete(key);
    }

    /**
     * 【面试考点】String INCR 原子自增（计数器场景）
     *
     * 测试要点：
     * 1. INCR 原子自增
     * 2. INCRBY 步长自增
     * 3. 并发安全性（单线程验证）
     */
    @Test
    @DisplayName("String: INCR 原子计数器")
    void testStringIncr() {
        String key = "test:string:counter";

        // 初始值为0，INCR 后为1
        Long count1 = stringRedisTemplate.opsForValue().increment(key);
        assertThat(count1).isEqualTo(1L);

        // 再次 INCR，值为2
        Long count2 = stringRedisTemplate.opsForValue().increment(key);
        assertThat(count2).isEqualTo(2L);

        // INCRBY 步长自增
        Long count3 = stringRedisTemplate.opsForValue().increment(key, 10);
        assertThat(count3).isEqualTo(12L);

        log.info("【String测试】INCR验证通过: 1→2→12");

        // 清理
        stringRedisTemplate.delete(key);
    }

    // ==================== Hash 类型测试 ====================

    /**
     * 【面试考点】Hash HSET/HGET/HDEL 购物车场景
     *
     * 测试要点：
     * 1. HSET 设置字段
     * 2. HGET 获取字段
     * 3. HDEL 删除字段
     * 4. HGETALL 获取所有字段
     */
    @Test
    @DisplayName("Hash: 购物车 HSET/HGET/HDEL/HGETALL")
    void testHashShoppingCart() {
        String cartKey = "test:hash:cart:1001";

        // HSET：添加商品
        redisTemplate.opsForHash().put(cartKey, "product:101", 2);
        redisTemplate.opsForHash().put(cartKey, "product:102", 3);

        // HGET：获取单个商品数量
        Object qty = redisTemplate.opsForHash().get(cartKey, "product:101");
        assertThat(qty).isEqualTo(2);
        log.info("【Hash测试】HGET验证通过: product:101 数量={}", qty);

        // HGETALL：获取整个购物车
        Map<Object, Object> cart = redisTemplate.opsForHash().entries(cartKey);
        assertThat(cart).hasSize(2);
        log.info("【Hash测试】HGETALL验证通过: 购物车商品数={}", cart.size());

        // HDEL：删除商品
        redisTemplate.opsForHash().delete(cartKey, "product:102");
        assertThat(redisTemplate.opsForHash().size(cartKey)).isEqualTo(1L);
        log.info("【Hash测试】HDEL验证通过: 删除后商品数=1");

        // 清理
        redisTemplate.delete(cartKey);
    }

    /**
     * 【面试考点】Hash HINCRBY 原子字段自增
     *
     * 测试要点：
     * 1. HINCRBY 原子自增指定字段
     * 2. 不影响其他字段（部分更新）
     */
    @Test
    @DisplayName("Hash: HINCRBY 原子字段自增")
    void testHashIncrBy() {
        String key = "test:hash:user:1001";

        // 初始化用户信息
        redisTemplate.opsForHash().put(key, "score", 100);
        redisTemplate.opsForHash().put(key, "level", 5);

        // HINCRBY：只更新 score 字段
        redisTemplate.opsForHash().increment(key, "score", 50);

        // 验证 score 更新，level 不变
        Object score = redisTemplate.opsForHash().get(key, "score");
        Object level = redisTemplate.opsForHash().get(key, "level");
        assertThat(score).isEqualTo(150L); // HINCRBY 返回 Long
        assertThat(level).isEqualTo(5);
        log.info("【Hash测试】HINCRBY验证通过: score=150, level=5（未变）");

        // 清理
        redisTemplate.delete(key);
    }

    // ==================== List 类型测试 ====================

    /**
     * 【面试考点】List LPUSH/LRANGE 消息列表场景
     *
     * 测试要点：
     * 1. LPUSH 从左端插入（最新消息在最前）
     * 2. LRANGE 获取指定范围
     * 3. LLEN 获取列表长度
     */
    @Test
    @DisplayName("List: LPUSH/LRANGE 消息列表")
    void testListMessageList() {
        String key = "test:list:messages";

        // LPUSH：插入消息（最新的在最前面）
        redisTemplate.opsForList().leftPush(key, "msg3");
        redisTemplate.opsForList().leftPush(key, "msg2");
        redisTemplate.opsForList().leftPush(key, "msg1");

        // LLEN：验证长度
        Long size = redisTemplate.opsForList().size(key);
        assertThat(size).isEqualTo(3L);

        // LRANGE：获取所有消息（最新的在最前面）
        List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
        assertThat(messages).containsExactly("msg1", "msg2", "msg3");
        log.info("【List测试】LPUSH/LRANGE验证通过: {}", messages);

        // 清理
        redisTemplate.delete(key);
    }

    /**
     * 【面试考点】List LPUSH/RPOP 队列（FIFO）
     *
     * 测试要点：
     * 1. LPUSH 入队
     * 2. RPOP 出队（FIFO）
     * 3. 验证先进先出顺序
     */
    @Test
    @DisplayName("List: LPUSH/RPOP 队列（FIFO）")
    void testListQueue() {
        String key = "test:list:queue";

        // LPUSH：入队（task1 先入队）
        redisTemplate.opsForList().leftPush(key, "task1");
        redisTemplate.opsForList().leftPush(key, "task2");
        redisTemplate.opsForList().leftPush(key, "task3");

        // RPOP：出队（FIFO，task1 先出队）
        Object first = redisTemplate.opsForList().rightPop(key);
        assertThat(first).isEqualTo("task1");
        log.info("【List测试】RPOP出队验证通过: 第一个出队={}", first);

        Object second = redisTemplate.opsForList().rightPop(key);
        assertThat(second).isEqualTo("task2");
        log.info("【List测试】RPOP出队验证通过: 第二个出队={}", second);

        // 清理
        redisTemplate.delete(key);
    }

    // ==================== Set 类型测试 ====================

    /**
     * 【面试考点】Set SADD/SINTER 共同好友场景
     *
     * 测试要点：
     * 1. SADD 添加元素（自动去重）
     * 2. SINTER 求交集（共同好友）
     * 3. SISMEMBER 判断元素是否存在
     */
    @Test
    @DisplayName("Set: SADD/SINTER 共同好友")
    void testSetCommonFriends() {
        String user1Key = "test:set:friends:1001";
        String user2Key = "test:set:friends:1002";

        // SADD：添加好友
        redisTemplate.opsForSet().add(user1Key, "user:A", "user:B", "user:C");
        redisTemplate.opsForSet().add(user2Key, "user:B", "user:C", "user:D");

        // SINTER：求共同好友
        Set<Object> commonFriends = redisTemplate.opsForSet().intersect(user1Key, user2Key);
        assertThat(commonFriends).containsExactlyInAnyOrder("user:B", "user:C");
        log.info("【Set测试】SINTER共同好友验证通过: {}", commonFriends);

        // SISMEMBER：判断是否是好友
        Boolean isFriend = redisTemplate.opsForSet().isMember(user1Key, "user:A");
        assertThat(isFriend).isTrue();
        log.info("【Set测试】SISMEMBER验证通过: user:A是1001的好友={}", isFriend);

        // 清理
        redisTemplate.delete(user1Key);
        redisTemplate.delete(user2Key);
    }

    /**
     * 【面试考点】Set SADD 自动去重（UV 统计）
     *
     * 测试要点：
     * 1. SADD 重复添加不计入
     * 2. SCARD 获取集合大小（UV 数量）
     */
    @Test
    @DisplayName("Set: SADD 自动去重（UV统计）")
    void testSetUniqueVisitor() {
        String uvKey = "test:set:uv:2024-01-01";

        // SADD：记录访问（重复访问不计入）
        redisTemplate.opsForSet().add(uvKey, "user:1001", "user:1002", "user:1003");
        redisTemplate.opsForSet().add(uvKey, "user:1001"); // 重复，不计入

        // SCARD：验证 UV 数量（应该是3，不是4）
        Long uv = redisTemplate.opsForSet().size(uvKey);
        assertThat(uv).isEqualTo(3L);
        log.info("【Set测试】UV去重验证通过: UV={}", uv);

        // 清理
        redisTemplate.delete(uvKey);
    }

    // ==================== ZSet 类型测试 ====================

    /**
     * 【面试考点】ZSet ZADD/ZREVRANGE 排行榜场景
     *
     * 测试要点：
     * 1. ZADD 添加元素和分数
     * 2. ZREVRANGE 从高到低排序
     * 3. ZINCRBY 原子增加分数
     */
    @Test
    @DisplayName("ZSet: ZADD/ZREVRANGE 排行榜")
    void testZSetLeaderboard() {
        String key = "test:zset:leaderboard";

        // ZADD：添加玩家积分
        redisTemplate.opsForZSet().add(key, "Alice", 9500);
        redisTemplate.opsForZSet().add(key, "Bob", 8800);
        redisTemplate.opsForZSet().add(key, "Charlie", 9200);

        // ZREVRANGE：获取 Top 2（从高到低）
        Set<Object> top2 = redisTemplate.opsForZSet().reverseRange(key, 0, 1);
        assertThat(top2).containsExactly("Alice", "Charlie");
        log.info("【ZSet测试】ZREVRANGE Top2验证通过: {}", top2);

        // ZINCRBY：增加 Bob 的积分
        Double newScore = redisTemplate.opsForZSet().incrementScore(key, "Bob", 1000);
        assertThat(newScore).isEqualTo(9800.0);
        log.info("【ZSet测试】ZINCRBY验证通过: Bob新积分={}", newScore);

        // 清理
        redisTemplate.delete(key);
    }

    /**
     * 【面试考点】ZSet ZRANGEBYSCORE 范围查询（延迟队列场景）
     *
     * 测试要点：
     * 1. ZADD score=时间戳
     * 2. ZRANGEBYSCORE 获取到期任务
     * 3. ZREM 删除已处理任务
     */
    @Test
    @DisplayName("ZSet: ZRANGEBYSCORE 延迟队列")
    void testZSetDelayQueue() {
        String key = "test:zset:delay:queue";
        long now = System.currentTimeMillis() / 1000;

        // ZADD：添加延迟任务（score=执行时间戳）
        redisTemplate.opsForZSet().add(key, "task:expired", now - 10);  // 已过期
        redisTemplate.opsForZSet().add(key, "task:future", now + 100);  // 未到期

        // ZRANGEBYSCORE：获取已到期的任务（score <= now）
        Set<Object> dueTasks = redisTemplate.opsForZSet().rangeByScore(key, 0, now);
        assertThat(dueTasks).containsExactly("task:expired");
        assertThat(dueTasks).doesNotContain("task:future");
        log.info("【ZSet测试】ZRANGEBYSCORE延迟队列验证通过: 到期任务={}", dueTasks);

        // ZREM：处理完成后删除
        redisTemplate.opsForZSet().remove(key, "task:expired");
        assertThat(redisTemplate.opsForZSet().size(key)).isEqualTo(1L);
        log.info("【ZSet测试】ZREM验证通过: 删除后剩余任务数=1");

        // 清理
        redisTemplate.delete(key);
    }
}
