# MyBatis 高频面试题

## Q1：#{} 和 ${} 的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版（背这个）：**
#{} 是预编译占位符，MyBatis会将其替换为?并通过PreparedStatement设置参数，可以防止SQL注入；${} 是字符串直接替换，有SQL注入风险，但在动态表名、列名场景下必须用${}。

**展开答版（追问时用）：**

### 原理区别

| 特性     | #{}                           | ${}                     |
| -------- | ----------------------------- | ----------------------- |
| SQL处理  | 预编译处理，参数绑定到?占位符 | 直接字符串替换          |
| SQL注入  | 安全，防注入                  | 危险，不防注入          |
| 数据类型 | 自动处理，加引号              | 直接替换，不加引号      |
| 适用场景 | 普通参数                      | 动态表名/列名、ORDER BY |

### 源码层面

```java
// #{} 会被处理成 ?
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE name = ?");
ps.setString(1, "zhangsan");

// ${} 直接替换
// "SELECT * FROM users WHERE name = " + "zhangsan"
```

### 实际案例

```xml
<!-- #{} 正确用法 -->
<select id="findByName" resultType="User">
    SELECT * FROM users WHERE name = #{name}
</select>

<!-- ${} 危险用法（演示） -->
<select id="findByNameDirect" resultType="User">
    SELECT * FROM users WHERE name = '${name}'  <!-- SQL注入风险！ -->
</select>

<!-- ${} 合法场景：ORDER BY 动态列名 -->
<select id="findAll" resultType="User">
    SELECT * FROM users ORDER BY ${columnName}  <!-- 必须用 ${} -->
</select>
```

**代码验证：** 见 `SqlInjectionTest.java`

**常见追问：**

- 什么场景下必须用 ${}？→ ORDER BY 动态列名、动态表名
- PreparedStatement 为什么能防注入？→ 参数不会被当作SQL解析
- LIKE 查询怎么用 #{}？→ 用 CONCAT('%', #{name}, '%') 或 @Bind 注解

---

## Q2：MyBatis 一级缓存和二级缓存的区别？（出现频率 :star::star::star::star:）

**30秒速答版：**
一级缓存是SqlSession级别的，生命周期短，默认开启；二级缓存是Mapper级别的，跨SqlSession共享，需要手动配置开启。

**展开答版：**

### 缓存层级对比

```
请求 → SqlSession1 → 一级缓存命中 → 返回
                ↓ miss
            查询DB → 写入一级缓存 → 返回

跨SqlSession：
SqlSession1 → 写入一级缓存 → 提交事务 → 数据进入二级缓存
SqlSession2 → 二级缓存命中 → 返回
```

### 一级缓存

| 特性     | 说明                      |
| -------- | ------------------------- |
| 作用域   | SqlSession                |
| 存储位置 | PerpetualCache（HashMap） |
| 生命周期 | SqlSession创建到关闭      |
| 失效场景 | commit/close/clearCache   |
| 默认状态 | 开启                      |

```java
// 验证一级缓存
User u1 = userMapper.selectById(1); // 查DB
User u2 = userMapper.selectById(1); // 命中缓存，不查DB
```

### 二级缓存

| 特性     | 说明                  |
| -------- | --------------------- |
| 作用域   | Mapper Namespace      |
| 存储位置 | CachingExecutor       |
| 生命周期 | 应用启动到停止        |
| 失效场景 | 该Namespace任意写操作 |
| 默认状态 | 关闭，需配置          |

```xml
<!-- 开启二级缓存 -->
<mapper namespace="com.interview.mapper.UserMapper">
    <cache eviction="LRU" flushInterval="60000" size="512" readOnly="false"/>
</mapper>
```

**代码验证：** 见 `CacheTest.java`

**常见追问：**

- 二级缓存的坑？→ 任意写操作清空整个Namespace缓存
- 缓存淘汰策略？→ LRU/FIFO/SOFT/WEAK
- 多表查询能用二级缓存吗？→ 不能，需要用第三方如Redis

---

## Q3：MyBatis 延迟加载是什么？（出现频率 :star::star::star:）

**30秒速答版：**
延迟加载是懒加载，关联数据在使用时才查询，可减少不必要的SQL执行。

**展开答版：**

### 配置方式

```yaml
mybatis-plus:
  configuration:
    lazy-loading-enabled: true # 开启懒加载
    aggressive-lazy-loading: false # 关闭积极加载
```

### 使用场景

```java
// 不开启延迟加载：一条SQL查完所有
User user = userMapper.selectUserWithOrders(1); // SELECT u.*, o.* FROM users u LEFT JOIN orders o

// 开启延迟加载：分步查询
User user = userMapper.selectById(1); // SELECT * FROM users WHERE id=1
List<Order> orders = user.getOrders(); // SELECT * FROM orders WHERE user_id=1 （使用时才查）
```

**常见追问：**

- 如何触发延迟加载？→ 调用关联对象的方法时
- 延迟加载的原理？→ 使用动态代理，调用方法时触发查询

---

# Redis 高频面试题

## Q4：Redis 分布式锁的实现方案？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
分布式锁经历了4个阶段的演进：SETNX+EXPIRE → SET NX EX + UUID → Lua脚本释放 → Redisson。

**展开答版：**

### 方案对比表

| 方案             | 原子性             | 防误删             | 锁续期             | 推荐指数                       |
| ---------------- | ------------------ | ------------------ | ------------------ | ------------------------------ |
| SETNX+EXPIRE     | :x:                | :x:                | :x:                | :star:                         |
| SET NX EX + UUID | :white_check_mark: | :white_check_mark: | :x:                | :star::star::star:             |
| Lua脚本释放      | :white_check_mark: | :white_check_mark: | :x:                | :star::star::star::star:       |
| Redisson         | :white_check_mark: | :white_check_mark: | :white_check_mark: | :star::star::star::star::star: |

