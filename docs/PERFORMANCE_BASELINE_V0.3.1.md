# Bill System 同步/异步性能基线 v0.3.1

测试日期：2026-09-13
结论：同步完整链路与异步 202 受理均通过各自阈值。异步入口提高了接入速率并降低了受理延迟，但把压力转化为 Outbox 与 Kafka 积压；不能把受理吞吐当作最终订单吞吐。

## 测试环境

| 项目 | 配置 |
|---|---|
| CPU | AMD Ryzen 7 5800H，8 核 16 线程 |
| 内存 | 15.9 GB |
| 操作系统 | Windows 11 + WSL2 + Docker Desktop |
| Docker Engine | 29.7.2 |
| 服务 | Order/Inventory/Payment 各 1 实例，MySQL 8.4、Redis 7.4 |
| Kafka | 4.1.2，单 Broker KRaft，命令 Topic 3 分区，3 个并发消费者 |
| 压测端 | `grafana/k6:0.54.0` 容器 |

两组测试使用相同的 40 秒阶梯：0→5 VU（10 秒）、5→20 VU（20 秒）、20→0 VU（10 秒），每个 VU 每轮暂停 50ms，使用独立商品 `LIFE-LOAD-001`。

## 结果对比

| 指标 | 同步完整订单 `/api/orders` | 异步受理 `/api/async-orders` |
|---|---:|---:|
| 请求数 | 1,804 | 4,254 |
| HTTP/业务检查通过率 | 100% | 100% |
| HTTP 错误率 | 0% | 0% |
| HTTP 吞吐 | 45.04 req/s | 106.18 req/s |
| 平均延迟 | 155.12 ms | 36.07 ms |
| P50 | 139.00 ms | 33.31 ms |
| P90 | 272.14 ms | 49.36 ms |
| P95 | 286.99 ms | 56.12 ms |
| P99 | 344.64 ms | 83.05 ms |
| 最大延迟 | 366.53 ms | 149.63 ms |

同步接口的成功表示订单落库、库存预占、支付单创建和 Trace 已完成。异步接口的成功只表示 `order_requests + outbox_events` 已在本地事务中落库并返回 HTTP 202，因此两列不是相同语义下的性能排名。

## 异步完成证据

- k6 结束后观察到 4,254 条已受理请求，Outbox Pending 与 Kafka Lag 均真实上升；
- Relay 将全部 Outbox 发布后，`outboxPending = 0`；
- Consumer Group 排空后，`lag = 0`；
- 最终 `SUCCEEDED = 4,254`、`REJECTED = 0`、`DEAD_LETTER = 0`；
- 首条受理时间 `04:19:10.041385Z`，最后完成时间 `04:25:12.620510Z`，整批从首条受理到最终收敛约 362 秒。

这里暴露了一个真实瓶颈：压测使用同一热点 SKU，库存悲观锁让最终业务处理不能与 202 接入速率等比例扩张。Kafka 能保护入口和缓冲峰值，但不能增加热点库存行的处理能力。

## 如何解释

正确说法：

> 本地 40 秒基线中，异步入口完成 4,254 次 202 受理，P95 56.12ms、错误率 0%；随后 Outbox 和 Kafka 将积压逐步排空，4,254 条请求全部成功，整批约 362 秒收敛。它证明了削峰和最终收敛，不代表最终订单容量达到 106 req/s。

不能说：

- “异步订单吞吐是同步的 2.36 倍”；两者完成语义不同。
- “P95 56ms 就是用户拿到订单结果的时间”；它只是受理时间。
- “Lag 清零说明业务百分之百正确”；还要核对终态、订单、库存、支付和 DLQ。
- “这就是线上 SLA”；测试是单机、短时、本地环境。

## 原始证据与复现

- 同步原始报告：`tests/results/k6-summary.json`
- 异步原始报告：`tests/results/k6-async-summary.json`
- 同步脚本：`tests/k6/order-load.js`，运行 `load-test.cmd`
- 异步脚本：`tests/k6/async-order-load.js`，运行 `load-test-kafka.cmd`

运行异步压测后，应在运维台观察 `Outbox Pending + Kafka Lag`，等待两者都为 0，再核对 succeeded/rejected/dead-letter 总数，最后运行 `reset-demo.cmd`。

## 后继方向

1. 用多 SKU 与单热点 SKU 做对照，量化数据库行锁瓶颈。
2. 分别采集受理速率、Relay 发布速率、消费完成速率和端到端完成 P95。
3. 同步采集 CPU、GC、连接池、MySQL 锁等待与慢查询。
4. 重复三轮并报告均值和方差，再做容量拐点与长稳测试。
