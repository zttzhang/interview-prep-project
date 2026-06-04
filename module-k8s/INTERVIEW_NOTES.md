# K8s 面试要点总结

> 本文档是 K8s 面试的核心复习资料，每个知识点都配有"30秒速答版"和"展开答版"。
> 相关 YAML 示例见：`deployment.yaml`、`probe-demo.yaml`、`service.yaml`、`hpa.yaml`、`configmap.yaml`

---

## 1. K8s 核心概念

### Pod / Node / Namespace / Label / Selector

| 概念          | 定义                                 | 关键点                               |
| ------------- | ------------------------------------ | ------------------------------------ |
| **Pod**       | K8s 最小调度单元，包含一个或多个容器 | 共享网络命名空间和存储卷             |
| **Node**      | 工作节点，运行 Pod 的物理/虚拟机     | 包含 kubelet、kube-proxy、容器运行时 |
| **Namespace** | 逻辑隔离单元，用于多租户/环境隔离    | 资源配额、RBAC 都基于 Namespace      |
| **Label**     | 键值对标签，附加在资源对象上         | `app: interview-app`、`env: prod`    |
| **Selector**  | 通过 Label 筛选资源                  | Service 通过 selector 找到目标 Pod   |

```yaml
# Pod 示例（最小单元）
apiVersion: v1
kind: Pod
metadata:
  name: interview-app
  labels:
    app: interview-app # Label
    version: v1.0
spec:
  containers:
    - name: app
      image: interview-app:latest
      ports:
        - containerPort: 8080
```

**面试速答**：Pod 是 K8s 最小调度单元，一个 Pod 内的容器共享网络（同一IP）和存储，适合紧耦合的容器组合（如应用+日志收集 sidecar）。

---

## 2. 工作负载对比

### Deployment / StatefulSet / DaemonSet / Job / CronJob

| 工作负载        | 特点                                   | 典型场景                                             |
| --------------- | -------------------------------------- | ---------------------------------------------------- |
| **Deployment**  | 无状态，Pod 可随意替换，支持滚动更新   | Web 服务、API 服务、微服务                           |
| **StatefulSet** | 有状态，Pod 有固定名称和存储，有序启停 | MySQL、Redis、Kafka、ZooKeeper                       |
| **DaemonSet**   | 每个节点运行一个 Pod                   | 日志收集（Fluentd）、监控（Node Exporter）、网络插件 |
| **Job**         | 一次性任务，完成后 Pod 退出            | 数据迁移、批量处理、初始化任务                       |
| **CronJob**     | 定时任务，基于 cron 表达式             | 定时报表、定时清理、定时备份                         |

```yaml
# StatefulSet 关键特性
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
spec:
  serviceName: "mysql" # 必须指定 headless service
  replicas: 3
  selector:
    matchLabels:
      app: mysql
  template:
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
  volumeClaimTemplates: # 每个 Pod 独立的 PVC
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 10Gi
# StatefulSet Pod 命名规则：mysql-0, mysql-1, mysql-2（固定且有序）
```

**Deployment vs StatefulSet 核心区别**：

- Deployment：Pod 名称随机（`app-7d9f8b-xxx`），可随意替换，共享存储
- StatefulSet：Pod 名称固定（`mysql-0`），有序启动/停止，每个 Pod 独立存储

---

## 3. Service 网络

### 四种 Service 类型

| 类型             | 访问范围                   | 场景                           |
| ---------------- | -------------------------- | ------------------------------ |
| **ClusterIP**    | 仅集群内部                 | 微服务内部调用（默认，最常用） |
| **NodePort**     | 节点IP:端口（30000-32767） | 开发测试环境                   |
| **LoadBalancer** | 云厂商外部负载均衡器       | 生产环境对外暴露               |
| **ExternalName** | 映射到外部 DNS             | 访问集群外部服务               |

### Ingress

