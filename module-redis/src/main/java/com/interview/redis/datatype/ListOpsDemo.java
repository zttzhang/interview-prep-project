package com.interview.redis.datatype;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 【面试考点】Redis List 类型核心操作演示
 *
 * 问题描述：List 是 Redis 中的双向链表，支持从两端插入/弹出
 * 解决思路：掌握 List 的典型使用场景（消息列表、队列、栈、阻塞队列）
 *
 * 【面试速记】List 底层编码：
 * - 元素数量 <= 128 且每个元素 <= 64字节：listpack（紧凑存储）
 * - 超过阈值：quicklist（多个 listpack 组成的双向链表）
 *
 * 【面试追问】List 和 Kafka 的区别？
 * → Redis List：简单，无持久化保证，消息消费后即删除，不支持消费者组
 * → Kafka：持久化，支持消费者组，支持消息回溯，适合高吞吐量场景
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListOpsDemo {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 【面试考点】消息列表场景 - LPUSH + LRANGE
     *
     * 问题描述：如何实现"最新消息列表"（如微博最新动态）？
     * 解决思路：LPUSH 从左端插入，LRANGE 获取最新的 N 条
     *
     * 【对比方案】
     * ❌ 方案一（数据库分页）：每次查询都需要 ORDER BY + LIMIT，性能差
     * ✅ 方案二（Redis List）：LPUSH 插入，LRANGE 0 N-1 获取最新N条，O(N) 时间复杂度
     *
     * 【面试追问】如何控制列表长度，避免内存无限增长？
     * → 答：使用 LTRIM 命令，保留最新的 N 条记录
     *       redisTemplate.opsForList().trim(key, 0, 999); // 只保留最新1000条
     *
     * 适用场景：微博/朋友圈最新动态、最近浏览记录、消息通知列表
     */
    public void messageListDemo() {
        String listKey = "user:1001:messages";

        // LPUSH：从左端插入（最新消息在最前面）
        redisTemplate.opsForList().leftPush(listKey, "消息1：您有新的订单");
        redisTemplate.opsForList().leftPush(listKey, "消息2：您的包裹已发货");
        redisTemplate.opsForList().leftPush(listKey, "消息3：您有新的评论");
        log.info("【消息列表】插入3条消息");

        // LRANGE：获取最新的 N 条消息（0 到 N-1）
        List<Object> latestMessages = redisTemplate.opsForList().range(listKey, 0, 2);
        log.info("【消息列表】最新3条消息: {}", latestMessages);

        // LLEN：获取列表长度
        Long size = redisTemplate.opsForList().size(listKey);
        log.info("【消息列表】消息总数: {}", size);

        // LTRIM：只保留最新的 100 条（防止内存无限增长）
        redisTemplate.opsForList().trim(listKey, 0, 99);
        log.info("【消息列表】LTRIM 后保留最新100条");

        // 清理
        redisTemplate.delete(listKey);
    }

    /**
     * 【面试考点】队列场景 - LPUSH + RPOP（先进先出）
     *
     * 问题描述：如何用 Redis 实现简单的消息队列？
     * 解决思路：LPUSH 从左端入队，RPOP 从右端出队，实现 FIFO
     *
     * ========== 方案对比 ==========
     * ✅ Redis List 队列：
     *    优点：实现简单，延迟低
     *    缺点：
     *      1. 消息消费后即删除，无法重复消费
     *      2. 不支持消费者组（多个消费者竞争同一队列）
     *      3. Redis 宕机可能丢失消息（需要 AOF 持久化）
     *      4. 消费者需要轮询（RPOP），浪费 CPU
     *
     * ✅ Kafka/RabbitMQ：
     *    优点：持久化，支持消费者组，支持消息回溯
     *    缺点：部署复杂，有额外的运维成本
     * ==============================
     *
     * 【面试追问】Redis List 队列如何避免消息丢失？
     * → 答：使用 RPOPLPUSH 命令，将消息从队列移到"处理中"列表
     *       处理完成后从"处理中"列表删除，宕机恢复后可以重新处理
     *
     * 适用场景：简单任务队列、异步处理、邮件发送队列
     */
    public void queueDemo() {
        String queueKey = "task:queue";

        // LPUSH：入队（从左端插入）
        redisTemplate.opsForList().leftPush(queueKey, "task:001:发送邮件");
        redisTemplate.opsForList().leftPush(queueKey, "task:002:生成报表");
        redisTemplate.opsForList().leftPush(queueKey, "task:003:清理日志");
        log.info("【队列】入队3个任务，队列长度: {}", redisTemplate.opsForList().size(queueKey));

        // RPOP：出队（从右端弹出，FIFO）
        Object task = redisTemplate.opsForList().rightPop(queueKey);
        log.info("【队列】出队任务: {}", task);

        // RPOPLPUSH：安全出队（原子操作，移到"处理中"列表）
        String processingKey = "task:processing";
        Object safeTask = redisTemplate.opsForList().rightPopAndLeftPush(queueKey, processingKey);
        log.info("【队列】安全出队（移到处理中列表）: {}", safeTask);

        // 处理完成后，从"处理中"列表删除
        redisTemplate.opsForList().remove(processingKey, 1, safeTask);
        log.info("【队列】任务处理完成，从处理中列表删除");

        // 清理
        redisTemplate.delete(queueKey);
        redisTemplate.delete(processingKey);
    }

    /**
     * 【面试考点】栈场景 - LPUSH + LPOP（后进先出）
     *
     * 问题描述：如何实现浏览历史记录（最近浏览的在最前面）？
     * 解决思路：LPUSH 从左端插入，LPOP 从左端弹出，实现 LIFO
     *
     * 【对比方案】
     * - 队列（FIFO）：LPUSH + RPOP（先进先出，如任务队列）
     * - 栈（LIFO）：LPUSH + LPOP（后进先出，如浏览历史）
     *
     * 【面试追问】浏览历史如何去重？
     * → 答：先 LREM 删除已存在的记录，再 LPUSH 插入新记录
     *       这样同一个商品不会重复出现在历史记录中
     *
     * 适用场景：浏览历史、撤销操作历史、函数调用栈
     */
    public void stackDemo() {
        String historyKey = "user:1001:browse:history";

        // LPUSH：记录浏览历史（最新的在最前面）
        redisTemplate.opsForList().leftPush(historyKey, "product:iPhone15");
        redisTemplate.opsForList().leftPush(historyKey, "product:MacBook");
        redisTemplate.opsForList().leftPush(historyKey, "product:AirPods");
        log.info("【浏览历史】记录3条浏览历史");

        // 再次浏览 iPhone15（先删除旧记录，再插入，实现去重）
        redisTemplate.opsForList().remove(historyKey, 0, "product:iPhone15");
        redisTemplate.opsForList().leftPush(historyKey, "product:iPhone15");
        log.info("【浏览历史】再次浏览iPhone15（去重后）: {}", redisTemplate.opsForList().range(historyKey, 0, -1));

        // LPOP：弹出最近浏览的商品（LIFO）
        Object lastViewed = redisTemplate.opsForList().leftPop(historyKey);
        log.info("【浏览历史】最近浏览: {}", lastViewed);

        // 只保留最近 10 条浏览记录
        redisTemplate.opsForList().trim(historyKey, 0, 9);
        log.info("【浏览历史】保留最近10条记录");

        // 清理
        redisTemplate.delete(historyKey);
    }

    /**
     * 【面试考点】阻塞队列场景 - BRPOP（避免轮询）
     *
     * 问题描述：消费者轮询 RPOP 时，队列为空会浪费 CPU
     * 解决思路：使用 BRPOP 阻塞等待，队列有消息时立即返回
     *
     * ========== 方案对比 ==========
     * ❌ 方案一（轮询 RPOP）：
     *    while (true) {
     *        Object task = redisTemplate.opsForList().rightPop(queueKey);
     *        if (task != null) { process(task); }
     *        else { Thread.sleep(100); } // 浪费 CPU，有延迟
     *    }
     *
     * ✅ 方案二（阻塞 BRPOP）：
     *    Object task = redisTemplate.opsForList().rightPop(queueKey, 30, TimeUnit.SECONDS);
     *    // 阻塞等待，有消息立即返回，无消息等待30秒后返回null
     *    // 节省 CPU，延迟更低
     * ==============================
     *
     * 【面试追问】BRPOP 的超时时间设置多少合适？
     * → 答：不能设置太长（连接长时间占用），也不能太短（频繁重连）
     * → 一般设置 30秒，超时后重新连接并继续阻塞
     *
     * 适用场景：实时消息处理、任务调度、事件驱动架构
     */
    public void blockingQueueDemo() {
        String queueKey = "task:blocking:queue";

        // 先放入一个任务
        redisTemplate.opsForList().leftPush(queueKey, "urgent:task:001");
        log.info("【阻塞队列】放入紧急任务");

        // BRPOP：阻塞等待（超时时间1秒，演示用）
        // 实际生产中超时时间设置为 30秒
        Object task = redisTemplate.opsForList().rightPop(queueKey, 1, TimeUnit.SECONDS);
        if (task != null) {
            log.info("【阻塞队列】立即获取到任务: {}", task);
        }

        // 队列为空时，BRPOP 会阻塞等待（这里超时1秒后返回null）
        Object emptyResult = redisTemplate.opsForList().rightPop(queueKey, 1, TimeUnit.SECONDS);
        log.info("【阻塞队列】队列为空时BRPOP结果: {} (null表示超时)", emptyResult);

        // 清理
        redisTemplate.delete(queueKey);
    }
}
