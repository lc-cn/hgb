import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { CloudTask, ConfigView, GameAccount, SettingField } from "../../api/types";
import { Button, Card, Check, Choices, Muted, Switch } from "../../components/ui";
import { useAction } from "../../components/useAction";
import s from "./Accounts.module.css";

/** The account's settings for one task. Fields come from the newest uploaded version that has the task. */
export function SettingsForm({ account, task }: { account: GameAccount; task: CloudTask }) {
  const [view, setView] = useState<ConfigView | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const [dirty, setDirty] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const { busy, message, run } = useAction();
  const path = `/api/accounts/${enc(account.id)}/configs/${enc(task.targetId)}/${enc(task.taskId)}`;
  useEffect(() => {
    let current = true;
    api<ConfigView>(path).then((loaded) => {
      if (!current) return;
      // A field never saved starts from its recommended value, marked until it is saved.
      const start: Record<string, unknown> = {}; const filled = new Set<string>();
      for (const def of loaded.fields) {
        if (Object.prototype.hasOwnProperty.call(loaded.values, def.id)) start[def.id] = loaded.values[def.id];
        else if (def.recommended != null) { start[def.id] = def.recommended; filled.add(def.id); }
      }
      setView(loaded); setValues(start); setFresh(filled); setDirty(filled.size > 0);
    }).catch((error: Error) => { if (current) setLoadError(error.message); });
    return () => { current = false; };
  }, [path]);
  const save = () => run(async () => {
    if (!view) return;
    const body = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined));
    const saved = await api<ConfigView>(path, { method: "PUT", body: { values: body, revision: view.revision } });
    setView(saved); setFresh(new Set()); setDirty(false);
  });
  const change = (id: string, value: unknown) => { setValues((now) => ({ ...now, [id]: value })); setDirty(true); };
  const status = message && !message.ok ? message.text : dirty ? (fresh.size ? "已填入推荐值，保存后生效" : "有未保存的修改") : "已保存在服务端 · 之后的运行都用这一套";
  const statusClass = message && !message.ok ? s.statusBad : dirty ? s.statusWarn : s.statusOk;
  return (
    <Card as="section" aria-labelledby="settings-title" className={s.settings}>
      <h2 id="settings-title" className={s.settingsTitle}>{task.name} · 设置</h2>
      {loadError ? <Muted>{loadError}</Muted> : !view ? <Muted>正在读取设置…</Muted> : view.fields.length === 0 ? <Muted>这个任务不需要设置。</Muted> : (
        <>
          <Muted>任务版本 {view.version}</Muted>
          {view.fields.map((def) => (
            <fieldset key={def.id} className={s.setting}>
              <legend className={s.legend}>{def.label}{def.required ? "（必填）" : ""}</legend>
              {def.description && <Muted>{def.description}</Muted>}
              <SettingInput def={def} value={values[def.id]} change={(value) => change(def.id, value)} />
            </fieldset>
          ))}
          <div className={s.saveBar}>
            <span role="status" className={statusClass}>{status}</span>
            <Button variant="primary" onClick={save} disabled={busy}>保存</Button>
          </div>
        </>
      )}
    </Card>
  );
}

/** One input per setting the task declares; undefined means the field is left out of the save. */
function SettingInput({ def, value, change }: { def: SettingField; value: unknown; change: (value: unknown) => void }) {
  const disabled = def.readOnly === true;
  const options = def.options ?? [];
  switch (def.widget) {
    case "SINGLE_CHOICE":
      return (
        <div role="radiogroup" aria-label={def.label} className={s.optionGrid}>
          {options.map((option) => (
            <button key={option.value} type="button" role="radio" aria-checked={value === option.value} disabled={disabled}
              className={value === option.value ? `${s.option} ${s.optionOn}` : s.option} onClick={() => change(option.value)}>{option.label}</button>
          ))}
        </div>
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
    case "SWITCH": return <Switch label={def.label} disabled={disabled} checked={value === true} onChange={change} />;
    case "NUMBER": case "RANGE":
      return <input className={s.input} type="number" aria-label={def.label} disabled={disabled} min={def.minimum} max={def.maximum} step={def.step ?? "any"}
        value={typeof value === "number" ? String(value) : ""} onChange={(e) => change(e.target.value === "" ? undefined : Number(e.target.value))} />;
    case "TEXT": case "TIME": case "DATE":
      return <input className={s.input} aria-label={def.label} type={def.widget === "TEXT" ? "text" : def.widget.toLowerCase()} disabled={disabled}
        value={typeof value === "string" ? value : ""} onChange={(e) => change(e.target.value || undefined)} />;
    default: return <Muted>这个设置暂时只能在 App 里修改</Muted>;
  }
}
