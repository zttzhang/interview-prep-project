# 任务：生成 Spring Boot 面试教学工程（interview-prep-project）

## :dart: 工程目标

这是一个专门为技术面试准备的教学工程，每一行代码都要服务于"能在面试中讲清楚"这个目标。
不追求业务完整性，追求：概念清晰、对比鲜明、场景真实。

---

## :package: 技术栈版本（严格遵守）

```xml
Java 17
Spring Boot 3.2.x
PostgreSQL 15
Redis 7.x
Kafka 3.6.x
MyBatis-Plus 3.5.x
Redisson 3.25.x
Testcontainers（集成测试用）
Lombok
MapStruct
```

---

## :building_construction: 工程结构（完整）

```
interview-prep-project/
├── pom.xml                              ← 父 pom，统一管理依赖版本
├── docker-compose.yml                   ← 一键启动所有中间件
├── docker-compose-test.yml             ← 测试专用轻量环境
├── README.md                           ← 工程说明 + 启动步骤
│
├── common/                             ← 公共模块
│   └── src/main/java/com/interview/common/
│       ├── result/Result.java          ← 统一返回结构
│       ├── exception/BizException.java ← 业务异常
│       └── constants/RedisKeys.java    ← Redis Key 常量（防止魔法字符串）
│
├── module-mybatis/                     ← MyBatis 核心考点模块
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/interview/mybatis/
│       │   │   ├── config/
│       │   │   │   ├── MyBatisConfig.java
│       │   │   │   └── SecondLevelCacheConfig.java
│       │   │   ├── entity/
│       │   │   │   ├── User.java
│       │   │   │   └── Order.java
│       │   │   ├── mapper/
│       │   │   │   ├── UserMapper.java
│       │   │   │   └── OrderMapper.java
│       │   │   ├── service/
│       │   │   │   ├── CacheCompareService.java    ← 缓存对比演示
│       │   │   │   └── BatchInsertService.java     ← 批量操作性能对比
│       │   │   └── typehandler/
│       │   │       └── JsonTypeHandler.java        ← 自定义类型处理器
│       │   └── resources/
│       │       ├── application.yml
│       │       └── mapper/
│       │           ├── UserMapper.xml
│       │           └── OrderMapper.xml
│       └── test/java/com/interview/mybatis/
│           ├── CacheTest.java                      ← 缓存行为验证
│           ├── DynamicSqlTest.java                 ← 动态SQL测试
│           ├── BatchInsertPerfTest.java             ← 性能对比测试
│           └── SqlInjectionTest.java               ← #{} vs ${} 演示
│
├── module-redis/                        ← Redis 核心考点模块
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/interview/redis/
│       │   ├── config/
│       │   │   ├── RedisConfig.java               ← 序列化配置
│       │   │   └── RedissonConfig.java            ← Redisson 配置
│       │   ├── datatype/                          ← 五种数据类型场景
│       │   │   ├── StringOpsDemo.java             ← 计数器/Token/Session
│       │   │   ├── HashOpsDemo.java               ← 购物车
│       │   │   ├── ListOpsDemo.java               ← 消息列表/队列
│       │   │   ├── SetOpsDemo.java                ← 共同好友/抽奖
│       │   │   └── ZSetOpsDemo.java               ← 排行榜/延迟队列
│       │   ├── lock/
│       │   │   ├── BadLockDemo.java               ← :x: 错误实现（面试陷阱）
│       │   │   ├── SetnxLockDemo.java             ← 基础 setnx 实现
│       │   │   ├── LuaScriptLockDemo.java         ← Lua 脚本原子性实现
│       │   │   └── RedissonLockDemo.java          ← Redisson 生产级实现
│       │   ├── cache/
│       │   │   ├── CachePenetrationDemo.java      ← 缓存穿透解决方案
│       │   │   ├── CacheBreakdownDemo.java        ← 缓存击穿解决方案
│       │   │   ├── CacheAvalancheDemo.java        ← 缓存雪崩解决方案
│       │   │   └── BloomFilterDemo.java           ← 布隆过滤器
│       │   └── advanced/
│       │       ├── PipelineDemo.java              ← Pipeline 批量操作
│       │       ├── LuaScriptDemo.java             ← Lua 脚本原子操作
│       │       └── RateLimiterDemo.java           ← 限流器
│       └── test/java/com/interview/redis/
│           ├── DataTypeTest.java
│           ├── DistributedLockTest.java
│           ├── CacheProblemTest.java
│           └── PerformanceTest.java
│
├── module-kafka/                        ← Kafka 核心考点模块
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/interview/kafka/
│       │   ├── config/
│       │   │   ├── KafkaProducerConfig.java
│       │   │   └── KafkaConsumerConfig.java
│       │   ├── producer/
│       │   │   ├── BasicProducer.java             ← 基础生产者
│       │   │   ├── TransactionalProducer.java     ← 事务消息生产者
│       │   │   └── ReliableProducer.java          ← 可靠性配置示例
│       │   ├── consumer/
│       │   │   ├── BasicConsumer.java             ← 基础消费者
│       │   │   ├── ManualCommitConsumer.java      ← 手动提交 offset
│       │   │   ├── IdempotentConsumer.java        ← 幂等消费
│       │   │   └── BatchConsumer.java             ← 批量消费
│       │   └── deadletter/
│       │       ├── DeadLetterConfig.java          ← 死信队列配置
│       │       └── RetryConsumer.java             ← 重试 + 死信处理
│       └── test/java/com/interview/kafka/
│           ├── ProducerReliabilityTest.java
│           ├── ConsumerIdempotentTest.java
│           └── DeadLetterTest.java
│
└── module-integration/                  ← 综合场景模块（面试系统设计题）
    ├── pom.xml
    └── src/
        ├── main/java/com/interview/integration/
        │   ├── seckill/                           ← 场景1：秒杀系统
        │   │   ├── SeckillController.java
        │   │   ├── SeckillService.java
        │   │   └── SeckillConsumer.java
        │   ├── idempotent/                        ← 场景2：接口幂等性
        │   │   ├── IdempotentAspect.java          ← AOP 实现
        │   │   └── IdempotentAnnotation.java
        │   └── delayqueue/                        ← 场景3：延迟队列
        │       ├── DelayQueueService.java         ← ZSet 实现
        │       └── DelayQueueScheduler.java       ← 轮询调度器
        └── test/java/com/interview/integration/
            ├── SeckillConcurrencyTest.java        ← 并发测试
            └── DelayQueueTest.java
```

