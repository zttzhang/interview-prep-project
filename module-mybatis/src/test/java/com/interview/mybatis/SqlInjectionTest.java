package com.interview.mybatis;

import com.interview.mybatis.entity.User;
import com.interview.mybatis.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】#{} vs ${} 区别演示
 * 
 * 测试目标：
 * 1. #{} 的预编译效果（打印SQL看到 ? 占位符）
 * 2. ${} 的注入漏洞（传入 "1 OR 1=1" 能查出所有数据）
 * 3. ${} 合法使用场景（ORDER BY 动态列名）
 * 
 * 【面试速记】
 * #{} → PreparedStatement 参数绑定，防注入，值会加引号
 * ${} → 字符串直接替换，不防注入，但可用于动态表名/列名
 */
@Slf4j
@SpringBootTest
class SqlInjectionTest {

    @Autowired
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        // 清理测试数据
    }

    /**
     * 【面试考点】#{} 预编译演示
     * 
     * 原理：MyBatis 使用 PreparedStatement 的 ? 占位符
     * SQL: SELECT * FROM users WHERE username = ?
     * 参数通过 setString() 绑定，安全防注入
     */
    @Test
    @DisplayName("#{} 使用 - 预编译参数绑定")
    void testSharpBraces() {
        log.info("========== #{} 预编译测试 ==========");
        
        // 正常查询
        User user = userMapper.findByUsername("#{test}");
        log.info("查询 '{}' 的结果: {}", "#{test}", user);
        
        // 演示注入攻击无效
        // 传入 "1 OR 1=1"，不会被执行，只会当作普通字符串查询
        User user2 = userMapper.findByUsername("1 OR 1=1");
        log.info("注入查询 '1 OR 1=1' 的结果: {}", user2);
        assertThat(user2).isNull(); // 不会查出所有数据，证明防注入成功
        
        log.info("【结论】#{} 将输入当作字面值，不会被解析为SQL");
    }

    /**
     * 【面试考点】${} 字符串替换演示（危险操作）
     * 
     * 原理：直接字符串替换，不经过 PreparedStatement
     * SQL: SELECT * FROM users WHERE username = 1 OR 1=1
     * 危险！可以被注入
     * 
     * 【面试追问】为什么不用 #{} 替代所有 ${}？
     * → 答：动态表名/列名场景下，#{} 会加引号变成字符串 'column'，语法错误
     */
    @Test
    @DisplayName("${} 使用 - 字符串直接替换（危险）")
    void testDollarBraces() {
        log.info("========== ${} 字符串替换测试 ==========");
        
        // 正常查询
        User user = userMapper.findByUsernameDirect("zhangsan");
        log.info("正常查询 'zhangsan': {}", user);
        
        // 【危险演示】注入攻击
        // 如果用户输入: "1 OR 1=1"，整个SQL变成:
        // SELECT * FROM users WHERE 1=1 OR 1=1
        // 会查出所有数据！
        User injectedUser = userMapper.findByUsernameDirect("1 OR 1=1");
        log.warn("【危险】注入查询 '1 OR 1=1' 的结果: {}", injectedUser);
        log.warn("【注意】${} 不防注入，查出了所有用户数据！");
        
        // 断言：应该能查到数据（证明注入成功，但实际开发中绝不能这样用）
        assertThat(injectedUser).isNotNull();
    }

    /**
     * 【面试考点】${} 合法场景 - 动态 ORDER BY 列名
     * 
     * 场景：需要根据不同列排序，但列名不能加引号
     * 
     * 如果用 #{}：
     * SELECT * FROM users ORDER BY 'id'  -- 错误！引号里的是字符串，不是列名
     * 
     * 必须用 ${}：
     * SELECT * FROM users ORDER BY id    -- 正确
     * 
     * 【面试追问】这种情况如何防护？
     * → 答：白名单校验，只允许预定义的列名
     */
    @Test
    @DisplayName("${} 合法场景 - ORDER BY 动态列名")
    void testDollarBracesInOrderBy() {
        log.info("========== ${} 合法场景测试 - ORDER BY ==========");
        
        // 允许的列名白名单
        String[] allowedColumns = {"id", "username", "create_time"};
        
        for (String column : allowedColumns) {
            log.info("按 {} 排序:", column);
            var users = userMapper.findAllOrderByColumn(column);
            log.info("结果数量: {}", users.size());
            users.forEach(u -> log.info("  {}", u.getUsername()));
        }
        
        // 测试非法列名（模拟攻击）
        try {
            log.info("尝试非法列名 '1; DROP TABLE users;--':");
            userMapper.findAllOrderByColumn("1; DROP TABLE users;--");
        } catch (Exception e) {
            log.error("异常: {}", e.getMessage());
        }
        
        log.info("【结论】ORDER BY 等动态列名场景必须用 ${}，但要做白名单校验");
    }

    /**
     * 【面试考点】LIKE 查询中的 #{} vs ${}
     * 
     * 问题：使用 #{} 进行 LIKE 查询时，'%#{value}%' 不会按预期工作
     * 因为 #{} 会被当作字符串处理
     * 
     * 正确做法：
     * 1. 使用 CONCAT 拼接：'%' + #{value} + '%'
     * 2. 使用 MySQL 的 LPAD/RPAD
     * 3. 使用 @Bind 注解（MyBatis 3.5+）
     */
    @Test
    @DisplayName("#{} 在 LIKE 查询中的正确用法")
    void testLikeWithSharpBraces() {
        log.info("========== LIKE 查询测试 ==========");
        
        // 错误写法：'%#{name}%' → 参数不会被展开
        // User user = userMapper.findByUsernameLikeWrong("zhang");
        
        // 正确写法1：使用 CONCAT
        var users1 = userMapper.findByUsernameLikeConcat("zhang");
        log.info("CONCAT 方式结果: {}", users1.size());
        
        // 正确写法2：使用 @Bind（推荐）
        var users2 = userMapper.findByUsernameLikeBind("zhang");
        log.info("@Bind 方式结果: {}", users2.size());
    }
}