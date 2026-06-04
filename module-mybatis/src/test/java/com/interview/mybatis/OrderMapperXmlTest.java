package com.interview.mybatis;

import com.interview.mybatis.dto.UserOrderDTO;
import com.interview.mybatis.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【面试考点】XML 方式多表查询测试
 *
 * 前提：需要本地 PostgreSQL 已启动，并执行过 init-sql/init.sql
 *
 * 启动数据库：
 *   podman machine start
 *   podman-compose -f docker-compose-test.yml up -d
 *
 * 测试数据（来自 init.sql）：
 *   users:  zhangsan(id=1), lisi(id=2), wangwu(id=3), zhaoliu(id=4), sunqi(id=5)
 *   orders:
 *     ORD001 → user_id=1 zhangsan  iPhone 15 Pro   8999.00  status=1(已支付)
 *     ORD002 → user_id=2 lisi      MacBook Pro 14 14999.00  status=1(已支付)
 *     ORD003 → user_id=3 wangwu    AirPods Pro     3798.00  status=0(待支付)
 *     ORD004 → user_id=1 zhangsan  iPad Air        4799.00  status=1(已支付)
 *     ORD005 → user_id=4 zhaoliu   Apple Watch     2999.00  status=2(已取消)
 */
@Slf4j
@SpringBootTest
class OrderMapperXmlTest {

    @Autowired
    private OrderMapper orderMapper;

    // =========================================================
    // 测试 1：LEFT JOIN + 动态 WHERE 条件
    // =========================================================

    @Test
    @DisplayName("多表 JOIN - 无条件查询所有用户订单")
    void testFindUserOrders_noCondition() {
        log.info("========== LEFT JOIN 无条件查询 ==========");

        List<UserOrderDTO> results = orderMapper.findUserOrdersByCondition(null, null);

        log.info("查询结果数量: {}", results.size());
        results.forEach(r -> log.info("  用户:{} | 订单:{} | 商品:{} | 金额:{} | 状态:{}",
                r.getUsername(), r.getOrderNo(), r.getProductName(),
                r.getTotalAmount(), r.getOrderStatus()));

        // init.sql 有 5 条订单，LEFT JOIN 后至少 5 行（sunqi 无订单也会出现）
        assertThat(results).isNotEmpty();
        // 验证字段映射正确（resultMap 生效）
        assertThat(results.get(0).getUserId()).isNotNull();
        assertThat(results.get(0).getUsername()).isNotBlank();
    }

