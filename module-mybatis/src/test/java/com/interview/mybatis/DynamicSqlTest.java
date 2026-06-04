package com.interview.mybatis;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】MyBatis 动态 SQL 测试
 *
 * 前提：需要本地 PostgreSQL 已启动，并执行过 init-sql/init.sql
 *
 * 测试数据（来自 init.sql）：
 *   users: zhangsan(id=1,status=1), lisi(id=2,status=1), wangwu(id=3,status=1),
 *          zhaoliu(id=4,status=1), sunqi(id=5,status=0)
 *
 * 本测试类验证 UserMapper.xml 中的动态 SQL：
 * 1. <if> + <where>  — 动态条件查询
 * 2. <foreach>       — IN 查询
 * 3. <set> + <if>    — 动态更新
 * 4. choose/when/otherwise — 类似 switch 的条件选择
 *
 * 【面试追问】
 * Q: 动态 SQL 的底层实现原理是什么？
 * A: MyBatis 在解析 XML 时，将动态 SQL 标签编译为 SqlNode 树（组合模式）：
 *    - IfSqlNode：对应 <if>
 *    - WhereSqlNode：对应 <where>
 *    - ForEachSqlNode：对应 <foreach>
 *    - SetSqlNode：对应 <set>
 *    执行时，遍历 SqlNode 树，根据参数动态拼接 SQL 字符串。
 */
@Slf4j
@SpringBootTest
class DynamicSqlTest {

    @Autowired
    private UserMapper userMapper;

    // ============================================================
    // 测试1：<if> + <where> 动态条件查询
    // ============================================================

    /**
     * 【面试考点】<if> + <where> 动态条件查询
     *
     * 测试目的：验证 <where> 标签在不同参数组合下生成正确的 SQL。
     *
     * 测试场景：
     * 1. 所有参数为 null → 查全表（无 WHERE 子句）
     * 2. 只传 name → WHERE username LIKE '%zhang%'
     * 3. 传 name + status → WHERE username LIKE '%zhang%' AND status = 1
     *
     * 【面试追问】
     * Q: <where> 标签如何处理 "WHERE AND" 的问题？
     * A: <where> 会自动去掉第一个条件前面的 AND/OR，
     *    所以每个 <if> 都可以放心写 "AND xxx"，不用担心第一个条件多余的 AND。
     */
    @Test
    @DisplayName("<if>+<where> - 无条件查询（查全表）")
    void testSelectByCondition_noCondition() {
        log.info("========== 测试：<where> 无条件（查全表）==========");

        // 所有参数为 null，生成 SQL：SELECT ... FROM users ORDER BY id
        List<User> users = userMapper.selectByCondition(null, null, null);

        log.info("查询结果数量: {}", users.size());
        users.forEach(u -> log.info("  id={}, username={}, status={}", u.getId(), u.getUsername(), u.getStatus()));

        // init.sql 有 5 个用户
        assertThat(users).isNotEmpty();
        assertThat(users.size()).isGreaterThanOrEqualTo(5);
        log.info("✅ 断言通过：无条件查询返回所有用户");
    }

    @Test
    @DisplayName("<if>+<where> - 按用户名模糊查询")
    void testSelectByCondition_byName() {
        log.info("========== 测试：<if> 动态条件 - 按用户名模糊查询 ==========");

        // 只传 name，生成 SQL：SELECT ... FROM users WHERE username LIKE '%zhang%'
        List<User> users = userMapper.selectByCondition("zhang", null, null);

        log.info("用户名含 'zhang' 的用户数: {}", users.size());
        users.forEach(u -> log.info("  id={}, username={}", u.getId(), u.getUsername()));

        // init.sql 中只有 zhangsan 包含 "zhang"
        assertThat(users).isNotEmpty();
        assertThat(users).allMatch(u -> u.getUsername().contains("zhang"));
        log.info("✅ 断言通过：所有结果的 username 都包含 'zhang'");
    }