### 错误实现（面试陷阱）

```java
// 错误1：加锁和设置过期时间非原子
redisTemplate.opsForValue().setIfAbsent(key, value);
redisTemplate.expire(key, 30, TimeUnit.SECONDS); // 这里宕机会死锁！

// 错误2：释放锁没有校验owner
redisTemplate.delete(key); // 会误删别人的锁

// 错误3：get+delete非原子
if (value.equals(redisTemplate.opsForValue().get(key))) {
    redisTemplate.delete(key); // 中间可能被其他线程获取锁
}
```

### 正确实现

```java
// 方案1：SET NX EX + UUID
Boolean success = redisTemplate.opsForValue()
    .setIfAbsent(key, uuid, 30, TimeUnit.SECONDS);

// 方案2：Lua脚本释放
String luaScript =
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "    return redis.call('del', KEYS[1]) " +
    "else return 0 end";
```

### Redisson 实现

```java
RLock lock = redissonClient.getLock(key);
lock.lock(30, TimeUnit.SECONDS); // 自动续期
try {
    // 业务逻辑
} finally {
    lock.unlock();
}
```

**代码验证：** 见 `BadLockDemo.java`, `SetnxLockDemo.java`, `RedissonLockDemo.java`

**常见追问：**

- 锁续期原理？→ 看门狗（Watchdog）机制，自动延长时间
- 可重入锁怎么实现？→ Hash结构存储线程ID和重入次数
- Redisson 和 Zookeeper 实现锁的区别？→ Redis是CP，Zookeeper是CA

---

## Q5：缓存穿透、击穿、雪崩的区别和解决方案？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
穿透是访问不存在的key，用布隆过滤器或缓存空值解决；击穿是热点key过期，用互斥锁或逻辑过期解决；雪崩是大量key同时过期，用随机TTL或多级缓存解决。

**展开答版：**

### 三大问题对比

| 问题     | 现象                        | 原因                   |
| -------- | --------------------------- | ---------------------- |
| 缓存穿透 | 查询不存在的key，打到DB     | 恶意攻击或业务漏洞     |
| 缓存击穿 | 热点key过期，大量请求打到DB | 并发访问热点数据       |
| 缓存雪崩 | 大量key同时过期             | 统一TTL设置或Redis宕机 |

### 穿透解决方案

```java
// 方案1：缓存空值（简单）
if (value == null) {
    redisTemplate.opsForValue().set(key, "", 5, TimeUnit.MINUTES);
}

// 方案2：布隆过滤器（内存效率高）
BloomFilter<Long> filter = BloomFilter.create(...);
if (!filter.mightContain(id)) {
    return null; // 一定不存在
}
```

### 击穿解决方案

```java
// 方案1：互斥锁（强一致）
Boolean lock = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, uuid, 10, TimeUnit.SECONDS);
if (lock) {
    // 查DB，写缓存
}

// 方案2：逻辑过期（高性能）
if (data.isExpired()) {
    new Thread(() -> rebuildCache()); // 异步重建
}
return data.getData(); // 返回旧数据
```

### 雪崩解决方案

```java
// 方案1：随机TTL
int ttl = baseTtl + RandomUtil.randomInt(0, 10);
redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.MINUTES);

// 方案2：多级缓存（本地+Redis）
// L1: Caffeine本地缓存
// L2: Redis缓存
// L3: DB

// 方案3：服务降级兜底
try {
    return queryFromCache();
} catch (Exception e) {
    return queryFromFallback(); // 返回默认值
}
```

**代码验证：** 见 `CachePenetrationDemo.java`, `CacheBreakdownDemo.java`, `CacheAvalancheDemo.java`

---

## Q6：Redis 五种数据类型的应用场景？（出现频率 :star::star::star::star:）

**30秒速答版：**
String用于缓存/计数器/Token，Hash用于购物车/对象存储，List用于消息队列/最新列表，Set用于标签/共同好友/抽奖，ZSet用于排行榜/延迟队列。

**展开答版：**

### 数据类型对比

| 类型   | 结构        | 典型场景                        |
| ------ | ----------- | ------------------------------- |
| String | KV          | 缓存、计数器、Session、分布式锁 |
| Hash   | Field-Value | 购物车、对象属性、用户信息      |
| List   | 有序列表    | 消息队列、最新商品、关注列表    |
| Set    | 无序去重    | 标签、共同好友、UV统计          |
| ZSet   | 有序去重    | 排行榜、延迟队列、权重队列      |

### 实际代码

```java
// String：Token存储
redisTemplate.opsForValue().set("token:" + userId, token, 2, TimeUnit.HOURS);

// Hash：购物车
redisTemplate.opsForHash().put("cart:" + userId, productId, count);
Long count = redisTemplate.opsForHash().increment("cart:" + userId, productId, 1);

// ZSet：排行榜
redisTemplate.opsForZSet().add("ranking:product", productId, sales);
Set<ZSetOperations.TypedTuple> top10 = redisTemplate.opsForZSet()
    .reverseRangeWithScores("ranking:product", 0, 9);
```

**代码验证：** 见 `StringOpsDemo.java`, `HashOpsDemo.java`, `ZSetOpsDemo.java`

---

# Kafka 高频面试题

## Q7：Kafka 如何保证消息不丢失？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
生产者端：acks=all + retries=3 + enable.idempotence=true；Broker端：副本数>=2 + min.insync.replicas>=2；消费者端：手动提交offset。

**展开答版：**

### 三层保证

