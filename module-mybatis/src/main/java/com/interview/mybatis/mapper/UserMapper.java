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
            "<bind name='pattern' value=\"'%' + name + '%'\"/> " +
            "WHERE username LIKE #{pattern} " +
            "</script>")
    List<User> findByUsernameLikeBind(@Param("name") String name);

    // ============================================================
    // 以下方法对应 UserMapper.xml 中的动态 SQL（P1 新增）
    // ============================================================

    /**
     * 【面试考点】动态条件查询 - <if> + <where>
     *
     * 根据 name（模糊）、email（精确）、status 动态组合查询条件。
     * SQL 写在 UserMapper.xml 中，id="selectByCondition"。
     *
     * @param name   用户名关键字（模糊匹配，可为 null）
     * @param email  邮箱（精确匹配，可为 null）
     * @param status 状态（可为 null，表示不过滤）
     * @return 符合条件的用户列表
     */
    List<User> selectByCondition(
            @Param("name") String name,
            @Param("email") String email,
            @Param("status") Integer status
    );

    /**
     * 【面试考点】<foreach> IN 查询
     *
     * 根据多个 ID 批量查询用户。
     * SQL 写在 UserMapper.xml 中，id="selectByIds"。
     *
     * @param ids 用户 ID 列表
     * @return 用户列表
     */
    List<User> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 【面试考点】一对多关联查询 - <resultMap> + <collection>
     *
     * 查询指定用户及其所有订单（LEFT JOIN orders）。
     * SQL 写在 UserMapper.xml 中，id="selectUserWithOrders"。
     *
     * @param userId 用户 ID（可为 null，表示查所有用户）
     * @return 用户列表（每个 User 对象包含 orders 集合）
     */
    List<User> selectUserWithOrders(@Param("userId") Long userId);

    /**
     * 【面试考点】批量插入 - <foreach> 拼接 VALUES
     *
     * 一条 SQL 插入多行数据，性能远优于 for 循环单条插入。
     * SQL 写在 UserMapper.xml 中，id="batchInsert"。
     *
     * @param users 要插入的用户列表
     * @return 影响行数
     */
    int batchInsert(@Param("users") List<User> users);

    /**
     * 【面试考点】动态更新 - <set> + <if>
     *
     * 只更新非 null 字段，避免覆盖其他字段为 null。
     * SQL 写在 UserMapper.xml 中，id="updateByCondition"。
     *
     * @param user 包含要更新字段的用户对象（id 必须不为 null）
     * @return 影响行数
     */
    int updateByCondition(User user);
}