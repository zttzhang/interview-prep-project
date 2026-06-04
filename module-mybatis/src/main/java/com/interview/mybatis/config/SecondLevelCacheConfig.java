package com.interview.mybatis.config;

import org.springframework.context.annotation.Configuration;

/**
 * 【面试考点】MyBatis 二级缓存（L2 Cache）演示与说明
 *
 * ⚠️ 注意：这是一个演示/教学类，不是真正启用二级缓存的配置。
 * 实际项目中二级缓存通常用 Redis 替代（见下方对比）。
 *
 * ============================================================
 * 一、什么是二级缓存？
 * ============================================================
 * MyBatis 有两级缓存：
 *   一级缓存（L1）：SqlSession 级别，同一个 SqlSession 内自动生效，无需配置
 *   二级缓存（L2）：Mapper（namespace）级别，跨 SqlSession 共享
 *
 * 二级缓存的生命周期：
 *   - 作用域：同一个 Mapper 接口（namespace）的所有 SqlSession 共享
 *   - 存储位置：默认存在 JVM 堆内存（PerpetualCache），可替换为 Redis/Ehcache
 *   - 数据写入时机：SqlSession.close() 或 SqlSession.commit() 时，L1 数据写入 L2
 *
 * ============================================================
 * 二、如何启用二级缓存？（三步走）
 * ============================================================
 *
 * 步骤 1：全局开关（application.yml）
 * <pre>
 * mybatis-plus:
 *   configuration:
 *     cache-enabled: true   # 默认就是 true，通常不需要显式配置
 * </pre>
 *
 * 步骤 2：在 Mapper XML 中声明 <cache> 标签
 * <pre>
 * &lt;mapper namespace="com.interview.mybatis.mapper.UserMapper"&gt;
 *     &lt;!-- 开启二级缓存，使用默认的 PerpetualCache --&gt;
 *     &lt;cache/&gt;
 *
 *     &lt;!-- 或者自定义配置 --&gt;
 *     &lt;cache
 *         eviction="LRU"          &lt;!-- 淘汰策略：LRU/FIFO/SOFT/WEAK --&gt;
 *         flushInterval="60000"   &lt;!-- 刷新间隔：60秒 --&gt;
 *         size="512"              &lt;!-- 最多缓存 512 个对象 --&gt;
 *         readOnly="true"/&gt;      &lt;!-- 只读模式：true=返回同一对象引用（快），false=返回副本（安全）--&gt;
 * &lt;/mapper&gt;
 * </pre>
 *
 * 步骤 3：实体类实现 Serializable 接口
 * <pre>
 * // ❌ 错误：没有实现 Serializable
 * public class User { ... }
 *
 * // ✅ 正确：实现 Serializable，二级缓存需要序列化对象
 * public class User implements Serializable {
 *     private static final long serialVersionUID = 1L;
 *     ...
 * }
 * </pre>
 *
 * ============================================================
 * 三、二级缓存的致命坑 ⚠️
 * ============================================================
 *
 * 坑1：任意写操作会清空整个 namespace 的缓存
 * <pre>
 * // 场景：UserMapper 开启了二级缓存
 * userMapper.selectById(1L);   // 查询，结果放入 L2 缓存
 * userMapper.selectById(1L);   // 命中 L2 缓存，不发 SQL ✅
 *
 * userMapper.updateById(user); // ⚠️ 执行 UPDATE → 清空 UserMapper 整个 namespace 的缓存！
 *
 * userMapper.selectById(1L);   // 缓存已清空，重新发 SQL ❌（缓存失效）
 * userMapper.selectById(2L);   // 缓存已清空，重新发 SQL ❌（连 id=2 的缓存也没了！）
 * </pre>
 *
 * 坑2：多表关联查询的脏读问题（最严重的坑！）
 * <pre>
 * // 场景：OrderMapper 查询 orders JOIN users，OrderMapper 开启了二级缓存
 * // 但 UserMapper 没有开启，或者是不同的 namespace
 *
 * // 第一步：通过 OrderMapper 查询（结果缓存在 OrderMapper 的 namespace）
 * orderMapper.findUserOrders(1L);  // 返回：zhangsan 的订单，金额 8999
 *
 * // 第二步：通过 UserMapper 更新用户名（清空 UserMapper 的缓存，但不影响 OrderMapper 的缓存！）
 * userMapper.updateById(user);  // 把 zhangsan 改成 zhangsan_new
 *
 * // 第三步：再次通过 OrderMapper 查询 → 命中 OrderMapper 的 L2 缓存
 * orderMapper.findUserOrders(1L);  // ⚠️ 脏读！返回的还是旧数据：zhangsan（已被改为 zhangsan_new）
 * </pre>
 *
 * 坑3：分布式环境下的缓存不一致
 * <pre>
 * // 默认的 PerpetualCache 存在 JVM 内存中
 * // 多个应用实例各自有独立的缓存，无法共享 → 数据不一致
 * // 解决：使用 Redis 作为二级缓存存储（见下方对比）
 * </pre>
 *
 * ============================================================
 * 四、二级缓存 vs Redis 缓存对比
 * ============================================================
 *
 * | 维度         | MyBatis 二级缓存              | Redis 缓存（@Cacheable）        |
 * |------------|-----------------------------|-----------------------------|
 * | 存储位置      | JVM 堆内存（默认）              | 独立 Redis 服务器               |
 * | 分布式支持    | ❌ 不支持（各实例独立）           | ✅ 支持（所有实例共享）             |
 * | 缓存粒度      | Mapper namespace 级别        | 方法/Key 级别（更细粒度）          |
 * | 失效控制      | 写操作自动清空整个 namespace     | 可精确控制 Key 的失效             |
 * | 脏读风险      | ⚠️ 多表关联时有脏读风险          | 需要手动维护一致性                |
 * | 序列化要求    | 实体类必须实现 Serializable     | 需要配置序列化器（如 Jackson）     |
 * | 适用场景      | 读多写少的单机字典表              | 通用缓存，推荐生产使用              |
 * | 配置复杂度    | 简单（XML 加一行 <cache/>）     | 需要配置 RedisTemplate/CacheManager |
 *
 * ============================================================
 * 五、什么时候用二级缓存？什么时候不用？
 * ============================================================
 *
 * ✅ 适合使用二级缓存的场景：
 *   1. 读多写少的字典表（如：省市区、商品分类、系统配置）
 *   2. 数据变化频率极低（如：一天只更新一次的汇率表）
 *   3. 单机部署，不需要分布式一致性
 *   4. 对数据实时性要求不高（允许短暂的缓存延迟）
 *
 * ❌ 不适合使用二级缓存的场景：
 *   1. 高并发写场景（频繁 INSERT/UPDATE/DELETE 会不断清空缓存，缓存命中率极低）
 *   2. 多表关联查询（脏读风险）
 *   3. 分布式部署（各实例缓存不一致）
 *   4. 对数据实时性要求高的业务（如：库存、余额）
 *   5. 大数据量查询（缓存占用大量 JVM 内存，可能导致 GC 压力）
 *
 * ============================================================
 * 六、面试追问：二级缓存的脏读问题如何解决？
 * ============================================================
 *
 * 方案1：使用 <cache-ref> 让多个 Mapper 共享同一个缓存 namespace
 * <pre>
 * &lt;!-- OrderMapper.xml --&gt;
 * &lt;cache-ref namespace="com.interview.mybatis.mapper.UserMapper"/&gt;
 * &lt;!-- 这样 UserMapper 的写操作也会清空 OrderMapper 的缓存 --&gt;
 * </pre>
 *
 * 方案2（推荐）：放弃 MyBatis 二级缓存，改用 Spring Cache + Redis
 * <pre>
 * {@literal @}Cacheable(value = "user", key = "#id")
 * public User getUserById(Long id) { ... }
 *
 * {@literal @}CacheEvict(value = "user", key = "#user.id")
 * public void updateUser(User user) { ... }
 * </pre>
 *
 * 方案3：在 select 语句上设置 useCache="false" 禁用特定查询的缓存
 * <pre>
 * &lt;select id="findUserOrders" useCache="false"&gt;...&lt;/select&gt;
 * </pre>
 */