```
生产者                          Broker                         消费者
  │                               │                               │
  ├─ acks=all ──────────────────>│                               │
  │   (所有ISR副本确认)            │                               │
  │                               │                               │
  ├─ retries=3 ──────────────────>│                               │
  │   (失败重试)                  │                               │
  │                               │                               │
  ├─ idempotence=true ───────────>│                               │
  │   (幂等发送)                  │                               │
  │                               │                               │
  │                               │<─────── manual ack ───────────┤
  │                               │    (处理完再提交offset)        │
```

### 生产者配置

```java
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.RETRIES_CONFIG, 3);
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
```

### 消费者配置

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// 手动提交
@KafkaListener(topics = "topic")
public void listen(String msg, Acknowledgment ack) {
    process(msg);
    ack.acknowledge(); // 处理完再提交
}
```

**代码验证：** 见 `KafkaProducerConfig.java`, `KafkaConsumerConfig.java`, `ReliableProducer.java`

**常见追问：**

- acks=all 性能很差怎么办？→ 调整 min.insync.replicas 或批量发送
- 消息重复怎么办？→ 开启幂等 + 消费者幂等处理

---

## Q8：Kafka 消息幂等消费的方案？（出现频率 :star::star::star::star:）

**30秒速答版：**
数据库唯一索引方案：消费前查DB是否已处理，处理完插入消费记录表（唯一索引防并发重复），重复消息catch异常直接ack。

**展开答版：**

### 方案对比

| 方案           | 优点     | 缺点           |
| -------------- | -------- | -------------- |
| 数据库唯一索引 | 简单可靠 | 性能略低       |
| Redis Setnx    | 性能好   | 双写一致性问题 |
| 消息表+状态机  | 最可靠   | 实现复杂       |

### 实现代码

```java
@KafkaListener(topics = "topic")
public void consume(String msgId, String content, Acknowledgment ack) {
    try {
        // 1. 检查是否已处理
        if (isProcessed(msgId)) {
            ack.acknowledge();
            return;
        }

        // 2. 业务处理
        process(content);

        // 3. 记录消费记录（唯一索引）
        saveRecord(msgId, content);

        // 4. 手动提交
        ack.acknowledge();

    } catch (DataIntegrityViolationException e) {
        // 重复消息，直接ACK
        ack.acknowledge();
    }
}
```

**代码验证：** 见 `IdempotentConsumer.java`

---

## Q9：Kafka 消费者组的作用？（出现频率 :star::star::star:）

**30秒速答版：**
同一消费者组内的消费者会负载均衡消费消息（一个分区只能被一个消费者消费），不同消费者组会各自消费一遍（广播模式）。

**展开答版：**

### 分区分配规则

```
Topic: 4个分区
消费者组A: 2个消费者 → 每个消费2个分区
消费者组B: 3个消费者 → 1个消费2个，2个各消费1个
消费者组C: 4个消费者 → 每个消费1个分区
```

### 代码示例

```java
// 同一消费者组：负载均衡
@KafkaListener(groupId = "order-group", topics = "order-topic")
public class OrderConsumer { }

// 不同消费者组：各自消费
@KafkaListener(groupId = "stock-group", topics = "order-topic")
public class StockConsumer { }
```

---

# 综合场景面试题

## Q10：如何设计一个秒杀系统？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
前端限流 + 秒杀链接隐藏 → Redis原子扣减库存 → Kafka异步下单 → 消费者幂等处理 → 订单结果轮询。

**展开答版：**

### 架构图

```
用户请求
    │
    ▼
┌─────────────┐
│  CDN/Nginx  │ ← 静态资源、限流
└─────────────┘
    │
    ▼
┌─────────────┐
│   前端      │ ← 验证码、倒计时、随机数
└─────────────┘
    │
    ▼
┌─────────────┐
│   网关      │ ← 鉴权、限流
└─────────────┘
    │
    ▼
┌─────────────┐
│  Redis Lua  │ ← 原子扣减库存
└─────────────┘
    │
    ├── 库存不足 → 直接返回
    │
    ▼
┌─────────────┐
│   Kafka     │ ← 异步下单
└─────────────┘
    │
    ▼
┌─────────────┐
│  Consumer   │ ← 幂等处理、创建订单
└─────────────┘
    │
    ▼
┌─────────────┐
│   MySQL     │ ← 持久化订单
└─────────────┘
```

### 核心代码

```java
// Lua脚本原子扣减
String luaScript = """
    if redis.call('get', KEYS[1]) == nil then return -1 end
    if tonumber(redis.call('get', KEYS[1])) <= 0 then return 0 end
    redis.call('decr', KEYS[1])
    return 1
    """;
```

**代码验证：** 见 `SeckillService.java`, `SeckillConsumer.java`

**常见追问：**

- 如何防止超卖？→ Lua脚本原子操作
- 如何防止重复购买？→ Redis标记用户已购买
- 如何保证不卡死？→ 异步处理，快速响应

---

## Q11：接口幂等性的实现方案？（出现频率 :star::star::star::star:）

**30秒速答版：**
前端生成唯一token，携带token请求；后端用Redis Setnx 或 数据库唯一索引 判断是否处理过。

**展开答版：**

### AOP实现

```java
@Around("@annotation(idempotent)")
public Object handleIdempotent(ProceedingJoinPoint point, Idempotent idempotent) {
    String token = request.getHeader("Idempotent-Token");

    // 尝试获取锁
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent("idempotent:" + token, "1", 5, TimeUnit.MINUTES);

    if (!acquired) {
        throw new BizException("请求重复");
    }

    return point.proceed();
}
```

### 注解定义

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String value() default "";
}
```

**代码验证：** 见 `IdempotentAnnotation.java`, `IdempotentAspect.java`

---

## Q12：延迟队列的实现方案？（出现频率 :star::star::star:）

