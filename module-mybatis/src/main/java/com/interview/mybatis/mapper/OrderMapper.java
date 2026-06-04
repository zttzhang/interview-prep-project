package com.interview.mybatis.mapper;

import com.interview.mybatis.dto.UserOrderDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 【面试考点】XML 方式的 Mapper 接口
 *
 * 本接口演示：
 * 1. 多表 JOIN 查询（users + orders）
 * 2. 动态 SQL（<if> / <where> / <foreach>）
 * 3. <resultMap> 结果映射（列名 → 字段名）
 * 4. 聚合统计查询（GROUP BY + HAVING）
 *
 * 注意：这里只声明方法签名，SQL 全部写在
 * resources/mapper/OrderMapper.xml 中，不混在 Java 代码里。
 */
@Mapper
public interface OrderMapper {

    /**
     * 【面试考点】多表 JOIN + 动态条件
     *
     * 根据用户名（模糊）和订单状态（可选）查询用户订单列表。
     * SQL 写在 XML 中，使用 <if> 动态拼接 WHERE 条件。
     *
     * @param username    用户名关键字（模糊匹配，可为 null）
     * @param orderStatus 订单状态（可为 null，表示不过滤）
     * @return 用户订单 DTO 列表
     */
    List<UserOrderDTO> findUserOrdersByCondition(
            @Param("username") String username,
            @Param("orderStatus") Integer orderStatus
    );

    /**
     * 【面试考点】聚合统计 + GROUP BY + HAVING
     *
     * 查询消费金额超过指定阈值的用户统计信息。
     * 演示 GROUP BY、HAVING、聚合函数在 XML 中的写法。
     *
     * @param minTotalSpent 最低消费金额阈值
     * @return 用户消费统计列表（含订单总数、消费总额）
     */
    List<UserOrderDTO> findHighValueUsers(@Param("minTotalSpent") java.math.BigDecimal minTotalSpent);

    /**
     * 【面试考点】<foreach> 批量查询
     *
     * 根据多个用户 ID 批量查询其最新订单。
     * 演示 <foreach> 生成 IN 子句的写法。
     *
     * @param userIds 用户 ID 列表
     * @return 用户订单 DTO 列表
     */
    List<UserOrderDTO> findLatestOrdersByUserIds(@Param("userIds") List<Long> userIds);
}
