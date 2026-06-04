package com.interview.redis.advanced;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【面试考点】Redis Pipeline（管道）批量操作演示
 *
 * 问题描述：每次 Redis 操作都需要一次网络往返（RTT），批量操作时性能差
 * 解决思路：Pipeline 将多条命令打包一次性发送，减少 RTT 次数
 *
 * 【面试考点】Pipeline 原理：
 * 1. 客户端将多条命令缓存在本地
 * 2. 一次性发送给 Redis 服务器
 * 3. Redis 服务器依次执行，将所有结果一次性返回
 * 4. 减少了 N 次 RTT 为 1 次 RTT
 *
 * 【面试速记】RTT（Round Trip Time）= 网络延迟
 * - 本地 Redis：RTT ≈ 0.1ms
 * - 同机房 Redis：RTT ≈ 1ms
 * - 跨机房 Redis：RTT ≈ 10ms
 * - 1000次操作：普通 = 1000 * RTT，Pipeline = 1 * RTT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineDemo {

    private final StringRedisTemplate redisTemplate;

    /**
     * 【面试考点】Pipeline 批量 SET
     *
     * 问题描述：批量写入 1000 条数据，如何提高性能？
     * 解决思路：使用 Pipeline 批量发送 SET 命令
     *
     * ========== 方案对比 ==========
     * ❌ 方案一（普通循环 SET）：
     *    for (Map.Entry<String, String> entry : data.entrySet()) {
     *        redisTemplate.opsForValue().set(entry.getKey(), entry.getValue());
     *    }
     *    问题：N 条命令 = N 次网络往返，性能差
     *
     * ✅ 方案二（Pipeline 批量 SET）：
     *    redisTemplate.executePipelined(...)
     *    优点：N 条命令 = 1 次网络往返，性能提升 N 倍
     * ==============================
     *
     * 【面试追问】Pipeline 和 MSET 的区别？
     * → MSET：只能批量 SET，是单个原子命令
     * → Pipeline：可以批量执行任意命令（SET/GET/HSET等混合），非原子
     *
     * @param data key-value 数据
     */
    public void pipelineSet(Map<String, String> data) {
        long startTime = System.currentTimeMillis();

        // Pipeline 批量 SET
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            data.forEach((key, value) -> {
                connection.stringCommands().set(
                        key.getBytes(),
                        value.getBytes()
                );
            });
            return null;
        });

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("【Pipeline SET】批量写入{}条数据，耗时: {}ms", data.size(), elapsed);
    }

    /**
     * 【面试考点】Pipeline 批量 GET
     *
     * 问题描述：批量读取多个 key，如何减少网络往返？
     * 解决思路：Pipeline 批量发送 GET 命令，一次性获取所有结果
     *
     * 【对比方案】
     * ❌ 普通循环 GET：N 次网络往返
     * ✅ Pipeline GET：1 次网络往返
     * ✅ MGET：也是 1 次网络往返，但只支持 GET 操作
     *
     * 【面试追问】Pipeline 和 MGET 哪个更好？
     * → 只需要批量 GET：MGET 更简单（单个命令）
     * → 需要混合操作（GET + HGET + LRANGE）：Pipeline 更灵活
     *
     * @param keys 要查询的 key 列表
     * @return 查询结果列表（顺序与 keys 一致）
     */
    public List<Object> pipelineGet(List<String> keys) {
        long startTime = System.currentTimeMillis();

        // Pipeline 批量 GET
        List<Object> results = redisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    keys.forEach(key -> connection.stringCommands().get(key.getBytes()));
                    return null;
                });

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("【Pipeline GET】批量读取{}个key，耗时: {}ms", keys.size(), elapsed);
        return results;
    }

    /**
     * 【面试考点】Pipeline vs 普通操作性能对比
     *
     * 问题描述：Pipeline 到底能提升多少性能？
     * 解决思路：各执行 1000 次 SET 操作，对比耗时
     *
     * ========== 性能对比 ==========
     * 普通操作：每次 SET 都需要一次网络往返
     *   耗时 ≈ 1000 * RTT + 1000 * 命令执行时间
     *
     * Pipeline 操作：所有 SET 只需一次网络往返
     *   耗时 ≈ 1 * RTT + 1000 * 命令执行时间
     *
     * 提升倍数 ≈ 1000 * RTT / (1 * RTT) = 1000 倍（理论值）
     * 实际提升：10~100 倍（受命令执行时间影响）
     * ==============================
     *
     * 【面试追问】Pipeline 有什么限制？
     * → 1. 不保证原子性（命令之间可能被其他客户端的命令插入）
     * → 2. 不支持事务（需要原子性用 MULTI/EXEC 或 Lua 脚本）
     * → 3. 命令数量不能太多（建议每批 100-1000 条，避免内存占用过大）
     * → 4. 集群模式下，Pipeline 中的 key 必须在同一个 slot（或使用 hash tag）
     */
    public void comparePerformance() {
        int count = 1000;
        String keyPrefix = "perf:test:";

        log.info("========== Pipeline vs 普通操作性能对比 ==========");
        log.info("测试规模: {}次 SET 操作", count);

        // ===== 普通操作 =====
        long normalStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            redisTemplate.opsForValue().set(keyPrefix + "normal:" + i, "value_" + i);
        }
        long normalElapsed = System.currentTimeMillis() - normalStart;
        log.info("【普通操作】{}次SET耗时: {}ms", count, normalElapsed);

        // ===== Pipeline 操作 =====
        long pipelineStart = System.currentTimeMillis();
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (int i = 0; i < count; i++) {
                String key = keyPrefix + "pipeline:" + i;
                String value = "value_" + i;
                connection.stringCommands().set(key.getBytes(), value.getBytes());
            }
            return null;
        });
        long pipelineElapsed = System.currentTimeMillis() - pipelineStart;
        log.info("【Pipeline操作】{}次SET耗时: {}ms", count, pipelineElapsed);

        // 性能对比报告
        if (pipelineElapsed > 0) {
            double improvement = (double) normalElapsed / pipelineElapsed;
            log.info("【性能对比】普通: {}ms, Pipeline: {}ms, 提升: {:.1f}倍",
                    normalElapsed, pipelineElapsed, improvement);
        }

        // 清理测试数据
        List<String> keysToDelete = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keysToDelete.add(keyPrefix + "normal:" + i);
            keysToDelete.add(keyPrefix + "pipeline:" + i);
        }
        redisTemplate.delete(keysToDelete);
        log.info("【性能对比】测试数据已清理");
        log.info("========== 对比结束 ==========");
    }
}