---

## :memo: 代码注释规范（必须严格遵守）

### 规范一：考点标注（每个核心方法必须有）

```java
/**
 * 【面试考点】缓存击穿解决方案 - 互斥锁方案
 *
 * 问题描述：热点key过期瞬间，大量请求同时打到DB
 *
 * 解决思路：
 *   1. 查缓存 miss 后，不直接查DB
 *   2. 先抢分布式锁
 *   3. 抢到锁的查DB并重建缓存
 *   4. 未抢到锁的等待重试（或返回降级数据）
 *
 * 【对比方案】逻辑过期方案见 {@link CacheBreakdownDemo#queryWithLogicalExpire}
 *   互斥锁：数据强一致，但有等待延迟
 *   逻辑过期：性能更好，但会短暂返回旧数据
 *
 * 【面试追问】如果缓存重建很慢，等待线程太多怎么办？
 *   → 答：限制等待次数，超过阈值直接返回降级数据（兜底）
 */
public <T> T queryWithMutex(String keyPrefix, Long id, Class<T> type,
                             Function<Long, T> dbFallback, Long ttl) {
```

### 规范二：对比注释（有多种实现时必须有）

```java
// ========== 方案对比 ==========
// :x: 方案一（错误示范）：非原子操作，高并发下有安全问题
//    SET key value  然后  EXPIRE key seconds
//    问题：两条命令之间如果宕机，key永远不过期
//
// :white_check_mark: 方案二（正确）：原子操作
//    SET key value EX seconds NX
//    原子性保证：命令要么全执行，要么不执行
// ==============================
Boolean result = redisTemplate.opsForValue()
    .setIfAbsent(key, value, timeout, TimeUnit.SECONDS); // SET NX EX 原子命令
```

### 规范三：数据流注释（复杂流程必须有）

```java
// 秒杀流程：
// ① 请求进入 → 检查Redis库存（原子扣减，Lua脚本）
// ② 库存不足 → 直接返回失败（不走DB）
// ③ 库存充足 → 发送Kafka消息（异步下单，快速响应用户）
// ④ Consumer消费消息 → 写DB（幂等处理，防止重复下单）
// ⑤ 失败 → 进入死信队列 → 告警 + 人工处理
```

### 规范四：性能数据注释（性能对比场景必须有）

```java
// 【性能测试结果】插入10000条数据（本地环境参考值）
// 方式一：for循环单条insert    耗时：约 8000ms   ← 绝对不要用
// 方式二：foreach批量insert    耗时：约  800ms   ← 一般场景
// 方式三：BATCH执行器          耗时：约  200ms   ← 大数据量首选
// 结论：BATCH模式比单条快40倍，原因是减少了网络RTT和事务开销
```

