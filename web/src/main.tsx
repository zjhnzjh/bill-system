import React, { Fragment, useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

type Order = {
  id: string; idempotencyKey: string; userId: string; sku: string; quantity: number;
  amount: number; status: string; paymentId?: string; traceId: string;
  failureReason?: string; createdAt: string; updatedAt: string;
};
type TraceEvent = { id: number; traceId: string; serviceName: string; operation: string; outcome: string; durationMs: number; detail: string; occurredAt: string };
type RecoveryJob = { id: number; orderId: string; jobType: string; status: string; attempts: number; maxAttempts: number; nextRunAt: string; lastError?: string; traceId: string; createdAt: string; updatedAt: string };
type InventoryItem = { sku: string; available: number; reserved: number };
type Reservation = { orderId: string; sku: string; quantity: number; status: string; createdAt: string };
type Payment = { id: string; orderId: string; amount: number; status: string; createdAt: string; updatedAt: string };
type Audit = { id: number; sku?: string; paymentId?: string; orderId?: string; action: string; operatorName: string; detail: string; occurredAt: string };
type Circuit = { state: string; bufferedCalls: number; failedCalls: number; notPermittedCalls: number; failureRate: number };
type FaultState = Record<string, number>;
type View = "client" | "operations" | "database";

const DEMO_SKU = "LIFE-DEMO-001";
const OPERATOR = "interview-admin";

async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
  return body as T;
}

