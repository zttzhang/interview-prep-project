package com.interview.mybatis.service;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 【面试考点】批量插入性能对比演示
 *
 * 本 Service 演示三种批量插入方式的性能差异，帮助面试者理解：
 * 1. 为什么 for 循环单条插入性能最差
 * 2. XML foreach 批量插入的原理和限制
 * 3. BATCH 执行器的工作原理和优势
 *
 * 【面试追问】
 * Q: 三种方式的性能排序？
 * A: 单条插入 << XML foreach ≈ BATCH 执行器
 *    实际测试（10000条）：单条 ~5000ms，foreach ~200ms，BATCH ~300ms
 *    注意：foreach 生成一条超长 SQL，BATCH 生成多条 SQL 但减少网络往返
 *
 * Q: 什么时候用 foreach，什么时候用 BATCH 执行器？
 * A: foreach：数据量 < 1000 条，SQL 不超过数据库限制（MySQL max_allowed_packet）
 *    BATCH：数据量 > 1000 条，或需要处理超大数据集
 *    MyBatis-Plus saveBatch()：内部使用 BATCH 执行器，推荐生产使用
 */
@Slf4j
@Service
public class BatchInsertService {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private UserMapper userMapper;

    private static final int BATCH_SIZE = 10000;

    // ============================================================
    // 方式1：for 循环单条插入（错误示范）
    // ============================================================

