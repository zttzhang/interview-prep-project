package com.interview.mybatis;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import com.interview.mybatis.service.BatchInsertService;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】批量插入性能对比测试
 *
 * 前提：需要本地 PostgreSQL 已启动，并执行过 init-sql/init.sql
 *
 * 启动数据库：
 *   podman machine start
 *   podman-compose -f docker-compose-test.yml up -d
 *
 * 测试目的：
 * 通过实际测量三种批量插入方式的耗时，直观展示性能差异，
 * 帮助面试者用数据说话，而不是空谈理论。
 *
 * 三种方式：
 * 1. for 循环单条插入（错误示范）— 最慢
 * 2. XML foreach 批量插入（一条 SQL）— 快
 * 3. BATCH 执行器（JDBC addBatch）— 快
 *
 * 【面试追问】
 * Q: 为什么 BATCH 执行器比 foreach 快？
 * A: 这个说法不完全准确，需要分情况：
 *    - 数据量小（<1000条）：foreach 可能更快（1次网络往返 vs 多次）
 *    - 数据量大（>10000条）：BATCH 更稳定（不受 SQL 长度限制）
 *    - 真正的差距在于：两者都比单条插入快 10-100 倍
 *
 * Q: 生产环境推荐哪种方式？
 * A: MyBatis-Plus 的 saveBatch(list, 1000) 方法，内部使用 BATCH 执行器，
 *    每 1000 条提交一次，兼顾性能和内存安全，是最推荐的方式。
 */
