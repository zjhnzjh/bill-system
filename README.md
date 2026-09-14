# Bill System v0.3.1

一个面向面试展示的生活服务交易可靠性实验台。项目不是普通商城 CRUD，而是把同步下单、Kafka 异步削峰、事务 Outbox、消费幂等、有限重试、DLQ、支付对账、Trace 和自动化质量门禁做成可运行、可注入、可观察的完整闭环。

> 当前定位：本机工程实验与面试演示，不宣称生产高可用，不连接真实支付或真实用户数据。

## 为什么做这个项目

生活服务订单会遇到重复请求、库存竞争、支付回调丢失、瞬时流量、下游超时和消息重复投递。Bill System 用三个独立后端服务和三个联动界面回答四个问题：

1. 用户请求如何快速、可靠地受理？
2. 消息重复、消费失败和流量积压时如何保证业务正确？
3. 支付成功但订单状态落后时如何自动恢复？
4. 如何把可靠性要求变成可重复运行的自动化测试门禁？

## 架构

```text
React Web :3100
  ├─ 同步下单 ───────────────→ Order Service :8785
  └─ HTTP 202 异步受理         ├─HTTP→ Inventory Service :8786
          ↓                    └─HTTP→ Payment Service :8787
order_requests + outbox_events
          ↓ Outbox Relay
Kafka :9092（3 partitions / 3 consumers）
          ↓
幂等消费 → 订单状态机 → Trace / Job Runner / WebSocket

MySQL :3306：bill_order / bill_inventory / bill_payment
Redis :6379：同步入口限流
```

Spring Boot 是三个 Java 后端服务使用的应用框架，不是数据库；MySQL 才保存业务事实，Kafka 保存消息日志，Redis 保存限流计数。

## 快速启动

要求：Windows 11，并已安装 Docker Desktop。`start.cmd` 会在需要时自动启动 Docker Desktop，并等待 Docker Engine 就绪；首次启动还需要下载镜像并构建服务。

双击：

```text
start.cmd
```

启动器会检查端口、构建并等待七个容器健康，然后打开 <http://127.0.0.1:3100>。

| 页面 | 地址 | 用途 |
|---|---|---|
| 客户端 | <http://127.0.0.1:3100/client> | 同步/异步下单、支付、取消、退款 |
| 服务端运维台 | <http://127.0.0.1:3100/operations> | Kafka Lag、故障注入、Trace、状态机、Job Runner |
| 数据库操作台 | <http://127.0.0.1:3100/database> | 真实表、Outbox、消费证据、受控写入和审计 |

其他入口：

```text
stop.cmd                 停止本项目容器
reset-demo.cmd           清理演示数据并恢复测试库存
verify-v0.3.1.cmd        执行当前版本全量自动验收
load-test.cmd            同步完整订单压测
load-test-kafka.cmd      Kafka异步受理压测
cleanup-load-data.cmd    清理压测数据
```

## 核心能力

- 同步订单：幂等键、数据库唯一约束、并发库存竞争、超时和补偿。
- 异步订单：HTTP 202、请求与 Outbox 同一本地事务、Kafka 三分区消费。
- 消息可靠性：至少一次投递、重复消费幂等、三次有限重试、DLQ 和人工回放。
- 削峰可观测：消费者暂停/恢复、Lag、分区水位、Outbox Pending 和最老请求年龄。
- 支付一致性：支付回调丢失、持久化 Job Runner、主动对账和幂等状态推进。
- 服务保护：Redis 限流、Resilience4j 熔断和并发隔离、可控故障注入。
- 证据链：订单/库存/支付关联、版本化 WebSocket、逻辑 Trace/Span、操作审计。
- 测试开发：接口、数据库、Kafka 与故障恢复的端到端自动 Gate。

## 一键验收

双击：

```text
verify-v0.3.1.cmd
```

唯一验收入口依次执行三层测试：

1. 核心交易可靠性：API 幂等、并发库存、超时、支付恢复、限流、熔断、取消退款。
2. 三端联动：三个页面、WebSocket、受控数据库修改、支付事实差异和审计。
3. Kafka 异步可靠性：Outbox、Partition/Offset、重复投递、重试、DLQ、Lag 和 Job Runner。

