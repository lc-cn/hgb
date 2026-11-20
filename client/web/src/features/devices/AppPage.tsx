import { useState } from "react";
import type { App, Device } from "../../api/types";
import { Breadcrumb, Button, Card, Empty, Grow, Muted, PageTabs, Pill, Row, Segments, TextSelect } from "../../components/ui";
import { appStatus, jobSource, jobStatus } from "../../format";
import { Link, navigate, paths, useQuery } from "../../router";
import { useConsole } from "../../state/context";
import { NotFound } from "../ConsolePage";
import { appJobs, JobControls, Note, RunRows, switchNote, useRunNow } from "../jobs/runs";
import { AccountMenu, Observed, useAppAccounts } from "./appAccounts";
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

/** One game instance on a device: its accounts behind a gear, what it runs now and what is queued, and running a task on it. */
export function AppDetail({ device, app, base }: { device: Device; app: App; base: string }) {
  const { data } = useConsole();
  const tab = useQuery().get("tab") === "runs" ? "runs" : "run";
  const phone = device.platform.toLowerCase() === "android";
  const accounts = data.accounts.filter((account) => account.gameId === app.gameId);
  const { current, fallback, setCurrent, setDefault } = useAppAccounts(device, app);
  const { current: job } = appJobs(data.jobs, device.id, app.key);
  const [status, tone] = job?.state === "RUNNING" && job.pauseRequested ? ["已暂停", "warn" as const] : appStatus(app.phase, device.online);
  return (
    <>
      <Breadcrumb items={[["设备", paths.devices], [device.name, phone ? null : paths.device(device.id)], [app.name, null]]} />
      {phone && device.apps.length > 1 && (
        <Segments role="radiogroup" label="这台手机的应用" value={app.key} onChange={(key) => navigate(key === device.apps[0].key ? paths.device(device.id) : paths.app(device.id, key))}
          items={device.apps.map((item) => [item.key, item.name])} />
      )}
      <header className={s.appHeader}>
        <h1 className={s.title}>{app.name}</h1>
        <AccountMenu app={app} accounts={accounts} current={current} fallback={fallback} setCurrent={setCurrent} setDefault={setDefault} />
        <span className={s.end}><Pill tone={tone} dot>{status}</Pill></span>
      </header>
      <Observed app={app} accounts={accounts} />
      <PageTabs label="应用内容" value={tab} tabs={[["run", "运行", base], ["runs", "运行记录", `${base}?tab=runs`]]} />
      {tab === "run" ? <RunTab device={device} app={app} /> : (
        <Card flush>
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
  const note = [pick ? switchNote(app, pick, accounts) : null, device.online ? null : "设备离线，上线后执行"].filter(Boolean).join("；");
  // An idle, online app has nothing to show here: the header already says 空闲.
  const showNow = job || queued.length > 0 || app.currentTask || !device.online;
  return (
    <>
      {showNow && (
        <Card as="section" aria-label="当前运行" flush className={job ? s.currentBusy : undefined}>
          <div className={s.current}>
            {job ? (
              <>
                <div className={s.currentHead}><b>{job.taskName}</b><Pill tone={jobStatus(job)[1]}>{jobStatus(job)[0]}</Pill><Link to={paths.job(job.id)} className={s.end}>步骤 ›</Link></div>
                <Muted>{[job.accountName, jobSource(job), job.detail].filter(Boolean).join(" · ")}</Muted>
                <div className={s.actions}><JobControls job={job} small /></div>
              </>
            ) : (
              <>
                <div className={s.currentHead}><b>{app.currentTask ?? (device.online ? "空闲" : "离线")}</b></div>
                {app.currentTask ? <Muted>这次运行不是从控制台发起的，这里不能暂停或停止</Muted> : !device.online && <Muted>上线后才会收到任务</Muted>}
              </>
            )}
          </div>
          {queued.map((item) => (
            <Row key={item.id}><Muted as="span">接下来</Muted><Grow><Link to={paths.job(item.id)} className={s.plain}>{item.taskName} · {item.accountName ?? "—"}</Link></Grow><Muted as="span">{jobSource(item)}</Muted></Row>
          ))}
        </Card>
      )}
      <Card as="section" flush aria-label="运行">
        {accounts.length > 0 && (
          <div className={s.choiceLine}>用
            <TextSelect label="运行用的游戏账号" value={pick} onChange={(id) => { setChosen(id); clear(); }} options={accounts.map((item): [string, string] => [item.id, item.name])} />运行
          </div>
        )}
        {accounts.length === 0 ? <Muted className={s.cardNote}>先在“游戏账号”里添加这个游戏的账号。</Muted> : (
          <>
            {note && <Muted className={s.cardNote}>{note}</Muted>}
            {tasks.length ? tasks.map((task) => (
              <Row key={task.taskId}>
                <Grow className={s.semi}>{task.name}</Grow>
                <Note note={notes[task.taskId]} />
                <Button small disabled={busy !== null} onClick={() => run(task, pick, { deviceId: device.id, appKey: app.key }, `已排进${app.name}的队列`)}>运行</Button>
              </Row>
            )) : <Muted className={s.cardNote}>这台设备还没有上报这个游戏的任务。</Muted>}
          </>
        )}
      </Card>
    </>
  );
}