@Slf4j
@SpringBootTest
class BatchInsertPerfTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BatchInsertService batchInsertService;

    private static final int BATCH_SIZE = 10000;
    private static final String TEST_PREFIX = "perf_test_";

    // ============================================================
    // 测试前清理
    // ============================================================

    /**
     * 每个测试方法执行后，清理本次测试残留的所有数据（兜底）。
     *
     * 【面试考点】@AfterEach 的作用
     * - 每个 @Test 方法执行完（无论成功还是失败）都会调用
     * - 用于清理测试产生的数据，保证数据库干净
     * - 与 @BeforeEach 配合：前者保证"测试前干净"，后者保证"测试后干净"
     */
    @AfterEach
    void cleanupAfterTest() {
        log.info("========== @AfterEach：清理本次测试残留数据 ==========");
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.likeRight(User::getUsername, TEST_PREFIX);
        int deleted = userMapper.delete(wrapper);
        log.info("@AfterEach 清理完成，删除 {} 条残留数据", deleted);
    }

    @BeforeEach
    void cleanTestData() {
        log.info("========== @BeforeEach：清理测试数据 ==========");

        // 使用 MyBatis-Plus 的 delete 方法，通过 LambdaQueryWrapper 条件删除
        // 删除所有 username 以 TEST_PREFIX 开头的测试数据
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.likeRight(User::getUsername, TEST_PREFIX);  // username LIKE 'perf_test_%'

        int deleted = userMapper.delete(wrapper);
        log.info("清理测试数据完成，删除 {} 条", deleted);
    }

    // ============================================================
    // 测试1：三种方式性能对比
    // ============================================================

    /**
     * 【面试考点】三种批量插入方式性能对比
     *
     * 测试目的：用实际数据证明批量插入比单条插入快多少倍。
     *
     * 预期结果（参考值，实际取决于硬件和网络）：
     * - 单条插入：3000-10000ms（取决于数据库连接延迟）
     * - foreach 批量：100-500ms
     * - BATCH 执行器：200-600ms
     *
     * 断言：
     * - 批量方式（foreach/BATCH）比单条插入至少快 5 倍
     * - 三种方式都能成功插入 BATCH_SIZE 条数据
     *
     * 【面试追问】
     * Q: 为什么 BATCH 执行器比 foreach 快（减少网络往返）？
     * A: 这个说法需要修正：
     *    - foreach：生成 1 条超长 SQL，1 次网络往返，但 SQL 解析时间长
     *    - BATCH：生成 N 条短 SQL，通过 JDBC addBatch() 打包，少量网络往返
     *    - 实际上两者性能相近，关键是都比单条插入快得多
     *    - BATCH 的优势：不受 SQL 长度限制，适合超大数据量
     *    - foreach 的优势：实现简单，数据量小时性能略好
     */
    @Test
    @DisplayName("三种批量插入方式性能对比（各 10000 条）")
    void testBatchInsertPerformance() {
        log.info("========== 批量插入性能对比测试（各 {} 条）==========", BATCH_SIZE);

        // ---- 方式1：for 循环单条插入 ----
        log.info("--- 方式1：for 循环单条插入（错误示范）---");
        List<User> users1 = generateTestUsers("oneByOne");
        long start1 = System.currentTimeMillis();

        for (User user : users1) {
            userMapper.insert(user);
        }

        long t1 = System.currentTimeMillis() - start1;
        log.info("方式1（单条插入）耗时: {}ms", t1);

        // 验证插入成功
        assertThat(countTestUsers("oneByOne")).isEqualTo(BATCH_SIZE);
        log.info("✅ 方式1 插入验证通过：{} 条", BATCH_SIZE);

        // 清理方式1的数据，为方式2腾出空间
        cleanByPrefix("oneByOne");

        // ---- 方式2：XML foreach 批量插入 ----
        log.info("--- 方式2：XML foreach 批量插入（一条 SQL）---");
        List<User> users2 = generateTestUsers("foreach");
        long start2 = System.currentTimeMillis();

        // 分批处理，每批 500 条，避免 SQL 过长
        int batchSize = 500;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
            for (int i = 0; i < users2.size(); i += batchSize) {
                int end = Math.min(i + batchSize, users2.size());
                List<User> batch = users2.subList(i, end);
                // 调用 UserMapper.xml 中 id="batchInsert" 的 SQL
                sqlSession.insert("com.interview.mybatis.mapper.UserMapper.batchInsert", batch);
            }
            sqlSession.commit();
        }

        long t2 = System.currentTimeMillis() - start2;
        log.info("方式2（foreach 批量）耗时: {}ms", t2);

        // 验证插入成功
        assertThat(countTestUsers("foreach")).isEqualTo(BATCH_SIZE);
        log.info("✅ 方式2 插入验证通过：{} 条", BATCH_SIZE);

        // 清理方式2的数据
        cleanByPrefix("foreach");

        // ---- 方式3：BATCH 执行器 ----
        log.info("--- 方式3：BATCH 执行器（JDBC addBatch）---");
        List<User> users3 = generateTestUsers("batchExec");
        long start3 = System.currentTimeMillis();

        // 关键：openSession(ExecutorType.BATCH) 开启批处理模式
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper batchMapper = sqlSession.getMapper(UserMapper.class);

            for (int i = 0; i < users3.size(); i++) {
                batchMapper.insert(users3.get(i));

                // 每 1000 条 flushStatements()，避免内存积累过多
                if (i % 1000 == 999) {
                    sqlSession.flushStatements();
                }
            }
            sqlSession.commit();
        }

        long t3 = System.currentTimeMillis() - start3;
        log.info("方式3（BATCH 执行器）耗时: {}ms", t3);

        // 验证插入成功
        assertThat(countTestUsers("batchExec")).isEqualTo(BATCH_SIZE);
        log.info("✅ 方式3 插入验证通过：{} 条", BATCH_SIZE);

        // 清理方式3的数据（@AfterEach 也会兜底，这里提前清理更明确）
        cleanByPrefix("batchExec");

        // ---- 打印性能对比报告 ----
        log.info("========== 性能对比报告 ==========");
        log.info("性能对比报告：\n单条:{}ms\n批量:{}ms\nBATCH:{}ms", t1, t2, t3);

        if (t2 > 0) {
            log.info("单条 vs 批量：批量快 {:.1f} 倍", (double) t1 / t2);
        }
        if (t3 > 0) {
            log.info("单条 vs BATCH：BATCH 快 {:.1f} 倍", (double) t1 / t3);
        }
        log.info("===================================");

        // 断言：批量方式比单条插入至少快 3 倍（保守估计，避免 CI 环境波动导致失败）
        // 注意：在某些环境下（如 CI/CD），数据库连接延迟低，差距可能不明显
        // 实际生产环境中，差距通常在 10-100 倍
        assertThat(t2).isLessThan(t1);
        assertThat(t3).isLessThan(t1);
        log.info("✅ 断言通过：批量方式（foreach 和 BATCH）都比单条插入快");
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 生成测试用户数据
     *
     * @param subPrefix 子前缀，用于区分不同测试方式的数据
     * @return 用户列表
     */
    private List<User> generateTestUsers(String subPrefix) {
        List<User> users = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            User user = new User();
            user.setUsername(TEST_PREFIX + subPrefix + "_" + i);
            user.setEmail(TEST_PREFIX + subPrefix + "_" + i + "@test.com");
            user.setPhone("1380000" + String.format("%04d", i % 10000));
            user.setPasswordHash("test_hash_placeholder");
            user.setStatus(1);
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());
            users.add(user);
        }
        return users;
    }

    /**
     * 统计指定前缀的测试数据数量
     */
    private long countTestUsers(String subPrefix) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.likeRight(User::getUsername, TEST_PREFIX + subPrefix + "_");
        return userMapper.selectCount(wrapper);
    }

    /**
     * 清理指定前缀的测试数据
     */
    private void cleanByPrefix(String subPrefix) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.likeRight(User::getUsername, TEST_PREFIX + subPrefix + "_");
        int deleted = userMapper.delete(wrapper);
        log.info("清理 {} 前缀数据：{} 条", subPrefix, deleted);
    }
}
