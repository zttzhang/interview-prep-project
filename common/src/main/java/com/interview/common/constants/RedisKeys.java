package com.interview.common.constants;

/**
 * 【面试考点】Redis Key 常量定义
 * 
 * 目的：统一管理 Redis Key，避免魔法字符串，便于维护和搜索
 * 
 * Key 设计原则：
 * 1. 层次分明：用 : 分隔不同层级
 * 2. 包含业务标识：让人一眼看出是什么数据
 * 3. 包含过期时间提示：方便后续维护
 * 
 * 【面试追问】为什么不把所有 key 放在一个文件？
 * → 答：按业务模块拆分，便于管理。如果 key 很多，可以用枚举+方法的方式动态生成
 */
public final class RedisKeys {

    private RedisKeys() {
        // 工具类，禁止实例化
    }

    // ==================== 用户相关 ====================
    
    /**
     * 用户信息缓存
     * 格式：user:info:{userId}
     * 用途：缓存用户基本信息
     * 过期时间：30分钟
     */
    public static String userInfo(Long userId) {
        return "user:info:" + userId;
    }

    /**
     * 用户Token
     * 格式：user:token:{token}
     * 用途：存储登录Token，实现单点登录
     * 过期时间：2小时
     */
    public static String userToken(String token) {
        return "user:token:" + token;
    }

    /**
     * 用户Session
     * 格式：user:session:{userId}
     * 用途：存储用户会话信息
     * 过期时间：30分钟
     */
    public static String userSession(Long userId) {
        return "user:session:" + userId;
    }

    // ==================== 订单相关 ====================

    /**
     * 订单信息
     * 格式：order:info:{orderNo}
     * 用途：缓存订单详情
     * 过期时间：1小时
     */
    public static String orderInfo(String orderNo) {
        return "order:info:" + orderNo;
    }

    /**
     * 订单支付状态
     * 格式：order:pay:status:{orderNo}
     * 用途：防重复支付
     * 过期时间：30分钟
     */
    public static String orderPayStatus(String orderNo) {
        return "order:pay:status:" + orderNo;
    }

    // ==================== 秒杀相关 ====================

    /**
     * 秒杀商品库存
     * 格式：seckill:stock:{productId}
     * 用途：原子扣减库存
     * 过期时间：活动结束时间
     */
    public static String seckillStock(Long productId) {
        return "seckill:stock:" + productId;
    }

    /**
     * 秒杀分布式锁
     * 格式：seckill:lock:{productId}:{userId}
     * 用途：防止同一用户重复秒杀
     * 过期时间：10秒
     */
    public static String seckillLock(Long productId, Long userId) {
        return "seckill:lock:" + productId + ":" + userId;
    }

    /**
     * 秒杀用户已购买标记
     * 格式：seckill:ordered:{productId}:{userId}
     * 用途：标记用户已购买，防止重复下单
     * 过期时间：活动结束
     */
    public static String seckillOrdered(Long productId, Long userId) {
        return "seckill:ordered:" + productId + ":" + userId;
    }

    // ==================== 缓存穿透/击穿/雪崩相关 ====================

    /**
     * 热点数据缓存
     * 格式：cache:hot:{key}
     * 用途：演示缓存击穿场景
     * 过期时间：30分钟 + 随机偏移
     */
    public static String cacheHot(String key) {
        return "cache:hot:" + key;
    }

    /**
     * 空值缓存（防穿透）
     * 格式：cache:null:{key}
     * 用途：缓存空值，防止缓存穿透
     * 过期时间：5分钟（较短，避免长期不一致）
     */
    public static String cacheNull(String key) {
        return "cache:null:" + key;
    }

    // ==================== 延迟队列相关 ====================

    /**
     * 延迟队列（ZSet的key）
     * 格式：delay:queue:{queueName}
     * 用途：ZSet存储延迟任务，score为执行时间戳
     */
    public static String delayQueue(String queueName) {
        return "delay:queue:" + queueName;
    }

    /**
     * 延迟任务处理中标记
     * 格式：delay:processing:{taskId}
     * 用途：防止任务重复执行
     * 过期时间：任务执行时间+缓冲
     */
    public static String delayProcessing(String taskId) {
        return "delay:processing:" + taskId;
    }

    // ==================== 分布式锁相关 ====================

    /**
     * 通用分布式锁
     * 格式：lock:{business}:{resourceId}
     * 用途：通用的分布式锁前缀
     */
    public static String lock(String business, String resourceId) {
        return "lock:" + business + ":" + resourceId;
    }

    /**
     * 分布式锁的value前缀（用于标识锁持有者）
     * 格式：lock:owner:{lockId}
     * 用途：存储锁持有者的唯一标识，用于安全释放锁
     */
    public static String lockOwner(String lockId) {
        return "lock:owner:" + lockId;
    }

    // ==================== 限流相关 ====================

    /**
     * 接口限流计数器
     * 格式：rate:limit:{endpoint}:{userId}
     * 用途：滑动窗口/令牌桶限流
     * 过期时间：1分钟
     */
    public static String rateLimit(String endpoint, String userId) {
        return "rate:limit:" + endpoint + ":" + userId;
    }

    /**
     * 接口访问计数（用于统计）
     * 格式：metrics:count:{endpoint}:{date}
     * 用途：统计接口访问量
     */
    public static String metricsCount(String endpoint, String date) {
        return "metrics:count:" + endpoint + ":" + date;
    }

    // ==================== 幂等性相关 ====================

    /**
     * 接口幂等Token
     * 格式：idempotent:token:{token}
     * 用途：防止重复提交
     * 过期时间：5分钟
     */
    public static String idempotentToken(String token) {
        return "idempotent:token:" + token;
    }

    /**
     * 消息处理幂等标记
     * 格式：msg:processed:{msgId}
     * 用途：防止消息重复消费
     * 过期时间：7天
     */
    public static String msgProcessed(String msgId) {
        return "msg:processed:" + msgId;
    }

    // ==================== 布隆过滤器相关 ====================

    /**
     * 布隆过滤器Key
     * 格式：bloom:{filterName}
     * 用途：存储布隆过滤器的bitmap数据
     */
    public static String bloomFilter(String filterName) {
        return "bloom:" + filterName;
    }

    // ==================== 验证码/Token相关 ====================

    /**
     * 验证码
     * 格式：captcha:code:{key}
     * 用途：存储短信/邮箱验证码
     * 过期时间：5分钟
     */
    public static String captchaCode(String key) {
        return "captcha:code:" + key;
    }

    /**
     * 注册邮箱验证Token
     * 格式：register:token:{token}
     * 用途：邮箱注册激活
     * 过期时间：24小时
     */
    public static String registerToken(String token) {
        return "register:token:" + token;
    }

    /**
     * 短信发送频率限制
     * 格式：sms:limit:{phone}
     * 用途：限制短信发送频率，防刷
     * 过期时间：1分钟
     */
    public static String smsLimit(String phone) {
        return "sms:limit:" + phone;
    }

    // ==================== 购物车相关（Hash场景） ====================

    /**
     * 用户购物车
     * 格式：cart:{userId}
     * 用途：Hash结构存储购物车商品
     * field: 商品ID, value: 数量
     */
    public static String cart(Long userId) {
        return "cart:" + userId;
    }
}