function jsonPost<T>(path: string, body?: unknown): Promise<T> {
  return api<T>(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
}

function short(value?: string, length = 12) { return value ? value.slice(0, length) : "—"; }
function clock(value?: string) { return value ? new Date(value).toLocaleTimeString("zh-CN", { hour12: false }) : "—"; }
function currentView(): View {
  const value = window.location.pathname.replace(/^\//, "");
  return value === "operations" || value === "database" ? value : "client";
}

function useLiveData() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [jobs, setJobs] = useState<RecoveryJob[]>([]);
  const [items, setItems] = useState<InventoryItem[]>([]);
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [inventoryAudits, setInventoryAudits] = useState<Audit[]>([]);
  const [paymentAudits, setPaymentAudits] = useState<Audit[]>([]);
  const [circuit, setCircuit] = useState<Circuit | null>(null);
  const [inventoryFaults, setInventoryFaults] = useState<FaultState>({});
  const [paymentFaults, setPaymentFaults] = useState<FaultState>({});
  const [health, setHealth] = useState<Record<string, boolean>>({ order: false, inventory: false, payment: false });
  const [socketState, setSocketState] = useState<"connected" | "reconnecting" | "offline">("reconnecting");
  const [lastSync, setLastSync] = useState<Date | null>(null);

  const refresh = useCallback(async () => {
    const results = await Promise.allSettled([
      api<Order[]>("/api/orders"), api<RecoveryJob[]>("/api/orders/jobs"),
      api<InventoryItem[]>("/inventory-api/items"), api<Reservation[]>("/inventory-api/reservations"),
      api<Payment[]>("/payment-api/all"), api<Audit[]>("/inventory-api/admin/audits"),
      api<Audit[]>("/payment-api/admin/audits"), api<Circuit>("/api/reliability/circuit-breakers/payment"),
      api<FaultState>("/inventory-api/faults"), api<FaultState>("/payment-api/faults"),
      api<{ status: string }>("/order-health"), api<{ status: string }>("/inventory-health"), api<{ status: string }>("/payment-health"),
    ]);
    const value = <T,>(index: number): T | undefined => results[index].status === "fulfilled" ? (results[index] as PromiseFulfilledResult<unknown>).value as T : undefined;
    const nextOrders = value<Order[]>(0); if (nextOrders) setOrders(nextOrders);
    const nextJobs = value<RecoveryJob[]>(1); if (nextJobs) setJobs(nextJobs);
    const nextItems = value<InventoryItem[]>(2); if (nextItems) setItems(nextItems);
    const nextReservations = value<Reservation[]>(3); if (nextReservations) setReservations(nextReservations);
    const nextPayments = value<Payment[]>(4); if (nextPayments) setPayments(nextPayments);
    const nextInventoryAudits = value<Audit[]>(5); if (nextInventoryAudits) setInventoryAudits(nextInventoryAudits);
    const nextPaymentAudits = value<Audit[]>(6); if (nextPaymentAudits) setPaymentAudits(nextPaymentAudits);
    const nextCircuit = value<Circuit>(7); if (nextCircuit) setCircuit(nextCircuit);
    const nextInventoryFaults = value<FaultState>(8); if (nextInventoryFaults) setInventoryFaults(nextInventoryFaults);
    const nextPaymentFaults = value<FaultState>(9); if (nextPaymentFaults) setPaymentFaults(nextPaymentFaults);
    setHealth({ order: value<{ status: string }>(10)?.status === "UP", inventory: value<{ status: string }>(11)?.status === "UP", payment: value<{ status: string }>(12)?.status === "UP" });
    setLastSync(new Date());
  }, []);

  useEffect(() => { refresh(); }, [refresh]);
  useEffect(() => {
    let socket: WebSocket | undefined;
    let reconnect: number | undefined;
    let closed = false;
    const connect = () => {
      setSocketState("reconnecting");
      const protocol = location.protocol === "https:" ? "wss" : "ws";
      socket = new WebSocket(`${protocol}://${location.host}/ws/events`);
      socket.onopen = () => setSocketState("connected");
      socket.onmessage = () => refresh();
      socket.onerror = () => setSocketState("offline");
      socket.onclose = () => { if (!closed) { setSocketState("reconnecting"); reconnect = window.setTimeout(connect, 1500); } };
    };
    connect();
    return () => { closed = true; if (reconnect) clearTimeout(reconnect); socket?.close(); };
  }, [refresh]);

  return { orders, jobs, items, reservations, payments, inventoryAudits, paymentAudits, circuit, inventoryFaults, paymentFaults, health, socketState, lastSync, refresh };
}
type LiveData = ReturnType<typeof useLiveData>;

function Shell({ view, data, children }: { view: View; data: LiveData; children: React.ReactNode }) {
  const healthy = Object.values(data.health).filter(Boolean).length;
  const titles = {
    client: ["生活服务客户端", "像用户一样下单、支付，并观察真实交易结果。"],
    operations: ["服务端运维台", "把订单、库存、支付、Trace 与恢复任务串成一条证据链。"],
    database: ["数据库操作台", "查看真实表，通过受控操作制造并修复数据变化。"],
  };
  return <div className="shell">
    <aside>
      <div className="brand"><span>B</span><div><strong>Bill System</strong><small>v0.2 面试展示版</small></div></div>
      <nav>
        <a className={view === "client" ? "active" : ""} href="/client"><i>01</i><span>客户端<small>下单与支付</small></span></a>
        <a className={view === "operations" ? "active" : ""} href="/operations"><i>02</i><span>服务端运维台<small>Trace 与 Jobs</small></span></a>
        <a className={view === "database" ? "active" : ""} href="/database"><i>03</i><span>数据库操作台<small>真实表与审计</small></span></a>
      </nav>
      <div className="boundary"><b>演示边界</b><p>模拟支付 · 本地单实例<br />受控后台操作 · 无真实用户</p></div>
    </aside>
    <main>
      <header>
        <div><p className="eyebrow">LIFE SERVICE COMMERCE · {view.toUpperCase()}</p><h1>{titles[view][0]}</h1><p>{titles[view][1]}</p></div>
        <div className="live-cluster"><span className={`socket ${data.socketState}`}><i></i>{data.socketState === "connected" ? "WebSocket 已连接" : data.socketState === "reconnecting" ? "正在重连" : "连接异常"}</span><span className="health"><i></i>{healthy} / 3 服务在线</span><small>同步 {data.lastSync?.toLocaleTimeString("zh-CN", { hour12: false }) || "—"}</small></div>
      </header>
      {children}
    </main>
  </div>;
}

function Status({ value }: { value: string }) { return <span className={`status status-${value.toLowerCase()}`}>{value}</span>; }

function OrderStateMachine({ status }: { status: string }) {
  const mainPath = ["CREATED", "STOCK_RESERVED", "PENDING_PAYMENT", "PAID", "FULFILLED"];
  const exceptionPath = ["FAILED", "MANUAL_REVIEW", "CANCELLED", "REFUNDED"];
  const mainIndex = mainPath.indexOf(status);
  return <div className="state-machine">
    <div className="state-machine-title"><span>ORDER STATE MACHINE</span><small>主链路与异常终态</small></div>
    <div className="state-path main-path">{mainPath.map((state, index) => <Fragment key={state}><div className={status === state ? "current" : mainIndex > index ? "passed" : ""}><i></i><b>{state}</b></div>{index < mainPath.length - 1 && <span>→</span>}</Fragment>)}</div>
    <div className="state-path exception-path"><small>任一步骤异常 / 补偿 →</small>{exceptionPath.map(state => <div key={state} className={status === state ? "current" : ""}><i></i><b>{state}</b></div>)}</div>
  </div>;
}

function ClientPage({ data }: { data: LiveData }) {
  const [selectedId, setSelectedId] = useState(() => localStorage.getItem("bill-selected-order") || "");
  const [trace, setTrace] = useState<TraceEvent[]>([]);
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => crypto.randomUUID());
  const [message, setMessage] = useState("选择一个动作，三个界面会同步显示变化。");
  const [busy, setBusy] = useState(false);
  const selected = data.orders.find(item => item.id === selectedId) || data.orders[0];
  const item = data.items.find(value => value.sku === DEMO_SKU);
  useEffect(() => { if (selected) { setSelectedId(selected.id); localStorage.setItem("bill-selected-order", selected.id); api<TraceEvent[]>(`/api/orders/${selected.id}/trace`).then(setTrace).catch(() => setTrace([])); } }, [selected?.id, selected?.updatedAt, data.lastSync]);

  async function action(run: () => Promise<Order>, success: (order: Order) => string) {
    setBusy(true);
    try { const order = await run(); setSelectedId(order.id); localStorage.setItem("bill-selected-order", order.id); setMessage(success(order)); await data.refresh(); }
    catch (error) { setMessage((error as Error).message); await data.refresh(); }
    finally { setBusy(false); }
  }
  const create = (repeat = false) => action(async () => {
    const key = repeat && selected ? selected.idempotencyKey : idempotencyKey;
    const order = await api<Order>("/api/orders", { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key }, body: JSON.stringify({ userId: "interview-user", sku: DEMO_SKU, quantity: 1, amount: 39.9 }) });
    if (!repeat) setIdempotencyKey(crypto.randomUUID());
    return order;
  }, order => repeat ? `幂等重放：仍返回同一订单 ${short(order.id)}` : order.status === "FAILED" ? `下单失败：${order.failureReason}` : `订单创建成功：${short(order.id)}`);
  const pay = (drop: boolean) => selected && action(() => jsonPost<Order>(`/api/orders/${selected.id}/pay?dropCallback=${drop}`), () => drop ? "支付事实已成功，回调已故意丢失，等待 Job Runner 恢复。" : "支付和回调均成功。");
  const reconcile = () => selected && action(() => jsonPost<Order>(`/api/orders/${selected.id}/reconcile`), order => `主动对账完成：${order.status}`);
  const change = (name: "cancel" | "refund") => selected && action(() => jsonPost<Order>(`/api/orders/${selected.id}/${name}`), order => name === "cancel" ? `订单已取消并释放库存：${order.status}` : `退款补偿完成：${order.status}`);

  return <Shell view="client" data={data}>
    <section className="client-hero"><div><span className="pill">到店美食 · 随时退</span><h2>双人招牌套餐</h2><p>模拟生活服务交易：库存预占、支付回调、取消退款与最终一致性。</p><div className="price"><strong>¥39.90</strong><del>¥68</del></div></div><div className={`stock-card ${!item || item.available === 0 ? "sold" : ""}`}><small>数据库实时库存</small><strong>{item ? item.available : "已删除"}</strong><span>{item ? `已预占 ${item.reserved}` : "后台不存在该 SKU"}</span></div></section>
    <div className={`notice ${message.includes("失败") || message.includes("Unknown") ? "danger" : ""}`}>{message}</div>
    <section className="client-grid">
      <article className="panel checkout"><div className="panel-title"><div><p>CHECKOUT</p><h2>确认订单</h2></div><span className="tag">{DEMO_SKU}</span></div><label>幂等键<input value={idempotencyKey} onChange={event => setIdempotencyKey(event.target.value)} /></label><div className="order-fields"><div><span>购买数量</span><b>1 份</b></div><div><span>订单金额</span><b>¥39.90</b></div><div><span>库存状态</span><b>{!item ? "商品已删除" : item.available === 0 ? "售罄，可强制尝试" : "可下单"}</b></div></div><button className="primary" disabled={busy} onClick={() => create(false)}>{item && item.available > 0 ? "提交订单并预占库存" : "尝试下单并观察失败"}</button><div className="actions"><button disabled={!selected || busy} onClick={() => create(true)}>重放相同请求</button><button disabled={!selected?.paymentId || busy} onClick={() => pay(false)}>正常支付</button><button disabled={!selected?.paymentId || busy} onClick={() => pay(true)}>支付但丢回调</button><button disabled={!selected || busy} onClick={reconcile}>主动对账</button><button disabled={!selected || selected.status !== "PENDING_PAYMENT" || busy} onClick={() => change("cancel")}>取消并释放库存</button><button disabled={!selected || selected.status !== "PAID" || busy} onClick={() => change("refund")}>退款补偿</button></div></article>
      <article className="panel"><div className="panel-title"><div><p>MY ORDERS</p><h2>我的订单</h2></div><span className="tag">{data.orders.length} 条</span></div><div className="order-list">{data.orders.length === 0 && <div className="empty">暂无订单</div>}{data.orders.map(order => <button key={order.id} onClick={() => { setSelectedId(order.id); localStorage.setItem("bill-selected-order", order.id); }} className={selected?.id === order.id ? "chosen" : ""}><Status value={order.status} /><b>双人招牌套餐 × {order.quantity}</b><small>{short(order.id)} · ¥{order.amount}</small></button>)}</div></article>
    </section>
    {selected && <section className="panel timeline-panel"><div className="panel-title"><div><p>ORDER TIMELINE</p><h2>订单状态与证据</h2></div><Status value={selected.status} /></div><div className="order-summary"><code>{selected.id}</code><span>Trace {selected.traceId}</span>{selected.failureReason && <b>{selected.failureReason}</b>}</div><div className="timeline">{trace.length === 0 ? <div className="empty">等待业务事件</div> : trace.map(event => <div key={event.id} className={event.outcome === "FAILED" ? "failed" : ""}><i></i><span>{clock(event.occurredAt)}</span><b>{event.operation}</b><small>{event.outcome} · {event.durationMs}ms · {event.detail}</small></div>)}</div></section>}
  </Shell>;
}

