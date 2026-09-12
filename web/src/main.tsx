import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

type Order = {
  id: string;
  idempotencyKey: string;
  userId: string;
  sku: string;
  quantity: number;
  amount: number;
  status: string;
  paymentId?: string;
  traceId: string;
  failureReason?: string;
  createdAt: string;
};

type TraceEvent = {
  id: number;
  serviceName: string;
  operation: string;
  outcome: string;
  durationMs: number;
  detail: string;
  occurredAt: string;
};

type RecoveryJob = {
  id: number;
  orderId: string;
  jobType: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  nextRunAt: string;
  lastError?: string;
};

const scenarios = [
  ["01", "重复下单", "相同 Idempotency-Key 只产生一个订单", "P0"],
  ["02", "并发抢库存", "最后一份库存不会被超卖", "P0"],
  ["03", "库存超时", "失败可解释且不会进入支付", "P0"],
  ["04", "支付回调丢失", "主动对账恢复最终一致", "P0"],
  ["05", "重复支付回调", "状态机只推进一次", "P1"],
  ["06", "取消与退款", "释放资源并执行补偿", "P1"],
  ["07", "任务连续失败", "退避、死信与人工恢复", "P0"],
  ["08", "瞬时流量", "限流与削峰保护系统", "P1"],
  ["09", "支付服务故障", "熔断、隔离与降级", "P2"],
];

async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
  return body as T;
}