**30秒速答版：**
Redis ZSet实现：任务按执行时间戳作为score，轮询获取当前时间之前的所有任务处理。

**展开答版：**

### 实现原理

```java
// 添加延迟任务
public void addTask(String taskId, String data, long delaySeconds) {
    long executeTime = System.currentTimeMillis() + delaySeconds * 1000;
    redisTemplate.opsForZSet().add("delay:queue", taskId + ":" + data, executeTime);
}

// 轮询获取任务
public List<String> pollTasks() {
    long now = System.currentTimeMillis();
    Set<String> tasks = redisTemplate.opsForZSet()
        .rangeByScore("delay:queue", 0, now);

    for (String task : tasks) {
        // 处理任务
        redisTemplate.opsForZSet().remove("delay:queue", task);
    }
    return tasks;
}
```

### 调度器

```java
@Scheduled(fixedRate = 1000)
public void scanDelayQueue() {
    List<String> tasks = delayQueueService.pollTasks();
    for (String task : tasks) {
        processTask(task);
    }
}
```

**代码验证：** 见 `DelayQueueService.java`, `DelayQueueScheduler.java`

---

# K8s 高频面试题

## Q1: Pod 一直 Pending 怎么排查？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
Pod Pending 通常是资源不足、调度失败或PVC未绑定导致。按 kubectl describe pod 查看Events，结合 kubectl top node 查看资源使用情况定位。

**展开答版：**

### 排查步骤（按顺序说，展示系统性思维）

1. `kubectl describe pod <name>` → 看 Events 部分
2. `kubectl get events --sort-by=.lastTimestamp` → 看最近事件
3. `kubectl top node` → 看节点资源使用率
4. `kubectl get pvc` → 检查 PVC 状态

### 常见原因及解决方案

| 原因                 | 表现                 | 解决方案                        |
| -------------------- | -------------------- | ------------------------------- |
| 资源不足（CPU/内存） | NoSchedule           | 扩容节点或降低资源请求          |
| 节点选择器不匹配     | node(s) didn't match | 检查 nodeSelector/亲和性        |
| PVC 未绑定           | Pending              | 检查 PV 和 StorageClass         |
| 镜像拉取失败         | ErrImagePull         | 检查 imagePullPolicy 和仓库权限 |
| 污点不匹配           | node(s) had taints   | 添加 tolerations                |

---

## Q2: 如何实现微服务的零停机发布？（出现频率 :star::star::star::star:）

**30秒速答版：**
K8s滚动更新+maxUnavailable=0保证始终有足量Pod，通过readinessProbe确保新Pod就绪才切流量，配合接口向下兼容实现。

**展开答版：**

### 四层保障

```
┌─────────────────────────────────────────────┐
│  K8s层：maxUnavailable=0（零停机关键）      │
├─────────────────────────────────────────────┤
│  应用层：readinessProbe（新Pod就绪才接流）   │
├─────────────────────────────────────────────┤
│  代码层：接口向下兼容（新旧版本并存）        │
├─────────────────────────────────────────────┤
│  数据库层：先加列可空，再部署，再填充数据    │
└─────────────────────────────────────────────┘
```

### 滚动更新参数

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1 # 最多额外1个Pod
    maxUnavailable: 0 # 【关键】零停机：不能有不可用Pod
```

**常见追问：**

- maxSurge=0,maxUnavailable=1 是什么？→ 先删后建，有短暂容量下降
- 如何回滚？→ `kubectl rollout undo deployment/xxx`

---

## Q3: 三种探针的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
livenessProbe检测是否存活（失败重启），readinessProbe检测是否就绪（失败摘流），startupProbe保护慢启动应用。

**展开答版：**

| 探针           | 失败行为             | 适用场景                 |
| -------------- | -------------------- | ------------------------ |
| livenessProbe  | 重启容器             | 进程假死、死锁、OOM      |
| readinessProbe | 摘除流量（不重启）   | 启动中、依赖不可用、过载 |
| startupProbe   | 禁用其他探针直到成功 | 应用启动慢（60s+）       |

### 配置示例

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  initialDelaySeconds: 30 # 等启动完成
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  failureThreshold: 2 # 恢复敏感

startupProbe:
  httpGet:
    path: /health
    port: 8080
  failureThreshold: 30 # 30*10s = 300s保护
  periodSeconds: 10
```

---

## Q4: HPA 如何根据指标自动扩缩容？（出现频率 :star::star::star:）

**30秒速答版：**
HPA根据CPU/内存等指标自动调整Pod数量，通过metrics-server采集指标，配合VPA可以实现更智能的扩缩。

**展开答版：**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70 # CPU>70%时扩容

  # 扩缩行为（避免抖动）
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300 # 缩容等待5分钟
    scaleUp:
      stabilizationWindowSeconds: 0 # 扩容立即响应
