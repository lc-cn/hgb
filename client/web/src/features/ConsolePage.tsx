import { api } from "../api/client";
import type { Account } from "../api/types";
import { Brand, Button, Grow, Message, Muted, Row } from "../components/ui";
import { orAlert } from "../components/useAction";
import { useConsoleStream } from "../state/console";
import { AccountsPanel } from "./accounts/Accounts";
import { DeviceList } from "./devices/DeviceCard";
import { JobForm, JobList } from "./jobs/Jobs";
import s from "./ConsolePage.module.css";

/** The signed-in console: devices, game accounts, running a task and the job records, all kept live by the stream. */
export function ConsolePage({ account, signedOut }: { account: Account; signedOut: () => void }) {
  const { data, connection, loaded } = useConsoleStream(signedOut);
  const changeZone = () => {
    const next = prompt("计划按哪个时区计时？例如 Asia/Shanghai", data.timeZone);
    if (next && next !== data.timeZone) orAlert(() => api("/api/me/time-zone", { method: "PUT", body: { timeZone: next } }));
  };
  const logout = () => orAlert(async () => { await api("/api/auth/logout", { method: "POST", body: {} }); signedOut(); });
  return (
    <main className={s.shell}>
      <header className={s.top}>
        <Grow><Brand /></Grow>
        <span className={s.who}>{account.email ?? account.githubLogin ?? ""}</span>
        <Button onClick={logout}>退出登录</Button>
      </header>
      <Row>
        <Grow><Message text={connection === "live" ? "实时连接中" : connection === "lost" ? "与服务器的连接断开了，正在重连…" : "正在连接…"} ok={connection === "live"} /></Grow>
        <Muted>计划时区：{data.timeZone} <Button variant="link" onClick={changeZone}>修改</Button></Muted>
      </Row>
      {loaded && <>
        <section className={s.section}><h2>设备</h2><DeviceList devices={data.devices} accounts={data.accounts} /></section>
        <section className={s.section}>
          <h2>游戏账号</h2>
          <Muted>任务设置、计划和运行记录都属于游戏账号，不属于某台设备。</Muted>
          <AccountsPanel accounts={data.accounts} games={data.games} plans={data.plans} devices={data.devices} />
        </section>
        <section className={s.section}><h2>下发作业</h2><JobForm devices={data.devices} accounts={data.accounts} games={data.games} /></section>
        <section className={s.section}><h2>作业记录</h2><JobList jobs={data.jobs} logVersion={data.logVersion} epoch={data.epoch} /></section>
      </>}
    </main>
  );
}
