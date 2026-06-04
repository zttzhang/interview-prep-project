package com.interview.mybatis.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 【面试考点】多表查询结果映射 DTO
 *
 * 用于接收 users JOIN orders 的联合查询结果。
 * MyBatis 的 <resultMap> 会将多列结果映射到这个对象的嵌套结构中。
 *
 * 注意：不要用实体类（User/Order）直接接收多表结果，
 * 应该专门定义 DTO，职责清晰，避免污染领域模型。
 */
@Data
public class UserOrderDTO {

    // ---- 来自 users 表 ----
    private Long userId;
    private String username;
    private String email;

    // ---- 来自 orders 表 ----
    private Long orderId;
    private String orderNo;
    private String productName;
    private Integer productCount;
    private BigDecimal totalAmount;

    /**
     * 订单状态（来自 orders.status）
     * 0-待支付 1-已支付 2-已取消 3-已退款
     */
    private Integer orderStatus;
    private LocalDateTime orderCreateTime;

    // ---- 聚合统计字段（来自 GROUP BY 查询）----
    /** 该用户的订单总数 */
    private Integer totalOrders;
    /** 该用户的消费总金额 */
    private BigDecimal totalSpent;
}