```

---

# 更多面试题

## Spring Boot 相关

### Q13：Spring Boot 自动装配原理？

核心是 `spring.factories` 和 `@Configuration` 注解。启动时扫描 `META-INF/spring.factories` 中的配置类，加载并注册Bean。

### Q14：Spring 事务传播行为？

| 传播行为     | 说明                       |
| ------------ | -------------------------- |
| REQUIRED     | 默认，有事务加入，无则新建 |
| REQUIRES_NEW | 挂起当前事务，新建独立事务 |
| NESTED       | 嵌套事务（savepoint）      |

### Q15：Bean 的生命周期？

实例化 → 属性填充 → 初始化 → 销毁

## 数据库相关

### Q16：MySQL 索引失效场景？

- 函数/计算
- 类型转换
- LIKE 前缀匹配
- OR 条件
- 最左前缀不匹配

### Q17：MVCC 原理？

ReadView + 隐藏字段（trx_id + roll_pointer）实现非阻塞读。

## 微服务相关

### Q18：Sentinel 和 Hystrix 的区别？

Sentinel 是阿里的流量控制组件，支持更丰富的流量整形策略。

### Q19：Seata 分布式事务模式？

AT 模式（自动补偿）、TCC 模式（自定义补偿）、Saga 模式（长事务）。

---

# 面试技巧

## 1. 回答问题的STAR法则

- **S**ituation：描述背景
- **T**ask：说明目标
- **A**ction：具体行动
- **R**esult：结果和收获

## 2. 展示深度的技巧

- 不仅说"是什么"，还要说"为什么"
- 给出对比方案和选型建议
- 结合实际项目经验

## 3. 被追问时的应对

- 不要慌，追问说明面试官感兴趣
- 可以说"这个问题我了解得不够深入，但我的理解是..."
- 反问可以引导话题到自己的优势领域

---

## Q5: Deployment 和 StatefulSet 的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
Deployment 是无状态工作负载，Pod 名称随机可替换；StatefulSet 是有状态工作负载，Pod 名称固定有序（mysql-0/1/2），每个 Pod 有独立 PVC，适合数据库等有状态服务。

**展开答版：**

### 核心区别对比

| 特性     | Deployment             | StatefulSet                           |
| -------- | ---------------------- | ------------------------------------- |
| Pod 名称 | 随机（app-7d9f8b-xxx） | 固定有序（mysql-0/1/2）               |
| 存储     | 共享 PVC               | 每个 Pod 独立 PVC                     |
| 启动顺序 | 并行启动               | 有序启动（0→1→2）                     |
| 停止顺序 | 并行停止               | 逆序停止（2→1→0）                     |
| 网络标识 | 随机 IP                | 固定 DNS（mysql-0.mysql.default.svc） |
| 适用场景 | Web 服务、API          | MySQL、Redis、Kafka、ZooKeeper        |

### StatefulSet 关键配置

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
spec:
  serviceName: "mysql" # 必须指定 Headless Service
  replicas: 3
  volumeClaimTemplates: # 每个 Pod 独立 PVC（关键）
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 10Gi
```

**常见追问：**

- StatefulSet 删除 Pod 后数据会丢失吗？→ 不会，PVC 独立于 Pod 存在
- StatefulSet 能滚动更新吗？→ 能，但是逆序更新（2→1→0）
- Headless Service 是什么？→ ClusterIP=None，不分配虚拟IP，直接返回 Pod IP

---

## Q6: ConfigMap 和 Secret 的区别？（出现频率 :star::star::star::star:）

**30秒速答版：**
ConfigMap 存储非敏感配置（明文），Secret 存储敏感数据（base64编码，注意不是加密）。两者都支持环境变量和文件挂载，文件挂载方式支持热更新，环境变量方式不支持。

**展开答版：**

### 使用方式对比

```yaml
# 方式1：env（单个key）
env:
  - name: APP_ENV
    valueFrom:
      configMapKeyRef:
        name: app-config
        key: APP_ENV
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: app-secret
        key: DB_PASSWORD

# 方式2：envFrom（批量注入）
envFrom:
  - configMapRef:
      name: app-config

# 方式3：volumeMount（文件挂载，支持热更新）
volumeMounts:
  - name: config-volume
    mountPath: /app/config
```

### Secret 类型

| 类型                                | 用途                 |
| ----------------------------------- | -------------------- |
| Opaque                              | 通用（最常用）       |
| kubernetes.io/tls                   | TLS 证书             |
| kubernetes.io/dockerconfigjson      | 镜像仓库认证         |
| kubernetes.io/service-account-token | ServiceAccount Token |

**常见追问：**

- Secret 安全吗？→ base64 不是加密，生产推荐 Vault 或 KMS
- 如何强制热更新？→ `kubectl rollout restart deployment/xxx`
- ConfigMap 大小限制？→ 1MB

---

## Q7: PV/PVC/StorageClass 的关系？（出现频率 :star::star::star:）

**30秒速答版：**
StorageClass 是存储类型模板，PV 是实际存储资源，PVC 是用户的存储申请。用户创建 PVC 后，K8s 自动将其绑定到合适的 PV（或通过 StorageClass 动态创建 PV）。

**展开答版：**

### 绑定流程

```
StorageClass（存储类）
    ↓ 动态制备 PV
PV（Persistent Volume）← 集群管理员静态创建 或 StorageClass 动态创建
    ↑ 1:1 绑定
PVC（Persistent Volume Claim）← 用户申请
    ↑ 挂载
Pod
```

### 访问模式

| 模式                | 说明       | 场景                       |
| ------------------- | ---------- | -------------------------- |
| ReadWriteOnce (RWO) | 单节点读写 | 数据库（MySQL/PostgreSQL） |
| ReadWriteMany (RWX) | 多节点读写 | 共享文件（NFS/CephFS）     |
| ReadOnlyMany (ROX)  | 多节点只读 | 静态资源分发               |

**常见追问：**

- PVC 删除后 PV 怎么处理？→ 取决于 ReclaimPolicy（Retain/Delete/Recycle）
- 动态制备需要什么？→ 安装对应的 CSI 驱动和 StorageClass

---

## Q8: K8s 的资源限制（requests/limits）如何工作？（出现频率 :star::star::star::star:）

**30秒速答版：**
requests 是调度依据（kube-scheduler 根据 requests 选节点），limits 是运行上限（CPU 超出被 throttle，内存超出被 OOMKill）。QoS 等级影响 Pod 被驱逐的优先级：Guaranteed > Burstable > BestEffort。

**展开答版：**

### 配置示例