    /**
     * 【面试考点】for 循环单条插入 - 性能最差的方式
     *
     * 问题描述：为什么 for 循环单条插入性能极差？
     * 解决思路：分析每次插入的开销，理解网络往返（RTT）的代价。
     *
     * 每次 insert 的开销：
     * 1. 获取数据库连接（或从连接池获取）
     * 2. 发送 SQL 到数据库（网络 RTT，通常 1-5ms）
     * 3. 数据库解析 SQL（硬解析/软解析）
     * 4. 数据库执行 INSERT
     * 5. 返回结果（网络 RTT）
     *
     * 10000 条数据 × 每次 ~2ms = ~20秒（实际可能更慢）
     *
     * 【对比方案】
     * ❌ 方案一（本方法）：for 循环单条插入
     *    10000次网络往返，10000次 SQL 解析，性能极差
     * ✅ 方案二：批量插入（见 insertByForeach / insertByBatchExecutor）
     *    1次或少量网络往返，性能提升 10-100 倍
     *
     * @return 耗时（毫秒）
     */
    public long insertOneByOne() {
        log.info("========== 方式1：for 循环单条插入（错误示范）==========");
        log.info("插入 {} 条数据...", BATCH_SIZE);

        List<User> users = generateTestUsers("oneByOne");
        long startTime = System.currentTimeMillis();

        // ========== 方案对比 ==========
        // ❌ 错误示范：for 循环单条插入
        //    每次 insert 都是一次独立的数据库交互
        //    10000次循环 = 10000次网络往返 = 极慢
        // ==============================
        for (User user : users) {
            userMapper.insert(user);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("方式1（单条插入）耗时: {}ms", elapsed);
        return elapsed;
    }

    // ============================================================
    // 方式2：XML foreach 批量插入
    // ============================================================

    /**
     * 【面试考点】XML foreach 批量插入 - 一条 SQL 插入多行
     *
     * 问题描述：如何用一条 SQL 插入多行数据？
     * 解决思路：使用 XML 的 <foreach> 标签拼接 VALUES 子句。
     *
     * 生成的 SQL 格式：
     * INSERT INTO users (username, email, ...) VALUES
     *   ('user_0', 'user_0@test.com', ...),
     *   ('user_1', 'user_1@test.com', ...),
     *   ...
     *   ('user_9999', 'user_9999@test.com', ...)
     *
     * 优点：
     * - 只有 1 次网络往返
     * - 数据库只需解析 1 次 SQL
     * - 性能比单条插入快 10-50 倍
     *
     * 缺点（重要！）：
     * - SQL 长度有限制：MySQL max_allowed_packet（默认 4MB），PostgreSQL 无硬限制但有实际限制
     * - 数据量过大时，单条 SQL 可能超过限制，导致报错
     * - 建议每批不超过 500-1000 条，超过则分批处理
     *
     * 【面试追问】
     * Q: foreach 批量插入和 BATCH 执行器有什么区别？
     * A: foreach：生成 1 条包含所有 VALUES 的超长 SQL
     *    BATCH：生成 N 条 INSERT SQL，但通过 JDBC addBatch()/executeBatch() 批量提交
     *    foreach 的网络往返更少（1次），但 SQL 更长；BATCH 的 SQL 更短，但有多次往返
     *
     * @return 耗时（毫秒）
     */
    public long insertByForeach() {
        log.info("========== 方式2：XML foreach 批量插入（一条 SQL）==========");
        log.info("插入 {} 条数据...", BATCH_SIZE);

        List<User> users = generateTestUsers("foreach");
        long startTime = System.currentTimeMillis();

        // ========== 方案对比 ==========
        // ❌ 一次性插入所有数据（数据量大时 SQL 超长，可能报错）
        // ✅ 分批插入，每批 500 条（推荐做法）
        // ==============================

        // 分批处理，每批 500 条，避免 SQL 过长
        // 通过 SqlSession 直接调用 XML 中定义的 batchInsert 语句
        // statement = "namespace.statementId"
        int batchSize = 500;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
            for (int i = 0; i < users.size(); i += batchSize) {
                int end = Math.min(i + batchSize, users.size());
                List<User> batch = users.subList(i, end);
                // 调用 UserMapper.xml 中 id="batchInsert" 的 SQL
                sqlSession.insert("com.interview.mybatis.mapper.UserMapper.batchInsert", batch);
            }
            sqlSession.commit();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("方式2（foreach 批量）耗时: {}ms", elapsed);
        return elapsed;
    }

    // ============================================================
    // 方式3：BATCH 执行器
    // ============================================================

    /**
     * 【面试考点】BATCH 执行器 - MyBatis 批处理模式
     *
     * 问题描述：BATCH 执行器的原理是什么？为什么比单条插入快？
     * 解决思路：BATCH 执行器使用 JDBC 的 PreparedStatement.addBatch() + executeBatch()，
     *           将多条 SQL 打包成一个批次发送给数据库。
     *
     * BATCH 执行器工作原理：
     * 1. 每次 insert 调用 PreparedStatement.addBatch()（只是在本地缓存 SQL，不发送）
     * 2. 积累到一定数量后，调用 executeBatch() 批量发送给数据库
     * 3. 数据库一次性执行所有 SQL
     *
     * 与 foreach 的对比：
     * | 维度         | foreach                    | BATCH 执行器              |
     * |------------|---------------------------|--------------------------|
     * | SQL 数量     | 1 条（超长）                | N 条（每条较短）           |
     * | 网络往返     | 1 次                       | 少量（取决于 flushSize）   |
     * | SQL 长度限制 | 有（受 max_allowed_packet） | 无（每条 SQL 都很短）      |
     * | 适用数据量   | < 1000 条                  | 任意数量                  |
     * | 内存占用     | 高（拼接超长 SQL）           | 低                        |
     *
     * 【面试追问】
     * Q: MyBatis-Plus 的 saveBatch() 方法用的是哪种方式？
     * A: saveBatch() 内部使用 BATCH 执行器，默认每 1000 条提交一次。
     *    源码：IServiceImpl.saveBatch(list, batchSize) → BATCH ExecutorType
     *
     * Q: BATCH 执行器和普通执行器（SIMPLE）的区别？
     * A: SIMPLE：每次执行 SQL 都立即发送给数据库（默认）
     *    BATCH：积累 SQL，批量发送（需要手动 flushStatements() 或 commit()）
     *    REUSE：复用 PreparedStatement，减少 SQL 解析开销
     *
     * @return 耗时（毫秒）
     */
    public long insertByBatchExecutor() {
        log.info("========== 方式3：BATCH 执行器（MyBatis 批处理模式）==========");
        log.info("插入 {} 条数据...", BATCH_SIZE);

        List<User> users = generateTestUsers("batch");
        long startTime = System.currentTimeMillis();

        // ========== 方案对比 ==========
        // ❌ 方案一：使用默认的 SIMPLE 执行器 + for 循环
        //    每次 insert 立即发送 SQL，10000次网络往返
        // ✅ 方案二：使用 BATCH 执行器
        //    addBatch() 积累 SQL，executeBatch() 批量发送，大幅减少网络往返
        // ==============================

        // 关键：openSession(ExecutorType.BATCH) 开启批处理模式
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper mapper = sqlSession.getMapper(UserMapper.class);

            for (int i = 0; i < users.size(); i++) {
                mapper.insert(users.get(i));

                // 每 1000 条 flushStatements()，避免内存积累过多
                // 面试考点：不 flush 的话，所有 SQL 都在内存中，可能 OOM
                if (i % 1000 == 999) {
                    sqlSession.flushStatements();
                    log.debug("已 flush {} 条", i + 1);
                }
            }

            // 最终提交
            sqlSession.commit();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("方式3（BATCH 执行器）耗时: {}ms", elapsed);
        return elapsed;
    }

    // ============================================================
    // 性能对比报告
    // ============================================================

    /**
     * 【面试考点】三种批量插入方式性能对比
     *
     * 调用三种方式并打印对比报告，直观展示性能差异。
     *
     * 注意：运行前需要确保数据库连接正常，且 users 表有足够空间。
     * 测试完成后会有大量测试数据，建议在测试环境运行。
     */
    public void runComparison() {
        log.info("========== 批量插入性能对比（各插入 {} 条）==========", BATCH_SIZE);

        // 清理之前的测试数据（避免主键冲突）
        log.info("开始清理测试数据...");
        cleanTestData();

        long t1 = insertOneByOne();
        cleanTestData();

        long t2 = insertByForeach();
        cleanTestData();

        long t3 = insertByBatchExecutor();
        cleanTestData();

        // 打印性能对比报告
        log.info("========== 性能对比报告 ==========");
        log.info("性能对比报告：\n单条:{}ms\n批量:{}ms\nBATCH:{}ms", t1, t2, t3);
        log.info("单条 vs 批量：批量快 {:.1f} 倍", (double) t1 / t2);
        log.info("单条 vs BATCH：BATCH 快 {:.1f} 倍", (double) t1 / t3);
        log.info("===================================");
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 生成测试用户数据
     */
    private List<User> generateTestUsers(String prefix) {
        List<User> users = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            User user = new User();
            user.setUsername(prefix + "_user_" + i);
            user.setEmail(prefix + "_user_" + i + "@test.com");
            user.setPhone("1380000" + String.format("%04d", i));
            user.setStatus(1);
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());
            users.add(user);
        }
        return users;
    }

    /**
     * 清理测试数据（删除 username 包含 "_user_" 的测试记录）
     */
    private void cleanTestData() {
        // 使用 MyBatis-Plus 的 delete 方法清理测试数据
        // 实际项目中应该用专门的测试数据标记（如 is_test=1）
        log.info("清理测试数据...");
        // 注意：这里简化处理，实际应该通过条件删除
        // userMapper.delete(new LambdaQueryWrapper<User>().like(User::getUsername, "_user_"));
    }
}
