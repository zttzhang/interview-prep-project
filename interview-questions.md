# MyBatis 高频面试题

## Q1：#{} 和 ${} 的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版（背这个）：**
#{} 是预编译占位符，MyBatis会将其替换为?并通过PreparedStatement设置参数，可以防止SQL注入；${} 是字符串直接替换，有SQL注入风险，但在动态表名、列名场景下必须用${}。

**展开答版（追问时用）：**

### 原理区别
| 特性 | #{} | ${} |
|------|-----|-----|
| SQL处理 | 预编译处理，参数绑定到?占位符 | 直接字符串替换 |
| SQL注入 | 安全，防注入 | 危险，不防注入 |
| 数据类型 | 自动处理，加引号 | 直接替换，不加引号 |
| 适用场景 | 普通参数 | 动态表名/列名、ORDER BY |

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
| 特性 | 说明 |
|------|------|
| 作用域 | SqlSession |
| 存储位置 | PerpetualCache（HashMap） |
| 生命周期 | SqlSession创建到关闭 |
| 失效场景 | commit/close/clearCache |
| 默认状态 | 开启 |

```java
// 验证一级缓存
User u1 = userMapper.selectById(1); // 查DB
User u2 = userMapper.selectById(1); // 命中缓存，不查DB
```

### 二级缓存
| 特性 | 说明 |
|------|------|
| 作用域 | Mapper Namespace |
| 存储位置 | CachingExecutor |
| 生命周期 | 应用启动到停止 |
| 失效场景 | 该Namespace任意写操作 |
| 默认状态 | 关闭，需配置 |

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
    lazy-loading-enabled: true  # 开启懒加载
    aggressive-lazy-loading: false  # 关闭积极加载
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
| 方案 | 原子性 | 防误删 | 锁续期 | 推荐指数 |
|------|--------|--------|--------|---------|
| SETNX+EXPIRE | :x: | :x: | :x: | :star: |
| SET NX EX + UUID | :white_check_mark: | :white_check_mark: | :x: | :star::star::star: |
| Lua脚本释放 | :white_check_mark: | :white_check_mark: | :x: | :star::star::star::star: |
| Redisson | :white_check_mark: | :white_check_mark: | :white_check_mark: | :star::star::star::star::star: |

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
| 问题 | 现象 | 原因 |
|------|------|------|
| 缓存穿透 | 查询不存在的key，打到DB | 恶意攻击或业务漏洞 |
| 缓存击穿 | 热点key过期，大量请求打到DB | 并发访问热点数据 |
| 缓存雪崩 | 大量key同时过期 | 统一TTL设置或Redis宕机 |

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
| 类型 | 结构 | 典型场景 |
|------|------|----------|
| String | KV | 缓存、计数器、Session、分布式锁 |
| Hash | Field-Value | 购物车、对象属性、用户信息 |
| List | 有序列表 | 消息队列、最新商品、关注列表 |
| Set | 无序去重 | 标签、共同好友、UV统计 |
| ZSet | 有序去重 | 排行榜、延迟队列、权重队列 |

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
| 方案 | 优点 | 缺点 |
|------|------|------|
| 数据库唯一索引 | 简单可靠 | 性能略低 |
| Redis Setnx | 性能好 | 双写一致性问题 |
| 消息表+状态机 | 最可靠 | 实现复杂 |

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
| 原因 | 表现 | 解决方案 |
|------|------|----------|
| 资源不足（CPU/内存） | NoSchedule | 扩容节点或降低资源请求 |
| 节点选择器不匹配 | node(s) didn't match | 检查 nodeSelector/亲和性 |
| PVC 未绑定 | Pending | 检查 PV 和 StorageClass |
| 镜像拉取失败 | ErrImagePull | 检查 imagePullPolicy 和仓库权限 |
| 污点不匹配 | node(s) had taints | 添加 tolerations |

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
    maxSurge: 1          # 最多额外1个Pod
    maxUnavailable: 0     # 【关键】零停机：不能有不可用Pod
```

**常见追问：**
- maxSurge=0,maxUnavailable=1 是什么？→ 先删后建，有短暂容量下降
- 如何回滚？→ `kubectl rollout undo deployment/xxx`

---

## Q3: 三种探针的区别？（出现频率 :star::star::star::star::star:）

**30秒速答版：**
livenessProbe检测是否存活（失败重启），readinessProbe检测是否就绪（失败摘流），startupProbe保护慢启动应用。

**展开答版：**

| 探针 | 失败行为 | 适用场景 |
|------|----------|----------|
| livenessProbe | 重启容器 | 进程假死、死锁、OOM |
| readinessProbe | 摘除流量（不重启） | 启动中、依赖不可用、过载 |
| startupProbe | 禁用其他探针直到成功 | 应用启动慢（60s+） |

### 配置示例
```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  initialDelaySeconds: 30   # 等启动完成
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  failureThreshold: 2        # 恢复敏感

startupProbe:
  httpGet:
    path: /health
    port: 8080
  failureThreshold: 30         # 30*10s = 300s保护
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
        averageUtilization: 70  # CPU>70%时扩容
  
  # 扩缩行为（避免抖动）
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 缩容等待5分钟
    scaleUp:
      stabilizationWindowSeconds: 0    # 扩容立即响应
```

---

# 更多面试题

## Spring Boot 相关

### Q13：Spring Boot 自动装配原理？
核心是 `spring.factories` 和 `@Configuration` 注解。启动时扫描 `META-INF/spring.factories` 中的配置类，加载并注册Bean。

### Q14：Spring 事务传播行为？
| 传播行为 | 说明 |
|---------|------|
| REQUIRED | 默认，有事务加入，无则新建 |
| REQUIRES_NEW | 挂起当前事务，新建独立事务 |
| NESTED | 嵌套事务（savepoint） |

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