```yaml
# Ingress：HTTP/HTTPS 路由规则（多服务共享一个外部IP）
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: interview-ingress
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: api-service
                port:
                  number: 80
          - path: /web
            pathType: Prefix
            backend:
              service:
                name: web-service
                port:
                  number: 80
```

### DNS 解析规则

K8s 内部 DNS 格式：`<service-name>.<namespace>.svc.cluster.local`

```
# 同 Namespace 内访问（可省略后缀）
http://interview-app-clusterip:80

# 跨 Namespace 访问
http://interview-app-clusterip.default.svc.cluster.local:80

# StatefulSet Pod 直接访问（通过 Headless Service）
http://mysql-0.mysql.default.svc.cluster.local:3306
```

---

## 4. 存储

### PV / PVC / StorageClass

```
StorageClass（存储类）
    ↓ 动态制备
PV（Persistent Volume）← 集群管理员创建（静态）或 StorageClass 动态创建
    ↑ 绑定
PVC（Persistent Volume Claim）← 用户申请存储
    ↑ 挂载
Pod
```

| 概念             | 角色             | 说明                                     |
| ---------------- | ---------------- | ---------------------------------------- |
| **PV**           | 集群级别存储资源 | 管理员预先创建，或 StorageClass 动态创建 |
| **PVC**          | 用户存储申请     | 声明需要多大、什么访问模式的存储         |
| **StorageClass** | 存储类型模板     | 定义存储提供商和参数，支持动态制备       |

```yaml
# PVC 示例
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: interview-data
spec:
  accessModes:
    - ReadWriteOnce # RWO: 单节点读写 | RWX: 多节点读写 | ROX: 多节点只读
  storageClassName: standard
  resources:
    requests:
      storage: 5Gi
```

### 存储卷类型对比

| 类型          | 生命周期          | 场景                                    |
| ------------- | ----------------- | --------------------------------------- |
| **emptyDir**  | 与 Pod 同生命周期 | 临时缓存、Pod 内容器间共享数据          |
| **hostPath**  | 与节点绑定        | 访问节点文件系统（日志、Docker socket） |
| **ConfigMap** | 配置文件挂载      | 应用配置文件                            |
| **Secret**    | 敏感数据挂载      | 证书、密码                              |
| **PVC**       | 独立于 Pod        | 数据库数据、持久化文件                  |

---

## 5. 配置管理

### ConfigMap vs Secret vs 环境变量

| 方式                    | 安全性 | 热更新              | 场景                     |
| ----------------------- | ------ | ------------------- | ------------------------ |
| **ConfigMap（env）**    | 明文   | ❌ 不支持           | 非敏感配置，重启生效     |
| **ConfigMap（volume）** | 明文   | ✅ 支持（~60s延迟） | 配置文件，应用监听变化   |
| **Secret（env）**       | base64 | ❌ 不支持           | 敏感配置，重启生效       |
| **Secret（volume）**    | base64 | ✅ 支持             | 证书、密钥文件           |
| **环境变量（直接写）**  | 明文   | ❌ 不支持           | 简单场景，不推荐敏感数据 |

**生产安全方案**：ConfigMap + Secret + External Secrets Operator（对接 Vault/KMS）

---

## 6. 弹性伸缩

### HPA / VPA / KEDA

| 方案     | 扩缩维度             | 触发条件                    | 适用场景                   |
| -------- | -------------------- | --------------------------- | -------------------------- |
| **HPA**  | 水平（Pod 数量）     | CPU/内存/自定义指标         | 无状态服务，流量波动       |
| **VPA**  | 垂直（CPU/内存规格） | 历史资源使用分析            | 资源难以预估的服务         |
| **KEDA** | 水平（支持缩到0）    | 事件驱动（Kafka/队列/Cron） | 消息驱动、批处理、定时任务 |

### HPA 扩缩容公式

```
期望副本数 = ceil(当前副本数 × 当前指标值 / 目标指标值)

示例：
  当前副本数=2，当前CPU=90%，目标CPU=70%
  期望副本数 = ceil(2 × 90 / 70) = ceil(2.57) = 3 → 扩容到3个Pod
```

