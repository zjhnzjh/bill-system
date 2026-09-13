# Bill System

生活服务交易可靠性与测试演示平台。当前已完成 v0.3.1 方案 A：在 v0.2 三端联动和同步可靠性基线上，增加真实 Kafka、事务 Outbox、幂等消费、有限重试、DLQ、Lag 实验和业务事件 WebSocket。实现证据见 [v0.3.1 Review](docs/REVIEW_V0.3.1.md)。

## 启动

要求：Windows 11、Docker Desktop，Docker Engine 已启动。

双击：

```text
start.cmd
```

启动器会固定检查端口、构建容器、等待健康检查并打开 <http://127.0.0.1:3100>。停止全部容器使用 `stop.cmd`。

启动后双击 `verify.cmd` 会依次回归 v0.1、v0.2 和 v0.3.1；只验收 Kafka 增量可双击 `verify-v0.3.1.cmd`。`load-test.cmd` 测同步完整订单，`load-test-kafka.cmd` 测异步入口 202 受理延迟。
练习或测试后双击 `reset-demo.cmd`，可只清空本项目的模拟交易数据并恢复三种测试库存。

## 三个页面

- 客户端：<http://127.0.0.1:3100/client>
- 服务端运维台：<http://127.0.0.1:3100/operations>
- 数据库操作台：<http://127.0.0.1:3100/database>

三个页面通过版本化 WebSocket 业务事件联动，业务数据仍以三个 MySQL Schema 中的真实记录为准。运维台提供 Kafka Lab，数据库台展示异步请求、Outbox 和消息消费证据；后台不提供任意 SQL，只通过受控 Admin API 修改并审计。

## v0.3.1 方案 A 已实现并验证

- Kafka 4.1.2 单 Broker KRaft，命令 Topic 三分区、三并发消费者；
- HTTP 202 异步受理，`order_requests` 与 `outbox_events` 同事务落库；
- Outbox Relay 保存发布状态、Partition 和 Offset；
- 至少一次投递下的业务幂等，重复消息可见但业务只执行一次；
- 技术错误有限重试三次、DLQ 与人工回放；业务拒绝不盲目重试；
- 消费暂停、Lag/分区水位、Outbox Pending、积压年龄和恢复排空；
- 异步订单继续复用库存、支付、Trace、Job Runner 与最终一致性；
- WebSocket 由定时全量刷新升级为真实业务事件，带 eventId、sequence 和 payloadVersion；
- v0.3.1 八道 Kafka Gate、v0.2 六道 Gate、v0.1 九类场景全部通过；
- k6 40 秒异步受理基线：4254 请求、106.18 req/s、P95 56.12 ms、错误率 0%。

## v0.2 已实现并验证

- 三个 Spring Boot 服务和独立数据 Schema；
- React 交易可靠性控制台；
- Docker Compose 全栈一键启动与固定端口健康检查；
- 请求指纹、按幂等键细粒度锁、数据库唯一索引三层幂等防护；
- 库存悲观锁预占，10 请求争抢最后一份库存仅 1 个成功；
- 1.2 秒下游超时边界、失败注入与库存补偿；
- 模拟支付、重复回调防护、回调丢失与主动/自动对账；
- 持久化 Job Runner、指数退避、死信和人工重跑；
- Redis 固定窗口限流，80 并发下 50 个通过、30 个返回 HTTP 429；
- Resilience4j 支付熔断与并发隔离；
- 取消、退款、库存释放及跨服务状态一致性；
- 内置 Trace 事件和 Job 控制台。
- 客户端、服务端运维台、数据库操作台三个独立路由；
- WebSocket 实时刷新通知、连接状态和自动重连；
- 库存与支付受控 Admin API、真实落库和操作审计；
- 跨服务数据关联、订单状态时间线、显式状态机与压测验收解释；
- v0.2 三端联动六道自动验收 Gate。

## 面试入口

- [v0.3.1 冻结范围与验收基准](BILL_SYSTEM_V0.3.1.md)
- [v0.3.1 实现 Review](docs/REVIEW_V0.3.1.md)
- [v0.3.1 架构说明](docs/ARCHITECTURE_V0.3.1.md)
- [v0.3.1 面试演示手册](docs/INTERVIEW_DEMO_V0.3.1.md)
- [v0.3.1 同步/异步性能基线](docs/PERFORMANCE_BASELINE_V0.3.1.md)
- [测试矩阵](docs/test-matrix.csv)
- [历史版本归档](history/README.md)

## 真实性边界

这是单机、本地 Demo，不处理真实支付或真实用户数据。Kafka 为单 Broker，不能证明 Broker 高可用；MySQL 是单容器多 Schema，不能宣传跨集群；202 受理性能不等于订单最终完成性能。Seata XA 为 `DESIGN_ONLY`，不在 v0.3.1 方案 A 的已实现范围。全部性能结论只适用于本机 Docker 环境，不能外推为线上容量或 SLA。
