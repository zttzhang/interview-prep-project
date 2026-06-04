package com.interview.integration.seckill;

import com.interview.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 【面试考点】秒杀接口设计 - Controller 层
 *
 * 问题描述：
 *   高并发秒杀场景下，Controller 层需要处理：
 *   1. 接口幂等性（同一用户不能重复秒杀）
 *   2. 限流（防止恶意刷接口）
 *   3. 库存超卖防护（Redis 原子操作）
 *
 * 解决思路：
 *   - 幂等性：Redis SET NX 记录用户已购买标记
 *   - 限流：可在此层加 @RateLimiter 注解（令牌桶/滑动窗口）
 *   - 超卖防护：Lua 脚本原子扣减库存（见 SeckillService）
 *
 * 【对比方案】
 * ❌ 方案一（错误示范）：直接在 Controller 写业务逻辑
 *    → 问题：Controller 职责混乱，难以测试，无法复用
 * ✅ 方案二（正确）：Controller 只做参数校验和路由，业务逻辑下沉到 Service
 *    → 优点：职责清晰，易于测试，符合单一职责原则
 *
 * 【面试追问】
 * Q: 秒杀系统如何防止超卖？
 * A: 三层防护：
 *    ① Redis Lua 脚本原子扣减（最快，在内存层拦截）
 *    ② 数据库乐观锁（UPDATE stock SET count=count-1 WHERE count>0）
 *    ③ 数据库唯一索引（user_id + product_id 联合唯一，防止重复下单）
 *
 * Q: 如何实现接口限流？
 * A: 常见方案：
 *    ① 令牌桶（Token Bucket）：允许突发流量，适合秒杀
 *    ② 滑动窗口：精确控制时间窗口内的请求数
 *    ③ Sentinel：阿里开源，支持多种限流策略，生产推荐
 *    实现方式：自定义 @RateLimiter 注解 + AOP + Redis
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final StringRedisTemplate redisTemplate;

    // ========== 方案对比：限流注解 ==========
    // ❌ 方案一（错误示范）：在方法内部手动判断限流
    //    if (requestCount > limit) { return Result.fail("请求过于频繁"); }
    //    → 问题：每个接口都要写，代码重复，且不是原子操作
    // ✅ 方案二（正确）：自定义 @RateLimiter 注解 + AOP
    //    @RateLimiter(key = "seckill", limit = 100, period = 1)
    //    → 优点：业务代码无侵入，统一管理限流策略
    // ==============================

    /**
     * 【面试考点】查询商品库存
     *
     * 问题描述：秒杀前需要展示实时库存，但直接查 DB 压力大
     * 解决思路：查 Redis 缓存，Redis 中存储的是实时扣减后的库存
     *
     * 【面试追问】
     * Q: 库存查询接口如何防止缓存穿透？
     * A: 商品不存在时，Redis 中存 null 值（空值缓存），设置较短过期时间
     *
     * @param productId 商品ID
     * @return 当前库存数量
     */
    @GetMapping("/stock/{productId}")
    public Result<Integer> getStock(@PathVariable Long productId) {
        log.info("查询库存: productId={}", productId);
        String stockKey = "seckill:stock:" + productId;
        String stockStr = redisTemplate.opsForValue().get(stockKey);
        if (stockStr == null) {
            return Result.fail(404, "商品不存在或活动未开始");
        }
        return Result.success(Integer.parseInt(stockStr));
    }

    /**
     * 【面试考点】秒杀核心接口
     *
     * 问题描述：
     *   高并发下，大量请求同时到达，需要：
     *   1. 快速响应（不能让用户等待）
     *   2. 保证不超卖（原子操作）
     *   3. 防止重复秒杀（幂等性）
     *
     * 解决思路：
     *   ① 接收请求 → 参数校验
     *   ② 调用 SeckillService.seckill() → Redis Lua 原子扣减
     *   ③ 扣减成功 → 发 Kafka 消息 → 立即返回"排队中"
     *   ④ Consumer 异步消费 → 写 DB → 更新订单状态
     *
     * 【对比方案】
     * ❌ 方案一（同步下单）：Controller → Service → DB（直接写库）
     *    → 问题：DB 成为瓶颈，高并发下响应慢，容易超时
     * ✅ 方案二（异步下单）：Controller → Redis扣减 → Kafka → Consumer → DB
     *    → 优点：快速响应，DB 压力分散，可水平扩展
     *
     * 【面试追问】
     * Q: 秒杀系统如何防止超卖？
     * A: Redis 原子操作（Lua脚本）+ 数据库乐观锁双重保障：
     *    ① Redis Lua：if stock > 0 then decr(stock) end（原子执行）
     *    ② DB 乐观锁：UPDATE SET stock=stock-1 WHERE stock>0 AND id=?
     *
     * Q: userId 为什么放在请求头而不是路径参数？
     * A: 生产环境 userId 应从 JWT Token 中解析（安全），
     *    这里用请求头模拟，避免用户篡改 userId
     *
     * @param productId 商品ID（路径参数）
     * @param userId    用户ID（请求头，生产环境应从 Token 解析）
     * @return 秒杀结果（包含订单号）
     */
    // 【注意】生产环境应加 @RateLimiter(key = "'seckill:' + #productId", limit = 1000, period = 1)
    @PostMapping("/{productId}")
    public Result<SeckillService.SeckillResult> seckill(
            @PathVariable Long productId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(value = "userId", required = false) Long userIdParam) {

        // userId 优先从请求头取，其次从参数取（兼容测试场景）
        Long finalUserId = userId != null ? userId : userIdParam;
        if (finalUserId == null) {
            return Result.fail(400, "用户未登录");
        }

        log.info("秒杀请求: productId={}, userId={}", productId, finalUserId);
        SeckillService.SeckillResult result = seckillService.seckill(finalUserId, productId);

        if (result.isSuccess()) {
            return Result.success(result);
        } else {
            return Result.fail(429, result.getMessage());
        }
    }

    /**
     * 【面试考点】查询秒杀结果（轮询模式）
     *
     * 问题描述：
     *   秒杀采用异步下单，用户提交后需要知道最终结果
     *
     * 解决思路：
     *   前端轮询此接口，后端查 Redis 中的订单状态
     *   订单状态由 SeckillConsumer 消费 Kafka 消息后写入
     *
     * 【对比方案】
     * ❌ 方案一（轮询）：前端每秒请求一次，简单但浪费资源
     * ✅ 方案二（WebSocket/SSE）：服务端主动推送，实时性好，节省资源
     *    → 生产推荐：WebSocket 或 Server-Sent Events
     *
     * 【面试追问】
     * Q: 如何优化轮询方案？
     * A: 长轮询（Long Polling）：服务端 hold 住请求，有结果才返回，
     *    减少无效请求次数，但实现复杂度增加
     *
     * @param orderId 订单号（秒杀成功时返回）
     * @return 订单处理结果
     */
    @GetMapping("/result/{orderId}")
    public Result<SeckillService.SeckillResult> getResult(@PathVariable String orderId) {
        log.info("查询秒杀结果: orderId={}", orderId);
        SeckillService.SeckillResult result = seckillService.queryResult(orderId);
        return Result.success(result);
    }
}