### 防抖动机制（stabilizationWindow）

```yaml
behavior:
  scaleUp:
    stabilizationWindowSeconds: 60 # 扩容：60s内取最大期望副本数
  scaleDown:
    stabilizationWindowSeconds: 300 # 缩容：300s内取最小期望副本数（防止频繁缩容）
```

---

## 7. 健康检查

### livenessProbe / readinessProbe / startupProbe

| 探针               | 失败行为             | 检测目标         | 典型场景                  |
| ------------------ | -------------------- | ---------------- | ------------------------- |
| **livenessProbe**  | 重启容器             | 进程是否存活     | 死锁、OOM、进程假死       |
| **readinessProbe** | 摘除流量（不重启）   | 是否能处理请求   | 启动中、依赖不可用、过载  |
| **startupProbe**   | 禁用其他探针直到成功 | 应用是否完成启动 | 启动慢的应用（JVM预热等） |

```yaml
# 三种探针配置示例（见 probe-demo.yaml）
startupProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  failureThreshold: 30 # 最多等待 30×10s = 300s
  periodSeconds: 10

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 0 # startupProbe 成功后才启动
  periodSeconds: 10
  failureThreshold: 3 # 连续3次失败才重启

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  periodSeconds: 5
  failureThreshold: 2 # 2次失败就摘流（恢复敏感）
```

**面试速答**：三种探针的核心区别在于**失败行为**：liveness 失败重启容器，readiness 失败摘除流量但不重启，startup 用于保护慢启动应用（防止 liveness 误杀）。

---

## 8. 滚动更新

### RollingUpdate 策略

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1 # 最多额外创建1个Pod（超出期望副本数）
    maxUnavailable: 0 # 【零停机关键】不允许有不可用Pod
```

### maxSurge vs maxUnavailable 组合

| 组合                               | 行为                 | 适用场景                   |
| ---------------------------------- | -------------------- | -------------------------- |
| `maxSurge=1, maxUnavailable=0`     | 先建新Pod，再删旧Pod | **生产推荐**，零停机       |
| `maxSurge=0, maxUnavailable=1`     | 先删旧Pod，再建新Pod | 资源紧张时，有短暂容量下降 |
| `maxSurge=25%, maxUnavailable=25%` | 默认值，混合策略     | 一般场景                   |

### 回滚操作

```bash
# 查看发布历史
kubectl rollout history deployment/interview-app

# 回滚到上一版本
kubectl rollout undo deployment/interview-app

# 回滚到指定版本
kubectl rollout undo deployment/interview-app --to-revision=2

# 查看回滚状态
kubectl rollout status deployment/interview-app
```

---

## 9. 资源管理

### requests / limits / QoS 等级

```yaml
resources:
  requests:
    cpu: "250m" # 调度依据：保证分配250m CPU
    memory: "256Mi" # 调度依据：保证分配256Mi内存
  limits:
    cpu: "500m" # 上限：最多使用500m CPU（超出被throttle）
    memory: "512Mi" # 上限：最多使用512Mi内存（超出被OOMKill）
```

### QoS 等级（影响 Pod 被驱逐的优先级）

| QoS 等级       | 条件                                        | 被驱逐优先级       |
| -------------- | ------------------------------------------- | ------------------ |
| **Guaranteed** | requests == limits（CPU和内存都设置且相等） | 最低（最后被驱逐） |
| **Burstable**  | requests < limits（或只设置了其中一个）     | 中等               |
| **BestEffort** | 未设置 requests 和 limits                   | 最高（最先被驱逐） |

**生产建议**：

- 关键服务设置 Guaranteed QoS（requests == limits）
- 一般服务设置 Burstable QoS（requests < limits，允许突发）
- 不要使用 BestEffort（资源紧张时最先被杀）

### LimitRange 和 ResourceQuota

```yaml
# LimitRange：为 Namespace 内的 Pod 设置默认资源限制
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
spec:
  limits:
    - type: Container
      default:
        cpu: "500m"
        memory: "256Mi"
      defaultRequest:
        cpu: "100m"
        memory: "128Mi"