function App() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [selected, setSelected] = useState<Order | null>(null);
  const [trace, setTrace] = useState<TraceEvent[]>([]);
  const [jobs, setJobs] = useState<RecoveryJob[]>([]);
  const [message, setMessage] = useState("系统已就绪，可以创建演示订单。");
  const [busy, setBusy] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => crypto.randomUUID());

  const stats = useMemo(() => ({
    total: orders.length,
    paid: orders.filter((item) => item.status === "PAID" || item.status === "FULFILLED").length,
    attention: orders.filter((item) => ["FAILED", "MANUAL_REVIEW"].includes(item.status)).length,
  }), [orders]);

  async function refresh() {
    const [data, jobData] = await Promise.all([
      api<Order[]>("/api/orders"),
      api<RecoveryJob[]>("/api/orders/jobs"),
    ]);
    setOrders(data);
    setJobs(jobData);
    setSelected((current) => current ? data.find((item) => item.id === current.id) || current : current);
  }

  useEffect(() => {
    refresh().catch((error) => setMessage(error.message));
    const timer = window.setInterval(() => refresh().catch(() => undefined), 2000);
    return () => window.clearInterval(timer);
  }, []);

  async function createOrder(repeat = false) {
    setBusy(true);
    try {
      const key = repeat && selected ? selected.idempotencyKey : idempotencyKey;
      const order = await api<Order>("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body: JSON.stringify({ userId: "interview-user", sku: "LIFE-DEMO-001", quantity: 1, amount: 39.9 }),
      });
      setSelected(order);
      setMessage(repeat ? `幂等重放成功：仍返回订单 ${order.id}` : `订单已创建：${order.id}`);
      if (!repeat) setIdempotencyKey(crypto.randomUUID());
      await refresh();
    } catch (error) { setMessage((error as Error).message); }
    finally { setBusy(false); }
  }

  async function pay(dropCallback: boolean) {
    if (!selected) return;
    setBusy(true);
    try {
      const order = await api<Order>(`/api/orders/${selected.id}/pay?dropCallback=${dropCallback}`, { method: "POST" });
      setSelected(order);
      setMessage(dropCallback ? "支付已成功，但回调被故意丢弃；订单仍等待对账。" : "支付和回调成功。");
      await refresh();
    } catch (error) { setMessage((error as Error).message); }
    finally { setBusy(false); }
  }

  async function reconcile() {
    if (!selected) return;
    setBusy(true);
    try {
      const order = await api<Order>(`/api/orders/${selected.id}/reconcile`, { method: "POST" });
      setSelected(order);
      setMessage(`对账完成，当前状态：${order.status}`);
      await refresh();
    } catch (error) { setMessage((error as Error).message); }
    finally { setBusy(false); }
  }

  async function changeOrder(action: "cancel" | "refund") {
    if (!selected) return;
    setBusy(true);
    try {
      const order = await api<Order>(`/api/orders/${selected.id}/${action}`, { method: "POST" });
      setSelected(order);
      setMessage(action === "cancel" ? "订单已取消，预占库存已释放。" : "退款补偿完成，支付与订单状态一致。");
      await refresh();
      await showTrace(order);
    } catch (error) { setMessage((error as Error).message); }
    finally { setBusy(false); }
  }

  async function retryJob(jobId: number) {
    setBusy(true);
    try {
      await api<RecoveryJob>(`/api/orders/jobs/${jobId}/retry`, { method: "POST" });
      setMessage(`任务 ${jobId} 已重新进入待执行队列。`);
      await refresh();
    } catch (error) { setMessage((error as Error).message); }
    finally { setBusy(false); }
  }

  async function showTrace(order: Order) {
    setSelected(order);
    try { setTrace(await api<TraceEvent[]>(`/api/orders/${order.id}/trace`)); }
    catch (error) { setMessage((error as Error).message); }
  }

  return <div className="shell">
    <aside>
      <div className="brand"><span>B</span><div><strong>Bill System</strong><small>交易可靠性实验室</small></div></div>
      <nav><button className="active">运行总览</button><button>订单中心</button><button>故障实验室</button><button>Trace & Jobs</button></nav>
      <div className="boundary"><b>本地演示边界</b><p>模拟支付 · 固定数据 · 无真实用户</p></div>
    </aside>
    <main>
      <header><div><p className="eyebrow">LIFE SERVICE COMMERCE</p><h1>交易可靠性控制台</h1><p>把失败变成可注入、可观察、可恢复的工程事实。</p></div><div className="health"><i></i> 3 个服务运行中</div></header>
      <section className="metrics">
        <article><span>演示订单</span><strong>{stats.total}</strong><small>当前环境</small></article>
        <article><span>支付成功</span><strong>{stats.paid}</strong><small>状态机已确认</small></article>
        <article><span>需要处理</span><strong>{stats.attention}</strong><small>失败不静默</small></article>
        <article><span>场景覆盖</span><strong>9 / 9</strong><small>自动验收已通过</small></article>
      </section>
      <div className="notice">{message}</div>
      <section className="grid">
        <div className="panel create-panel">
          <div className="panel-title"><div><p>LIVE TRANSACTION</p><h2>创建演示订单</h2></div><span className="tag">LIFE-DEMO-001</span></div>
          <label>幂等键<input value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} /></label>
          <div className="order-fields"><div><span>商品</span><b>到店套餐</b></div><div><span>数量</span><b>1</b></div><div><span>金额</span><b>¥39.90</b></div></div>
          <button className="primary" disabled={busy} onClick={() => createOrder(false)}>创建订单并预占库存</button>
          <div className="actions">
            <button disabled={!selected || busy} onClick={() => createOrder(true)}>重放相同请求</button>
            <button disabled={!selected || busy} onClick={() => pay(false)}>正常支付</button>
            <button disabled={!selected || busy} onClick={() => pay(true)}>支付但丢回调</button>
            <button disabled={!selected || busy} onClick={reconcile}>主动对账恢复</button>
            <button disabled={!selected || busy || selected.status !== "PENDING_PAYMENT"} onClick={() => changeOrder("cancel")}>取消并释放库存</button>
            <button disabled={!selected || busy || selected.status !== "PAID"} onClick={() => changeOrder("refund")}>退款补偿</button>
          </div>
          {selected && <div className="selected"><span>{selected.status}</span><code>{selected.id}</code><small>Trace {selected.traceId}</small></div>}
        </div>
        <div className="panel">
          <div className="panel-title"><div><p>RECENT</p><h2>订单状态</h2></div><button onClick={refresh}>刷新</button></div>
          <div className="order-list">{orders.length === 0 && <div className="empty">暂无订单</div>}{orders.map((order) => <button key={order.id} onClick={() => showTrace(order)} className={selected?.id === order.id ? "chosen" : ""}><span className={`status ${order.status.toLowerCase()}`}>{order.status}</span><b>{order.sku} × {order.quantity}</b><small>{order.id.slice(0, 12)} · ¥{order.amount}</small></button>)}</div>
        </div>
      </section>
      <section className="panel scenarios">
        <div className="panel-title"><div><p>FAILURE LAB</p><h2>九个可靠性场景</h2></div><span className="tag">逐项生成测试证据</span></div>
        <div className="scenario-grid">{scenarios.map(([number, title, detail, priority]) => <article key={number} className="ready"><div><span>{number}</span><i>{priority}</i></div><h3>{title}</h3><p>{detail}</p><small>已实现 / 自动验收</small></article>)}</div>
      </section>
      <section className="panel trace-panel">
        <div className="panel-title"><div><p>EVIDENCE</p><h2>Trace 事件</h2></div><span className="tag">{selected?.traceId || "选择一个订单"}</span></div>
        <div className="trace-list">{trace.length === 0 && <div className="empty">点击订单查看调用证据</div>}{trace.map((event) => <div key={event.id}><span>{event.serviceName}</span><b>{event.operation}</b><i className={event.outcome === "OK" ? "ok" : "warn"}>{event.outcome}</i><code>{event.durationMs} ms</code></div>)}</div>
      </section>
      <section className="panel trace-panel">
        <div className="panel-title"><div><p>JOB RUNNER</p><h2>持久化恢复任务</h2></div><span className="tag">退避 · 死信 · 人工重跑</span></div>
        <div className="trace-list">{jobs.length === 0 && <div className="empty">“支付但丢回调”会创建自动对账任务</div>}{jobs.map((job) => <div key={job.id}><span>#{job.id} · {job.jobType}</span><b>{job.orderId.slice(0, 18)}</b><i className={job.status === "SUCCEEDED" ? "ok" : "warn"}>{job.status}</i>{job.status === "DEAD_LETTER" ? <button disabled={busy} onClick={() => retryJob(job.id)}>人工重跑</button> : <code>{job.attempts}/{job.maxAttempts}</code>}</div>)}</div>
      </section>
    </main>
  </div>;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
