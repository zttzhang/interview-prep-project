package com.interview.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【面试考点】Redisson 配置类
 *
 * 问题描述：为什么要用 Redisson 而不是手写 SET NX？
 * 解决思路：Redisson 提供了 watchdog 机制，自动解决锁续期问题
 *
 * ========== 客户端对比 ==========
 * ❌ Jedis（同步阻塞）：
 *    - 同步阻塞 I/O，每个线程需要独立连接
 *    - 连接池管理复杂，高并发下性能瓶颈
 *    - 不支持分布式对象（锁、队列等）
 *
 * ✅ Lettuce（异步响应式）：
 *    - 基于 Netty，异步非阻塞 I/O
 *    - 多线程共享单个连接，性能更好
 *    - Spring Boot 默认使用 Lettuce
 *    - 不支持分布式对象
 *
 * ✅✅ Redisson（分布式对象）：
 *    - 基于 Netty，异步非阻塞 I/O
 *    - 提供分布式锁、分布式集合、分布式队列等高级对象
 *    - 内置 watchdog 机制，自动续期
 *    - 支持 Redis Cluster、Sentinel、Master-Slave
 * ================================
 *
 * 【面试考点】watchdog 机制原理：
 * 1. 默认锁超时时间（leaseTime）= 30秒
 * 2. 每隔 10秒（leaseTime/3）检查一次锁是否还被持有
 * 3. 如果持有，续期到 30秒
 * 4. 如果持有锁的线程宕机，watchdog 停止续期，30秒后锁自动释放
 *
 * 【面试追问】为什么用 Redisson 而不是手写 SET NX？
 * → 手写 SET NX 存在锁续期问题：业务执行时间 > 锁超时时间时，锁自动释放
 * → 其他线程可以获取锁，导致并发安全问题
 * → Redisson watchdog 自动续期，彻底解决这个问题
 *
 * 【面试追问】watchdog 什么时候不生效？
 * → 当手动指定 leaseTime 时（如 lock(10, TimeUnit.SECONDS)），watchdog 不生效
 * → 只有不指定 leaseTime（使用默认值）时，watchdog 才会自动续期
 */
@Slf4j
@Configuration
public class RedissonConfig {

    /**
     * 【面试考点】创建 RedissonClient Bean
     *
     * 问题描述：如何配置 Redisson 连接池？
     * 解决思路：通过 SingleServerConfig 配置单机模式连接参数
     *
     * 【对比方案】
     * - 单机模式（useSingleServer）：开发/测试环境
     * - 哨兵模式（useSentinelServers）：高可用生产环境
     * - 集群模式（useClusterServers）：大规模生产环境
     * - 主从模式（useMasterSlaveServers）：读写分离场景
     *
     * 【面试追问】Redisson 连接池参数如何调优？
     * → connectionMinimumIdleSize：最小空闲连接数，避免频繁创建连接
     * → connectionTimeout：连接超时，超过后抛出异常
     * → retryAttempts：重试次数，网络抖动时自动重试
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        log.info("初始化 RedissonClient，连接 redis://localhost:6379");

        Config config = new Config();

        SingleServerConfig serverConfig = config.useSingleServer()
                // Redis 地址
                .setAddress("redis://localhost:6379")
                // 最小空闲连接数（保持至少10个连接，避免频繁创建）
                .setConnectionMinimumIdleSize(10)
                // 最大连接池大小
                .setConnectionPoolSize(64)
                // 连接超时（ms）
                .setConnectTimeout(3000)
                // 命令等待超时（ms）
                .setTimeout(3000)
                // 重试次数（网络抖动时自动重试）
                .setRetryAttempts(3)
                // 重试间隔（ms）
                .setRetryInterval(1500)
                // 数据库编号
                .setDatabase(0);

        log.info("RedissonClient 配置完成: address={}, minIdle={}, timeout={}ms, retryAttempts={}",
                "redis://localhost:6379", 10, 3000, 3);

        return Redisson.create(config);
    }
}