@Configuration
public class SecondLevelCacheConfig {

    /*
     * 这个类故意不定义任何 @Bean 方法。
     *
     * 原因：MyBatis 二级缓存通过 XML 的 <cache> 标签启用，
     * 不需要在 Java Config 中注册 Bean。
     *
     * 本类的价值在于：通过详细注释，帮助面试者理解二级缓存的
     * 配置方式、工作原理、常见坑和最佳实践。
     *
     * 如果要在代码中配置二级缓存（不用 XML），可以这样做：
     *
     * @Bean
     * public ConfigurationCustomizer mybatisCacheCustomizer() {
     *     return configuration -> {
     *         // 全局开启二级缓存（默认已开启）
     *         configuration.setCacheEnabled(true);
     *
     *         // 注意：仅开启全局开关还不够，
     *         // 还需要在每个 Mapper XML 中添加 <cache/> 标签，
     *         // 或者在 Mapper 接口上添加 @CacheNamespace 注解
     *     };
     * }
     *
     * 使用 @CacheNamespace 注解的方式（替代 XML 的 <cache> 标签）：
     *
     * @CacheNamespace(
     *     eviction = LruCache.class,    // LRU 淘汰策略
     *     flushInterval = 60000,        // 60秒刷新
     *     size = 512,                   // 最多512个对象
     *     readWrite = true              // readWrite=true 等同于 readOnly=false，返回副本
     * )
     * public interface UserMapper extends BaseMapper<User> { ... }
     */
}