    @Test
    @DisplayName("多表 JOIN - 按用户名模糊过滤（<if> 动态条件）")
    void testFindUserOrders_filterByUsername() {
        log.info("========== <if> 动态条件：用户名模糊查询 ==========");

        // 查询用户名包含 "zhang" 的订单
        List<UserOrderDTO> results = orderMapper.findUserOrdersByCondition("zhang", null);

        log.info("用户名含 'zhang' 的订单数: {}", results.size());
        results.forEach(r -> log.info("  用户:{} | 订单:{} | 金额:{}",
                r.getUsername(), r.getOrderNo(), r.getTotalAmount()));

        // zhangsan 有 2 笔订单（ORD001 + ORD004）
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> r.getUsername().contains("zhang"));
    }

    @Test
    @DisplayName("多表 JOIN - 按订单状态过滤（<if> 动态条件）")
    void testFindUserOrders_filterByStatus() {
        log.info("========== <if> 动态条件：订单状态过滤 ==========");

        // 只查已支付订单（status=1）
        List<UserOrderDTO> paidOrders = orderMapper.findUserOrdersByCondition(null, 1);

        log.info("已支付订单数: {}", paidOrders.size());
        paidOrders.forEach(r -> log.info("  用户:{} | 订单:{} | 金额:{}",
                r.getUsername(), r.getOrderNo(), r.getTotalAmount()));

        // init.sql 中 status=1 的有 3 条（ORD001, ORD002, ORD004）
        assertThat(paidOrders).hasSize(3);
        assertThat(paidOrders).allMatch(r -> r.getOrderStatus() == 1);
    }

    @Test
    @DisplayName("多表 JOIN - 用户名 + 状态同时过滤（两个 <if> 都生效）")
    void testFindUserOrders_filterByBothConditions() {
        log.info("========== 两个 <if> 同时生效 ==========");

        // zhangsan 的已支付订单
        List<UserOrderDTO> results = orderMapper.findUserOrdersByCondition("zhang", 1);

        log.info("zhangsan 已支付订单数: {}", results.size());
        results.forEach(r -> log.info("  订单:{} | 商品:{} | 金额:{}",
                r.getOrderNo(), r.getProductName(), r.getTotalAmount()));

        // zhangsan 有 2 笔已支付订单
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r ->
                r.getUsername().equals("zhangsan") && r.getOrderStatus() == 1);
    }

    // =========================================================
    // 测试 2：GROUP BY + HAVING 聚合统计
    // =========================================================

    @Test
    @DisplayName("聚合统计 - 查询高消费用户（GROUP BY + HAVING）")
    void testFindHighValueUsers() {
        log.info("========== GROUP BY + HAVING 聚合统计 ==========");

        // 查询消费总额 >= 5000 的用户
        List<UserOrderDTO> highValueUsers = orderMapper.findHighValueUsers(new BigDecimal("5000"));

        log.info("消费 >= 5000 的用户数: {}", highValueUsers.size());
        highValueUsers.forEach(r -> log.info("  用户:{} | 订单数:{} | 消费总额:{}",
                r.getUsername(), r.getTotalOrders(), r.getTotalSpent()));

        // zhangsan: 8999+4799=13798, lisi: 14999 → 两人都 >= 5000
        assertThat(highValueUsers).isNotEmpty();
        assertThat(highValueUsers).allMatch(r ->
                r.getTotalSpent().compareTo(new BigDecimal("5000")) >= 0);

        // 验证聚合字段不为 null
        assertThat(highValueUsers).allMatch(r -> r.getTotalOrders() != null && r.getTotalOrders() > 0);
    }

    @Test
    @DisplayName("聚合统计 - 阈值很高时返回空列表")
    void testFindHighValueUsers_noResult() {
        log.info("========== HAVING 过滤后无结果 ==========");

        // 阈值设为 100万，没有用户能达到
        List<UserOrderDTO> results = orderMapper.findHighValueUsers(new BigDecimal("1000000"));

        log.info("消费 >= 100万 的用户数: {}", results.size());
        assertThat(results).isEmpty();
    }

    // =========================================================
    // 测试 3：<foreach> 批量查询 + 窗口函数
    // =========================================================

    @Test
    @DisplayName("<foreach> 批量查询 - 多用户最新订单（ROW_NUMBER 窗口函数）")
    void testFindLatestOrdersByUserIds() {
        log.info("========== <foreach> IN 子句 + ROW_NUMBER() 窗口函数 ==========");

        // 查询 zhangsan(1) 和 lisi(2) 的最新订单
        List<Long> userIds = List.of(1L, 2L);
        List<UserOrderDTO> results = orderMapper.findLatestOrdersByUserIds(userIds);

        log.info("查询用户 {} 的最新订单，结果数: {}", userIds, results.size());
        results.forEach(r -> log.info("  用户:{} | 最新订单:{} | 商品:{} | 下单时间:{}",
                r.getUsername(), r.getOrderNo(), r.getProductName(), r.getOrderCreateTime()));

        // 每个用户只返回 1 条最新订单，共 2 条
        assertThat(results).hasSize(2);

        // zhangsan 有 ORD001 和 ORD004，最新的应该是 ORD004（create_time 更晚）
        UserOrderDTO zhangsanLatest = results.stream()
                .filter(r -> "zhangsan".equals(r.getUsername()))
                .findFirst()
                .orElseThrow();
        log.info("zhangsan 最新订单: {} (应为 ORD202401010004)", zhangsanLatest.getOrderNo());
        assertThat(zhangsanLatest.getOrderNo()).isEqualTo("ORD202401010004");
    }

    @Test
    @DisplayName("<foreach> 批量查询 - 单用户")
    void testFindLatestOrdersByUserIds_singleUser() {
        log.info("========== <foreach> 单元素 IN 子句 ==========");

        List<UserOrderDTO> results = orderMapper.findLatestOrdersByUserIds(List.of(2L));

        log.info("lisi 的最新订单: {}", results);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUsername()).isEqualTo("lisi");
        assertThat(results.get(0).getOrderNo()).isEqualTo("ORD202401010002");
    }
}
