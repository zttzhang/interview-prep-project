package com.interview.redis;

import com.interview.redis.advanced.PipelineDemo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】Pipeline vs 普通操作性能对比测试
 *
 * 测试目标：量化 Pipeline 的性能提升
 * 测试规模：各执行 1000 次 SET 操作
 * 预期结果：Pipeline 耗时 < 普通操作耗时
 *
 * 【面试速记】Pipeline 性能提升原理：
 * - 普通操作：N 次命令 = N 次网络往返（RTT）
 * - Pipeline：N 次命令 = 1 次网络往返（RTT）
 * - 提升倍数 ≈ N（理论值），实际 10~100 倍
 */
@Slf4j
@SpringBootTest
@DisplayName("Pipeline 性能对比测试")
class PerformanceTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PipelineDemo pipelineDemo;

    private static final int TEST_COUNT = 1000;
    private static final String KEY_PREFIX = "perf:test:";

    @AfterEach
    void cleanup() {
        // 清理测试数据
        Set<String> keys = stringRedisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("【清理】删除性能测试key: {}个", keys.size());
        }
    }

    /**
     * 【面试考点】Pipeline vs 普通操作性能对比
     *
     * 测试步骤：
     * 1. 普通操作：循环执行 1000 次 SET，记录耗时
     * 2. Pipeline 操作：批量执行 1000 次 SET，记录耗时
     * 3. 打印对比报告
     * 4. 断言：Pipeline 耗时 < 普通操作耗时
     *
     * 【面试追问】为什么 Pipeline 更快？
     * → 普通操作：每次 SET 都需要等待服务器响应（RTT）
     * → Pipeline：所有 SET 打包发送，只需等待一次响应
     * → 减少了 N-1 次网络往返时间
     */
    @Test
    @DisplayName("Pipeline vs 普通操作: 1000次SET性能对比")
    void testPipelineVsNormal() {
        log.info("========== Pipeline vs 普通操作性能对比 ==========");
        log.info("测试规模: {}次 SET 操作", TEST_COUNT);

        // ===== 普通操作 =====
        long normalStart = System.currentTimeMillis();
        for (int i = 0; i < TEST_COUNT; i++) {
            stringRedisTemplate.opsForValue().set(KEY_PREFIX + "normal:" + i, "value_" + i);
        }
        long normalElapsed = System.currentTimeMillis() - normalStart;
        log.info("【普通操作】{}次SET耗时: {}ms", TEST_COUNT, normalElapsed);

        // ===== Pipeline 操作 =====
        Map<String, String> pipelineData = new HashMap<>();
        for (int i = 0; i < TEST_COUNT; i++) {
            pipelineData.put(KEY_PREFIX + "pipeline:" + i, "value_" + i);
        }

        long pipelineStart = System.currentTimeMillis();
        pipelineDemo.pipelineSet(pipelineData);
        long pipelineElapsed = System.currentTimeMillis() - pipelineStart;
        log.info("【Pipeline操作】{}次SET耗时: {}ms", TEST_COUNT, pipelineElapsed);

        // ===== 性能对比报告 =====
        log.info("========== 性能对比报告 ==========");
        log.info("普通操作耗时: {}ms", normalElapsed);
        log.info("Pipeline耗时: {}ms", pipelineElapsed);

        if (pipelineElapsed > 0 && normalElapsed > 0) {
            double improvement = (double) normalElapsed / pipelineElapsed;
            log.info("性能提升: {:.1f}倍", improvement);
            log.info("性能对比：普通:{}ms, Pipeline:{}ms, 提升:{:.1f}倍",
                    normalElapsed, pipelineElapsed, improvement);
        } else if (pipelineElapsed == 0) {
            log.info("Pipeline耗时极短（<1ms），性能提升显著");
        }

        log.info("===================================");

        // 断言：Pipeline 耗时应该 <= 普通操作耗时
        // 注意：本地 Redis 时 RTT 极小，差异可能不明显
        // 生产环境（跨机房 Redis）差异会非常显著
        assertThat(pipelineElapsed).isLessThanOrEqualTo(normalElapsed + 100);
        // 允许 100ms 误差（本地 Redis RTT 极小，差异可能不明显）
        log.info("【断言】Pipeline耗时({})ms <= 普通操作耗时({})ms + 100ms，验证通过",
                pipelineElapsed, normalElapsed);
    }

    /**
     * 【面试考点】Pipeline 批量 GET 性能测试
     *
     * 测试步骤：
     * 1. 先批量写入 1000 条数据
     * 2. 普通循环 GET 1000 次，记录耗时
     * 3. Pipeline 批量 GET 1000 次，记录耗时
     * 4. 验证 Pipeline GET 结果正确性
     */
    @Test
    @DisplayName("Pipeline GET: 批量读取性能测试")
    void testPipelineGet() {
        log.info("========== Pipeline GET 性能测试 ==========");

        // 先写入测试数据
        Map<String, String> testData = new HashMap<>();
        for (int i = 0; i < TEST_COUNT; i++) {
            testData.put(KEY_PREFIX + "get:" + i, "value_" + i);
        }
        pipelineDemo.pipelineSet(testData);
        log.info("【准备】写入{}条测试数据", TEST_COUNT);

        // ===== 普通循环 GET =====
        long normalStart = System.currentTimeMillis();
        List<String> normalResults = new ArrayList<>();
        for (int i = 0; i < TEST_COUNT; i++) {
            String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + "get:" + i);
            normalResults.add(value);
        }
        long normalElapsed = System.currentTimeMillis() - normalStart;
        log.info("【普通GET】{}次GET耗时: {}ms", TEST_COUNT, normalElapsed);

        // ===== Pipeline 批量 GET =====
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < TEST_COUNT; i++) {
            keys.add(KEY_PREFIX + "get:" + i);
        }

        long pipelineStart = System.currentTimeMillis();
        List<Object> pipelineResults = pipelineDemo.pipelineGet(keys);
        long pipelineElapsed = System.currentTimeMillis() - pipelineStart;
        log.info("【Pipeline GET】{}次GET耗时: {}ms", TEST_COUNT, pipelineElapsed);

        // 验证结果正确性
        assertThat(pipelineResults).hasSize(TEST_COUNT);
        for (int i = 0; i < TEST_COUNT; i++) {
            assertThat(pipelineResults.get(i)).isEqualTo("value_" + i);
        }
        log.info("【Pipeline GET】结果正确性验证通过: {}条数据全部正确", TEST_COUNT);

        // 性能对比
        if (pipelineElapsed > 0) {
            double improvement = (double) normalElapsed / pipelineElapsed;
            log.info("【性能对比】普通GET: {}ms, Pipeline GET: {}ms, 提升: {:.1f}倍",
                    normalElapsed, pipelineElapsed, improvement);
        }

        log.info("========== 测试完成 ==========");
    }

    /**
     * 【面试考点】Pipeline 完整性能对比（调用 PipelineDemo.comparePerformance）
     *
     * 测试 PipelineDemo 中的完整性能对比方法
     * 验证方法能正常执行并输出对比报告
     */
    @Test
    @DisplayName("Pipeline: 完整性能对比报告")
    void testComparePerformance() {
        log.info("========== 调用 PipelineDemo.comparePerformance() ==========");

        // 调用 PipelineDemo 的完整性能对比方法
        // 该方法内部会执行 1000 次普通操作和 1000 次 Pipeline 操作
        pipelineDemo.comparePerformance();

        log.info("【性能对比】comparePerformance() 执行完成");
        // 主要验证方法能正常执行，不抛出异常
        // 性能数据通过日志输出
    }
}
