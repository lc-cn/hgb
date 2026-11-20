import { useState } from "react";
import type { App, Device } from "../../api/types";
import { Breadcrumb, Button, Card, CardTitle, Empty, Field, Grow, Muted, PageTabs, Pill, Row, Segments } from "../../components/ui";
import { appStatus, jobSource, jobStatus, platformName } from "../../format";
import { Link, navigate, paths, useQuery } from "../../router";
import { useConsole } from "../../state/context";
import { NotFound } from "../ConsolePage";
import { appJobs, Hint, JobControls, Note, placeHint, RunRows, useRunNow } from "../jobs/runs";
import { AccountSelect, CurrentAccount, defaultHint, Observed, useAppAccounts } from "./appAccounts";
import s from "./Devices.module.css";

/** 应用详情 at its own address; a phone's device page shows this for its first app. */
export function AppPage({ deviceId, appKey }: { deviceId: string; appKey: string }) {
  const { data } = useConsole();
  const device = data.devices.find((item) => item.id === deviceId);
  const app = device?.apps.find((item) => item.key === appKey);
  if (!device) return <NotFound text="找不到这台设备，它可能已经解绑" />;
  if (!app) return <NotFound text="这台设备上没有这个应用" back={paths.device(device.id)} backText="回到设备" />;
  return <AppDetail device={device} app={app} base={paths.app(device.id, app.key)} />;
}

/** One game instance on a device: its accounts, what it runs now, what waits for it, and running a task on it. */
export function AppDetail({ device, app, base }: { device: Device; app: App; base: string }) {
  const { data } = useConsole();
  const tab = useQuery().get("tab") === "runs" ? "runs" : "run";
  const phone = device.platform.toLowerCase() === "android";
  const game = data.games.find((item) => item.id === app.gameId);
  const accounts = data.accounts.filter((account) => account.gameId === app.gameId);
  const { current, fallback, setCurrent, setDefault } = useAppAccounts(device, app);
  const { current: job } = appJobs(data.jobs, device.id, app.key);
  const [status, tone] = job?.state === "RUNNING" && job.pauseRequested ? ["已暂停", "warn" as const] : appStatus(app.phase, device.online);
  const signedIn = accounts.find((item) => item.id === app.accountId);
  return (
    <>
      <Breadcrumb items={[["设备", paths.devices], [device.name, phone ? null : paths.device(device.id)], [app.name, null]]} />
      {phone && device.apps.length > 1 && (
        <Segments role="radiogroup" label="这台手机的应用" value={app.key} onChange={(key) => navigate(key === device.apps[0].key ? paths.device(device.id) : paths.app(device.id, key))}
          items={device.apps.map((item) => [item.key, item.name])} />
      )}
      <header className={s.appHeader}>
        <h1 className={s.title}>{app.name}</h1>
        <Pill tone={tone} dot>{status}</Pill>
        <span className={s.headLine}>
          {game?.name ?? app.gameId} · {platformName(device.platform)} · 当前登录 <CurrentAccount app={app} accounts={accounts} current={current} setCurrent={setCurrent} />
          {signedIn && <> · <Link to={paths.account(signedIn.id)}>{signedIn.name}的设置和计划</Link></>}
        </span>
        <Field inline label="默认账号"><AccountSelect label={`${app.name} 的默认账号`} value={fallback} accounts={accounts} onChange={setDefault} none="不设" /></Field>
      </header>
      <Muted className={s.under}>{defaultHint(accounts, fallback)}</Muted>
      <Observed app={app} accounts={accounts} />
      <PageTabs label="应用内容" value={tab} tabs={[["run", "运行", base], ["runs", "运行记录", `${base}?tab=runs`]]} />
      {tab === "run" ? <RunTab device={device} app={app} /> : (
        <Card flush>
          <Muted className={s.cardNote}>在{app.name}上运行过的记录，包括所有账号。</Muted>
          {(() => {
            const mine = data.jobs.filter((item) => item.deviceId === device.id && item.appKey === app.key);
            return mine.length ? <RunRows jobs={mine} devices={data.devices} columns={["account", "source"]} /> : <Empty>还没有运行记录</Empty>;
          })()}
        </Card>
      )}
    </>
  );
}

