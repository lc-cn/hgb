import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { App, Device, GameAccount } from "../../api/types";
import { Button, Card, Field, Grow, Muted, Pill, Row } from "../../components/ui";
import { dateTime, PHASES } from "../../format";
import s from "./DeviceCard.module.css";

export function DeviceList({ devices, accounts }: { devices: Device[]; accounts: GameAccount[] }) {
  if (devices.length === 0) return <Card><Muted>还没有设备。在手机上打开护肝宝并登录这个账号，设备会自动出现在这里。</Muted></Card>;
  return <div className={s.grid}>{devices.map((device) => <DeviceCard key={device.id} device={device} accounts={accounts} />)}</div>;
}

function DeviceCard({ device, accounts }: { device: Device; accounts: GameAccount[] }) {
  const remove = () => {
    if (!confirm(`解绑“${device.name}”？这台设备会下线，正在运行的作业结果将标记为未知。`)) return;
    api(`/api/devices/${enc(device.id)}`, { method: "DELETE" }).catch((error: Error) => alert(error.message));
  };
  return (
    <Card className={s.device}>
      <Row><b className={s.name}>{device.name}</b><Pill tone={device.online ? "ok" : ""}>{device.online ? "在线" : "离线"}</Pill></Row>
      <Muted>{device.online ? `${device.platform} · 可用任务 ${device.tasks.length} 个` : `最后在线：${dateTime(device.lastSeenAt)}`}</Muted>
      {device.apps.length
        ? <ul className={s.apps} aria-label="应用">{device.apps.map((app) => <AppLine key={app.key} device={device} app={app} accounts={accounts} />)}</ul>
        : <Muted>{device.online ? "这台设备上还没有护肝宝支持的游戏" : "离线时不显示应用状态"}</Muted>}
      <Button variant="danger" onClick={remove}>解绑设备</Button>
    </Card>
  );
}

/** What the game showed, and a way to keep it as a game account when it is not one yet. */
function Observed({ app, accounts }: { app: App; accounts: GameAccount[] }) {
  const seen = app.observed!;
  const saved = accounts.some((account) => account.uid === seen.uid);
  const keep = () => {
    const name = prompt("给这个账号起个名字，例如 主号", seen.nickname);
    if (!name) return;
    api("/api/accounts", { method: "POST", body: { gameId: app.gameId, name, uid: seen.uid, nickname: seen.nickname } }).catch((error: Error) => alert(error.message));
  };
  return (
    <Row>
      <Grow><Muted>游戏里：{seen.nickname}（编号 {seen.uid}）{app.switchable ? " · 可切号" : ""}</Muted></Grow>
      {!saved && <Button onClick={keep}>保存为游戏账号</Button>}
    </Row>
  );
}

/**
 * One game instance on the device: what it is doing, and which accounts it uses. Until the app can read the signed-in
 * account, the user picks it here.
 */
function AppLine({ device, app, accounts }: { device: Device; app: App; accounts: GameAccount[] }) {
  const mine = accounts.filter((account) => account.gameId === app.gameId);
  // Both choices are kept here and sent together, so two quick changes cannot undo each other.
  const [current, setCurrent] = useState(app.accountId);
  const [fallback, setFallback] = useState(app.defaultAccountId);
  useEffect(() => { setCurrent(app.accountId); setFallback(app.defaultAccountId); }, [app.accountId, app.defaultAccountId]);
  const save = (accountId: string | null, defaultAccountId: string | null) => {
    setCurrent(accountId); setFallback(defaultAccountId);
    api(`/api/devices/${enc(device.id)}/apps/${enc(app.key)}`, { method: "PUT", body: { accountId, defaultAccountId } }).catch((error: Error) => {
      // Not accepted: show again what the server has.
      setCurrent(app.accountId); setFallback(app.defaultAccountId); alert(error.message);
    });
  };
  const phase = PHASES[app.phase] ?? app.phase;
  const doing = app.currentTask ? `${phase}：${app.currentTask}` : phase;
  const select = (label: string, value: string | null, change: (id: string | null) => void) => (
    <Field inline label={label}>
      <select aria-label={label} value={value ?? ""} onChange={(e) => change(e.target.value || null)}>
        <option value="">未指定</option>
        {mine.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}
      </select>
    </Field>
  );
  return (
    <li className={s.app}>
      <Row><Grow>{app.name}</Grow><Muted>{[doing, app.queued ? `排队 ${app.queued} 个` : ""].filter(Boolean).join(" · ")}</Muted></Row>
      {app.observed && <Observed app={app} accounts={mine} />}
      {mine.length
        ? <div className={s.accounts}>
          {/* Once the app reads its account on screen, the server keeps 当前登录 in step; choosing it by hand is for games it cannot read. */}
          {app.observed ? <Muted>当前登录：{mine.find((item) => item.id === app.accountId)?.name ?? "未保存的账号"}</Muted> : select("当前登录", current, (id) => save(id, fallback))}
          {select("默认账号", fallback, (id) => save(current, id))}
        </div>
        : !app.observed && <Muted>先在“游戏账号”里添加这个游戏的账号</Muted>}
    </li>
  );
}
