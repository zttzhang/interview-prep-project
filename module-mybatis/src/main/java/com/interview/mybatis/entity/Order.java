package com.interview.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体类
 *
 * 【面试考点】MyBatis-Plus 注解说明
 *
 * @TableName("orders")
 *   → 指定数据库表名。当类名与表名不一致时必须使用。
 *   → 如果类名是 Order，默认映射表名是 order（小写），但 order 是 SQL 关键字，
 *     所以数据库表名用 orders，必须显式指定。
 *
 * @TableId(type = IdType.AUTO)
 *   → 标记主键字段，type 指定主键生成策略：
 *   → IdType.AUTO：数据库自增（依赖数据库的 AUTO_INCREMENT / SERIAL）
 *   → IdType.ASSIGN_ID：雪花算法生成 Long 类型 ID（分布式场景推荐）
 *   → IdType.ASSIGN_UUID：UUID 字符串（全局唯一，但较长，不适合做索引）
 *   → IdType.INPUT：手动赋值（需要在插入前自己设置 ID）
 *
 * @TableField("column_name")
 *   → 指定字段对应的数据库列名。
 *   → 当开启 mapUnderscoreToCamelCase=true 时，userId 会自动映射 user_id，
 *     此时 @TableField 可以省略（但显式写出更清晰）。
 *   → 特殊用途：@TableField(exist = false) 标记该字段不对应数据库列（如计算字段）。
 *
 * 【面试追问】
 * Q: 为什么实现 Serializable？
 * A: 1. 如果开启 MyBatis 二级缓存，实体类必须实现 Serializable（缓存需要序列化）
 *    2. 分布式场景下对象需要在网络传输（如 RPC 调用）
 *    3. 存入 Redis 时需要序列化
 *
 * Q: @Builder 和 @AllArgsConstructor 同时使用有什么注意事项？
 * A: @Builder 会生成全参构造器，与 @AllArgsConstructor 冲突。
 *    必须同时加 @NoArgsConstructor，否则 MyBatis 反射创建对象时会失败
 *    （MyBatis 需要无参构造器来实例化结果对象）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("orders")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     * IdType.AUTO：依赖数据库自增（PostgreSQL 使用 SERIAL / BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联用户 ID
     * 对应数据库列：user_id
     * 开启驼峰映射后可省略 @TableField，但显式写出更清晰
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 商品 ID
     * 对应数据库列：product_id
     */
    @TableField("product_id")
    private Long productId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 订单金额
     * BigDecimal：精确小数，用于金融计算，避免 float/double 的精度问题
     *
     * 【面试考点】为什么金额用 BigDecimal 而不是 double？
     * double 存在精度问题：0.1 + 0.2 = 0.30000000000000004
     * BigDecimal 基于十进制运算，精确表示小数
     */
    private BigDecimal amount;

    /**
     * 订单状态
     * 0-待支付 1-已支付 2-已取消 3-已退款
     *
     * 【面试考点】为什么用 Integer 而不是枚举？
     * 数据库存储整数，枚举需要额外的 TypeHandler 转换。
     * 实际项目中推荐定义枚举 + 自定义 TypeHandler，类型更安全。
     */
    private Integer status;

    /**
     * 创建时间
     * 对应数据库列：created_at
     *
     * 【面试考点】LocalDateTime vs Date
     * LocalDateTime：Java 8+，线程安全，无时区信息，推荐使用
     * Date：旧 API，非线程安全，有时区问题，不推荐
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 对应数据库列：updated_at
     *
     * 【面试考点】自动填充
     * 可以配合 @TableField(fill = FieldFill.INSERT_UPDATE) + MetaObjectHandler
     * 实现创建时间和更新时间的自动填充，无需在业务代码中手动设置。
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