脚本不仅检查 HTTP 200，还会真实调用三个服务、查询数据库、注入故障并断言最终状态；完成后恢复演示数据。

## 压测基线

两组 k6 测试采用相同的 40 秒阶梯：10 秒升至 5 VU、20 秒升至 20 VU、10 秒降至 0。VU 是 Virtual User，即循环执行测试脚本的虚拟用户；20 VU 不是总共 20 个请求，也不严格等于 20 个真实注册用户。

| 指标 | 同步完整订单 | Kafka异步受理 |
|---|---:|---:|
| 成功语义 | 订单、库存、支付、Trace 已完成 | request 与 Outbox 已落库并返回 202 |
| 请求数 | 1,804 | 4,254 |
| 吞吐 | 45.04 req/s | 106.18 req/s |
| P50 | 139.00ms | 33.31ms |
| P95 | 286.99ms | 56.12ms |
| P99 | 344.64ms | 83.05ms |
| HTTP 错误率 | 0% | 0% |

异步入口更快完成的是“可靠受理”，不是“完整订单”。4,254 条请求在压测结束后继续由 Outbox 和 Kafka 排空，整批约 362 秒才全部 `SUCCEEDED`。因此不能宣称 Kafka 将订单吞吐提升了 2.36 倍；它把峰值压力转化为排队时间，并保护下游，但不增加热点库存行锁的处理能力。

所有数字只适用于 Ryzen 7 5800H、16GB 内存、Docker 单实例的本地短时基线，不外推为生产容量或 SLA。详细口径见[性能基线](docs/PERFORMANCE_BASELINE_V0.3.1.md)。

## 面试推荐主线

1. 异步下单，说明 `202 → Outbox → Kafka → Consumer → Order`。
2. 暂停消费者并制造 12 条积压，观察三分区 Lag；恢复后观察排空。
3. 重复投递成功消息，展示 `received=2`、`businessExecutions=1`。
4. 连续失败三次进入 DLQ，清除故障后人工回放。
5. 模拟支付成功但回调丢失，展示 Job Runner 对账和 Trace 恢复证据。
6. 数据库台串联 `order_requests → outbox_events → kafka_deliveries → orders`。

## 项目结构

```text
bill system/
├─ order-service/          订单、异步请求、Outbox、Kafka、Trace、Job
├─ inventory-service/      库存、预占和审计
├─ payment-service/        模拟支付事实和审计
├─ web/                    React三端控制台
├─ scripts/                启动、复位和自动验收实现
├─ tests/                  k6脚本与CSV性能证据
├─ docs/                   当前v0.3.1文档
├─ history/                v0.1、v0.2和旧v0.3冻结文档
├─ docker-compose.yml
└─ start.cmd
```

## 当前文档

- [v0.3.1范围与验收基准](BILL_SYSTEM_V0.3.1.md)
- [架构、数据所有权与生产差距](docs/ARCHITECTURE_V0.3.1.md)
- [90秒/5分钟面试演示手册](docs/INTERVIEW_DEMO_V0.3.1.md)
- [同步/异步性能基线](docs/PERFORMANCE_BASELINE_V0.3.1.md)
- [实现Review与证据强度](docs/REVIEW_V0.3.1.md)
- [自动测试矩阵](docs/test-matrix.csv)
- [历史版本归档](history/README.md)

## 真实性与生产边界

- Kafka 是单 Broker、单机 KRaft，不证明 Broker 高可用。
- MySQL 是单容器多 Schema，不等于三个物理数据库集群。
- Outbox 使用数据库轮询，不包含 CDC 和多实例 Relay 抢占。
- Job Runner 是单实例扫描器，没有生产级租约或 `SKIP LOCKED`。
- Trace 是自建逻辑证据链，不是完整 OpenTelemetry/Jaeger 平台。
- 支付为模拟服务，不包含渠道签名、真实资金、账务和风控。
- Seata XA 只做设计比较，没有作为已实现成果展示。
- Docker Compose 中的账号仅供本机演示；生产环境必须使用密钥管理、TLS、RBAC 和审计保护。

生产化差距和后继方向见[架构说明](docs/ARCHITECTURE_V0.3.1.md#7-距离生产级的主要差距)。
