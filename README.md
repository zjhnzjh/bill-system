# Bill System

生活服务交易可靠性与测试演示平台。v0.2 的三个实时联动界面、真实数据后台和自动验收已经完成，范围与证据见 [Bill System v0.2 目标规划](BILL_SYSTEM_V0.2.md) 与 [v0.2 实现 Review](docs/REVIEW_V0.2.0.md)。

## 启动

要求：Windows 11、Docker Desktop，Docker Engine 已启动。

双击：

```text
start.cmd
```

启动器会固定检查端口、构建容器、等待健康检查并打开 <http://127.0.0.1:3100>。停止全部容器使用 `stop.cmd`。

启动后双击 `verify.cmd` 会先回归 v0.1 九类可靠性场景，再执行 v0.2 三端联动验收；只验收 v0.2 可双击 `verify-v0.2.cmd`。`load-test.cmd` 使用 k6 做阶梯加压。
练习或测试后双击 `reset-demo.cmd`，可只清空本项目的模拟交易数据并恢复三种测试库存。

## v0.2 三个页面

- 客户端：<http://127.0.0.1:3100/client>
- 服务端运维台：<http://127.0.0.1:3100/operations>
- 数据库操作台：<http://127.0.0.1:3100/database>

三个页面通过 WebSocket 接收刷新通知，业务数据仍以三个 MySQL Schema 中的真实记录为准。数据库操作台不提供任意 SQL，而是通过 Inventory/Payment Service 的受控 Admin API 修改数据并追加审计记录。

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

- [v0.2 当前目标与验收基准](BILL_SYSTEM_V0.2.md)
- [v0.2 实现 Review](docs/REVIEW_V0.2.0.md)
- [v0.1 原始目标与证据边界](BILL_SYSTEM_V0.1.md)
- [三分钟演示与追问](docs/INTERVIEW_DEMO.md)
- [架构与可靠性机制](docs/ARCHITECTURE.md)
- [v0.1 实现 Review](docs/REVIEW_V0.1.0.md)
- [k6 性能基线 v0.1](docs/PERFORMANCE_BASELINE_V0.1.md)
- [测试矩阵](docs/test-matrix.csv)

## 真实性边界

这是单机、单实例、本地 Demo，不处理真实支付或真实用户数据。九场景证据来自本机 Docker 环境的接口、并发和故障恢复验收；它不等于生产容量结论。已完成一轮参数完整的本地 k6 基线压测，但不可把这组结果外推为线上容量。Web 自动化和多实例一致性验证属于下一阶段。
