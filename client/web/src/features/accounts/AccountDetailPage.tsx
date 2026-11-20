import { useState } from "react";
import type { CloudTask, GameAccount } from "../../api/types";
import { Breadcrumb, Button, Card, Empty, Muted, PageTabs, Pill, TextSelect } from "../../components/ui";
import { paths } from "../../router";
import { useConsole } from "../../state/context";
import { NotFound } from "../ConsolePage";
import { Note, RunRows, switchNote, useRunNow } from "../jobs/runs";
import { accountNow } from "./GameAccountsPage";
import { PlanList } from "./Plans";
import { SettingsForm } from "./Settings";
import s from "./Accounts.module.css";

type Tab = "tasks" | "plans" | "runs";

/** 游戏账号详情: everything that belongs to the account, whichever device or app runs it. */
export function AccountDetailPage({ id, tab }: { id: string; tab: Tab }) {
  const { data } = useConsole();
  const account = data.accounts.find((item) => item.id === id);
  if (!account) return <NotFound text="找不到这个游戏账号，它可能已经移除" back={paths.accounts} backText="回到游戏账号" />;
  const game = data.games.find((item) => item.id === account.gameId);
  const [now, tone] = accountNow(account, data);
  const runs = data.jobs.filter((job) => job.accountId === account.id);
  return (
    <>
      <Breadcrumb items={[["游戏账号", paths.accounts], [game?.name ?? account.gameId, null], [account.name, null]]} />
      <header className={s.header}>
        <h1 className={s.title}>{account.name}</h1>
        <Muted as="span">{[game?.name ?? account.gameId, account.uid ? `编号 ${account.uid}` : null, account.nickname].filter(Boolean).join(" · ")}</Muted>
        <Pill tone={tone} className={s.headPill}>{now}</Pill>
      </header>
      <PageTabs label="账号内容" value={tab} tabs={[["tasks", "任务与设置", paths.account(account.id)], ["plans", "计划", paths.account(account.id, "plans")], ["runs", "运行记录", paths.account(account.id, "runs")]]} />
      {tab === "tasks" && <TasksTab account={account} tasks={game?.tasks ?? []} />}
      {tab === "plans" && <PlanList account={account} game={game} plans={data.plans} devices={data.devices} jobs={data.jobs} />}
      {tab === "runs" && <Card as="section" flush aria-label="运行记录">{runs.length ? <RunRows jobs={runs} devices={data.devices} columns={["place", "source"]} /> : <Empty>这个账号还没有运行记录</Empty>}</Card>}
    </>
  );
}

function TasksTab({ account, tasks }: { account: GameAccount; tasks: CloudTask[] }) {
  const { data } = useConsole();
  const [chosen, setChosen] = useState(tasks[0]?.taskId ?? "");
  const taskId = tasks.some((task) => task.taskId === chosen) ? chosen : tasks[0]?.taskId ?? "";
  const [where, setWhere] = useState("");
  const { notes, busy, run, clear } = useRunNow();
  const places = data.devices.filter((device) => device.online).flatMap((device) => device.apps.filter((app) => app.gameId === account.gameId)
    .map((app) => ({ value: JSON.stringify([device.id, app.key]), label: device.apps.length > 1 ? `${device.name} · ${app.name}` : device.name, device, app })));
  const place = places.find((item) => item.value === where);
  if (tasks.length === 0) return <Card><Empty>这个游戏还没有任务。设备上传任务包后会出现在这里。</Empty></Card>;
  return (
    <>
      <div className={s.split}>
        <Card as="section" flush aria-label="任务" className={s.taskList}>
          <div className={s.choiceLine}>在
            <TextSelect label="运行的位置" value={place ? where : ""} onChange={(next) => { setWhere(next); clear(); }}
              options={[["", "自动分配"], ...places.map((item): [string, string] => [item.value, item.label])]} />运行
          </div>
          {place && switchNote(place.app, account.id, data.accounts.filter((item) => item.gameId === account.gameId)) &&
            <Muted className={s.listNote}>{switchNote(place.app, account.id, data.accounts.filter((item) => item.gameId === account.gameId))}</Muted>}
          {tasks.map((task) => (
            <div key={task.taskId} className={task.taskId === taskId ? `${s.taskRow} ${s.taskOn}` : s.taskRow}>
              <button type="button" className={s.taskPick} aria-pressed={task.taskId === taskId} onClick={() => setChosen(task.taskId)}>
                <b>{task.name}</b>
              </button>
              <Note note={notes[task.taskId]} />
              <Button small disabled={busy !== null} onClick={() => run(task, account.id, place ? { deviceId: place.device.id, appKey: place.app.key } : null,
                place ? `已排进${place.label}的队列` : "已提交，服务端会分配应用")}>运行</Button>
            </div>
          ))}
        </Card>
        <SettingsForm key={taskId} account={account} task={tasks.find((task) => task.taskId === taskId)!} />
      </div>
    </>
  );
}
