package com.interview.redis.datatype;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 【面试考点】Redis Set 类型核心操作演示
 *
 * 问题描述：Set 是 Redis 中的无序集合，元素唯一，支持集合运算
 * 解决思路：掌握 Set 的典型使用场景（共同好友、抽奖、UV统计、标签）
 *
 * 【面试速记】Set 底层编码：
 * - 元素数量 <= 128 且元素都是整数：intset（紧凑存储，内存效率高）
 * - 元素数量 <= 128 且有非整数元素：listpack
 * - 超过阈值：hashtable（O(1) 查找）
 *
 * 【面试追问】Set 和 ZSet 的区别？
 * → Set：无序，元素唯一，支持集合运算（交集、并集、差集）
 * → ZSet：有序（按 score 排序），元素唯一，支持范围查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetOpsDemo {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 【面试考点】共同好友场景 - SINTER（交集）
     *
     * 问题描述：如何快速找出两个用户的共同好友？
     * 解决思路：将每个用户的好友列表存为 Set，使用 SINTER 求交集
     *
     * 【对比方案】
     * ❌ 方案一（数据库查询）：
     *    SELECT friend_id FROM friends WHERE user_id = 1001
     *    INTERSECT
     *    SELECT friend_id FROM friends WHERE user_id = 1002
     *    缺点：数据库压力大，响应慢
     *
     * ✅ 方案二（Redis SINTER）：
     *    SINTER user:1001:friends user:1002:friends
     *    优点：O(N*M) 时间复杂度，内存计算，速度快
     * ==============================
     *
     * 【面试追问】SINTER、SUNION、SDIFF 的区别？
     * → SINTER：交集（共同好友）
     * → SUNION：并集（所有好友）
     * → SDIFF：差集（A有B没有的好友）
     *
     * 适用场景：共同好友、共同关注、共同喜好推荐
     */
    public void commonFriendsDemo() {
        String user1FriendsKey = "user:1001:friends";
        String user2FriendsKey = "user:1002:friends";

        // 用户1001的好友
        redisTemplate.opsForSet().add(user1FriendsKey, "user:2001", "user:2002", "user:2003", "user:2004");
        // 用户1002的好友
        redisTemplate.opsForSet().add(user2FriendsKey, "user:2002", "user:2003", "user:2005", "user:2006");

        // SINTER：求交集（共同好友）
        Set<Object> commonFriends = redisTemplate.opsForSet().intersect(user1FriendsKey, user2FriendsKey);
        log.info("【共同好友】用户1001和1002的共同好友: {}", commonFriends);

        // SUNION：求并集（所有好友）
        Set<Object> allFriends = redisTemplate.opsForSet().union(user1FriendsKey, user2FriendsKey);
        log.info("【共同好友】所有好友（并集）: {}", allFriends);

        // SDIFF：求差集（1001有但1002没有的好友）
        Set<Object> uniqueFriends = redisTemplate.opsForSet().difference(user1FriendsKey, user2FriendsKey);
        log.info("【共同好友】1001独有的好友（差集）: {}", uniqueFriends);

        // SISMEMBER：判断是否是好友
        Boolean isFriend = redisTemplate.opsForSet().isMember(user1FriendsKey, "user:2002");
        log.info("【共同好友】user:2002是否是1001的好友: {}", isFriend);

        // 清理
        redisTemplate.delete(user1FriendsKey);
        redisTemplate.delete(user2FriendsKey);
    }

    /**
     * 【面试考点】抽奖场景 - SRANDMEMBER vs SPOP
     *
     * 问题描述：抽奖时如何随机选取中奖用户？
     * 解决思路：根据是否允许重复中奖，选择 SRANDMEMBER 或 SPOP
     *
     * ========== 方案对比 ==========
     * ✅ SRANDMEMBER（不删除元素）：
     *    适用场景：允许重复中奖（如每日签到抽奖）
     *    特点：随机返回元素，但不从集合中删除
     *    命令：SRANDMEMBER key count
     *
     * ✅ SPOP（删除元素）：
     *    适用场景：不允许重复中奖（如年会抽奖）
     *    特点：随机弹出元素，并从集合中删除（保证不重复）
     *    命令：SPOP key count
     * ==============================
     *
     * 【面试追问】SRANDMEMBER count 为负数时有什么区别？
     * → count > 0：返回 count 个不重复的元素（不够时返回全部）
     * → count < 0：返回 |count| 个元素，可能有重复
     *
     * 适用场景：抽奖系统、随机推荐、随机广告
     */
    public void lotteryDemo() {
        String lotteryKey = "lottery:2024:participants";

        // 添加参与抽奖的用户
        redisTemplate.opsForSet().add(lotteryKey,
                "user:1001", "user:1002", "user:1003", "user:1004", "user:1005",
                "user:1006", "user:1007", "user:1008", "user:1009", "user:1010");
        log.info("【抽奖】参与人数: {}", redisTemplate.opsForSet().size(lotteryKey));

        // SRANDMEMBER：随机抽取3名幸运观众（不删除，可重复中奖）
        Set<Object> luckyViewers = redisTemplate.opsForSet().distinctRandomMembers(lotteryKey, 3);
        log.info("【抽奖】幸运观众（可重复）: {}", luckyViewers);

        // SPOP：随机抽取3名中奖用户（删除，不可重复中奖）
        List<Object> winners = redisTemplate.opsForSet().pop(lotteryKey, 3);
        log.info("【抽奖】中奖用户（不可重复）: {}", winners);
        log.info("【抽奖】抽奖后剩余参与人数: {}", redisTemplate.opsForSet().size(lotteryKey));

        // 清理
        redisTemplate.delete(lotteryKey);
    }

    /**
     * 【面试考点】UV 统计场景 - Set vs HyperLogLog
     *
     * 问题描述：如何统计网站的独立访客数（UV）？
     * 解决思路：根据精确度要求选择 Set 或 HyperLogLog
     *
     * ========== 方案对比 ==========
     * ✅ Set 方案（精确统计）：
     *    优点：精确，可以知道具体哪些用户访问过
     *    缺点：内存占用大（每个用户ID都要存储）
     *    适用：UV < 100万的场景
     *
     * ✅ HyperLogLog 方案（近似统计）：
     *    优点：内存极小（固定12KB），误差率约0.81%
     *    缺点：不精确，无法知道具体哪些用户访问过
     *    适用：UV > 100万的大规模统计场景
     *    命令：PFADD key element, PFCOUNT key
     * ==============================
     *
     * 【面试追问】HyperLogLog 的原理是什么？
     * → 答：基于概率算法，通过哈希函数将元素映射到位图
     * → 统计最长连续0的个数来估算基数
     * → 误差率约 0.81%，固定内存 12KB
     *
     * 适用场景：网站UV统计、APP日活统计
     */
    public void uniqueVisitorDemo() {
        String uvKey = "uv:2024-01-01";

        // SADD：记录访问用户（自动去重）
        redisTemplate.opsForSet().add(uvKey, "user:1001", "user:1002", "user:1003");
        redisTemplate.opsForSet().add(uvKey, "user:1001"); // 重复访问，不计入
        redisTemplate.opsForSet().add(uvKey, "user:1004", "user:1005");

        // SCARD：获取 UV 数量
        Long uv = redisTemplate.opsForSet().size(uvKey);
        log.info("【UV统计】今日UV（Set精确统计）: {}", uv);

        // 判断某用户是否访问过
        Boolean visited = redisTemplate.opsForSet().isMember(uvKey, "user:1001");
        log.info("【UV统计】user:1001是否访问过: {}", visited);

        log.info("【UV统计】大规模UV建议使用HyperLogLog（PFADD/PFCOUNT），内存固定12KB，误差0.81%");

        // 清理
        redisTemplate.delete(uvKey);
    }

    /**
     * 【面试考点】标签系统场景 - SADD + SMEMBERS
     *
     * 问题描述：如何实现文章标签系统，支持按标签查找文章？
     * 解决思路：
     *   - 文章的标签：Set key=article:{id}:tags, value=tagId
     *   - 标签下的文章：Set key=tag:{id}:articles, value=articleId
     *
     * 【对比方案】
     * ❌ 数据库方案：article_tag 关联表，查询需要 JOIN，性能差
     * ✅ Redis Set 方案：直接 SMEMBERS 获取，O(N) 时间复杂度
     *
     * 【面试追问】如何查找同时包含多个标签的文章？
     * → 答：SINTER tag:java:articles tag:spring:articles
     * → 求多个标签对应文章集合的交集
     *
     * 适用场景：文章标签、商品分类、用户兴趣标签
     */
    public void tagSystemDemo() {
        String article1TagsKey = "article:1001:tags";
        String article2TagsKey = "article:1002:tags";
        String javaArticlesKey = "tag:java:articles";
        String springArticlesKey = "tag:spring:articles";
        String redisArticlesKey = "tag:redis:articles";

        // 为文章添加标签
        redisTemplate.opsForSet().add(article1TagsKey, "java", "spring", "redis");
        redisTemplate.opsForSet().add(article2TagsKey, "java", "spring", "mysql");

        // 维护标签->文章的反向索引
        redisTemplate.opsForSet().add(javaArticlesKey, "article:1001", "article:1002");
        redisTemplate.opsForSet().add(springArticlesKey, "article:1001", "article:1002");
        redisTemplate.opsForSet().add(redisArticlesKey, "article:1001");

        // 获取文章的所有标签
        Set<Object> tags = redisTemplate.opsForSet().members(article1TagsKey);
        log.info("【标签系统】文章1001的标签: {}", tags);

        // 查找同时包含 java 和 spring 标签的文章（交集）
        Set<Object> javaAndSpringArticles = redisTemplate.opsForSet()
                .intersect(javaArticlesKey, springArticlesKey);
        log.info("【标签系统】同时包含java和spring的文章: {}", javaAndSpringArticles);

        // 查找包含 java 或 redis 标签的文章（并集）
        Set<Object> javaOrRedisArticles = redisTemplate.opsForSet()
                .union(javaArticlesKey, redisArticlesKey);
        log.info("【标签系统】包含java或redis的文章: {}", javaOrRedisArticles);

        // 清理
        redisTemplate.delete(article1TagsKey);
        redisTemplate.delete(article2TagsKey);
        redisTemplate.delete(javaArticlesKey);
        redisTemplate.delete(springArticlesKey);
        redisTemplate.delete(redisArticlesKey);
    }
}