```yaml
resources:
  requests:
    cpu: "250m" # 0.25 核，调度依据
    memory: "256Mi" # 调度依据
  limits:
    cpu: "500m" # 超出被 CPU throttle（限速，不杀Pod）
    memory: "512Mi" # 超出被 OOMKill（直接杀Pod！）
```

### QoS 等级

| 等级       | 条件                                        | 被驱逐优先级       |
| ---------- | ------------------------------------------- | ------------------ |
| Guaranteed | requests == limits（CPU和内存都设置且相等） | 最低（最后被驱逐） |
| Burstable  | requests < limits                           | 中等               |
| BestEffort | 未设置 requests 和 limits                   | 最高（最先被驱逐） |

**常见追问：**

- CPU throttle 和 OOMKill 的区别？→ CPU 超限只是限速，内存超限直接杀进程
- 生产建议？→ 关键服务用 Guaranteed，一般服务用 Burstable，禁止 BestEffort

---

## Q9: K8s 网络模型（CNI）？（出现频率 :star::star::star:）

**30秒速答版：**
K8s 要求每个 Pod 有唯一IP，Pod 间可直接通信（不需要 NAT）。CNI 插件负责实现这个网络模型，常用的有 Flannel（简单）、Calico（高性能+网络策略）、Cilium（eBPF高性能）。

**展开答版：**

### CNI 插件对比

| 插件    | 实现方式      | 特点                       | 适用场景        |
| ------- | ------------- | -------------------------- | --------------- |
| Flannel | VXLAN overlay | 简单，性能一般             | 开发测试        |
| Calico  | BGP 路由      | 高性能，支持 NetworkPolicy | 生产推荐        |
| Cilium  | eBPF          | 最高性能，可观测性强       | 高性能/服务网格 |
| Weave   | overlay       | 支持加密                   | 安全要求高      |

**常见追问：**

- Pod 间通信的流量路径？→ Pod → veth pair → CNI bridge/路由 → 目标 Pod
- Service 的负载均衡怎么实现？→ kube-proxy 维护 iptables/ipvs 规则

---

## Q10: K8s 如何排查 Pod 启动失败？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
按顺序：`kubectl describe pod` 看 Events → `kubectl logs` 看容器日志 → `kubectl get events` 看集群事件 → `kubectl top node` 看资源使用。

**展开答版：**

### 常见错误状态及原因

| 状态             | 原因                                | 解决方案                                      |
| ---------------- | ----------------------------------- | --------------------------------------------- |
| Pending          | 资源不足/节点选择器不匹配/PVC未绑定 | 扩容节点/修改亲和性/检查PVC                   |
| ImagePullBackOff | 镜像不存在/仓库认证失败             | 检查镜像名称/配置imagePullSecrets             |
| CrashLoopBackOff | 容器启动后立即退出                  | 查看logs，检查启动命令/配置                   |
| OOMKilled        | 内存超出 limits                     | 增大 memory limits                            |
| Terminating      | Pod 卡在删除状态                    | `kubectl delete pod --force --grace-period=0` |

### 排查命令

```bash
# 第一步：看 Events（最重要）
kubectl describe pod <pod-name> -n <namespace>

# 第二步：看容器日志（--previous 看上次崩溃的日志）
kubectl logs <pod-name> -c <container-name> --previous

# 第三步：看集群事件
kubectl get events --sort-by=.lastTimestamp -n <namespace>

# 第四步：看节点资源
kubectl top node
kubectl describe node <node-name>
```

**常见追问：**

- CrashLoopBackOff 怎么看上次崩溃的日志？→ `kubectl logs --previous`
- Pod 一直 Terminating 怎么强制删除？→ `kubectl delete pod --force --grace-period=0`

---

# MySQL/PostgreSQL 高频面试题

## Q1: 索引的数据结构（B+树）及为什么用B+树？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
MySQL InnoDB 使用 B+树作为索引结构。B+树所有数据存在叶子节点，叶子节点通过链表相连，非叶子节点只存键值，树高低（通常3-4层），范围查询效率高。

**展开答版：**

### B树 vs B+树 对比

| 特性         | B树                  | B+树                           |
| ------------ | -------------------- | ------------------------------ |
| 数据存储位置 | 所有节点             | 只在叶子节点                   |
| 叶子节点链表 | 无                   | 有（双向链表）                 |
| 范围查询     | 需要中序遍历         | 直接遍历叶子链表（高效）       |
| 单次查询     | 可能在非叶子节点命中 | 必须到叶子节点                 |
| 树高         | 相对较高             | 相对较低（非叶子节点存更多键） |

### 为什么用B+树？

1. **磁盘IO少**：非叶子节点只存键值，单个节点存更多键，树高更低（3-4层可存千万数据）
2. **范围查询高效**：叶子节点链表，范围扫描只需遍历链表
3. **全表扫描高效**：叶子节点链表天然支持顺序扫描
4. **查询稳定**：每次查询都到叶子节点，时间复杂度稳定 O(log n)

**常见追问：**

- 为什么不用哈希索引？→ 不支持范围查询，不支持排序
- 为什么不用红黑树？→ 树高太高，磁盘IO次数多
- B+树一个节点多大？→ 默认16KB（InnoDB page size）

---

## Q2: 聚簇索引 vs 非聚簇索引？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
聚簇索引（主键索引）的叶子节点存储完整行数据；非聚簇索引（二级索引）的叶子节点存储主键值，查询时需要回表（再查一次主键索引）。

**展开答版：**

### 聚簇索引（Clustered Index）

- InnoDB 中主键就是聚簇索引
- 叶子节点存储**完整行数据**
- 每张表只能有一个聚簇索引
- 数据按主键顺序物理存储

### 非聚簇索引（Secondary Index）