---
# ResourceQuota：限制 Namespace 的总资源使用量
apiVersion: v1
kind: ResourceQuota
metadata:
  name: namespace-quota
spec:
  hard:
    requests.cpu: "4"
    requests.memory: "8Gi"
    limits.cpu: "8"
    limits.memory: "16Gi"
    pods: "20"
```

---

## 10. 高频面试题 Q&A

### Q1：Pod 和容器的区别？

**速答**：Pod 是 K8s 最小调度单元，一个 Pod 可以包含多个容器。同一 Pod 内的容器共享网络命名空间（同一IP）、IPC 和存储卷，但有独立的文件系统。

**追问**：什么时候在一个 Pod 里放多个容器？
→ Sidecar 模式：主容器 + 日志收集容器（共享日志目录）
→ Init 容器：初始化完成后退出，主容器才启动

---

### Q2：Deployment 和 StatefulSet 的区别？

**速答**：

- Deployment：无状态，Pod 名称随机，可随意替换，适合 Web 服务
- StatefulSet：有状态，Pod 名称固定（`mysql-0/1/2`），有序启停，每个 Pod 独立 PVC，适合数据库

**核心区别**：

1. Pod 标识：Deployment 随机 vs StatefulSet 固定有序
2. 存储：Deployment 共享 vs StatefulSet 独立 PVC
3. 启停顺序：Deployment 并行 vs StatefulSet 有序（0→1→2 启动，2→1→0 停止）
4. 网络：StatefulSet 需要 Headless Service，Pod 有固定 DNS

---

### Q3：Service 四种类型及使用场景？

**速答**：

- ClusterIP：集群内部通信，微服务互调（默认，最常用）
- NodePort：通过节点IP:端口外部访问，开发测试用
- LoadBalancer：云厂商外部LB，生产对外暴露
- ExternalName：映射外部DNS，访问集群外服务

**追问**：Ingress 和 LoadBalancer 的区别？
→ LoadBalancer 每个 Service 独立一个外部IP（成本高）
→ Ingress 多个 Service 共享一个外部IP，通过路径/域名路由（推荐）

---

### Q4：K8s 如何实现滚动更新？

**速答**：Deployment 的 RollingUpdate 策略，通过 `maxSurge` 和 `maxUnavailable` 控制更新节奏。零停机配置：`maxSurge=1, maxUnavailable=0`，配合 readinessProbe 确保新 Pod 就绪后才切流量。

**更新流程**：

1. 创建新版本 Pod（maxSurge 控制最多额外几个）
2. 等待新 Pod readinessProbe 通过
3. 将新 Pod 加入 Service 端点
4. 删除旧版本 Pod（maxUnavailable 控制最多几个不可用）
5. 重复直到所有 Pod 更新完成

---

### Q5：HPA 的工作原理？

**速答**：metrics-server 每15s采集 Pod 指标 → HPA 控制器每15s查询指标 → 按公式计算期望副本数 → 调用 Deployment scale 接口调整副本数。

**扩缩容公式**：`期望副本数 = ceil(当前副本数 × 当前指标值 / 目标指标值)`

**防抖动**：stabilizationWindowSeconds（扩容60s，缩容300s）

**前提条件**：

1. 安装 metrics-server
2. Pod 必须设置 resources.requests

---

### Q6：livenessProbe 和 readinessProbe 的区别？

**速答**：

- livenessProbe 失败 → **重启容器**（解决死锁、OOM等问题）
- readinessProbe 失败 → **摘除流量**，不重启（解决启动中、依赖不可用等问题）
- startupProbe → 保护慢启动应用，成功前禁用其他探针

**面试陷阱**：不要把 livenessProbe 配置得太敏感（failureThreshold 太小），否则应用稍微慢一点就被重启，导致雪崩。

---

### Q7：K8s 的资源限制（requests/limits）如何工作？

**速答**：

- requests：调度依据，kube-scheduler 根据 requests 选择有足够资源的节点
- limits：运行上限，CPU 超出被 throttle（限速），内存超出被 OOMKill

**QoS 等级**：

- Guaranteed（requests==limits）：最后被驱逐
- Burstable（requests<limits）：中等优先级
- BestEffort（未设置）：最先被驱逐

---

### Q8：ConfigMap 和 Secret 的区别？

**速答**：

- ConfigMap：存储非敏感配置，明文存储
- Secret：存储敏感数据，base64 编码（注意：不是加密！）

**安全建议**：生产环境使用 Vault 或云厂商 KMS，通过 External Secrets Operator 同步到 K8s Secret。

**热更新**：挂载为文件时自动更新（~60s延迟），环境变量方式不会自动更新。

---

### Q9：PV/PVC/StorageClass 的关系？

**速答**：

- StorageClass：存储类型模板，定义如何动态创建 PV
- PV：实际的存储资源（管理员创建或 StorageClass 动态制备）
- PVC：用户的存储申请，K8s 自动将 PVC 绑定到合适的 PV

**绑定流程**：

1. 用户创建 PVC（声明需要 5Gi ReadWriteOnce）
2. K8s 找到匹配的 PV 或通过 StorageClass 动态创建 PV
3. PVC 与 PV 绑定（1:1关系）
4. Pod 挂载 PVC

---

### Q10：K8s 网络模型（CNI）？

**速答**：K8s 要求每个 Pod 有唯一IP，Pod 间可以直接通信（不需要 NAT）。CNI（Container Network Interface）插件负责实现这个网络模型。

**常见 CNI 插件对比**：

| 插件        | 特点                          | 适用场景             |
| ----------- | ----------------------------- | -------------------- |
| **Flannel** | 简单，overlay 网络（VXLAN）   | 开发测试，小规模集群 |
| **Calico**  | 高性能，支持网络策略，BGP路由 | 生产推荐，大规模集群 |
| **Cilium**  | eBPF 实现，高性能，可观测性强 | 高性能场景，服务网格 |
| **Weave**   | 简单，支持加密                | 安全要求高的场景     |

**网络策略（NetworkPolicy）**：

```yaml
# 只允许来自同 namespace 的 Pod 访问
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
spec:
  podSelector:
    matchLabels:
      app: interview-app
  ingress:
    - from:
        - podSelector: {} # 同 namespace 所有 Pod
```

---

## 附录：常用 kubectl 命令速查

```bash
# Pod 操作
kubectl get pods -n default -o wide          # 查看Pod列表（含IP和节点）
kubectl describe pod <name>                   # 查看Pod详情（排查问题必用）
kubectl logs <pod> -c <container> --tail=100  # 查看容器日志
kubectl exec -it <pod> -- /bin/sh             # 进入容器
kubectl top pod                               # 查看Pod资源使用

# Deployment 操作
kubectl rollout status deployment/<name>      # 查看发布状态
kubectl rollout history deployment/<name>     # 查看发布历史
kubectl rollout undo deployment/<name>        # 回滚
kubectl scale deployment/<name> --replicas=3  # 手动扩缩容

# 排查问题
kubectl get events --sort-by=.lastTimestamp   # 查看最近事件
kubectl get nodes -o wide                     # 查看节点状态
kubectl top node                              # 查看节点资源使用
kubectl describe node <name>                  # 查看节点详情（含资源分配）

# 配置管理
kubectl create configmap <name> --from-file=config.yml  # 从文件创建ConfigMap
kubectl create secret generic <name> --from-literal=key=value  # 创建Secret
kubectl get secret <name> -o jsonpath='{.data.key}' | base64 -d  # 解码Secret
```