### 规范五：面试题内嵌注释（关键实现旁必须有）

```java
redisTemplate.opsForValue().set(key, value,
    30 + RandomUtil.randomInt(0, 10),  // 【面试考点】随机TTL防雪崩
    TimeUnit.MINUTES);                  // 原因：固定TTL会导致同时过期，随机偏移打散过期时间
```

---

## :key: 关键实现要求

### MyBatis 模块

#### 1. SqlInjectionTest.java — #{} vs ${} 对比

```java
// 必须同时演示：
// ① #{} 的预编译效果（打印SQL看到 ? 占位符）
// ② ${} 的注入漏洞（传入 "1 OR 1=1" 能查出所有数据）
// ③ ${} 合法使用场景（ORDER BY 动态列名，此时用#{}会变成字符串）
```

#### 2. CacheTest.java — 缓存行为验证

```java
// 必须演示：
// ① 一级缓存：同一SqlSession查两次，第二次不发SQL（日志证明）
// ② 一级缓存失效：update后再查，重新发SQL
// ③ 二级缓存：不同SqlSession查同一数据，第二次命中缓存
// ④ 二级缓存失效：任意写操作后整个namespace缓存清空（这是坑！）
// 每个场景后面加断言：verify(sqlSession, times(1)).selectOne(...)
```

#### 3. BatchInsertPerfTest.java

```java
// 必须记录耗时并打印对比结果：
// 三种方式插入同量数据，最后 log.info("性能对比报告：\n单条:{}ms\n批量:{}ms\nBATCH:{}ms")
```

### Redis 模块

#### 4. 分布式锁演进（最重要！）

```java
// BadLockDemo.java 演示3个经典错误：
//   错误1：加锁和设置过期时间非原子 → 死锁风险
//   错误2：释放锁时没有校验owner → 误删他人锁
//   错误3：释放锁的 get+del 非原子 → 仍有误删风险

// SetnxLockDemo.java：
//   用 SET key value NX EX seconds 解决原子问题
//   value 用 UUID 解决误删问题
//   但仍有问题：业务超时后锁自动释放，其他线程进入，原线程还在执行（锁续期问题）

// LuaScriptLockDemo.java：
//   释放锁用Lua脚本保证 判断+删除 原子性
//   附上Lua脚本内容和解释

// RedissonLockDemo.java：
//   watchdog机制解决锁续期
//   可重入锁原理（hash结构存重入次数）
//   对比以上所有方案的优缺点表格（注释中）
```

#### 5. 三大缓存问题（每种必须有两种解决方案）

```java
// CachePenetrationDemo.java
//   方案1：缓存空值（简单，但浪费内存，有短暂不一致）
//   方案2：布隆过滤器（内存效率高，但有误判，且删除麻烦）
//   方案对比：什么时候选哪个

// CacheBreakdownDemo.java
//   方案1：互斥锁（强一致，有等待）
//   方案2：逻辑过期（高性能，弱一致）
//   用相同的函数签名实现，便于对比

// CacheAvalancheDemo.java
//   方案1：随机TTL（简单有效）
//   方案2：多级缓存（本地Caffeine + Redis）
//   方案3：服务降级兜底
```

### Kafka 模块

#### 6. 消息可靠性三层保证

```java
// ReliableProducer.java 必须配置并注释原因：
//   acks=all           → 所有ISR副本确认，不丢消息
//   retries=3          → 失败重试（幂等开启后重试安全）
//   enable.idempotence=true → 精确一次语义，防重复
//   compression.type=snappy → 压缩减少网络传输

// ManualCommitConsumer.java 演示：
//   ① 自动提交的问题：消费中宕机，消息丢失
//   ② 手动提交时机：业务处理完再提交
//   ③ 批量手动提交优化写法
```

#### 7. IdempotentConsumer.java — 幂等消费（面试重点）

```java
// 必须演示数据库唯一索引方案：
//   ① 消息带全局唯一ID（msgId）
//   ② 消费前查DB是否已处理
//   ③ 处理完插入消费记录（唯一索引防并发重复）
//   ④ 重复消息 catch DuplicateKeyException 直接ack，不抛出
//
// 注释说明为什么不用Redis做幂等：
//   Redis可以，但要考虑Redis和DB的双写一致性问题
//   DB唯一索引更简单可靠，但性能略低
```

### 集成模块

#### 8. SeckillService.java — 完整秒杀流程