function OperationsPage({ data }: { data: LiveData }) {
  const [selectedId, setSelectedId] = useState(() => localStorage.getItem("bill-selected-order") || "");
  const [trace, setTrace] = useState<TraceEvent[]>([]);
  const [message, setMessage] = useState("运维操作会真实影响后续请求，并可从数据库台核对结果。");
  const selected = data.orders.find(order => order.id === selectedId) || data.orders[0];
  const reservation = data.reservations.find(value => value.orderId === selected?.id);
  const payment = data.payments.find(value => value.orderId === selected?.id);
  const relatedJobs = data.jobs.filter(value => value.orderId === selected?.id);
  useEffect(() => { if (selected) api<TraceEvent[]>(`/api/orders/${selected.id}/trace`).then(setTrace).catch(() => setTrace([])); }, [selected?.id, selected?.updatedAt, data.lastSync]);
  async function mutate(run: () => Promise<unknown>, text: string) { try { await run(); setMessage(text); await data.refresh(); } catch (error) { setMessage((error as Error).message); } }

  return <Shell view="operations" data={data}>
    <section className="service-grid">{[["Order Service", "8785", data.health.order], ["Inventory Service", "8786", data.health.inventory], ["Payment Service", "8787", data.health.payment]].map(([name, port, up]) => <article className="panel service" key={String(name)}><div><i className={up ? "up" : "down"}></i><span>{up ? "UP" : "DOWN"}</span></div><h3>{name}</h3><small>127.0.0.1:{port} · Spring Boot</small></article>)}<article className="panel service"><div><i className={data.circuit?.state === "OPEN" ? "down" : "up"}></i><span>{data.circuit?.state || "—"}</span></div><h3>Payment Circuit</h3><small>失败 {data.circuit?.failedCalls ?? 0} · 拒绝 {data.circuit?.notPermittedCalls ?? 0}</small></article></section>
    <div className="notice">{message}</div>
    <section className="ops-grid">
      <article className="panel"><div className="panel-title"><div><p>TRANSACTION LINK</p><h2>跨服务数据关联</h2></div><select value={selected?.id || ""} onChange={event => { setSelectedId(event.target.value); localStorage.setItem("bill-selected-order", event.target.value); }}><option value="">选择订单</option>{data.orders.map(order => <option key={order.id} value={order.id}>{short(order.id)} · {order.status}</option>)}</select></div>{!selected ? <div className="empty">先在客户端创建订单</div> : <><div className="service-flow"><div><span>ORDER · bill_order</span><Status value={selected.status} /><code>{short(selected.id, 18)}</code></div><b>→</b><div><span>INVENTORY · bill_inventory</span><Status value={reservation?.status || "NONE"} /><code>{reservation ? `${reservation.sku} × ${reservation.quantity}` : "未产生预占"}</code></div><b>→</b><div><span>PAYMENT · bill_payment</span><Status value={payment?.status || "NONE"} /><code>{payment ? short(payment.id, 18) : "未创建支付单"}</code></div></div><OrderStateMachine status={selected.status} /></>}</article>
      <article className="panel faults"><div className="panel-title"><div><p>FAULT CONTROL</p><h2>故障与容错状态</h2></div><button onClick={() => mutate(() => jsonPost("/api/reliability/circuit-breakers/payment/reset"), "支付熔断器已复位。")}>复位熔断器</button></div><div className="fault-row"><span>库存失败</span><b>剩余 {data.inventoryFaults.failNext ?? 0} 次</b><button onClick={() => mutate(() => jsonPost("/inventory-api/faults", { failNext: 1, delayNextMs: 0 }), "下一次库存预占将失败。")}>注入一次</button></div><div className="fault-row"><span>库存延迟</span><b>{data.inventoryFaults.delayNextMs ?? 0} ms</b><button onClick={() => mutate(() => jsonPost("/inventory-api/faults", { failNext: 0, delayNextMs: 2500 }), "下一次库存请求将延迟 2500ms，超过 1200ms 超时边界。")}>注入超时</button></div><div className="fault-row"><span>支付调用失败</span><b>剩余 {data.paymentFaults.failNextCalls ?? 0} 次</b><button onClick={() => mutate(() => jsonPost("/payment-api/faults", { failNextQueries: 0, failNextCalls: 4 }), "接下来四次支付调用将失败，用于打开熔断器。")}>注入四次</button></div><button className="quiet" onClick={() => Promise.all([jsonPost("/inventory-api/faults", { failNext: 0, delayNextMs: 0 }), jsonPost("/payment-api/faults", { failNextQueries: 0, failNextCalls: 0 })]).then(() => data.refresh())}>清除全部故障</button></article>
    </section>
    <section className="panel trace-panel"><div className="panel-title"><div><p>TRACE / SPAN</p><h2>调用链证据</h2></div><span className="tag">{selected?.traceId || "选择订单"}</span></div><div className="data-table trace-table"><div className="thead"><span>时间</span><span>服务</span><span>操作 / Span</span><span>结果</span><span>耗时</span><span>详情</span></div>{trace.length === 0 ? <div className="empty">暂无 Trace</div> : trace.map(event => <div className="trow" key={event.id}><span>{clock(event.occurredAt)}</span><span>{event.serviceName}</span><b>{event.operation}</b><Status value={event.outcome} /><code>{event.durationMs} ms</code><small title={event.detail}>{event.detail}</small></div>)}</div></section>
    <section className="panel jobs-panel"><div className="panel-title"><div><p>JOB RUNNER</p><h2>持久化恢复任务</h2></div><span className="tag">退避 · 死信 · 人工重跑</span></div><div className="job-grid">{data.jobs.length === 0 ? <div className="empty">支付回调丢失后会产生真实任务</div> : data.jobs.map(job => <article key={job.id}><div><Status value={job.status} /><code>#{job.id}</code></div><b>{job.jobType}</b><small>订单 {short(job.orderId, 18)}</small><small>尝试 {job.attempts}/{job.maxAttempts} · 下次 {clock(job.nextRunAt)}</small>{job.lastError && <p>{job.lastError}</p>}{job.status === "DEAD_LETTER" && <button onClick={() => mutate(() => jsonPost(`/api/orders/jobs/${job.id}/retry`), `任务 #${job.id} 已重新入队。`)}>人工重跑</button>}</article>)}</div>{selected && relatedJobs.length === 0 && <p className="hint">当前订单没有恢复任务；这是正常支付链路的预期结果。</p>}</section>
    <PerformancePanel />
  </Shell>;
}

function PerformancePanel() {
  return <section className="panel performance"><div className="panel-title"><div><p>LOAD TEST EVIDENCE</p><h2>怎么验收压测</h2></div><span className="tag">k6 · 本地基线 · 2026-09-13</span></div><div className="perf-metrics"><article><span>请求数</span><strong>1,804</strong><small>完整创建订单链路</small></article><article><span>吞吐量</span><strong>45.04</strong><small>requests / second</small></article><article><span>P50</span><strong>139.00 ms</strong><small>一半请求更快</small></article><article><span>P95</span><strong>286.99 ms</strong><small>95% 请求在此内完成</small></article><article><span>P99</span><strong>344.64 ms</strong><small>观察最慢 1%</small></article><article><span>HTTP 失败</span><strong>0%</strong><small>本轮 checks 100%</small></article></div><div className="acceptance-steps"><div><i>1</i><b>压什么</b><p>订单落库 → 库存预占 → 支付单创建 → Trace 记录，不是只压一个空接口。</p></div><div><i>2</i><b>怎么压</b><p>40 秒阶梯加压，最高 20 个虚拟用户；运行 <code>load-test.cmd</code> 可复现。</p></div><div><i>3</i><b>看什么</b><p>先看错误率，再看吞吐量与 P95/P99；数字必须带机器、时长和并发条件。</p></div><div><i>4</i><b>能证明什么</b><p>仅证明这台电脑、单实例 Docker 环境的本地基线，不能外推线上容量或 SLA。</p></div></div></section>;
}

function DatabasePage({ data }: { data: LiveData }) {
  const [stock, setStock] = useState("10");
  const [message, setMessage] = useState("这里展示真实 MySQL 记录；页面操作通过各服务的受控 Admin API 完成。");
  const [busy, setBusy] = useState(false);
  const item = data.items.find(value => value.sku === DEMO_SKU);
  async function mutate(run: () => Promise<unknown>, text: string) { setBusy(true); try { await run(); setMessage(text); await data.refresh(); } catch (error) { setMessage((error as Error).message); } finally { setBusy(false); } }
  const setAvailable = (value: number) => mutate(() => jsonPost(`/inventory-api/admin/items/${DEMO_SKU}/stock`, { available: value, operator: OPERATOR }), `真实库存已修改为 ${value}，客户端会通过 WebSocket 同步。`);
  const deleteItem = () => { if (confirm("确认删除演示商品？存在有效预占时后端会拒绝。")) mutate(() => jsonPost(`/inventory-api/admin/items/${DEMO_SKU}/delete`, { operator: OPERATOR }), "商品已从 bill_inventory.inventory_items 删除。现在去客户端尝试下单。"); };
  const restoreItem = () => mutate(() => jsonPost(`/inventory-api/admin/items/${DEMO_SKU}/restore`, { available: 100, operator: OPERATOR }), "商品已恢复，可用库存为 100。");
  const paymentStatus = (payment: Payment, status: string) => mutate(() => jsonPost(`/payment-api/${payment.id}/admin-status`, { status, operator: OPERATOR }), `支付事实 ${short(payment.id)} 已推进为 ${status}；订单状态不会跨库自动改变，请到运维台对账。`);

  return <Shell view="database" data={data}>
    <div className="notice">{message}</div>
    <section className="db-control-grid">
      <article className="panel db-control"><div className="panel-title"><div><p>CONTROLLED WRITE</p><h2>库存后台操作</h2></div><span className="schema">bill_inventory</span></div><div className="db-fact"><span>inventory_items / {DEMO_SKU}</span><strong>{item ? `${item.available} 可用 / ${item.reserved} 预占` : "记录不存在"}</strong></div><div className="preset-actions"><button disabled={!item || busy} onClick={() => setAvailable(0)}>库存归零</button><button disabled={!item || busy} onClick={() => setAvailable(1)}>设为 1</button><button disabled={!item || busy} onClick={() => setAvailable(10)}>设为 10</button></div><div className="custom-stock"><input type="number" min="0" value={stock} onChange={event => setStock(event.target.value)} /><button disabled={!item || busy || Number(stock) < 0} onClick={() => setAvailable(Number(stock))}>写入自定义库存</button></div><div className="danger-actions"><button disabled={!item || busy} onClick={deleteItem}>删除商品记录</button><button disabled={!!item || busy} onClick={restoreItem}>恢复演示商品</button></div><p className="hint">删除前会检查有效预占；所有操作记录操作者 `{OPERATOR}`，不提供任意 SQL。</p></article>
      <article className="panel db-control"><div className="panel-title"><div><p>CONSISTENCY LAB</p><h2>支付事实控制</h2></div><span className="schema">bill_payment</span></div><p className="hint">后台只允许状态向前推进。把支付改为成功后，订单仍可能待支付，用主动对账解释最终一致性。</p><div className="payment-actions">{data.payments.length === 0 ? <div className="empty">先在客户端创建订单</div> : data.payments.slice(0, 5).map(payment => <div key={payment.id}><span><code>{short(payment.id)}</code><small>订单 {short(payment.orderId)}</small></span><Status value={payment.status} /><button disabled={busy || payment.status !== "PENDING"} onClick={() => paymentStatus(payment, "SUCCEEDED")}>标记成功</button><button disabled={busy || payment.status !== "SUCCEEDED"} onClick={() => paymentStatus(payment, "REFUNDED")}>标记退款</button></div>)}</div></article>
    </section>
    <DataSection title="订单表" eyebrow="bill_order.orders" columns={["ID", "SKU", "状态", "支付ID", "更新时间"]} rows={data.orders.map(order => [short(order.id, 18), order.sku, <Status value={order.status} />, short(order.paymentId, 18), clock(order.updatedAt)])} />
    <div className="two-tables"><DataSection title="库存表" eyebrow="bill_inventory.inventory_items" columns={["SKU", "可用", "预占"]} rows={data.items.map(value => [value.sku, value.available, value.reserved])} /><DataSection title="库存预占表" eyebrow="bill_inventory.inventory_reservations" columns={["订单ID", "SKU", "数量", "状态"]} rows={data.reservations.map(value => [short(value.orderId), value.sku, value.quantity, <Status value={value.status} />])} /></div>
    <div className="two-tables"><DataSection title="支付表" eyebrow="bill_payment.payments" columns={["支付ID", "订单ID", "金额", "状态"]} rows={data.payments.map(value => [short(value.id), short(value.orderId), `¥${value.amount}`, <Status value={value.status} />])} /><DataSection title="恢复任务表" eyebrow="bill_order.recovery_jobs" columns={["ID", "订单ID", "任务", "状态", "次数"]} rows={data.jobs.map(value => [value.id, short(value.orderId), value.jobType, <Status value={value.status} />, `${value.attempts}/${value.maxAttempts}`])} /></div>
    <section className="panel audit-panel"><div className="panel-title"><div><p>AUDIT EVIDENCE</p><h2>后台操作审计</h2></div><span className="tag">只追加 · 可追溯</span></div><div className="audit-list">{[...data.inventoryAudits, ...data.paymentAudits].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)).length === 0 ? <div className="empty">尚无后台修改</div> : [...data.inventoryAudits, ...data.paymentAudits].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)).map((audit, index) => <div key={`${audit.action}-${audit.id}-${index}`}><span>{clock(audit.occurredAt)}</span><b>{audit.action}</b><code>{audit.sku || short(audit.paymentId)}</code><small>{audit.detail}</small><em>{audit.operatorName}</em></div>)}</div></section>
  </Shell>;
}

function DataSection({ title, eyebrow, columns, rows }: { title: string; eyebrow: string; columns: string[]; rows: React.ReactNode[][] }) {
  return <section className="panel data-section"><div className="panel-title"><div><p>{eyebrow}</p><h2>{title}</h2></div><span className="tag">{rows.length} rows</span></div><div className="generic-table" style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(90px, 1fr))` }}>{columns.map(column => <b className="column" key={column}>{column}</b>)}{rows.length === 0 ? <div className="empty table-empty" style={{ gridColumn: `1 / span ${columns.length}` }}>暂无记录</div> : rows.flatMap((row, rowIndex) => row.map((cell, columnIndex) => <span className="cell" key={`${rowIndex}-${columnIndex}`}>{cell}</span>))}</div></section>;
}

function App() {
  const data = useLiveData();
  const view = currentView();
  if (window.location.pathname === "/") window.history.replaceState(null, "", "/client");
  if (view === "operations") return <OperationsPage data={data} />;
  if (view === "database") return <DatabasePage data={data} />;
  return <ClientPage data={data} />;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