    @Test
    @DisplayName("<if>+<where> - 多条件组合查询")
    void testSelectByCondition_multipleConditions() {
        log.info("========== 测试：<if> 多条件组合 ==========");

        // 传 name + status，生成 SQL：
        // SELECT ... FROM users WHERE username LIKE '%zhang%' AND status = 1
        List<User> users = userMapper.selectByCondition("zhang", null, 1);

        log.info("用户名含 'zhang' 且 status=1 的用户数: {}", users.size());
        users.forEach(u -> log.info("  id={}, username={}, status={}", u.getId(), u.getUsername(), u.getStatus()));

        assertThat(users).isNotEmpty();
        assertThat(users).allMatch(u -> u.getUsername().contains("zhang") && u.getStatus() == 1);
        log.info("✅ 断言通过：所有结果满足 username LIKE '%zhang%' AND status=1");
    }

    // ============================================================
    // 测试2：<foreach> IN 查询
    // ============================================================

    /**
     * 【面试考点】<foreach> IN 查询
     *
     * 测试目的：验证 <foreach> 生成正确的 IN 子句。
     *
     * 生成的 SQL：SELECT ... FROM users WHERE id IN (1, 2, 3)
     *
     * 【面试追问】
     * Q: <foreach> 的 collection 属性在不同情况下怎么写？
     * A: - @Param("ids") List<Long> ids → collection="ids"
     *    - 单个 List 参数（无 @Param）→ collection="list" 或 collection="collection"
     *    - 数组参数 → collection="array"
     *    - Map 中的 List → collection="map中的key名"
     */
    @Test
    @DisplayName("<foreach> - IN 查询多个 ID")
    void testSelectByIds() {
        log.info("========== 测试：<foreach> IN 查询 ==========");

        List<Long> ids = Arrays.asList(1L, 2L, 3L);
        log.info("查询 ids: {}", ids);

        // 生成 SQL：SELECT ... FROM users WHERE id IN (1, 2, 3)
        List<User> users = userMapper.selectByIds(ids);

        log.info("查询结果数量: {}", users.size());
        users.forEach(u -> log.info("  id={}, username={}", u.getId(), u.getUsername()));

        // 断言：返回 3 条记录
        assertThat(users).hasSize(3);
        // 断言：所有返回的 id 都在查询列表中
        assertThat(users).allMatch(u -> ids.contains(u.getId()));
        log.info("✅ 断言通过：IN 查询返回正确数量的记录");
    }

    @Test
    @DisplayName("<foreach> - 单个 ID 的 IN 查询（边界测试）")
    void testSelectByIds_singleId() {
        log.info("========== 测试：<foreach> 单个 ID ==========");

        List<Long> ids = Arrays.asList(1L);
        List<User> users = userMapper.selectByIds(ids);

        assertThat(users).hasSize(1);
        assertThat(users.get(0).getId()).isEqualTo(1L);
        log.info("✅ 断言通过：单个 ID 的 IN 查询正常");
    }

    // ============================================================
    // 测试3：<set> + <if> 动态更新
    // ============================================================

