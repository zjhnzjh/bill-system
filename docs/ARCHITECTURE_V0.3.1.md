# Bill System v0.3.1 架构说明

版本：方案 A（Kafka 优先面试核心版）

员工编号：`MYPROJECT-GPT5-001`
证据状态：本机真实实现并通过自动验收

## 1. 架构结论

系统把两个容易混淆的维度分开：

- 通信方式：普通订单走同步 HTTP；高峰订单走 `202 + Outbox + Kafka`。
- 一致性方式：订单受理与 Outbox 使用单库本地事务；订单、库存、支付跨服务使用幂等、补偿、回调与对账达到最终一致。

Kafka 解决削峰、排队和解耦，不自动解决跨库事务；Outbox 解决“数据库已提交但消息未发出”的窗口，幂等消费者解决至少一次投递造成的重复执行。

```text
Client
  ├─ POST /api/orders ───────────────→ Order ─HTTP→ Inventory / Payment
  └─ POST /api/async-orders → order_requests + outbox_events（同一事务）
                                      ↓ Outbox Relay
                                Kafka order-create-command（3 partitions）
                                      ↓ consumer group（3 consumers）
                                Order ─HTTP→ Inventory / Payment
                                      ↓
                           order/status、Trace、Job、WebSocket event
```

## 2. 真实组件与数据所有权

| 组件 | 端口/存储 | 负责事实 | 不负责 |
|---|---|---|---|
| Web | 3100 | 三个展示界面 | 不保存业务最终事实 |
| Order Service | 8785 / `bill_order` | 订单、异步请求、Outbox、消费证据、Trace、恢复任务 | 不直接改库存或支付表 |
| Inventory Service | 8786 / `bill_inventory` | 商品库存、预占、库存审计 | 不推进订单状态 |
| Payment Service | 8787 / `bill_payment` | 模拟支付事实、支付审计 | 不跨库直改订单 |
| Kafka | 9092 / 单 Broker KRaft | 消息日志、分区、Offset、Consumer Group | 不作为订单最终事实 |
| Redis | 6379 | 同步下单入口限流 | 不作为订单数据库 |

本机用一个 MySQL 容器承载三个 Schema，这是进程和数据所有权隔离实验，不宣称物理集群隔离。

## 3. Kafka Topic 与状态

| Topic | 作用 | Key | 异常策略 |
|---|---|---|---|
| `order-create-command` | 主订单命令 | 幂等键 | Consumer Group、3 分区 |
| `order-create-retry` | 有限技术重试 | 幂等键 | 400/800ms 级退避，最多 3 次 |
| `order-create-dlq` | 失败终点 | 幂等键 | 人工回放前不再执行业务 |
| `order-status-event` | 异步结果事件 | 请求 ID | 当前用于证明结果发布，不作为数据库事实 |

`order_requests` 状态：`ACCEPTED → QUEUED → PROCESSING → SUCCEEDED/REJECTED`；技术异常走 `RETRYING → DEAD_LETTER`，人工回放回到 `QUEUED`。

## 4. 可靠性闭环

1. 接口在一个本地事务中写 `order_requests` 与 `outbox_events`，随后返回 HTTP 202。
2. Relay 扫描 PENDING Outbox，生产者启用 `acks=all` 与幂等生产；成功后保存 Partition/Offset。
3. Broker 发布成功但数据库标记前宕机会重复发送，所以消费者必须幂等，不能依赖“绝不重复”。
4. 消费者用异步请求终态和业务订单唯一幂等键两层防重；`receivedCount` 可增加，`businessExecutions` 保持 1。
5. 技术异常有限重试三次后进 DLQ；库存不足等业务拒绝直接落 `REJECTED`，不做无意义重试。
6. Consumer Offset 提交后才形成可观测进度，`Lag = Log End Offset - Committed Offset`。
7. 支付成功但回调丢失时，持久化 Job Runner 对账并恢复订单，继续沿用 v0.2 的最终一致性链路。

## 5. WebSocket 边界

WebSocket 从固定定时刷新升级为业务事件通知，事件包含 `eventId`、递增 `sequence`、`eventType`、`aggregateType/id`、`occurredAt`、`traceId` 与 `payloadVersion`。前端收到业务事件后重新查询 API；心跳只维护连接，不触发全量刷新。因此断线会影响实时感，不会改变数据库事实。

## 6. 适用边界

- Kafka 是单 Broker，只证明消息、分区、Offset、Lag、重试和 DLQ 机制，不证明 Broker 高可用。
- 当前 Outbox 是数据库轮询，适合 Demo；生产可考虑 CDC、租约、多实例 Relay 和监控告警。
- 消费链是至少一次语义，不宣称端到端 exactly-once；正确性来自业务幂等。
- 支付为模拟服务，不包含渠道签名、资金风控和真实账务。
- Seata XA 在本版本为 `DESIGN_ONLY`，没有页面伪造，也不算已实现能力。
