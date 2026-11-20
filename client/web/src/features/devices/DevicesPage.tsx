import type { Device, GameAccount, Job } from "../../api/types";
import { ButtonLink, Card, Empty, Facts, PageHeader, Pill } from "../../components/ui";
import { dateTime, JOB_STATES, platformName, shortTime, type Tone } from "../../format";
import { Link, paths } from "../../router";
import { useConsole } from "../../state/context";
import { lastFinished, RunRows } from "../jobs/runs";
import s from "./Devices.module.css";

/** 设备: a card per device with what it is doing, then the latest runs across all of them. */
export function DevicesPage() {
  const { data } = useConsole();
  return (
    <>
      <PageHeader title="设备" subtitle="设备上的应用负责执行；任务设置和计划在游戏账号里。进入设备可以远程查看和控制。"
        aside={<ButtonLink to={paths.me}>添加设备</ButtonLink>} />
      {data.devices.length === 0
        ? <Card><Empty>还没有设备。在手机上打开护肝宝并登录这个账号，设备会自动出现在这里。</Empty></Card>
        : <div className={s.grid}>{data.devices.map((device) => <DeviceCard key={device.id} device={device} accounts={data.accounts} jobs={data.jobs} />)}</div>}
      <section aria-labelledby="recent" className={s.section}>
        <h2 id="recent" className={s.h2}>最近运行 · 所有设备</h2>
        <Card flush>
          {data.jobs.length ? <RunRows jobs={data.jobs} devices={data.devices} columns={["place", "source"]} limit={12} /> : <Empty>还没有运行记录</Empty>}
        </Card>
      </section>
    </>
  );
}

export function deviceStatus(device: Device, jobs: Job[]): [string, Tone, number] {
  const running = device.apps.filter((app) => app.phase === "RUNNING" || jobs.some((job) => job.deviceId === device.id && job.appKey === app.key && job.state === "RUNNING")).length;
  if (!device.online) return ["离线", "", 0];
  if (running > 1) return [`${running} 个应用运行中`, "info", running];
  if (running === 1 || device.phase === "RUNNING") return ["运行中", "info", Math.max(running, 1)];
  return ["在线", "ok", 0];
}
const accountName = (accounts: GameAccount[], id: string | null) => accounts.find((item) => item.id === id)?.name;

function DeviceCard({ device, accounts, jobs }: { device: Device; accounts: GameAccount[]; jobs: Job[] }) {
  const [status, tone, running] = deviceStatus(device, jobs);
  const active = jobs.filter((job) => job.deviceId === device.id && (job.state === "RUNNING" || job.state === "LEASED"));
  let now: string;
  if (!device.online) now = `最后在线 ${dateTime(device.lastSeenAt)}`;
  else if (active.length > 1 || running > 1) now = `${active.length || running} 个应用在运行（${active.map((job) => job.accountName ?? job.taskName).join("、")}）`;
  else if (active.length === 1) {
    const job = active[0]; const app = device.apps.find((item) => item.key === job.appKey);
    now = [app?.name, job.accountName, job.taskName].filter(Boolean).join(" · ");
  } else if (device.apps.length === 0) now = "空闲 · 还没有护肝宝支持的游戏";
  else now = `空闲 · ${device.apps.map((app) => `${app.name}当前登录${accountName(accounts, app.accountId) ?? (app.observed ? "未保存的账号" : "未指定账号")}`).join("；")}`;
  const apps = device.apps.map((app) => { const def = accountName(accounts, app.defaultAccountId); return def ? `${app.name}（默认${def}）` : app.name; }).join("、") || "—";
  const last = lastFinished(jobs, (job) => job.deviceId === device.id);
  const queued = jobs.filter((job) => job.deviceId === device.id && job.state === "QUEUED").length;
  const facts: [string, string][] = [["应用", apps]];
  if (queued) facts.push(["排队", `${queued} 个作业`]);
  facts.push(["上次结果", last ? `${shortTime(last.updatedAt)} ${last.taskName} ${JOB_STATES[last.state][0]}` : "还没有运行过"]);
  return (
    <Link to={paths.device(device.id)} className={s.deviceCard}>
      <div className={s.cardHead}>
        <span className={s.cardName}><b>{device.name}</b><span>{platformName(device.platform)}</span></span>
        <Pill tone={tone} dot>{status}</Pill>
      </div>
      <div className={running && device.online ? `${s.now} ${s.nowBusy}` : s.now}>
        <div className={s.nowLabel}>{device.online ? "现在" : "最后状态"}</div>
        <div className={s.nowText}>{now}</div>
      </div>
      <Facts items={facts} />
    </Link>
  );
}