- 叶子节点存储**主键值**（不是完整数据）
- 查询时需要**回表**：先查二级索引找到主键，再查主键索引找完整数据
- 一张表可以有多个非聚簇索引

### 回表示例

```sql
-- 假设有索引 idx_name(name)
SELECT * FROM users WHERE name = 'zhangsan';
-- 执行过程：
-- 1. 查 idx_name 索引，找到 name='zhangsan' 对应的主键 id=100
-- 2. 回表：查主键索引，找到 id=100 的完整行数据（共2次B+树查询）
```

**常见追问：**

- 为什么建议用自增主键？→ 顺序插入，避免页分裂，B+树维护成本低
- UUID 做主键的问题？→ 随机插入导致频繁页分裂，性能差

---

## Q3: 覆盖索引是什么？（出现频率 :star::star::star::star:）

**30秒速答版：**
覆盖索引是指查询所需的所有列都在索引中，不需要回表查主键索引。EXPLAIN 中 Extra 列显示 "Using index" 表示使用了覆盖索引。

**展开答版：**

```sql
-- 创建联合索引
CREATE INDEX idx_name_age ON users(name, age);

-- 覆盖索引（只查 name 和 age，索引已包含）
SELECT name, age FROM users WHERE name = 'zhangsan';
-- EXPLAIN: Extra = Using index（不需要回表）

-- 非覆盖索引（查了 email，索引不包含）
SELECT name, age, email FROM users WHERE name = 'zhangsan';
-- EXPLAIN: Extra = NULL（需要回表）
```

**常见追问：**

- 如何判断是否用了覆盖索引？→ EXPLAIN 的 Extra 列看 "Using index"
- 联合索引的列顺序有影响吗？→ 有，遵循最左前缀原则

---

## Q4: 索引失效的场景？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
索引失效的主要场景：对索引列使用函数/计算、隐式类型转换、LIKE 前缀通配符（`%abc`）、OR 条件（部分情况）、违反最左前缀原则。

**展开答版：**

```sql
-- 1. 对索引列使用函数（失效）
SELECT * FROM users WHERE YEAR(create_time) = 2024;
-- 优化：WHERE create_time BETWEEN '2024-01-01' AND '2024-12-31'

-- 2. 隐式类型转换（失效，phone 是 VARCHAR 类型）
SELECT * FROM users WHERE phone = 13800138000;
-- 优化：WHERE phone = '13800138000'

-- 3. LIKE 前缀通配符（失效）
SELECT * FROM users WHERE name LIKE '%zhang';   -- 前缀%，失效
SELECT * FROM users WHERE name LIKE 'zhang%';   -- 后缀%，有效

-- 4. 违反最左前缀原则（联合索引 idx_a_b_c）
SELECT * FROM users WHERE b = 1 AND c = 2;      -- 跳过a，失效
SELECT * FROM users WHERE a = 1 AND c = 2;      -- 跳过b，c失效（a有效）

-- 5. 范围查询后的列失效（联合索引 idx_a_b_c）
SELECT * FROM users WHERE a = 1 AND b > 10 AND c = 3;
-- a 和 b 走索引，c 不走索引（b 是范围查询，后面的列失效）
```

**常见追问：**

- 如何验证索引是否生效？→ EXPLAIN 查看 type 列（ref/range 好，ALL 差）
- 索引失效后怎么强制走索引？→ `FORCE INDEX(idx_name)`（不推荐，应优化SQL）

---

## Q5: EXPLAIN 如何分析慢查询？（出现频率 :star::star::star::star:）

**30秒速答版：**
EXPLAIN 输出中重点看：type（访问类型，ALL最差）、key（实际使用的索引）、rows（预估扫描行数）、Extra（Using filesort/Using temporary 是警告信号）。

**展开答版：**

### EXPLAIN 关键字段

| 字段  | 说明           | 优化目标                                            |
| ----- | -------------- | --------------------------------------------------- |
| type  | 访问类型       | system > const > eq_ref > ref > range > index > ALL |
| key   | 实际使用的索引 | 不为 NULL                                           |
| rows  | 预估扫描行数   | 越小越好                                            |
| Extra | 额外信息       | 避免 Using filesort / Using temporary               |

### type 类型说明

```
system  → 表只有一行（最优）
const   → 主键/唯一索引等值查询
eq_ref  → JOIN 时主键/唯一索引
ref     → 普通索引等值查询
range   → 索引范围查询（BETWEEN/>/< 等）
index   → 全索引扫描（比 ALL 好，但仍需优化）
ALL     → 全表扫描（最差，必须优化）
```

**常见追问：**

- rows 很大但查询很快，正常吗？→ 正常，rows 是预估值，实际可能更少
- Using filesort 一定要优化吗？→ 不一定，小数据量影响不大，大数据量必须优化

---

## Q6: 事务的四大特性（ACID）？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
A（原子性）：事务要么全成功要么全失败，由 undo log 保证；C（一致性）：事务前后数据满足约束，由其他三个特性共同保证；I（隔离性）：事务间互不干扰，由 MVCC + 锁保证；D（持久性）：提交后数据永久保存，由 redo log 保证。

**展开答版：**

### ACID 实现机制

| 特性                  | 实现机制            | 说明                              |
| --------------------- | ------------------- | --------------------------------- |
| 原子性（Atomicity）   | undo log            | 回滚时用 undo log 撤销操作        |
| 一致性（Consistency） | 其他三个特性 + 约束 | 业务逻辑保证数据合法性            |
| 隔离性（Isolation）   | MVCC + 锁           | 不同隔离级别有不同实现            |
| 持久性（Durability）  | redo log + WAL      | 提交前先写 redo log，崩溃后可恢复 |

**常见追问：**

