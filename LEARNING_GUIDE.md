# 🚀 Spring Boot 面试教学工程 - 学习指南

## 📋 学习路径（建议按顺序进行）

---

### 第一步：环境准备

```bash
# 1. 确认已安装必要工具
# - JDK 17+
# - Maven 3.8+
# - Docker Desktop

# 2. 进入工程目录
cd d:/projects/06-bmw/interview-prep-project

# 3. 启动中间件（PostgreSQL + Redis + Kafka）
docker-compose up -d

# 4. 验证服务状态
docker-compose ps
# 应该看到 postgres、redis、kafka 都是 healthy 状态
```

---

### 第二步：编译工程

```bash
# 1. 编译整个项目
mvn clean compile

# 2. 编译成功后会看到 BUILD SUCCESS
```

---

### 第三步：按模块学习（建议顺序）

#### 📚 模块1：MyBatis 核心

**先读代码，再跑测试：**
```bash
# 运行测试
mvn test -pl module-mybatis -Dtest=SqlInjectionTest
```

**学习要点：**
- `#{}` vs `${}` 的区别（防SQL注入）
- LIKE 查询的正确写法

---

#### 📚 模块2：Redis 分布式锁

**学习要点（分布式锁演进）：**

| 阶段 | 问题 | 解决方案 |
|------|------|----------|
| SETNX+EXPIRE | 非原子，可能死锁 | ❌ 不推荐 |
| SET NX EX + UUID | 解决了原子，但有锁续期问题 | ⚠️ 基础方案 |
| Lua脚本释放 | 解决了误删问题 | ✅ 可用 |
| Redisson | 完整解决方案（推荐生产使用） | ✅✅ 生产首选 |

---

#### 📚 模块3：Redis 缓存问题

**三大缓存问题速记：**

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| **穿透** | 不存在的数据被打到DB | 布隆过滤器 / 缓存空值 |
| **击穿** | 热点key过期，大量打到DB | 互斥锁 / 逻辑过期 |
| **雪崩** | 大量key同时过期 | 随机TTL / 多级缓存 |

---

#### 📚 模块4：Kafka 消息可靠性

**可靠性三板斧：**
```java
acks=all        // 所有ISR副本确认，不丢消息
retries=3       // 失败重试（需开启幂等）
idempotence=true // 幂等发送，防止重复
```

---

#### 📚 模块5：秒杀系统（综合场景）

**完整链路：**
```
用户请求 
    ↓
Redis Lua原子扣减库存（不超卖）
    ↓
Kafka异步下单（快速响应）
    ↓
Consumer幂等处理（不重复）
    ↓
订单入库
```

---

### 第四步：背面试题

**重点背诵（出现频率最高）：**
- Q1: `#{}` vs `${}` 的区别（⭐⭐⭐⭐⭐）
- Q4: Redis分布式锁方案对比（⭐⭐⭐⭐⭐）
- Q5: 缓存穿透/击穿/雪崩（⭐⭐⭐⭐⭐）
- Q7: Kafka消息不丢失配置（⭐⭐⭐⭐⭐）
- Q10: 如何设计秒杀系统（⭐⭐⭐⭐⭐）

---

### 第五步：速查表复习

**面试前快速过一遍所有速记代码**
- 包含：Redis命令、Kafka配置、MyBatis SQL等

---

## 📅 推荐学习计划

| 天数 | 内容 | 目标 |
|------|------|------|
| 第1天 | MyBatis + 缓存 | 理解#{}vs${}，缓存问题 |
| 第2天 | Redis分布式锁 | 掌握锁演进，会手写基本锁 |
| 第3天 | Kafka可靠性 | 理解消息不丢失配置 |
| 第4天 | 秒杀系统 | 串联所有知识点 |
| 第5天 | K8s配置 | 理解探针、滚动更新 |
| 第6-7天 | 面试题背诵 | 重点题目30秒速记 |

---

## 🔍 常见问题

**Q: Docker启动失败？**
```bash
# 检查Docker是否运行
docker version

# 查看日志
docker-compose logs postgres
```

**Q: Maven编译报错？**
```bash
# 确保在工程根目录
cd d:/projects/06-bmw/interview-prep-project

# 清理后重试
mvn clean compile -DskipTests
```

---

## 📂 关键文件速查

| 模块 | 关键文件 | 面试考点 |
|------|---------|---------|
| MyBatis | `module-mybatis/...SqlInjectionTest.java` | #{} vs ${} |
| Redis锁 | `module-redis/...lock/BadLockDemo.java` | 分布式锁演进 |
| Redis缓存 | `module-redis/...cache/CacheBreakdownDemo.java` | 穿透/击穿/雪崩 |
| Kafka | `module-kafka/...config/KafkaProducerConfig.java` | 消息可靠性 |
| 秒杀 | `module-integration/...seckill/SeckillService.java` | 完整链路 |
| K8s | `module-k8s/probe-demo.yaml` | 三种探针 |

---

## ✅ 验收标准

学完后能回答：
1. `#{}` 和 `${}` 的区别？什么时候用 `${}`？
2. 分布式锁的演进过程？Redisson解决了什么问题？
3. 缓存穿透、击穿、雪崩的区别？
4. Kafka如何保证消息不丢失？
5. 秒杀系统的完整链路？

---

## 📚 学习资源

- `interview-questions.md` - 完整面试题库
- `cheatsheet.md` - 速查表
- `br.md` - 工程需求文档