    /**
     * 【面试考点】<set> + <if> 动态更新
     *
     * 测试目的：验证 <set> 标签只更新非 null 字段，不影响其他字段。
     *
     * 测试场景：只更新 username，不更新 email 和 phone。
     * 生成的 SQL：UPDATE users SET username = 'new_name', update_time = NOW() WHERE id = 1
     *
     * 【面试追问】
     * Q: <set> 标签如何处理最后一个多余的逗号？
     * A: <set> 会自动去掉最后一个 <if> 生成的内容末尾的逗号。
     *    例如：SET username = 'xxx', update_time = NOW()（最后没有多余逗号）
     *
     * Q: 如果所有 <if> 都不满足，会发生什么？
     * A: 本例中有兜底的 update_time = NOW()，所以不会出现空 SET 的问题。
     *    如果没有兜底字段，<set> 内容为空，SQL 语法错误。
     */
    @Test
    @DisplayName("<set>+<if> - 动态更新（只更新非 null 字段）")
    @Transactional  // 使用事务，测试完成后回滚，不影响测试数据
    void testUpdateByCondition() {
        log.info("========== 测试：<set>+<if> 动态更新 ==========");

        // 先查出原始数据
        User originalUser = userMapper.selectById(1L);
        assertThat(originalUser).isNotNull();
        log.info("原始数据: id={}, username={}, email={}", 
                originalUser.getId(), originalUser.getUsername(), originalUser.getEmail());

        // 构造只更新 username 的对象（email 和 phone 为 null，不应该被更新）
        User updateUser = new User();
        updateUser.setId(1L);
        updateUser.setUsername("updated_zhangsan");
        // email 和 phone 故意不设置（null），验证 <if> 不会更新这些字段

        // 执行动态更新
        // 生成 SQL：UPDATE users SET username = 'updated_zhangsan', update_time = NOW() WHERE id = 1
        userMapper.updateByCondition(updateUser);
        log.info("执行动态更新：只更新 username");

        // 验证更新结果
        User updatedUser = userMapper.selectById(1L);
        assertThat(updatedUser).isNotNull();

        // 断言：username 已更新
        assertThat(updatedUser.getUsername()).isEqualTo("updated_zhangsan");
        log.info("✅ username 已更新: {}", updatedUser.getUsername());

        // 断言：email 未被更新（<if test="email != null"> 不满足，email 保持原值）
        assertThat(updatedUser.getEmail()).isEqualTo(originalUser.getEmail());
        log.info("✅ email 未被更新（保持原值）: {}", updatedUser.getEmail());

        // 事务回滚，恢复原始数据
        log.info("事务回滚，恢复原始数据");
    }

    // ============================================================
    // 测试4：choose/when/otherwise（类似 switch）
    // ============================================================

    /**
     * 【面试考点】<choose> + <when> + <otherwise> — 类似 switch/case
     *
     * 测试目的：验证 <choose> 标签的互斥条件选择行为。
     *
     * <choose> 的行为：
     * - 从上到下依次判断 <when> 条件
     * - 第一个满足的 <when> 生效，后续 <when> 不再判断（互斥）
     * - 如果所有 <when> 都不满足，执行 <otherwise>
     *
     * 与 <if> 的区别：
     * - <if>：多个条件可以同时满足（非互斥）
     * - <choose>：只有一个条件生效（互斥，类似 if-else if-else）
     *
     * 本测试通过 @Select 注解的 <script> 方式演示 choose/when/otherwise，
     * 因为 UserMapper.xml 中没有单独的 choose 示例方法。
     *
     * 【面试追问】
     * Q: <choose> 和多个 <if> 有什么区别？
     * A: <if>：每个条件独立判断，多个可以同时满足
     *    <choose>：互斥选择，只有第一个满足的 <when> 生效
     *    场景：根据不同的排序字段排序时，用 <choose> 确保只有一种排序生效
     */
    @Test
    @DisplayName("<choose>/<when>/<otherwise> - 类似 switch 的条件选择")
    void testChooseWhen() {
        log.info("========== 测试：<choose>/<when>/<otherwise> ==========");

        // 测试场景1：按 id 排序（传入 column="id"）
        log.info("--- 场景1：按 id 排序 ---");
        List<User> byId = userMapper.findAllOrderByColumn("id");
        assertThat(byId).isNotEmpty();
        // 验证结果按 id 升序排列
        for (int i = 0; i < byId.size() - 1; i++) {
            assertThat(byId.get(i).getId()).isLessThan(byId.get(i + 1).getId());
        }
        log.info("✅ 按 id 排序正确，第一条: id={}", byId.get(0).getId());

        // 测试场景2：按 username 排序（传入 column="username"）
        log.info("--- 场景2：按 username 排序 ---");
        List<User> byUsername = userMapper.findAllOrderByColumn("username");
        assertThat(byUsername).isNotEmpty();
        log.info("✅ 按 username 排序，第一条: username={}", byUsername.get(0).getUsername());

        // 注意：findAllOrderByColumn 使用 ${column}（字符串替换），
        // 这是 <choose>/<when> 的典型使用场景：根据不同的排序字段选择不同的 ORDER BY
        // 面试考点：${} 用于动态列名，但有 SQL 注入风险，需要白名单校验
        log.info("⚠️ 注意：ORDER BY ${column} 使用了 ${{}}，有 SQL 注入风险，生产环境需要白名单校验");
    }
}
