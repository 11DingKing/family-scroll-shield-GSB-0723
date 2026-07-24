# Family Scroll Shield — 观看配额协调服务

家庭端观看时间配额协调 REST API 服务，基于 Java 21 + Spring Boot 3 + PostgreSQL + Redis 构建，确保多实例并发下的租约互斥、配额精确扣减、幂等重试和故障恢复。

## 技术栈

- **Java 21** + **Spring Boot 3.3**
- **PostgreSQL 16** — 权威数据存储（成员档案、配额记录、租约、审批、Outbox）
- **Redis 7** — 可丢失的租约加速缓存层（宕机/清空不影响正确性）
- **HikariCP** 连接池 + **Lettuce** Redis 客户端
- **Transactional Outbox** 模式保证事件可靠投递
- **JPA/Hibernate** ORM，悲观锁 + 乐观锁 + 原子条件更新

## 快速开始

### 使用 Docker Compose 启动依赖

```bash
docker compose up -d
```

这会启动 PostgreSQL（5432）和 Redis（6379）。

### 构建与运行

```bash
./gradlew bootRun
```

服务默认运行在 `http://localhost:8080`。

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | localhost | PostgreSQL 主机 |
| `DB_PORT` | 5432 | PostgreSQL 端口 |
| `DB_NAME` | scrollshield | 数据库名 |
| `DB_USER` | postgres | 用户名 |
| `DB_PASS` | postgres | 密码 |
| `REDIS_HOST` | localhost | Redis 主机 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `OUTBOX_WEBHOOK_URL` | (空) | Outbox 事件 HTTP 回调地址，未配置时事件标记为 SENT 并记录日志 |

## 配额规则

| 角色 | 每日限额 | 单次限额 | 特殊限制 |
|------|----------|----------|----------|
| ADULT（成年人） | 60 分钟 | 15 分钟 | — |
| TEEN（青少年） | 30 分钟 | 15 分钟 | 睡前 1 小时禁刷（`bedtime_local` 前 60 分钟开始） |
| CHILD（儿童） | 30 分钟 | 10 分钟 | — |

延长审批：每天最多 **1 次**，最多 **5 分钟**，不可突破睡前窗口。

## REST API

### 成员档案

```
POST   /api/v1/members               创建成员
GET    /api/v1/members               列出所有成员
GET    /api/v1/members/{memberId}    查询成员详情
```

创建成员请求体示例：
```json
{
  "name": "小明",
  "role": "TEEN",
  "timezone": "Asia/Shanghai",
  "bedtime_local": "22:00:00"
}
```

### 会话租约

```
POST   /api/v1/leases/acquire        获取租约
POST   /api/v1/leases/release        释放租约
GET    /api/v1/leases/{leaseToken}   查询租约
POST   /api/v1/leases/heartbeat      心跳续约
```

获取租约请求体：
```json
{
  "member_id": "uuid",
  "idempotency_key": "unique-client-key",
  "requested_minutes": 15,
  "device_id": "device-1"
}
```

心跳请求体（sequence 为客户端单调递增序号，用于乱序检测）：
```json
{
  "lease_token": "uuid",
  "idempotency_key": "hb-key",
  "sequence": 42
}
```

### 延长审批

```
POST   /api/v1/extensions/request              申请延长
POST   /api/v1/extensions/{token}/approve       审批（家长端）
GET    /api/v1/extensions/{token}               查询审批
```

### 配额查询

```
GET    /api/v1/usage/{memberId}/today           今日配额使用情况
GET    /api/v1/usage/{memberId}?date=YYYY-MM-DD 指定日期配额
```

## 并发安全机制

### 1. 租约互斥（单活保证）
- PostgreSQL 部分唯一索引：`CREATE UNIQUE INDEX ... WHERE status = 'ACTIVE'`，同一成员最多一个 ACTIVE 租约
- 获取租约时 `SELECT ... FOR UPDATE` 悲观锁 + 检查活跃租约
- 并发请求由 DB 串行化，唯一索引兜底防竞争条件

### 2. 心跳序号权威存储
- `session_leases.heartbeat_seq`（服务端计数器）+ `last_client_seq`（客户端序号）双字段
- 心跳使用原子条件更新：`UPDATE ... SET heartbeat_seq = heartbeat_seq + 1 WHERE lease_token = ? AND heartbeat_seq = ? AND last_client_seq < ?`
- 多实例并发心跳、重复重试、乱序心跳均由 DB 原子更新保证只处理一次
- **Redis 清空后心跳序号仍然正确**——DB 是唯一权威来源

