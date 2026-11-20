import { useState } from "react";
import { api, enc } from "../../api/client";
import type { Device, Game, GameAccount, Job, Plan, PlanSave } from "../../api/types";
import { Button, Card, Check, Choices, Facts, Field, Form, Message, Muted, Row, Switch } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { clockText, JOB_STATES, planRule, shortTime, WEEK } from "../../format";
import s from "./Accounts.module.css";

const saveOf = (plan: Plan, change: Partial<PlanSave> = {}): PlanSave => ({
  name: plan.name, days: plan.days, minuteOfDay: plan.minuteOfDay, tasks: plan.tasks, deviceIds: plan.deviceIds,
  latestStartMinutes: plan.latestStartMinutes, enabled: plan.enabled, revision: plan.revision, ...change,
});

/** The account's plans. Changes that arrive while one is being edited do not touch the editor. */
export function PlanList({ account, game, plans, devices, jobs }: { account: GameAccount; game?: Game; plans: Plan[]; devices: Device[]; jobs: Job[] }) {
  const [editing, setEditing] = useState<string | "new" | null>(null);
  const mine = plans.filter((plan) => plan.accountId === account.id);
  const deviceNames = (plan: Plan) => plan.deviceIds.length
    ? `只在 ${plan.deviceIds.map((id) => devices.find((device) => device.id === id)?.name ?? "已解绑的设备").join("、")}` : `所有装了${game?.name ?? "这个游戏"}的设备`;
  const lastRun = (plan: Plan) => {
    const job = jobs.find((item) => item.planId === plan.id);
    return job ? [shortTime(job.updatedAt), job.deviceName, JOB_STATES[job.state][0]].filter(Boolean).join(" · ") : "还没有运行过";
  };
  return (
    <Card as="section" flush aria-label="计划">
      <Row>
        <Muted as="span" className={s.grow}>计划由服务端计时，到点按顺序找应用：登录着{account.name}的应用 → 空闲、没设默认账号、能切号的应用 → 等待。</Muted>
        <Button onClick={() => setEditing("new")} disabled={editing === "new"}>新建计划</Button>
      </Row>
      {editing === "new" && <div className={s.editorWrap}><PlanEditor account={account} game={game} devices={devices} plan={null} close={() => setEditing(null)} /></div>}
      {mine.map((plan) => (
        <div key={plan.id}>
          <Row top>
            <span className={s.planMain}>
              <b className={s.semi}>{plan.name}</b><br />
              <Muted as="span">{planRule(plan)} · {plan.tasks.map((task) => task.name).join("、") || "没有任务"}</Muted><br />
              <span className={s.days} aria-label={`运行日：${planRule(plan)}`}>
                {WEEK.map((label, i) => <span key={label} aria-hidden="true" className={plan.days.includes(i + 1) ? `${s.day} ${s.dayOn}` : s.day}>{label}</span>)}
              </span>
            </span>
            <Facts className={s.planFacts} items={[["可运行设备", deviceNames(plan)], ["最晚开始", `到点后 ${plan.latestStartMinutes} 分钟`], ["上次", lastRun(plan)]]} />
            <span className={s.planOps}>
              <Switch label={`启用计划：${plan.name}`} checked={plan.enabled}
                onChange={(on) => orAlert(() => api(`/api/plans/${enc(plan.id)}`, { method: "PUT", body: saveOf(plan, { enabled: on }) }))} />
              <Button small onClick={() => orAlert(() => api(`/api/plans/${enc(plan.id)}/run`, { method: "POST", body: {} }))}>立即运行</Button>
              <Button small onClick={() => setEditing(editing === plan.id ? null : plan.id)} aria-expanded={editing === plan.id}>编辑</Button>
            </span>
          </Row>
          {editing === plan.id && <div className={s.editorWrap}><PlanEditor key={plan.id} account={account} game={game} devices={devices} plan={plan} close={() => setEditing(null)} /></div>}
        </div>
      ))}
      {mine.length === 0 && editing !== "new" && <Muted className={s.cardNote}>这个账号还没有计划。</Muted>}
    </Card>
  );
}