```java
// Lua脚本实现原子库存扣减（必须内嵌Lua脚本并逐行注释）：
String luaScript =
    "if (redis.call('get', KEYS[1]) == nil) then\n" +   -- 商品不存在
    "    return -1\n" +
    "end\n" +
    "if (tonumber(redis.call('get', KEYS[1])) <= 0) then\n" +  -- 库存不足
    "    return 0\n" +
    "end\n" +
    "redis.call('decr', KEYS[1])\n" +                    -- 原子扣减
    "return 1\n";                                         -- 成功

// 必须演示的完整链路：
// Controller → SeckillService(Redis扣减) → KafkaProducer → 快速返回
//                                      ↓
//                              SeckillConsumer → 写DB → 幂等处理
```

---

## :page_facing_up: 必须生成的文档

### interview-questions.md 格式要求

```markdown
# MyBatis 高频面试题

## Q1：#{} 和 ${} 的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版（背这个）：**
#{} 是预编译占位符，MyBatis会将其替换为?并通过PreparedStatement设置参数，
可以防止SQL注入；${} 是字符串直接替换，有SQL注入风险，
但在动态表名、列名场景下必须用${}。

**展开答版（追问时用）：**
[详细原理 + 源码层面解释 + 实际案例]

**代码验证：** 见 SqlInjectionTest.java

**常见追问：**

- 什么场景下必须用 ${}？→ ORDER BY 动态列名
- PreparedStatement 为什么能防注入？→ 参数不会被当作SQL解析

---
```

### cheatsheet.md 格式要求

```markdown
# Redis 速查表

## 分布式锁选型

| 方案             | 原子性             | 防误删             | 锁续期             | 推荐指数                       |
| ---------------- | ------------------ | ------------------ | ------------------ | ------------------------------ |
| SETNX+EXPIRE     | :x:                | :x:                | :x:                | :star:                         |
| SET NX EX + UUID | :white_check_mark: | :white_check_mark: | :x:                | :star::star::star:             |
| Lua脚本释放      | :white_check_mark: | :white_check_mark: | :x:                | :star::star::star::star:       |
| Redisson         | :white_check_mark: | :white_check_mark: | :white_check_mark: | :star::star::star::star::star: |

## 缓存问题速查

| 问题 | 现象 | 解决方案 |
...
```

---

## :whale: docker-compose.yml 要求

````yaml
# 必须包含健康检查
# 必须挂载初始化SQL（自动建表建测试数据）
# 必须设置合理的资源限制（防止本地内存爆）
# 服务启动顺序：PostgreSQL → Redis → Kafka（depends_on + condition）

services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: interview_prep
      POSTGRES_USER: interview
      POSTGRES_PASSWORD: interview123
    volumes:
      - ./init-sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U interview"]
      interval: 5s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits:
          memory: 512M

  redis:
    image: redis:7
    command: redis-server --appendonly yes   # 开启AOF
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
    deploy:
      resources:
        limits:
          memory: 256M
          kafka:
            image: confluentinc/cp-kafka:7.5.0
            # 使用 KRaft 模式（无需 ZooKeeper）
            environment:
            KAFKA_NODE_ID: 1
            KAFKA_PROCESS_ROLES: broker,controller
            KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
            KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
            KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
            KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
            deploy:
            resources:
            limits:
            memory: 512M
            ```

---

## :white_check_mark: 验收标准

生成完成后，以下命令必须全部成功：

```bash
# 1. 环境启动
docker-compose up -d
docker-compose ps # 所有服务 healthy

# 2. 各模块测试（绿色通过）
mvn test -pl module-mybatis
mvn test -pl module-redis
mvn test -pl module-kafka
mvn test -pl module-integration

# 3. 核心演示
# 能看到缓存命中/miss 的日志输出
# 能看到分布式锁获取/释放的日志
# 能看到 Kafka 消息生产/消费的全链路日志
# 能看到批量插入的性能对比数据
````

---

## :no_entry_sign: 禁止事项

- 禁止使用 Spring Data Redis 的 `@Cacheable` 注解实现缓存（要手写，展示原理）
- 禁止 Kafka 只用 `@KafkaListener` 自动配置（至少一个手动配置 ConsumerFactory 的例子）
- 禁止注释只写 "// 查询用户"这种废话注释
- 禁止没有对应 Test 类的功能实现
- 禁止 Test 类只有 main 方法，必须用 JUnit5 + 断言

## 新增模块：module-microservice

### 文件结构

