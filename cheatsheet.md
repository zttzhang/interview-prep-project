# Redis 速查表

## 分布式锁选型

| 方案 | 原子性 | 防误删 | 锁续期 | 推荐指数 |
|------|--------|--------|--------|---------|
| SETNX+EXPIRE | :x: | :x: | :x: | :star: |
| SET NX EX + UUID | :white_check_mark: | :white_check_mark: | :x: | :star::star::star: |
| Lua脚本释放 | :white_check_mark: | :white_check_mark: | :x: | :star::star::star::star: |
| Redisson | :white_check_mark: | :white_check_mark: | :white_check_mark: | :star::star::star::star::star: |

### 快速代码

```java
// Redisson 分布式锁（生产环境推荐）
RLock lock = redissonClient.getLock(key);
lock.lock(30, TimeUnit.SECONDS);
try {
    // 业务逻辑
} finally {
    lock.unlock();
}

// Lua 脚本释放锁
String script = 
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "    return redis.call('del', KEYS[1]) " +
    "else return 0 end";
```

---

## 缓存问题速查

| 问题 | 现象 | 解决方案 |
|------|------|----------|
| 缓存穿透 | 查不存在的key，打到DB | 布隆过滤器 / 缓存空值 |
| 缓存击穿 | 热点key过期，大量打到DB | 互斥锁 / 逻辑过期 |
| 缓存雪崩 | 大量key同时过期 | 随机TTL / 多级缓存 |

### 快速代码

```java
// 互斥锁防击穿
Boolean lock = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, uuid, 10, TimeUnit.SECONDS);

// 逻辑过期防击穿
if (data.isExpired()) {
    new Thread(() -> rebuildCache()).start();
}
return data.getData();

// 随机TTL防雪崩
int ttl = 30 + RandomUtil.randomInt(0, 10);
```

---

## 五种数据类型

| 类型 | 命令 | 场景 |
|------|------|------|
| String | SET/GET/INCR | 缓存/计数器/Token |
| Hash | HSET/HGET/HINCRBY | 购物车/对象 |
| List | LPUSH/RPOP | 消息队列/最新列表 |
| Set | SADD/SINTER | 标签/共同好友 |
| ZSet | ZADD/ZRANGE | 排行榜/延迟队列 |

### 快速代码

```java
// String
redisTemplate.opsForValue().set(key, value, ttl);

// Hash
redisTemplate.opsForHash().put(key, field, value);

// ZSet 排行
redisTemplate.opsForZSet().add(key, member, score);
redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, 9);
```

---

# MyBatis 速查表

## #{} vs ${}

| 特性 | #{} | ${} |
|------|-----|-----|
| 安全性 | 防注入 | 不防注入 |
| 场景 | 普通参数 | 动态表名/列名/ORDER BY |

```xml
<!-- #{} 预编译 -->
<select>
    WHERE name = #{name}
</select>

<!-- ${} 字符串替换 -->
<select>
    ORDER BY ${column}
</select>

<!-- LIKE 查询 -->
<select>
    WHERE name LIKE CONCAT('%', #{name}, '%')
</select>
```

## 缓存对比

| 级别 | 作用域 | 默认 | 失效条件 |
|------|--------|------|----------|
| 一级缓存 | SqlSession | 开启 | commit/close/clear |
| 二级缓存 | Namespace | 关闭 | 任意写操作 |

---

# Kafka 速查表

## 可靠性配置

```java
// 生产者
props.put("acks", "all");
props.put("retries", 3);
props.put("enable.idempotence", true);
props.put("compression.type", "snappy");

// 消费者
props.put("enable.auto.commit", false);
```

## 幂等消费

```java
@KafkaListener(topics = "topic")
public void consume(String msg, Acknowledgment ack) {
    try {
        process(msg);
        saveRecord(msg); // 唯一索引防重
        ack.acknowledge();
    } catch (DataIntegrityViolationException e) {
        ack.acknowledge(); // 重复消息
    }
}
```

## 消费者组

```
同一组内：负载均衡（一个分区只能被一个消费者消费）
不同组：广播模式（各自消费所有消息）
```

---

# 秒杀系统速查

## 核心链路

```
请求 → Lua原子扣减库存 → 发送Kafka → 立即返回
                              ↓
                          Consumer
                              ↓
                          幂等写DB
```

## Lua 库存扣减

```lua
if redis.call('get', KEYS[1]) == nil then return -1 end
if tonumber(redis.call('get', KEYS[1])) <= 0 then return 0 end
redis.call('decr', KEYS[1])
return 1
```

## 防重复购买

