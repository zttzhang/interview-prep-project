package com.interview.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;

/**
 * 【面试考点】MyBatis-Plus 全局配置类
 *
 * 问题描述：如何通过 Java 代码配置 MyBatis-Plus 的分页插件、日志、懒加载等全局行为？
 * 解决思路：使用 @Configuration + @MapperScan 注册配置，通过 MybatisPlusInterceptor 添加插件链。
 *
 * 【对比方案】
 * ❌ 方案一（纯 YAML 配置）：mybatis-plus.configuration.xxx=yyy
 *    缺点：无法注册插件（分页、乐观锁等），只能配置基础属性
 * ✅ 方案二（Java Config）：@Configuration 类 + @Bean 方法
 *    优点：类型安全、可注册插件、IDE 自动补全、便于单元测试
 *
 * 【面试追问】
 * Q: @MapperScan 和 @Mapper 有什么区别？
 * A: @Mapper 需要在每个接口上标注；@MapperScan 在配置类上扫描整个包，
 *    推荐用 @MapperScan，减少重复注解，统一管理。
 *
 * Q: MybatisPlusInterceptor 的拦截原理是什么？
 * A: 实现了 MyBatis 的 Interceptor 接口，拦截 StatementHandler.prepare() 方法，
 *    在 SQL 执行前动态改写 SQL（如分页插件在 SELECT 外包一层 LIMIT/OFFSET）。
 */
@org.springframework.context.annotation.Configuration
@MapperScan("com.interview.mybatis.mapper")
public class MyBatisConfig {

    /**
     * 【面试考点】MyBatis-Plus 插件拦截器
     *
     * 问题描述：如何实现物理分页（真正的 LIMIT/OFFSET），而不是内存分页？
     * 解决思路：注册 PaginationInnerInterceptor，它会在 SQL 执行前自动改写 SQL。
     *
     * 【对比方案】
     * ❌ 内存分页：查出所有数据再截取 → 数据量大时 OOM，性能极差
     * ✅ 物理分页：PaginationInnerInterceptor 改写 SQL 加 LIMIT → 数据库层面分页
     *
     * 【面试追问】
     * Q: 分页插件的原理？
     * A: 拦截 StatementHandler.prepare()，解析原始 SQL，
     *    先执行 COUNT(*) 查总数，再在原 SQL 末尾追加 LIMIT #{size} OFFSET #{offset}。
     *    PostgreSQL 方言：LIMIT ? OFFSET ?
     *    MySQL 方言：LIMIT ?, ?
     *
     * Q: 为什么要指定 DbType？
     * A: 不同数据库分页语法不同（MySQL: LIMIT x,y；PostgreSQL: LIMIT x OFFSET y；
     *    Oracle: ROWNUM），指定 DbType 让插件生成正确的方言 SQL。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ========== 方案对比 ==========
        // ❌ 方案一（不指定 DbType）：new PaginationInnerInterceptor()
        //    MyBatis-Plus 会自动检测，但在某些场景下检测失败，生成错误 SQL
        // ✅ 方案二（明确指定 DbType）：new PaginationInnerInterceptor(DbType.POSTGRE_SQL)
        //    明确告知数据库类型，生成正确的 LIMIT ? OFFSET ? 语法
        // ==============================
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        return interceptor;
    }

    /**
     * 【面试考点】MyBatis 全局配置 - 懒加载与驼峰映射
     *
     * 问题描述：如何配置 MyBatis 的懒加载（延迟加载）？懒加载的原理是什么？
     * 解决思路：通过 Configuration 对象设置 lazyLoadingEnabled=true，
     *           MyBatis 使用 CGLIB/Javassist 动态代理实现懒加载。
     *
     * 【懒加载原理】
     * 1. MyBatis 返回的对象实际上是 CGLIB 代理对象（不是原始 POJO）
     * 2. 当访问关联属性（如 user.getOrders()）时，代理拦截 getter 调用
     * 3. 代理检测到该属性未加载，触发额外的 SELECT 查询
     * 4. 查询结果填充到属性后，返回给调用方
     *
     * 【N+1 问题】
     * 场景：查询 100 个用户，每个用户懒加载订单 → 1次查用户 + 100次查订单 = 101次SQL
     * 解决：使用 JOIN 一次性查出（fetchType=EAGER）或 BatchLoader
     *
     * 【面试追问】
     * Q: aggressiveLazyLoading=false 是什么意思？
     * A: true（激进懒加载）：访问对象的任意属性都会触发所有懒加载属性的加载
     *    false（按需懒加载）：只有访问到具体的懒加载属性时才触发该属性的加载
     *    推荐设为 false，避免不必要的查询。
     *
     * Q: 懒加载在什么情况下会失效？
     * A: 1. SqlSession 已关闭（常见于 Service 层返回对象后 Session 关闭）
     *    2. 序列化时（JSON 序列化会触发所有 getter，导致 N+1）
     *    3. 使用 @Transactional 时 Session 在事务结束后关闭
     */
    @Bean
    public Configuration mybatisConfiguration() {
        Configuration configuration = new Configuration();

        // 日志实现：使用 SLF4J，与 Spring Boot 的 Logback 集成
        // 面试考点：可以通过日志观察 MyBatis 是否发出了 SQL，验证缓存是否命中
        configuration.setLogImpl(Slf4jImpl.class);

        // 驼峰映射：数据库 user_name → Java userName（自动转换，无需 @TableField）
        // 面试考点：如果不开启，需要在 resultMap 中手动映射每个字段
        configuration.setMapUnderscoreToCamelCase(true);

        // 懒加载开关：true=开启延迟加载（需要 CGLIB 代理）
        // 面试考点：懒加载可以减少不必要的 JOIN 查询，但要注意 N+1 问题
        configuration.setLazyLoadingEnabled(true);

        // 激进懒加载：false=按需加载（推荐），true=访问任意属性触发全部加载
        // 面试考点：设为 false 才能真正实现"按需"懒加载
        configuration.setAggressiveLazyLoading(false);

        return configuration;
    }
}