function RunTab({ device, app }: { device: Device; app: App }) {
  const { data } = useConsole();
  const { current: job, queued } = appJobs(data.jobs, device.id, app.key);
  const accounts = data.accounts.filter((account) => account.gameId === app.gameId);
  const tasks = device.tasks.filter((task) => task.targetId === app.gameId);
  const [chosen, setChosen] = useState<string | null>(null);
  const pick = accounts.some((item) => item.id === chosen) ? chosen! : (app.accountId && accounts.some((item) => item.id === app.accountId) ? app.accountId : accounts[0]?.id ?? "");
  const { notes, busy, run, clear } = useRunNow();
  return (
    <>
      <div className={s.split}>
        <Card as="section" aria-label="当前运行" className={job ? `${s.current} ${s.currentBusy}` : s.current}>
          {job ? (
            <>
              <div className={s.currentHead}><b>{job.taskName}</b><Pill tone={jobStatus(job)[1]}>{jobStatus(job)[0]}</Pill></div>
              <Muted>{[job.accountName, job.planName ? `来自计划“${job.planName}”` : "立即运行", job.detail].filter(Boolean).join(" · ")}</Muted>
              <div className={s.actions}><JobControls job={job} /><Link to={paths.job(job.id)} className={s.end}>查看步骤</Link></div>
            </>
          ) : (
            <>
              <div className={s.currentHead}><b>{app.currentTask ?? (device.online ? "空闲" : "离线")}</b></div>
              <Muted>{app.currentTask ? "云端没有这次运行的作业，网页上不能暂停或停止它。" : device.online ? "没有正在运行的任务。" : "设备离线，上线后才能收到任务。"}</Muted>
            </>
          )}
        </Card>
        <Card as="section" flush aria-labelledby="queue" className={s.queue}>
          <CardTitle id="queue">排在这个应用后面</CardTitle>
          {queued.length ? queued.map((item) => (
            <Row key={item.id}><Grow><Link to={paths.job(item.id)} className={s.plain}><b className={s.semi}>{item.taskName}</b> · {item.accountName ?? "—"}</Link></Grow><Muted as="span">{jobSource(item)}</Muted></Row>
          )) : <Muted className={s.cardNote}>队列为空</Muted>}
        </Card>
      </div>
      <Card as="section" flush aria-labelledby="run-here">
        <CardTitle id="run-here">在这个应用运行</CardTitle>
        {accounts.length === 0 ? <Muted className={s.cardNote}>先在“游戏账号”里添加这个游戏的账号，任务会用账号的设置运行。</Muted> : (
          <>
            <Row>
              <Field inline label="账号">
                <select aria-label="运行用的游戏账号" value={pick} onChange={(e) => { setChosen(e.target.value); clear(); }}>
                  {accounts.map((item) => <option key={item.id} value={item.id}>{item.name}{item.id === app.accountId ? "（当前登录）" : ""}</option>)}
                </select>
              </Field>
              <Muted as="span">{placeHint(app, pick, accounts)}</Muted>
            </Row>
            {!device.online && <Row><Hint>设备离线：现在提交会等它上线，超过最晚开始时间（30 分钟）就不执行。</Hint></Row>}
            {tasks.length ? tasks.map((task) => (
              <Row key={task.taskId}>
                <Grow className={s.semi}>{task.name}</Grow>
                <Note note={notes[task.taskId]} />
                <Button variant="primary" disabled={busy !== null} onClick={() => run(task, pick, { deviceId: device.id, appKey: app.key }, `已提交，排在${app.name}的队列`)}>运行</Button>
              </Row>
            )) : <Muted className={s.cardNote}>这台设备还没有上报这个游戏的任务。</Muted>}
          </>
        )}
      </Card>
    </>
  );
}