```java
String orderedKey = "seckill:ordered:" + productId + ":" + userId;
Boolean exists = redisTemplate.hasKey(orderedKey);
if (Boolean.TRUE.equals(exists)) {
    return "已购买";
}
```

---

# 接口幂等性速查

## 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| Redis Setnx | 性能好 | 双写一致性风险 |
| 数据库唯一索引 | 简单可靠 | 性能略低 |
| 消息表+状态机 | 最可靠 | 实现复杂 |

## AOP 实现

```java
@Around("@annotation(idempotent)")
public Object handle(ProceedingJoinPoint pjp) {
    String token = request.getHeader("Idempotent-Token");
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent("idempotent:" + token, "1", 5, TimeUnit.MINUTES);
    if (!acquired) throw new BizException("重复请求");
    return pjp.proceed();
}
```

---

# 延迟队列速查

## Redis ZSet 实现

```java
// 添加任务（score = 执行时间戳）
long executeTime = System.currentTimeMillis() + delay * 1000;
redisTemplate.opsForZSet().add("delay:queue", taskId, executeTime);

// 轮询任务
Set<String> tasks = redisTemplate.opsForZSet()
    .rangeByScore("delay:queue", 0, System.currentTimeMillis());
for (String task : tasks) {
    process(task);
    redisTemplate.opsForZSet().remove("delay:queue", task);
}
```

---

# Spring Boot 速查

## 事务传播

| 传播 | 说明 |
|------|------|
| REQUIRED | 有事务加入，无则新建 |
| REQUIRES_NEW | 挂起当前事务，新建独立事务 |
| NESTED | 嵌套事务（savepoint） |

## Bean 生命周期

```
实例化 → 属性填充 → Aware接口 → BeanPostProcessor → 初始化 → 销毁
```

## 自动装配

```
@SpringBootApplication
    ↓
@EnableAutoConfiguration
    ↓
spring.factories → Configuration类 → @Bean注册
```

---

# MySQL 速查

## 索引失效场景

- 函数/计算：`WHERE YEAR(create_time) = 2024`
- 类型转换：`WHERE phone = 13800138001`（phone是varchar）
- LIKE前缀：`WHERE name LIKE '%zhang'`
- OR条件：`WHERE id = 1 OR name = 'zhang'`
- 最左前缀：`WHERE name = 'zhang'`（联合索引是name,age）

## MVCC

```
ReadView: [trx_ids, min_trx_id, max_trx_id, creator_trx_id]
判断: trx_id < min_trx_id 或 trx_id = creator_trx_id → 可见
```

---

# 常用命令

## Docker

```bash
# 启动
docker-compose up -d

# 查看状态
docker-compose ps

# 查看日志
docker-compose logs -f postgres

# 停止
docker-compose down
```

## Maven

```bash
# 编译
mvn clean compile

# 测试
mvn test -pl module-mybatis

# 跳过测试
mvn clean package -DskipTests
```

## Redis CLI

```bash
# 连接
redis-cli -h localhost -p 6379

# 查看key
KEYS *

# 查看值
GET key

# 查看TTL
TTL key
```

## Kafka CLI

```bash
# 创建Topic
kafka-topics.sh --create --topic test --bootstrap-server localhost:9092

# 查看Topic
kafka-topics.sh --list --bootstrap-server localhost:9092

# 发送消息
kafka-console-producer.sh --topic test --bootstrap-server localhost:9092

# 消费消息
kafka-console-consumer.sh --topic test --from-beginning --bootstrap-server localhost:9092
```

---

# 面试话术

## 30秒速记

**MySQL vs Redis 缓存：**
Redis 是内存数据库，查询快但不支持复杂查询；MySQL 是磁盘数据库，支持SQL但查询慢。

**CAP 定理：**
C（一致性）A（可用性）P（分区容错性）。Redis 是 AP，Zookeeper 是 CP。

**Base 理论：**
 Basically Available（基本可用）+ Soft state（软状态）+ Eventually consistent（最终一致）。

**分布式事务：**
TCC（Try-Confirm-Cancel）、Seata AT 模式（自动补偿）、Saga 模式（长事务链）。

---

## 回答模板

**"请介绍你最近的项目"**
> 这是一个[项目类型]，主要解决[业务问题]。我负责[模块]，使用了[技术栈]。核心挑战是[技术难点]，我通过[解决方案]克服了。

**"遇到过什么技术难题"**
> 在[项目]中，我们遇到了[问题描述]。我尝试了[方案1]但效果不好，最后采用[方案2]解决了问题。这个经历让我认识到[经验教训]。

**"你有什么想问我的"**
> 1. 这个岗位的技术团队规模和技术栈？
> 2. 团队的代码审查流程是怎样的？
> 3. 新人入职后的培养机制？
