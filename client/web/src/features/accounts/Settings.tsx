import { useState } from "react";
import { api, enc } from "../../api/client";
import type { ConfigView, Game, GameAccount, SettingField } from "../../api/types";
import { Button, Check, Choices, Message, Muted, Row } from "../../components/ui";
import { useAction } from "../../components/useAction";
import s from "./Accounts.module.css";

/** Settings belong to a game account. Fields come from the newest uploaded version that has the task. */
export function SettingsForm({ account, game }: { account: GameAccount; game?: Game }) {
  const tasks = game?.tasks ?? [];
  const [chosen, setChosen] = useState(tasks[0]?.taskId ?? "");
  // Follows the game's task list: a task that is gone falls back to the first one.
  const taskId = tasks.some((task) => task.taskId === chosen) ? chosen : tasks[0]?.taskId ?? "";
  const [view, setView] = useState<ConfigView | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const { busy, message, run } = useAction();
  if (tasks.length === 0) return <Muted>这个游戏还没有可设置的任务。</Muted>;
  const pathOf = (task: string) => `/api/accounts/${enc(account.id)}/configs/${enc(account.gameId)}/${enc(task)}`;
  const open = () => run(async () => {
    const loaded = await api<ConfigView>(pathOf(taskId));
    // A field never saved starts from its recommended value, marked until it is saved.
    const start: Record<string, unknown> = {}; const filled = new Set<string>();
    for (const def of loaded.fields) {
      if (Object.prototype.hasOwnProperty.call(loaded.values, def.id)) start[def.id] = loaded.values[def.id];
      else if (def.recommended != null) { start[def.id] = def.recommended; filled.add(def.id); }
    }
    setView(loaded); setValues(start); setFresh(filled);
  });
  const save = () => run(async () => {
    if (!view) return;
    const body = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined));
    // Saved to the task the open settings were read for, whatever the list shows meanwhile.
    const saved = await api<ConfigView>(pathOf(view.taskId), { method: "PUT", body: { values: body, revision: view.revision } });
    setView(saved); setFresh(new Set());
    return `已保存，之后${account.name}的作业和计划都用这些设置`;
  });
  return (
    <div className={s.settings}>
      <Row>
        <select aria-label="任务" className={s.taskSelect} value={taskId} disabled={busy} onChange={(e) => { setChosen(e.target.value); setView(null); }}>
          {tasks.map((task) => <option key={task.taskId} value={task.taskId}>{task.name}</option>)}
        </select>
        <Button onClick={open} disabled={busy}>编辑设置</Button>
      </Row>
      {view && (view.fields.length === 0 ? <Muted>这个任务没有可修改的设置。</Muted> : (
        <>
          <Muted>{view.taskName} · 任务版本 {view.version}</Muted>
          {view.fields.map((def) => (
            <div key={def.id} className={s.setting}>
              <div className={s.settingName}>{def.label}{def.required ? "（必填）" : ""}</div>
              {def.description && <Muted>{def.description}</Muted>}
              {fresh.has(def.id) && <Muted>已填入推荐值，保存后生效</Muted>}
              <SettingInput def={def} value={values[def.id]} change={(value) => setValues((now) => ({ ...now, [def.id]: value }))} />
            </div>
          ))}
          <Button variant="primary" onClick={save} disabled={busy}>保存设置</Button>
        </>
      ))}
      <Message text={message?.text} ok={message?.ok} />
    </div>
  );
}

/** One input per setting the task declares; undefined means the field is left out of the save. */
function SettingInput({ def, value, change }: { def: SettingField; value: unknown; change: (value: unknown) => void }) {
  const disabled = def.readOnly === true;
  const options = def.options ?? [];
  switch (def.widget) {
    case "SINGLE_CHOICE":
      return (
        <select disabled={disabled} value={typeof value === "string" ? value : ""} onChange={(e) => change(e.target.value || undefined)}>
          <option value="">请选择</option>
          {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      );
    case "MULTI_CHOICE": {
      const chosen = new Set(Array.isArray(value) ? value as string[] : []);
      return (
        <Choices>
          {options.map((option) => <Check key={option.value} label={option.label} disabled={disabled} checked={chosen.has(option.value)}
            onChange={(on) => change(options.map((item) => item.value).filter((id) => id === option.value ? on : chosen.has(id)))} />)}
        </Choices>
      );
    }
    case "SWITCH": return <Check label="开启" disabled={disabled} checked={value === true} onChange={change} />;
    case "NUMBER": case "RANGE":
      return <input type="number" disabled={disabled} min={def.minimum} max={def.maximum} step={def.step ?? "any"}
        value={typeof value === "number" ? String(value) : ""} onChange={(e) => change(e.target.value === "" ? undefined : Number(e.target.value))} />;
    case "TEXT": case "TIME": case "DATE":
      return <input type={def.widget === "TEXT" ? "text" : def.widget.toLowerCase()} disabled={disabled}
        value={typeof value === "string" ? value : ""} onChange={(e) => change(e.target.value || undefined)} />;
    default: return <Muted>这个设置暂时只能在 App 里修改</Muted>;
  }
}