```
module-microservice/
└── src/main/java/com/interview/microservice/
    ├── circuit/
    │   ├── SentinelDemo.java          ← 熔断规则配置+演示
    │   └── FallbackHandler.java       ← 降级处理
    ├── transaction/
    │   ├── TccDemo.java               ← TCC 事务演示
    │   ├── SagaDemo.java              ← Saga 补偿演示
    │   └── LocalMessageTable.java     ← 本地消息表（最推荐方案）
    └── idempotent/
        ├── TokenService.java          ← Token幂等方案
        └── IdempotentAspect.java      ← AOP统一幂等处理
```

### 关键注释要求

```java
/**
 * 【面试考点】本地消息表实现分布式事务
 *
 * 核心思路：将分布式事务拆成本地事务 + 消息最终一致
 *
 * 流程：
 * ① 业务操作 + 插入消息记录（同一本地事务，原子性保证）
 * ② 定时任务扫描未发送消息 → 发送Kafka
 * ③ 消费方幂等处理 + 回调确认
 * ④ 消息标记为已完成
 *
 * 【对比Seata】
 * 本地消息表：侵入性小，性能好，适合最终一致场景
 * Seata AT：自动化程度高，适合强一致场景，但有性能开销
 */
```

## 新增模块：module-k8s（配置文件 + 说明）

### 文件结构

```
module-k8s/
├── deployment.yaml          ← 带详细注释的部署配置
├── service.yaml             ← Service 四种类型示例
├── hpa.yaml                 ← 自动扩缩容配置
├── configmap.yaml           ← 配置管理
├── probe-demo.yaml          ← 三种探针完整配置
└── INTERVIEW_NOTES.md       ← K8s 面试要点总结
```

### probe-demo.yaml 注释要求

```yaml
# 【面试考点】三种探针的区别和适用场景

livenessProbe: # 存活探针：失败 → 重启容器
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30 # 等待应用启动再开始检测
  periodSeconds: 10 # 每10秒检测一次
  failureThreshold: 3 # 连续失败3次才重启（避免抖动）

readinessProbe: # 就绪探针：失败 → 从Service移除（不重启）
  httpGet: # 关键区别：应用还活着，但暂时不能处理请求
    path: /actuator/health/readiness # 典型场景：预热中/连接池未就绪
    port: 8080
  periodSeconds: 5
  failureThreshold: 2 # 失败2次就摘流，恢复快

startupProbe: # 启动探针：慢启动应用保护
  httpGet: # 场景：Java应用启动慢（60s+）
    path: /actuator/health
    port: 8080
  failureThreshold: 30 # 30次 * 10s = 最多等300秒
  periodSeconds: 10 # startupProbe成功后才启动另外两个探针
```

### deployment.yaml 滚动更新注释

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1 # 【面试考点】更新时最多额外创建1个Pod
    maxUnavailable: 0 # 保证始终有足量Pod在服务（零停机发布）
    # 面试追问：maxSurge=0,maxUnavailable=1 是什么策略？
    # 答：先删后建，节省资源但有短暂容量下降
    # 面试追问：如何回滚？
    # 答：kubectl rollout undo deployment/xxx
```

## 新增到 interview-questions.md

```markdown
# K8s 高频面试题

## Q1: Pod 一直 Pending 怎么排查？（出现频率 ⭐⭐⭐⭐⭐）

排查步骤（按顺序说，展示系统性思维）：
① kubectl describe pod <name> → 看 Events 部分
② 常见原因：

- 资源不足（CPU/内存）→ 看 Node 资源使用率
- 节点选择器/亲和性不匹配 → 检查 nodeSelector
- PVC 未绑定 → 检查 PersistentVolumeClaim 状态
- 镜像拉取失败 → 检查 imagePullPolicy 和仓库权限

## Q2: 如何实现微服务的零停机发布？

答题要点：
① K8s 层面：滚动更新 + maxUnavailable=0
② 应用层面：readinessProbe 确保新Pod就绪才切流量
③ 代码层面：接口向下兼容（新旧版本并存期间）
④ 数据库层面：先加列（可空），再部署，再填充数据

# MySQL vs PostgreSQL 高频题

## Q1: MVCC 在 MySQL 和 PG 中的实现差异？

MySQL(InnoDB)：
通过 undo log 链实现，读取时根据事务ID和undo log
构建数据快照，原始数据在表中只有一份

PostgreSQL：
多版本直接存在堆表中，旧版本数据和新版本数据
都在表文件里，需要 VACUUM 清理死元组

优劣对比：
MySQL：存储更节省，但undo log读取有开销
PG：读取更直接，但需要定期VACUUM，表膨胀风险
```
