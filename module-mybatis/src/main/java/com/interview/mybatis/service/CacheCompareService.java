package com.interview.mybatis.service;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【面试考点】MyBatis 一级缓存 vs 二级缓存行为对比演示
 *
 * 本 Service 通过手动控制 SqlSession 来演示缓存的命中与失效行为。
 *
 * 缓存层级总结：
 * ┌─────────────────────────────────────────────────────────┐
 * │  请求                                                    │
 * │    ↓                                                    │
 * │  二级缓存（L2）：Mapper namespace 级别，跨 SqlSession 共享  │
 * │    ↓ 未命中                                              │
 * │  一级缓存（L1）：SqlSession 级别，同一 Session 内共享       │
 * │    ↓ 未命中                                              │
 * │  数据库                                                  │
 * └─────────────────────────────────────────────────────────┘
 *
 * 【面试追问】
 * Q: Spring 中 @Transactional 方法内的一级缓存是否生效？
 * A: 生效。Spring 事务会绑定同一个 SqlSession 到当前线程，
 *    事务方法内的多次查询共享同一个 SqlSession，一级缓存有效。
 *    事务提交/回滚后，SqlSession 关闭，一级缓存清空。
 *
 * Q: 为什么 Spring 默认情况下一级缓存"看起来"不生效？
 * A: Spring 默认每次 Mapper 方法调用都会获取新的 SqlSession（通过 SqlSessionTemplate），
 *    每次调用后立即关闭 Session，所以一级缓存无法跨方法调用。
 *    只有在同一个 @Transactional 事务内，才会复用同一个 SqlSession。
 */
@Slf4j
@Service
public class CacheCompareService {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // ============================================================
    // 方法1：演示一级缓存（L1 Cache）命中
    // ============================================================