- 为什么需要 redo log，直接刷磁盘不行吗？→ 随机IO太慢，redo log 是顺序IO
- undo log 存在哪里？→ 系统表空间（ibdata）或独立 undo 表空间

---

## Q7: 事务隔离级别及对应的问题？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
四个隔离级别：读未提交（脏读）→ 读已提交（不可重复读）→ 可重复读（幻读，MySQL默认）→ 串行化（无并发问题）。MySQL InnoDB 默认可重复读，通过 MVCC 解决了大部分幻读问题。

**展开答版：**

### 隔离级别对比

| 隔离级别                | 脏读 | 不可重复读 | 幻读   | 性能 |
| ----------------------- | ---- | ---------- | ------ | ---- |
| READ UNCOMMITTED        | 有   | 有         | 有     | 最高 |
| READ COMMITTED          | 无   | 有         | 有     | 高   |
| REPEATABLE READ（默认） | 无   | 无         | 部分有 | 中   |
| SERIALIZABLE            | 无   | 无         | 无     | 最低 |

**常见追问：**

- MySQL 默认隔离级别是什么？→ REPEATABLE READ
- 如何查看隔离级别？→ `SELECT @@transaction_isolation;`
- MySQL InnoDB 如何解决幻读？→ 快照读用 MVCC，当前读用 Next-Key Lock

---

## Q8: MVCC 原理？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
MVCC（多版本并发控制）通过 ReadView + 隐藏字段（trx_id + roll_pointer）实现非阻塞读。每行数据有版本链，读操作根据 ReadView 判断哪个版本可见，写操作不阻塞读操作。

**展开答版：**

### 核心组件

```
隐藏字段：
  - trx_id：最近修改该行的事务ID
  - roll_pointer：指向 undo log 中的上一个版本

ReadView（读视图）：
  - m_ids：当前活跃（未提交）的事务ID列表
  - min_trx_id：m_ids 中最小的事务ID
  - max_trx_id：下一个将分配的事务ID
  - creator_trx_id：创建 ReadView 的事务ID
```

### RC vs RR 的区别

- READ COMMITTED：每次 SELECT 都生成新的 ReadView（能读到最新提交）
- REPEATABLE READ：事务开始时生成 ReadView，整个事务复用（保证可重复读）

**常见追问：**

- MVCC 解决了什么问题？→ 读写不互相阻塞，提高并发性能
- MVCC 能完全解决幻读吗？→ 快照读可以，当前读需要 Next-Key Lock

---

## Q9: 死锁如何产生和解决？（出现频率 :star::star::star::star:）

**30秒速答版：**
死锁是两个事务互相等待对方持有的锁。MySQL InnoDB 有死锁检测机制，自动回滚代价小的事务。预防死锁：固定加锁顺序、缩短事务、使用索引避免全表锁。

**展开答版：**

### 死锁产生示例

```sql
-- 事务1
BEGIN;
UPDATE accounts SET balance=balance-100 WHERE id=1;  -- 锁住 id=1
UPDATE accounts SET balance=balance+100 WHERE id=2;  -- 等待 id=2 的锁

-- 事务2（同时执行）
BEGIN;
UPDATE accounts SET balance=balance-100 WHERE id=2;  -- 锁住 id=2
UPDATE accounts SET balance=balance+100 WHERE id=1;  -- 等待 id=1 的锁
-- 结果：死锁！MySQL 自动回滚其中一个事务
```

### 预防死锁的方法

1. **固定加锁顺序**：所有事务按相同顺序访问资源（id 从小到大）
2. **缩短事务**：减少事务持有锁的时间
3. **使用索引**：避免全表扫描导致的大范围锁
4. **一次性加锁**：`SELECT ... FOR UPDATE` 一次锁定所有需要的行

**常见追问：**

- MySQL 如何检测死锁？→ 等待图（Wait-for Graph）检测环路
- 死锁和锁等待超时的区别？→ 死锁立即检测回滚，锁等待超时由 innodb_lock_wait_timeout 控制

---

## Q10: 分库分表的策略？（出现频率 :star::star::star::star:）

**30秒速答版：**
分库分表分为垂直分（按业务拆分）和水平分（按数据量拆分）。水平分表常用策略：取模分片（均匀分布）、范围分片（便于扩容）、一致性哈希（减少迁移）。

**展开答版：**

### 水平分片策略对比

| 策略       | 方式            | 优点             | 缺点               |
| ---------- | --------------- | ---------------- | ------------------ |
| 取模分片   | user_id % 16    | 数据均匀         | 扩容需迁移数据     |
| 范围分片   | id 1-100w → 表1 | 便于扩容         | 数据不均匀（热点） |
| 一致性哈希 | 哈希环          | 扩容迁移少       | 实现复杂           |
| 按时间分片 | 按月/年建表     | 历史数据归档方便 | 当前月热点         |

### 分库分表带来的问题

```
1. 分布式事务：跨库操作无法用本地事务
   → 解决：Seata AT 模式 / 最终一致性

2. 跨库 JOIN：无法直接 JOIN 不同库的表
   → 解决：冗余字段 / 应用层 JOIN / 数据同步到 ES

3. 全局唯一ID：自增主键在多表中会重复
   → 解决：雪花算法 / 号段模式 / Redis 自增

4. 分页查询：ORDER BY + LIMIT 需要汇总多个分片
   → 解决：禁止深分页 / 游标分页 / ES 搜索
```

**常见追问：**

- 什么时候考虑分库分表？→ 单表超过 500w 行或 2GB，查询变慢时
- 分库分表用什么中间件？→ ShardingSphere（推荐）、MyCat
- 如何选择分片键？→ 选择查询频率最高的字段，避免热点，数据分布均匀
