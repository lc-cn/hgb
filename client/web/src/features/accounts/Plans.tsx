import { useState } from "react";
import { api, enc } from "../../api/client";
import type { Device, Game, GameAccount, Plan, PlanSave } from "../../api/types";
import { Button, Check, Choices, Field, Form, Grow, Message, Muted, Pill, Row } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { clockText, planRule, WEEK } from "../../format";
import s from "./Accounts.module.css";

/** The account's plans. Changes that arrive while one is being edited do not touch the editor. */
export function PlanList({ account, game, plans, devices }: { account: GameAccount; game?: Game; plans: Plan[]; devices: Device[] }) {
  const [editing, setEditing] = useState<Plan | "new" | null>(null);
  const mine = plans.filter((plan) => plan.accountId === account.id);
  const remove = (plan: Plan) => { if (confirm(`删除计划“${plan.name}”？`)) orAlert(() => api(`/api/plans/${enc(plan.id)}`, { method: "DELETE" })); };
  return (
    <div className={s.plans}>
      {mine.map((plan) => (
        <div key={plan.id} className={s.planRow}>
          <Grow>
            <div className={s.settingName}>{plan.name}</div>
            <Muted>{[planRule(plan), plan.tasks.map((task) => task.name).join("、"), plan.deviceIds.length ? `${plan.deviceIds.length} 台设备` : "所有设备",
              `最晚 ${plan.latestStartMinutes} 分钟内开始`].join(" · ")}</Muted>
          </Grow>
          <Pill tone={plan.enabled ? "ok" : ""}>{plan.enabled ? "已启用" : "已停用"}</Pill>
          <Button onClick={() => orAlert(() => api(`/api/plans/${enc(plan.id)}/run`, { method: "POST", body: {} }))}>立即运行</Button>
          <Button onClick={() => setEditing(plan)}>编辑</Button>
          <Button onClick={() => remove(plan)}>删除</Button>
        </div>
      ))}
      {mine.length === 0 && <Muted>这个账号还没有计划。</Muted>}
      {editing ? <PlanEditor key={editing === "new" ? "new" : editing.id} account={account} game={game} devices={devices}
        plan={editing === "new" ? null : editing} close={() => setEditing(null)} />
        : <Button onClick={() => setEditing("new")}>新建计划</Button>}
    </div>
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
  return (
    <Form onSubmit={submit} className={s.editor}>
      <Field label="计划名称"><input type="text" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
      <div className={s.settingName}>运行周期</div>
      <Choices>{WEEK.map((label, i) => <Check key={label} label={`周${label}`} checked={days.has(i + 1)} onChange={(on) => setDays(flip(days, i + 1, on))} />)}</Choices>
      <Field label="运行时间"><input type="time" required value={at} onChange={(e) => setAt(e.target.value)} /></Field>
      <div className={s.settingName}>按顺序运行的任务</div>
      <Choices>{(game?.tasks ?? []).map((task) => <Check key={task.taskId} label={task.name} checked={tasks.has(task.taskId)} onChange={(on) => setTasks(flip(tasks, task.taskId, on))} />)}</Choices>
      <div className={s.settingName}>可以在哪些设备上运行（都不选就是所有装了这个游戏的设备）</div>
      <Choices>{devices.map((device) => <Check key={device.id} label={device.name} checked={where.has(device.id)} onChange={(on) => setWhere(flip(where, device.id, on))} />)}</Choices>
      <Field label="到点后最晚多少分钟内开始，超过就跳过"><input type="number" min={1} max={1440} required value={late} onChange={(e) => setLate(e.target.value)} /></Field>
      <Check label="启用" checked={enabled} onChange={setEnabled} />
      <Row>
        <Button variant="primary" type="submit" disabled={busy}>{plan ? "保存计划" : "新建计划"}</Button>
        <Button onClick={close}>取消</Button>
      </Row>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}