    /**
     * 【面试考点】一级缓存（SqlSession 级别）命中演示
     *
     * 问题描述：同一个 SqlSession 内查询同一条数据两次，第二次是否发 SQL？
     * 解决思路：手动创建 SqlSession，在同一个 Session 内执行两次相同查询。
     *
     * 预期结果：
     *   第一次查询 → 发 SQL，结果存入 L1 缓存
     *   第二次查询 → 命中 L1 缓存，不发 SQL（日志中只有一条 SQL）
     *
     * 【一级缓存的 Key 构成】
     * CacheKey = statementId + offset + limit + sql + parameters + environment
     * 只有这五个维度完全相同，才能命中缓存。
     *
     * 【面试追问】
     * Q: 一级缓存的存储结构是什么？
     * A: HashMap<CacheKey, Object>，存在 BaseExecutor 的 localCache 字段中。
     *
     * Q: 一级缓存有什么问题？
     * A: 在分布式环境下，不同实例的 SqlSession 相互独立，
     *    一个实例更新了数据，另一个实例的 L1 缓存不会失效 → 脏读。
     */
    public void demonstrateL1Cache() {
        log.info("========== 演示一级缓存命中 ==========");
        log.info("预期：同一 SqlSession 内查询两次，只发一条 SQL");

        // 手动创建 SqlSession（不使用 Spring 管理的 SqlSessionTemplate）
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UserMapper mapper = sqlSession.getMapper(UserMapper.class);

            log.info("--- 第一次查询 id=1（预期：发 SQL）---");
            User user1 = mapper.selectById(1L);
            log.info("第一次查询结果: {}", user1 != null ? user1.getUsername() : "null");

            log.info("--- 第二次查询 id=1（预期：命中 L1 缓存，不发 SQL）---");
            User user2 = mapper.selectById(1L);
            log.info("第二次查询结果: {}", user2 != null ? user2.getUsername() : "null");

            // 验证：两次返回的是同一个对象引用（缓存直接返回，未重新创建对象）
            log.info("两次查询是否为同一对象引用: {}", user1 == user2);
            // 预期输出：true（一级缓存直接返回缓存中的对象，不是副本）
        }
        // SqlSession 关闭后，L1 缓存自动清空
        log.info("SqlSession 已关闭，L1 缓存已清空");
    }

    // ============================================================
    // 方法2：演示一级缓存失效
    // ============================================================

    /**
     * 【面试考点】一级缓存失效场景演示
     *
     * 问题描述：什么操作会导致一级缓存失效？
     * 解决思路：在两次查询之间执行 UPDATE 操作，观察第二次查询是否重新发 SQL。
     *
     * 一级缓存失效的场景：
     * 1. 执行了 INSERT / UPDATE / DELETE 操作（清空整个 L1 缓存）
     * 2. 调用了 sqlSession.clearCache()
     * 3. 查询语句设置了 flushCache="true"
     * 4. SqlSession 关闭后重新打开
     *
     * 预期结果：
     *   第一次查询 → 发 SQL，结果存入 L1 缓存
     *   执行 UPDATE → L1 缓存被清空
     *   第二次查询 → 重新发 SQL（缓存已失效）
     *
     * 【面试追问】
     * Q: 为什么 UPDATE 会清空整个 L1 缓存，而不只是清空被更新的那条记录？
     * A: MyBatis 无法判断 UPDATE 影响了哪些缓存条目（UPDATE 可能影响多行），
     *    为了保证数据一致性，采用保守策略：清空整个 SqlSession 的 L1 缓存。
     */
    @Transactional
    public void demonstrateL1CacheInvalidation() {
        log.info("========== 演示一级缓存失效（UPDATE 后重新发 SQL）==========");

        // 注意：使用 @Transactional 时，Spring 会绑定同一个 SqlSession 到当前线程
        // 所以这里不需要手动创建 SqlSession，直接注入 Mapper 即可
        // 但为了演示清晰，我们仍然手动控制

        try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
            UserMapper mapper = sqlSession.getMapper(UserMapper.class);

            log.info("--- 第一次查询 id=1（预期：发 SQL）---");
            User user = mapper.selectById(1L);
            log.info("查询结果: {}", user != null ? user.getUsername() : "null");

            log.info("--- 执行 UPDATE 操作（预期：清空 L1 缓存）---");
            if (user != null) {
                // 更新一个无关紧要的字段（不改变实际数据，只是触发缓存失效）
                mapper.updateById(user);
                log.info("UPDATE 执行完毕，L1 缓存已被清空");
            }

            log.info("--- 第二次查询 id=1（预期：重新发 SQL，因为 L1 缓存已失效）---");
            User userAfterUpdate = mapper.selectById(1L);
            log.info("查询结果: {}", userAfterUpdate != null ? userAfterUpdate.getUsername() : "null");

            // 注意：这里不提交事务，避免影响测试数据
            sqlSession.rollback();
        }
    }

    // ============================================================
    // 方法3：演示二级缓存（L2 Cache）命中
    // ============================================================

    /**
     * 【面试考点】二级缓存（Mapper namespace 级别）命中演示
     *
     * 问题描述：不同 SqlSession 查询同一条数据，第二个 Session 是否命中缓存？
     * 解决思路：创建两个独立的 SqlSession，第一个查询后关闭（数据写入 L2），
     *           第二个 Session 再查询同一条数据。
     *
     * 预期结果：
     *   Session1 查询 → 发 SQL，Session1 关闭时数据写入 L2 缓存
     *   Session2 查询 → 命中 L2 缓存，不发 SQL
     *
     * ⚠️ 前提：UserMapper.xml 中必须有 <cache/> 标签，否则 L2 不生效
     *
     * 【二级缓存写入时机】
     * 重要：L2 缓存不是在查询时立即写入，而是在 SqlSession.close() 或
     * SqlSession.commit() 时，才将 L1 缓存的数据写入 L2 缓存。
     * 这意味着：如果 Session1 没有关闭，Session2 无法命中 L2 缓存！
     *
     * 【面试追问】
     * Q: 二级缓存的命中率如何统计？
     * A: MyBatis 会统计 hits（命中次数）和 requests（请求次数），
     *    可以通过日志看到 "Cache Hit Ratio [namespace]: 0.5" 这样的输出。
     */
    public void demonstrateL2Cache() {
        log.info("========== 演示二级缓存命中（跨 SqlSession）==========");
        log.info("注意：需要 UserMapper.xml 中有 <cache/> 标签才能生效");

        // ========== 方案对比 ==========
        // ❌ 错误做法：Session1 不关闭就让 Session2 查询
        //    Session1 的数据还没写入 L2，Session2 无法命中
        // ✅ 正确做法：Session1 关闭（触发 L1→L2 写入），再开 Session2
        // ==============================

        // Session1：查询并关闭（触发 L1 → L2 写入）
        log.info("--- Session1：第一次查询 id=1（预期：发 SQL）---");
        try (SqlSession session1 = sqlSessionFactory.openSession()) {
            UserMapper mapper1 = session1.getMapper(UserMapper.class);
            User user = mapper1.selectById(1L);
            log.info("Session1 查询结果: {}", user != null ? user.getUsername() : "null");
            // session1.close() 在 try-with-resources 中自动调用
            // 关闭时，L1 缓存数据写入 L2 缓存
        }
        log.info("Session1 已关闭，L1 数据已写入 L2 缓存");

        // Session2：查询同一条数据（预期命中 L2 缓存）
        log.info("--- Session2：第二次查询 id=1（预期：命中 L2 缓存，不发 SQL）---");
        try (SqlSession session2 = sqlSessionFactory.openSession()) {
            UserMapper mapper2 = session2.getMapper(UserMapper.class);
            User user = mapper2.selectById(1L);
            log.info("Session2 查询结果: {}", user != null ? user.getUsername() : "null");
            // 如果 L2 缓存生效，日志中不会出现 SQL 语句
            // 会看到类似：Cache Hit Ratio [com.interview.mybatis.mapper.UserMapper]: 0.5
        }
    }

    // ============================================================
    // 方法4：演示二级缓存失效
    // ============================================================

    /**
     * 【面试考点】二级缓存失效（写操作清空整个 namespace 缓存）
     *
     * 问题描述：执行写操作后，二级缓存如何失效？
     * 解决思路：先查询（填充 L2 缓存），再执行 UPDATE，再查询（验证缓存已失效）。
     *
     * 预期结果：
     *   Session1 查询 → 发 SQL，关闭后写入 L2
     *   Session2 执行 UPDATE → 清空 UserMapper namespace 的整个 L2 缓存
     *   Session3 查询 → 重新发 SQL（L2 缓存已失效）
     *
     * 【二级缓存失效的场景】
     * 1. 同一 namespace 下执行了 INSERT / UPDATE / DELETE
     * 2. 查询语句设置了 flushCache="true"
     * 3. 调用了 sqlSession.clearCache()（只清 L1，不清 L2）
     *    注意：clearCache() 不清 L2！只有写操作才清 L2。
     *
     * 【面试追问】
     * Q: 如何只清空特定 Key 的二级缓存，而不是整个 namespace？
     * A: MyBatis 原生不支持按 Key 清空 L2 缓存。
     *    如果需要精细化缓存控制，应该使用 Redis + @CacheEvict(key="#id")。
     */
    public void demonstrateL2CacheInvalidation() {
        log.info("========== 演示二级缓存失效（写操作清空整个 namespace）==========");

        // 步骤1：Session1 查询，填充 L2 缓存
        log.info("--- 步骤1：Session1 查询 id=1（填充 L2 缓存）---");
        try (SqlSession session1 = sqlSessionFactory.openSession()) {
            UserMapper mapper = session1.getMapper(UserMapper.class);
            User user = mapper.selectById(1L);
            log.info("Session1 查询结果: {}", user != null ? user.getUsername() : "null");
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
                log.info("⚠️ 注意：不只是 id=1 的缓存被清空，整个 namespace 的缓存都没了！");
            }
        }

        // 步骤3：Session3 查询（预期：L2 缓存已失效，重新发 SQL）
        log.info("--- 步骤3：Session3 查询 id=1（预期：重新发 SQL，L2 缓存已失效）---");
        try (SqlSession session3 = sqlSessionFactory.openSession()) {
            UserMapper mapper = session3.getMapper(UserMapper.class);
            User user = mapper.selectById(1L);
            log.info("Session3 查询结果: {}", user != null ? user.getUsername() : "null");
            // 预期：日志中出现 SQL 语句（缓存未命中）
        }
    }
}
