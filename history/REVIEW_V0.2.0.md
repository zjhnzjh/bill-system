# Bill System 实现 Review v0.2.0

Review 日期：2026-09-13  
员工编号：`MYPROJECT-GPT5-001`  
状态：实现与本机验收完成

## 1. 结论

Bill System v0.2 已把 v0.1 的单页可靠性控制台升级为三个可独立打开、实时联动的面试展示界面，并保留原有九类可靠性能力。

- 客户端：`/client`；
- 服务端运维台：`/operations`；
- 数据库操作台：`/database`；
- 实时通知：`/ws/events`；
- 一键启动：`start.cmd`；
- 全量验收：`verify.cmd`；
- 单独 v0.2 验收：`verify-v0.2.cmd`。

最终构建、运行、接口回归、三端联动验收和浏览器视觉检查均已通过。验证结束后演示数据已清空，`LIFE-DEMO-001` 可用库存恢复为 100。

## 2. 逐项完成证据

| 要求 | 实现 | 验收证据 | 结果 |
|---|---|---|---|
| 三个独立界面 | React 按 URL 渲染 Client、Operations、Database | 三个路由均返回 200，浏览器逐页检查 | PASS |
| 实时联动 | Spring WebSocket 每秒推送 `STATE_REFRESH`，前端断线 1.5 秒后重连 | 客户端在 5 秒内收到消息；页面显示连接和同步时间 | PASS |
| 真实落库 | 订单、库存预占、支付、Job 分别由对应服务写入三个 Schema | API 查询与 MySQL 数据一致 | PASS |
| 受控库存后台 | 设置非负库存、安全删除、恢复商品 | 库存归零导致订单 FAILED；删除后失败；恢复后成功 | PASS |
| 受控支付后台 | 支付状态只允许推进到 SUCCEEDED 或 REFUNDED | 支付成功事实与待支付订单并存，对账后订单 PAID | PASS |
| 审计记录 | `inventory_audit`、`payment_audit` | 操作者、动作、前后值和时间均可查询 | PASS |
| 服务端详情 | 健康、端口、跨服务关联、Trace、Job、故障与熔断 | 浏览器运维台检查，真实订单 Trace 可见 | PASS |
| 订单状态机 | 主链路与 FAILED、MANUAL_REVIEW、CANCELLED、REFUNDED 异常终态可视化 | 浏览器创建订单后，PENDING_PAYMENT 当前节点正确高亮 | PASS |
| 压测解释 | 运维台展示对象、负载、指标、环境和适用边界 | 与 v0.1 k6 原始基线一致 | PASS |
| v0.1 回归 | 原九场景未删除 | `scripts/smoke-test.ps1` 全部通过 | PASS |
| v0.2 自动验收 | 新增六道 Gate | `scripts/v0.2-integration-test.ps1` 全部通过 | PASS |
| 一键启动 | Windows CRLF、运行实例识别、健康检查 | `start.cmd` 返回 0，六容器 healthy | PASS |
| 安全重置 | 清理订单、Trace、Job、支付、预占和审计，恢复种子库存 | Gate 6 验证订单 0 条、库存 100 | PASS |

## 3. 关键实现

### 3.1 WebSocket 的职责边界

WebSocket 只发送“状态可能改变”的刷新通知，消息不承载业务事实。页面收到通知后重新查询 HTTP API。这样即使断线漏消息，重连或刷新页面后仍能从 MySQL 恢复真实状态，避免把长连接当作数据库。

### 3.2 受控后台操作

数据库操作台没有数据库账号，也不能执行任意 SQL。页面调用 Inventory Service 和 Payment Service 的 Admin API，由服务完成参数校验、本地事务和审计写入。

- 库存不能设为负数；
- 有有效预占时不能删除商品；
- 删除后的商品可以按固定初始值恢复；
- 支付状态只允许向成功或退款推进；
- 每次后台变更保存操作者和变更前后信息。

### 3.3 跨服务一致性演示

支付后台将支付事实推进为 SUCCEEDED 后，订单仍保持 PENDING_PAYMENT，证明代码没有跨库直接改订单。调用对账后，订单服务查询支付事实并合法推进到 PAID，Trace 同时记录 payment.reconcile 和 payment.callback。

## 4. 本轮发现并修复的问题

| 问题 | 原因 | 修复 | 可复用经验 |
|---|---|---|---|
| 支付列表经 Nginx 返回 404 | 基础路径被补成尾斜杠 | 增加明确的 `/api/payments/all` 查询路径 | 代理重写后的最终 URI 必须做运行验证 |
| reset-demo.cmd 每行首字符丢失 | 文件是 LF 换行，Windows cmd 解析异常 | 所有 cmd 统一为 CRLF、UTF-8 无 BOM | Windows 启动脚本不能只在编辑器里检查 |
| v0.2 验收把空支付数组计为一条 | Windows PowerShell 对空 JSON 数组的包装语义不同 | 按非空 id 过滤后再计数，并用 MySQL 直查确认 | 测试脚本本身也需要校验口径 |
| 运维台首次进入短暂显示无 Trace | 页面查询与初始渲染异步 | WebSocket 后续刷新并重新拉取 Trace | UI 验收要等待异步状态稳定 |

## 5. 验收结果摘要

- Docker 生产构建：四个自建镜像成功；
- 容器：Web、三个 Spring Boot 服务、MySQL、Redis 共六个，全部 healthy；
- v0.1：九类场景全部通过；
- v0.2：六道 Gate 全部通过；
- WebSocket：5 秒内收到 STATE_REFRESH；
- 浏览器：三个页面完成可访问性树和截图检查，运维台状态机当前节点正确高亮；
- UI 写入：库存从 94 改为 10，客户端同步读到 10，审计记录包含 `available 94 -> 10`；
- 最终清理：订单 0 条，演示库存 100，可直接开始排练。

## 6. 适用边界

- WebSocket 当前每秒广播刷新提示，适合本地演示；生产应改为业务事件触发，并控制订阅范围和广播成本；
- 当前 Admin API 仅用于本机演示，没有登录、RBAC、审批和审计防篡改；
- Trace 是自建业务事件，不是完整 OpenTelemetry 分布式追踪；
- Job Runner 仍为单实例扫描器，没有多副本租约或数据库抢占；
- 三个服务使用同一个 MySQL 容器的独立 Schema，隔离程度不等于独立数据库集群；
- 压测结果只适用于本机 Docker 单实例，不代表生产容量或 SLA；
- 支付服务是模拟实现，没有真实签名、渠道回调和资金风险控制。

## 7. 关键文件

- `BILL_SYSTEM_V0.2.md`：v0.2 唯一范围和验收基准；
- `web/src/main.tsx`：三个界面及实时数据联动；
- `order-service/.../LiveEventSocket.java`：WebSocket 刷新通道；
- `inventory-service/.../InventoryController.java`：库存查询和受控后台操作；
- `payment-service/.../PaymentController.java`：支付查询和受控状态推进；
- `scripts/v0.2-integration-test.ps1`：v0.2 六道自动验收 Gate；
- `docs/test-matrix.csv`：v0.1 与 v0.2 测试证据矩阵；
- `docs/INTERVIEW_DEMO.md`：五分钟演示顺序和追问回答。

## 8. 后继方向

面试前优先做三次完整演练，不继续增加中间件。面试后可按顺序增加 OpenTelemetry/Jaeger、事件驱动 WebSocket、Outbox、多实例 Job 租约、真实 RBAC、Playwright 浏览器自动化和多轮性能对比。
