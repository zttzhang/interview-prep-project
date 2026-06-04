package com.interview.mybatis;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】MyBatis 一级缓存 vs 二级缓存测试
 *
 * 前提：需要本地 PostgreSQL 已启动，并执行过 init-sql/init.sql
 *
 * 启动数据库：
 *   podman machine start
 *   podman-compose -f docker-compose-test.yml up -d
 *
 * 测试目的：
 * 1. 验证一级缓存（L1）在同一 SqlSession 内命中
 * 2. 验证一级缓存在 UPDATE 后失效
 * 3. 验证二级缓存（L2）跨 SqlSession 命中
 * 4. 验证二级缓存在写操作后失效
 *
 * 【如何通过日志验证缓存是否命中】
 * 命中缓存：日志中不出现 "Preparing:" 或 "==>  Preparing:" 这样的 SQL 日志
 * 未命中：日志中出现完整的 SQL 语句
 * L2 命中：日志中出现 "Cache Hit Ratio [namespace]: 0.5" 这样的统计信息
 */
@Slf4j
@SpringBootTest
class CacheTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // ============================================================
    // 测试1：一级缓存命中
    // ============================================================

    /**
     * 【面试考点】一级缓存命中验证
     *
     * 测试目的：验证同一 SqlSession 内查询同一条数据两次，只发一次 SQL。
     *
     * 验证方式：
     * 1. 通过日志观察：只有第一次查询会打印 SQL，第二次不打印
     * 2. 通过对象引用验证：两次返回的是同一个对象（== 比较为 true）
     *
     * 预期结果：
     * - user1 != null（数据库有数据）
     * - user1 == user2（同一对象引用，证明第二次从缓存取，未重新创建对象）
     *
     * 【面试追问】
     * Q: 为什么要手动创建 SqlSession，而不是直接 @Autowired UserMapper？
     * A: Spring 的 SqlSessionTemplate 每次方法调用后会关闭 SqlSession，
     *    无法在两次调用之间保持同一个 Session。
     *    手动创建 SqlSession 可以控制 Session 的生命周期，演示 L1 缓存行为。
     */
    @Test
    @DisplayName("一级缓存命中 - 同 SqlSession 查两次，只发一次 SQL")
    void testL1CacheHit() {
        log.info("========== 测试：一级缓存命中 ==========");
        log.info("预期：第一次查询发 SQL，第二次命中 L1 缓存不发 SQL");

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UserMapper mapper = sqlSession.getMapper(UserMapper.class);

            log.info("--- 第一次查询（预期：发 SQL）---");
            User user1 = mapper.selectById(1L);

            log.info("--- 第二次查询（预期：命中 L1 缓存，不发 SQL）---");
            User user2 = mapper.selectById(1L);

            // 断言1：查询结果不为空（数据库有数据）
            assertThat(user1).isNotNull();
            assertThat(user2).isNotNull();

            // 断言2：两次返回同一对象引用（L1 缓存直接返回缓存对象，不重新创建）
            // 这是验证 L1 缓存命中的关键断言！
            assertThat(user1).isSameAs(user2);
            log.info("✅ 断言通过：两次查询返回同一对象引用（user1 == user2），L1 缓存命中");
        }
    }

    // ============================================================
    // 测试2：一级缓存失效
    // ============================================================

    /**
     * 【面试考点】一级缓存失效验证
     *
     * 测试目的：验证 UPDATE 操作后，一级缓存被清空，再次查询重新发 SQL。
     *
     * 验证方式：
     * 1. 通过日志观察：UPDATE 后的查询会重新打印 SQL
     * 2. 通过对象引用验证：UPDATE 前后查询返回不同的对象（!= 比较）
     *
     * 预期结果：
     * - user1 != null（第一次查询成功）
     * - user1 != user3（UPDATE 后重新查询，返回新对象，证明缓存已失效）
     *
     * 【面试追问】
     * Q: 为什么 UPDATE 会清空整个 L1 缓存，而不只是清空被更新的那条记录？
     * A: MyBatis 无法判断 UPDATE 影响了哪些缓存条目（UPDATE 可能影响多行），
     *    为了保证数据一致性，采用保守策略：清空整个 SqlSession 的 L1 缓存。
     *    这是 MyBatis 的设计决策，简单但有效。
     */
    @Test
    @DisplayName("一级缓存失效 - UPDATE 后重新发 SQL")
    @Transactional  // 使用事务，测试完成后回滚，不影响测试数据
    void testL1CacheInvalidation() {
        log.info("========== 测试：一级缓存失效（UPDATE 后）==========");

        try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
            UserMapper mapper = sqlSession.getMapper(UserMapper.class);

            log.info("--- 第一次查询（预期：发 SQL）---");
            User user1 = mapper.selectById(1L);
            assertThat(user1).isNotNull();
            log.info("第一次查询结果: id={}, username={}", user1.getId(), user1.getUsername());

            log.info("--- 第二次查询（预期：命中 L1 缓存）---");
            User user2 = mapper.selectById(1L);
            assertThat(user1).isSameAs(user2);
            log.info("✅ 第二次命中 L1 缓存（同一对象引用）");

            log.info("--- 执行 UPDATE（预期：清空 L1 缓存）---");
            // 执行更新（不改变实际数据，只是触发缓存失效）
            mapper.updateById(user1);
            log.info("UPDATE 执行完毕，L1 缓存已被清空");

            log.info("--- 第三次查询（预期：重新发 SQL，L1 缓存已失效）---");
            User user3 = mapper.selectById(1L);
            assertThat(user3).isNotNull();

            // 断言：UPDATE 后查询返回新对象（不是缓存中的旧对象）
            assertThat(user1).isNotSameAs(user3);
            log.info("✅ 断言通过：UPDATE 后返回新对象（user1 != user3），L1 缓存已失效");

            // 回滚，不影响测试数据
            sqlSession.rollback();
        }
    }

    // ============================================================
    // 测试3：二级缓存命中
    // ============================================================

    /**
     * 【面试考点】二级缓存命中验证
     *
     * 测试目的：验证不同 SqlSession 查询同一条数据，第二个 Session 命中 L2 缓存。
     *
     * 验证方式：
     * 1. 通过日志观察：第二个 Session 的查询不打印 SQL
     * 2. 通过日志观察：出现 "Cache Hit Ratio" 统计信息
     *
     * 预期结果：
     * - user1 != null（Session1 查询成功）
     * - user2 != null（Session2 从 L2 缓存获取）
     * - 日志中 Session2 不发 SQL（L2 缓存命中）
     *
     * ⚠️ 注意：此测试需要 UserMapper.xml 中有 <cache/> 标签才能生效。
     * 如果没有 <cache/> 标签，Session2 仍然会发 SQL（L2 未启用）。
     *
     * 【面试追问】
     * Q: 为什么 Session1 必须先关闭，Session2 才能命中 L2 缓存？
     * A: L2 缓存的写入时机是 SqlSession.close() 或 commit()，
     *    不是查询时立即写入。Session1 关闭时，L1 数据才写入 L2。
     *    如果 Session1 不关闭，Session2 无法命中 L2。
     */
    @Test
    @DisplayName("二级缓存命中 - 不同 SqlSession 查同一条数据")
    void testL2CacheHit() {
        log.info("========== 测试：二级缓存命中（跨 SqlSession）==========");
        log.info("注意：需要 UserMapper.xml 中有 <cache/> 标签才能生效");

        // Session1：查询并关闭（触发 L1 → L2 写入）
        User user1;
        log.info("--- Session1：查询 id=1（预期：发 SQL）---");
        try (SqlSession session1 = sqlSessionFactory.openSession()) {
            UserMapper mapper1 = session1.getMapper(UserMapper.class);
            user1 = mapper1.selectById(1L);
            log.info("Session1 查询结果: {}", user1 != null ? user1.getUsername() : "null");
            // session1 在 try-with-resources 中自动关闭，触发 L1 → L2 写入
        }
        log.info("Session1 已关闭，数据已写入 L2 缓存（如果 <cache/> 已配置）");

        assertThat(user1).isNotNull();

        // Session2：查询同一条数据（预期命中 L2 缓存）
        User user2;
        log.info("--- Session2：查询 id=1（预期：命中 L2 缓存，不发 SQL）---");
        try (SqlSession session2 = sqlSessionFactory.openSession()) {
            UserMapper mapper2 = session2.getMapper(UserMapper.class);
            user2 = mapper2.selectById(1L);
            log.info("Session2 查询结果: {}", user2 != null ? user2.getUsername() : "null");
            // 如果 L2 缓存生效，日志中不会出现 SQL，会出现 Cache Hit Ratio 统计
        }

        // 断言：两次查询结果一致（数据相同）
        assertThat(user2).isNotNull();
        assertThat(user2.getId()).isEqualTo(user1.getId());
        assertThat(user2.getUsername()).isEqualTo(user1.getUsername());

        // 注意：L2 缓存默认 readOnly=false，返回的是序列化后的副本，所以 user1 != user2（不同对象）
        // 如果 readOnly=true，则返回同一对象引用（user1 == user2）
        log.info("✅ 断言通过：两次查询数据一致（id={}, username={}）", user2.getId(), user2.getUsername());
        log.info("提示：观察日志，Session2 是否发出了 SQL？如果没有，说明 L2 缓存命中");
    }

    // ============================================================
    // 测试4：二级缓存失效
    // ============================================================

    /**
     * 【面试考点】二级缓存失效验证
     *
     * 测试目的：验证写操作（UPDATE）后，整个 namespace 的 L2 缓存被清空。
     *
     * 验证方式：
     * 1. 通过日志观察：写操作后的查询重新发 SQL
     * 2. 通过日志观察：Cache Hit Ratio 不再增长
     *
     * 预期结果：
     * - Session1 查询后，L2 缓存有数据
     * - Session2 执行 UPDATE 后，L2 缓存被清空
     * - Session3 查询时，重新发 SQL（L2 缓存未命中）
     *
     * 【面试追问】
     * Q: 二级缓存失效的范围是什么？
     * A: 整个 namespace（即整个 Mapper 接口）的缓存都会被清空，
     *    不只是被更新的那条记录。这是 MyBatis 二级缓存的一个重要特性（也是缺陷）。
     *    例如：更新 id=1 的用户，会导致 id=2、id=3 等所有用户的缓存也失效。
     */
    @Test
    @DisplayName("二级缓存失效 - 写操作后缓存被清空")
    void testL2CacheInvalidation() {
        log.info("========== 测试：二级缓存失效（写操作后）==========");

        // 步骤1：Session1 查询，填充 L2 缓存
        log.info("--- 步骤1：Session1 查询 id=1（填充 L2 缓存）---");
        try (SqlSession session1 = sqlSessionFactory.openSession()) {
            UserMapper mapper = session1.getMapper(UserMapper.class);
            User user = mapper.selectById(1L);
            assertThat(user).isNotNull();
            log.info("Session1 查询完成: {}", user.getUsername());
        }
        log.info("Session1 关闭，数据已写入 L2 缓存");

        // 步骤2：Session2 执行 UPDATE（清空 L2 缓存）
        log.info("--- 步骤2：Session2 执行 UPDATE（预期：清空整个 namespace 的 L2 缓存）---");
        try (SqlSession session2 = sqlSessionFactory.openSession()) {
            UserMapper mapper = session2.getMapper(UserMapper.class);
            User user = mapper.selectById(1L);
            if (user != null) {
                // 执行更新（不改变实际数据）
                mapper.updateById(user);
                session2.commit(); // 提交事务，触发 L2 缓存清空
                log.info("UPDATE 已提交，UserMapper namespace 的 L2 缓存已全部清空");
            }
        }

        // 步骤3：Session3 查询（预期：L2 缓存已失效，重新发 SQL）
        log.info("--- 步骤3：Session3 查询 id=1（预期：重新发 SQL，L2 缓存已失效）---");
        try (SqlSession session3 = sqlSessionFactory.openSession()) {
            UserMapper mapper = session3.getMapper(UserMapper.class);
            User user = mapper.selectById(1L);
            assertThat(user).isNotNull();
            log.info("Session3 查询结果: {}", user.getUsername());
        }

        log.info("✅ 测试完成：观察日志，步骤3 是否重新发出了 SQL？");
        log.info("提示：如果步骤3 发出了 SQL，说明 L2 缓存已被步骤2 的 UPDATE 清空");
    }
}
