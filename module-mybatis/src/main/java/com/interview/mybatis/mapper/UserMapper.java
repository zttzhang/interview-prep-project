package com.interview.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interview.mybatis.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 【面试考点】MyBatis Mapper 示例
 * 
 * 本接口演示：
 * 1. #{} 预编译参数绑定（防注入）
 * 2. ${} 字符串直接替换（动态表名/列名）
 * 3. LIKE 查询的正确写法
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 【面试考点】#{} 预编译查询
     * 
     * SQL: SELECT * FROM users WHERE username = ?
     * 参数通过 PreparedStatement.setString() 绑定，安全防注入
     */
    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);

    /**
     * 【面试考点】${} 字符串替换（危险演示）
     * 
     * SQL: SELECT * FROM users WHERE username = 'zhangsan'
     * 直接替换，不经过预编译，有 SQL 注入风险！
     * 
     * 【面试追问】为什么这里用 ${} 而不是 #{}？
     * → 答：这是演示注入风险，实际开发中应该避免使用
     */
    @Select("SELECT * FROM users WHERE username = '${username}'")
    User findByUsernameDirect(@Param("username") String username);

    /**
     * 【面试考点】${} 合法场景 - ORDER BY 动态列名
     * 
     * 如果使用 #{}：ORDER BY 'id'（引号里的id是字符串，不是列名，语法错误）
     * 必须使用 ${}：ORDER BY id（正确的列名引用）
     * 
     * 【面试追问】如何防护 ORDER BY 注入？
     * → 答：白名单校验，只允许预定义的列名
     */
    @Select("<script>" +
            "SELECT * FROM users " +
            "ORDER BY ${column} " +
            "</script>")
    List<User> findAllOrderByColumn(@Param("column") String column);

    /**
     * 【面试考点】LIKE 查询正确写法 - 使用 CONCAT
     */
    @Select("SELECT * FROM users WHERE username LIKE CONCAT('%', #{name}, '%')")
    List<User> findByUsernameLikeConcat(@Param("name") String name);

    /**
     * 【面试考点】LIKE 查询正确写法 - 使用 @Bind 注解
     * 
     * MyBatis 3.5+ 支持 @Bind 注解，在 SQL 中定义变量
     */
    @Select("<script>" +
            "SELECT * FROM users " +
            "<bind name='pattern' value=\"'%' + #{name} + '%'\"/> " +
            "WHERE username LIKE #{pattern} " +
            "</script>")
    List<User> findByUsernameLikeBind(@Param("name") String name);
}