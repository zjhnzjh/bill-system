# Bill System 实现 Review v0.3.1

Review 日期：2026-09-13

员工编号：`MYPROJECT-GPT5-001`

实施包：方案 A（Kafka 优先面试核心版）
状态：实现、构建、本机运行与全量回归完成

## 1. 结论

方案 A 已完成。Bill System 在不破坏 v0.1/v0.2 的前提下形成了完整的异步交易展示闭环：HTTP 202 受理、请求与 Outbox 同事务、真实 Kafka 分区投递、幂等消费、有限重试、DLQ、人工回放、Lag 积压与排空、业务事件 WebSocket，以及异步订单对原支付恢复链路的复用。

新增八道 Gate 全部通过；v0.1 九类场景和 v0.2 六道 Gate 也全部回归通过。验证结束时测试数据已清理。另完成一次 40 秒 k6 异步受理基线，原始结果保存于 `tests/results/k6-async-summary.json`。

## 2. 逐项证据

| 能力 | 真实实现 | 自动证据 | 结果 |
|---|---|---|---|
| Kafka 基础设施 | Kafka 4.1.2、KRaft、单 Broker、三分区 | Broker API 可达，Topic 分区数为 3 | PASS |
| 异步受理 | `POST /api/async-orders` 返回 202 | 首次响应为 ACCEPTED，稍后 SUCCEEDED | PASS |
| 事务 Outbox | `order_requests` 与 `outbox_events` 同一 Spring 事务 | 两行同时存在；Relay 后 PUBLISHED | PASS |
| 发布证据 | 幂等 Producer、acks=all | Outbox 保存 Partition/Offset | PASS |
| 消费幂等 | 请求终态防重 + 业务订单唯一幂等键 | received≥2、businessExecutions=1、订单=1 | PASS |
| 重试/DLQ | 主 Topic、Retry Topic、DLQ Topic | 三次 Attempt 后 DEAD_LETTER，执行次数 0 | PASS |
| 人工恢复 | 受控 replay API | 清除故障后 SUCCEEDED，执行次数 1 | PASS |
| 削峰与 Lag | 消费器暂停/恢复，AdminClient 水位计算 | 暂停后 Lag≥6，恢复后 Lag=0 | PASS |
| 三分区消费 | command Topic 3 分区，listener concurrency=3 | 分区水位和消费证据可查询 | PASS |
| 业务事件 | 版本化 WebSocket envelope | eventId、sequence、eventType、version 可断言 | PASS |
| 支付恢复复用 | 异步订单进入现有支付状态机 | 丢回调后 Job Runner 将订单恢复到 PAID | PASS |
| 三端展示 | 客户端、运维台、数据库台 | 三路由 200；数据来自真实 API/DB/Kafka | PASS |
| 性能基线 | k6 40 秒、最高 20 VU | 4254 次、106.18 req/s、P95 56.12ms、错误 0% | PASS |

## 3. 本轮关键决策

### 3.1 不把 Kafka 当作分布式事务

Kafka 负责排队、削峰和解耦；受理与消息之间由 Outbox 缩小丢失窗口，跨服务正确性仍由状态机、幂等、补偿和对账负责。页面同时展示 Outbox Pending 与 Kafka Lag，避免只看 Broker 队列而漏掉尚未发布的积压。

### 3.2 明确采用至少一次语义

Relay 在“Broker 已确认、数据库尚未标记”窗口宕机会重发，因此系统不宣称无重复。重复投递是一个可点击实验：同一消息再次被消费者收到并记录 `DUPLICATE_IGNORED`，但业务执行次数和订单数保持 1。

### 3.3 技术错误与业务拒绝分流

消费者连接/执行技术故障走有限重试，第三次进入 DLQ；库存不足等业务结论直接 REJECTED。这样能避免把确定失败变成重试风暴。

### 3.4 受理性能与完成性能分开

异步 k6 的 106.18 req/s 和 P95 56.12ms 只描述 HTTP 202 受理，不等价于完整订单吞吐。压测期间 Outbox、Kafka 和热点库存形成真实积压；必须等 Outbox Pending 与 Lag 清零，再核对终态数量。

## 4. 发现并修复

| 问题 | 原因 | 修复 | 经验 |
|---|---|---|---|
| Kafka 首次启动退出 | 官方镜像 appuser 无权写新建命名卷 | 本地 Demo 明确用 root 运行 Kafka 容器并限制监听到本机 | 镜像健康检查必须包含首次空卷场景 |
| 前端 UUID state 编译失败 | TypeScript 将初值推断为 UUID 模板字面量 | 显式声明 `useState<string>` | 浏览器 API 的窄类型可能比业务 state 更严格 |
| DLQ Gate 偶发只看到一条证据 | 请求终态与消费证据分别提交，查询存在极短可见窗口 | Gate 对证据表做有界轮询 | 异步测试必须等“事实集合稳定”，不能只等一个状态字段 |
| PowerShell 5 数组计数错误 | `Invoke-RestMethod` 管道包装与 PowerShell 7 不同 | 保留原数组再读取 Count | Windows 双击脚本必须用实际 powershell.exe 验证 |
| 单消费者未利用三分区 | Listener 默认并发为 1 | command listener concurrency 调整为 3 | 分区数只有配合消费者并发才产生吞吐价值 |

## 5. 证据强度

- 强证据：真实容器、Kafka Admin/Consumer Offset、MySQL 事实、HTTP 接口和自动故障恢复断言。
- 中等证据：单机 40 秒 k6 基线，可重复但样本时间短、资源共机。
- 设计证据：生产多 Broker、多副本、CDC Outbox、多实例租约、认证授权与 Seata XA；本版本没有实现，不列为项目成果。

## 6. 适用边界与风险

- Kafka 以 root 身份运行仅用于受控本机演示；生产必须使用最小权限、持久卷权限初始化和认证授权。
- 单 Broker 无副本，不具备 Kafka 高可用和灾备证据。
- 同一 MySQL 容器的多 Schema 不等于跨集群。
- Relay 为轮询且没有多实例租约；宕机窗口依靠重复投递和幂等收敛。
- 热点 SKU 的悲观锁限制最终消费速率；Kafka 可以缓冲，不能创造下游容量。
- WebSocket 影响实时展示，不影响数据库最终事实。
- Seata XA 明确保留为 `DESIGN_ONLY`，面试中不可称为已实现。

## 7. 验收入口

- 当前唯一全量入口：`verify-v0.3.1.cmd`，依次运行核心可靠性、三端联动与 Kafka 异步验收。
- 同步压测：`load-test.cmd`
- 异步受理压测：`load-test-kafka.cmd`
- 演示复位：`reset-demo.cmd`

## 8. 后继方向

面试前停止扩展基础设施，优先演练 90 秒与 5 分钟主线。面试后按顺序考虑 OpenTelemetry/Jaeger、Playwright、CDC Outbox、多实例消费者/Relay、Kafka ACL/TLS，再决定是否以独立 v0.3.2 实现 Seata XA 对比实验。
