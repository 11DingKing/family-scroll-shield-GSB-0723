# family-scroll-shield-GSB-0723

家庭端「观看配额协调服务」。为成员档案、观看计划、会话租约、心跳与临时延长审批提供 REST API，
在多实例并发下保证同一成员只有一个活动租约，客户端重试 / 进程崩溃 / 网络分区 / 心跳乱序 /
数据库连接中断都不会造成重复扣时或超额放行。

## 技术栈
- Java 21, Spring Boot 3.3
- PostgreSQL（权威额度与审批记录）
- Redis（**可丢失**的租约加速层，丢失后可从 PostgreSQL 恢复）
- Flyway（数据库迁移）
- Transactional Outbox（状态变化记录 + 审计重放）
- Testcontainers（并发 / 缓存丢失恢复集成测试）

## 核心不变量
- **单一活动租约**：由 `session_leases` 上的部分唯一索引 `idx_one_active_lease_per_member` 强制，
  并发获取在数据库层竞争，恰好一个成功。
- **PostgreSQL 为权威**：消费时间在事务内结算到加锁的 `viewing_plans` 行；Redis 仅为提示。
- **幂等、单调计费**：按服务端观测到的心跳间隔计费并夹取到单次 / 每日预算，
  重试、重复或乱序心跳、长时间停顿都不会重复扣时或超额。
- **可审计**：每次状态变化写入事务性 outbox，每次被接受的心跳写入 `lease_heartbeats`。

## 规则
- 成年人：每日 60 分钟，单次 15 分钟
- 青少年：每日 30 分钟，睡前 1 小时禁刷
- 儿童：单次 10 分钟
- 延长审批：每天最多一次 5 分钟，且不能突破睡前窗口
- 全部按成员时区计算，支持夏令时跳变、跨午夜自动结算、租约过期接管与审计重放

## 构建与测试
测试使用 Testcontainers，需要本机可用的 Docker（OrbStack / Docker Desktop 均可）。

```bash
export JAVA_HOME=<JDK 21 home>
./gradlew test      # 运行全部单元 + 并发/缓存丢失恢复集成测试
./gradlew build     # 构建可执行 bootJar
```

## 主要 REST 端点
- `POST /api/members` 注册成员
- `GET  /api/members/{externalId}` 查询成员
- `GET  /api/plans/{memberExternalId}/today` 查询当天计划（成员时区）
- `POST /api/leases/acquire` 获取租约
- `POST /api/leases/heartbeat` 心跳
- `POST /api/leases/release` 释放租约
- `POST /api/extensions/approve` 延长审批
- `GET  /api/audit/aggregate/{aggregateId}` 审计重放（outbox 事件流）