/** Creates a plan for [account], or edits [plan]. Nothing is filled in on the user's behalf beyond the task list. */
function PlanEditor({ account, game, devices, plan, close }: { account: GameAccount; game?: Game; devices: Device[]; plan: Plan | null; close: () => void }) {
  const [name, setName] = useState(plan?.name ?? "");
  const [at, setAt] = useState(plan ? clockText(plan.minuteOfDay) : "");
  const [late, setLate] = useState(String(plan?.latestStartMinutes ?? 30));
  const [enabled, setEnabled] = useState(plan?.enabled ?? true);
  const [days, setDays] = useState(new Set(plan?.days ?? []));
  const [tasks, setTasks] = useState(new Set((plan?.tasks ?? []).map((task) => task.taskId)));
  const [where, setWhere] = useState(new Set(plan?.deviceIds ?? []));
  const { busy, message, run } = useAction();
  const flip = <T,>(set: Set<T>, item: T, on: boolean) => { const next = new Set(set); if (on) next.add(item); else next.delete(item); return next; };
  const submit = () => run(async () => {
    const [hour, minute] = at.split(":").map(Number);
    // The revision the edit started from: a save over a newer one is refused instead of overwriting it.
    const body: PlanSave = { name, days: [...days].sort(), minuteOfDay: hour * 60 + minute, tasks: (game?.tasks ?? []).filter((task) => tasks.has(task.taskId)),
      deviceIds: devices.filter((device) => where.has(device.id)).map((device) => device.id), latestStartMinutes: Number(late), enabled, revision: plan?.revision ?? 0 };
    if (plan) await api(`/api/plans/${enc(plan.id)}`, { method: "PUT", body });
    else await api(`/api/accounts/${enc(account.id)}/plans`, { method: "POST", body });
    close();
  });
  const remove = () => { if (plan && confirm(`删除计划“${plan.name}”？`)) orAlert(async () => { await api(`/api/plans/${enc(plan.id)}`, { method: "DELETE" }); close(); }); };
  return (
    <Form onSubmit={submit} className={s.editor}>
      <div className={s.editorRow}>
        <Field label="计划名称"><input type="text" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="运行时间"><input type="time" required value={at} onChange={(e) => setAt(e.target.value)} /></Field>
        <Field label="到点后最晚多少分钟内开始"><input type="number" min={1} max={1440} required value={late} onChange={(e) => setLate(e.target.value)} /></Field>
      </div>
      <fieldset className={s.plainSet}>
        <legend className={s.legend}>运行周期</legend>
        <div className={s.days}>
          {WEEK.map((label, i) => (
            <button key={label} type="button" role="checkbox" aria-checked={days.has(i + 1)} aria-label={`周${label}`}
              className={days.has(i + 1) ? `${s.dayPick} ${s.dayOn}` : s.dayPick} onClick={() => setDays(flip(days, i + 1, !days.has(i + 1)))}>{label}</button>
          ))}
        </div>
      </fieldset>
      <fieldset className={s.plainSet}>
        <legend className={s.legend}>按顺序运行的任务</legend>
        <Choices>{(game?.tasks ?? []).map((task) => <Check key={task.taskId} label={task.name} checked={tasks.has(task.taskId)} onChange={(on) => setTasks(flip(tasks, task.taskId, on))} />)}</Choices>
      </fieldset>
      <fieldset className={s.plainSet}>
        <legend className={s.legend}>可以在哪些设备上运行（都不选就是所有装了这个游戏的设备）</legend>
        <Choices>{devices.map((device) => <Check key={device.id} label={device.name} checked={where.has(device.id)} onChange={(on) => setWhere(flip(where, device.id, on))} />)}</Choices>
      </fieldset>
      <label className={s.switchLine}><Switch label="启用" checked={enabled} onChange={setEnabled} /><span>启用</span></label>
      <div className={s.formButtons}>
        <Button variant="primary" type="submit" disabled={busy}>{plan ? "保存计划" : "新建计划"}</Button>
        <Button onClick={close}>取消</Button>
        {plan && <Button variant="danger" onClick={remove} className={s.pushEnd}>删除计划</Button>}
      </div>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}