### 3. 幂等性
- 所有写操作携带 `idempotency_key`，DB 唯一约束防重复
- 重复请求返回首次结果（HTTP 200），不会重复扣时

### 4. Redis 可丢失设计
- Redis 仅做租约缓存加速和心跳序号快速检查
- 所有 Redis 操作 try-catch 包裹，故障时自动降级为纯 DB 模式
- 缓存丢失后通过 `reconcileLeaseCache()` 从 PG 重建缓存

### 5. 故障恢复
- 定时任务扫描过期租约（`expires_at < now()`）→ EXPIRED
- 心跳超时（`last_heartbeat_at < now - heartbeat_interval - grace_period`）→ TAKEN_OVER
- 跨午夜结算：每 5 分钟检查每个成员的本地时区午夜，前一天的活跃租约自动结算

### 6. 跨时区午夜结算
- 不以服务器固定时间结算，而是对每个成员按其配置时区计算本地日期
- 活跃租约在成员本地时区午夜自动过期，未使用时间退还

## Transactional Outbox

所有状态变更（LEASE_ACQUIRED、LEASE_HEARTBEAT、LEASE_RELEASED、LEASE_EXPIRED、LEASE_TAKEN_OVER、EXTENSION_REQUESTED、EXTENSION_APPROVED、EXTENSION_REJECTED）在同一数据库事务中写入 `outbox_events` 表。

后台轮询器每 500ms 检查 PENDING 事件：
- 若配置了 `OUTBOX_WEBHOOK_URL`，以 HTTP POST 分发事件，成功标记 SENT
- 未配置 webhook 时直接标记 SENT 并记录日志
- 失败重试（最多 5 次），超过阈值标记 FAILED

## 测试

31 个集成测试覆盖以下场景：

| 测试类别 | 测试数 | 覆盖内容 |
|----------|--------|----------|
| 基本配额规则 | 6 | 成人/青少年/儿童日限额、单次限额、配额耗尽 |
| 并发安全 | 3 | 20线程并发抢租约（仅1成功）、10线程并发心跳（同seq仅1赢）、10线程无seq心跳（全部计入） |
| 幂等与重复 | 2 | 重复请求不双扣、同key返回原结果 |
| Redis 故障 | 4 | 缓存清空后租约互斥仍生效、心跳序号从PG恢复、完全Redis宕机仍可工作、缓存重建 |
| 时区与夏令时 | 3 | DST 日期计算、东京/纽约独立午夜结算、跨时区结算互不干扰 |
| 心跳序号 | 3 | DB权威心跳序列、Redis清空后旧seq拒绝、新seq正常推进 |
| 延长审批 | 3 | 每日一次限制、审批加时、拒绝重复申请 |
| Outbox | 3 | 事件记录、标记SENT、所有状态变更均有事件 |
| 故障恢复 | 2 | 过期租约回收、崩溃后心跳超时接管 |
| 租约生命周期 | 2 | 释放退还未用时间、过期结算consumed分钟 |

```bash
# 确保 Redis 运行在 localhost:6379（docker compose up -d redis）
./gradlew test
```

## 项目结构

```
src/main/java/com/family/scrollshield/
├── ScrollShieldApplication.java
├── config/                    # Redis、Jackson、Web、RestClient 配置
├── controller/                # REST API 控制器
│   ├── MemberController.java
│   ├── LeaseController.java
│   ├── HeartbeatController.java
│   ├── ExtensionController.java
│   └── UsageController.java
├── domain/                    # JPA 实体和枚举
│   ├── FamilyMember.java
│   ├── DailyUsage.java
│   ├── SessionLease.java
│   ├── ExtensionApproval.java
│   ├── OutboxEvent.java
│   └── *Enum.java
├── dto/                       # 请求/响应 DTO
├── exception/                 # 异常类型 + 全局异常处理
├── repository/                # Spring Data JPA Repository
└── service/
    ├── TimezoneService.java   # 时区转换、DST、就寝窗口
    ├── QuotaService.java      # 配额规则引擎
    ├── LeaseService.java      # 租约获取/释放/过期/接管（核心并发控制）
    ├── HeartbeatService.java  # 心跳处理（DB原子条件更新）
    ├── ExtensionApprovalService.java
    ├── OutboxService.java     # Outbox 写入 + HTTP 分发 + 重试
    ├── LeaseRecoveryService.java  # 过期回收、跨午夜结算、缓存重建
    ├── MemberService.java
    └── UsageQueryService.java
```
