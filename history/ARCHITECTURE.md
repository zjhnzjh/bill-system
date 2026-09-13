# Bill System v0.2 架构与可靠性机制

## 一句话定位

这是一个生活服务订单可靠性 Demo：让重复请求、并发库存、支付部分失败和恢复过程都可复现、可观察、可自动验证。v0.2 增加客户端、服务端运维台、数据库操作台三个独立界面，把真实数据变化与可靠性证据关联起来。

## 运行结构

```text
React/Nginx :3100
  /client  /operations  /database
       │ HTTP 命令与查询 + WebSocket 刷新通知
       ▼
Order Service :8785 ─────── Redis :6379
       │                    入口固定窗口限流
       ├──────────► Inventory Service :8786 ─► bill_inventory
       │              悲观锁、预占、释放、故障注入
       └──────────► Payment Service :8787 ───► bill_payment
                      模拟支付、回调、退款、故障注入

Order Service ──────────────────────────────► bill_order
  订单状态、Trace Event、Recovery Job
```

MySQL 使用一个容器和三个 Schema。服务间只通过 HTTP 协作，不跨库读取。

## v0.2 三端联动

数据库操作台不直接连接 MySQL，也不提供任意 SQL。它调用库存服务和支付服务的受控 Admin API，各服务在本地事务中修改自己的 Schema 并追加审计记录。WebSocket 每秒发出轻量的状态刷新通知，三个页面收到通知后重新查询 HTTP API。

```text
受控后台命令 → 服务本地事务 → MySQL 事实与 Audit
                              ↓
WebSocket STATE_REFRESH → 页面重新读取 HTTP API
```

WebSocket 不是数据源。即使通知丢失，浏览器重连或刷新后仍会从数据库恢复真实状态。

## 创建订单

```text
校验 Idempotency-Key 与请求指纹
→ 同键细粒度进程锁
→ 事务内查询已有结果
→ 唯一索引兜底跨实例竞态
→ 保存 CREATED
→ 库存预占
→ 创建支付单
→ PENDING_PAYMENT
```

同一个键配同一个请求返回首次订单；同一个键配不同请求返回 400。锁按键粒度创建，不会把所有用户的下单请求串行化。数据库唯一索引仍是最终事实源。

## 支付最终一致性

支付服务先保存 `SUCCEEDED` 事实，再尝试回调订单。回调丢失时，订单仍为 `PENDING_PAYMENT`，同时创建持久化 `PAYMENT_RECONCILE` Job。Job Runner 主动查询支付事实并推进订单：

```text
PENDING → RETRYING → SUCCEEDED
                 └→ DEAD_LETTER → 人工重跑 → SUCCEEDED
```

失败间隔指数增加，最多三次后进入死信。人工重跑会清理错误并重新排队；订单状态机和支付回调均幂等。

## 保护策略

| 风险 | 机制 | 本地证据 |
|---|---|---|
| 重复下单 | 请求指纹、键级锁、唯一索引 | 20 个并发同键请求只有一个订单 ID |
| 超卖 | MySQL 悲观写锁 | 10 个请求争抢 1 份库存只有 1 个成功 |
| 下游卡死 | 连接 800 ms、读取 1200 ms 超时 | 注入 2500 ms 延迟，约 1.2 秒失败返回 |
| 回调丢失 | 持久化 Job + 主动对账 | 自动从待支付恢复为已支付 |
| 任务一直失败 | 指数退避、死信、人工重跑 | 三次失败进死信，恢复后重跑成功 |
| 瞬时洪峰 | Redis 每客户端固定窗口限流 | 80 并发中 30 个明确返回 429 |
| 支付故障 | Resilience4j 熔断 + Bulkhead | 四次连续失败开路，后续快速失败 |
| 跨服务补偿 | 取消/退款释放库存 | 订单、支付、库存最终状态一致 |

## 适用边界

- 单机单 Order Service 实例已验证；数据库唯一索引可兜底多实例幂等冲突，但尚未做多实例压测。
- Job Runner 没有实现生产级租约或 `SKIP LOCKED` 抢占，因此不应直接扩成多副本。
- Redis 限流采用固定窗口，边界时刻可能出现突刺；生产可换滑动窗口或令牌桶。
- Demo 的 Saga 是同步补偿加异步对账，没有接 Kafka/RocketMQ，也没有真实支付签名。
- Trace 是业务事件 Trace，不是完整 OpenTelemetry 分布式 Span。
