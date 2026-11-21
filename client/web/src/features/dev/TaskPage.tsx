import { useState } from "react";
import { Link, navigate } from "../../router";
import type { Obj } from "./api";
import { devPaths, useDev, type Edit } from "./context";
import { clock, uniqueId, uses } from "./HomePage";
import { childIds, childrenOf, isObj, nodeDigest, objs, programOf, snippets, str, target, tasks } from "./model";
import w from "./Wb.module.css";
import t from "./Task.module.css";

const CATEGORY: Record<string, string> = { daily: "日常", weekly: "每周", event: "活动", account: "账号" };

/** ③ 任务编辑: the task's steps in order, inserted from the snippet library or new, moved and removed. */
export function TaskPage({ id }: { id: string }) {
  const { draft, gameName, trials, digests, apply } = useDev();
  const [selected, setSelected] = useState(0);
  const [insertAt, setInsertAt] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [json, setJson] = useState(false);
  const [text, setText] = useState<string | null>(null);
  const task = draft ? tasks(draft.body).find((x) => x.id === id) : undefined;
  if (!draft || !task) return <div className={w.card}><div className={w.empty}>草稿里没有这个任务</div></div>;
  const lib = snippets(draft.body);
  const all = tasks(draft.body);
  const trial = trials.find((x) => x.task === id);
  const fresh = trial && trial.digest === digests[`trial/${id}`] ? trial : null;
  const steps = task.steps;
  const sel = steps[Math.min(selected, steps.length - 1)];

  const run = async (edit: Edit) => { setText(null); const problem = await apply(edit); if (problem) setText(problem); return !problem; };
  /** The task's steps as the latest draft has them. */
  const stepsIn = (body: Obj) => tasks(body).find((x) => x.id === id)?.steps ?? [];
  const taskPath = `tasks:${id}`;
  const move = async (i: number, to: number) => {
    const sid = String(steps[i].id);
    if (await run(async (body) => {
      const now = stepsIn(body); const step = now.find((st) => st.id === sid); if (!step) return [];
      const rest = now.filter((st) => st.id !== sid);
      return [{ op: "delete", path: `${taskPath}/steps:${sid}`, expect: await nodeDigest(step) },
        { op: "insert", path: taskPath, field: "steps", after: to === 0 ? null : String(rest[to - 1]?.id ?? rest.at(-1)?.id), value: step }];
    })) setSelected(to);
  };
  const remove = async (i: number) => {
    const sid = String(steps[i].id);
    if (!confirm(`把「${str(steps[i].name) ?? sid}」移出任务？`)) return;
    await run(async (body) => { const step = stepsIn(body).find((st) => st.id === sid); return step ? [{ op: "delete", path: `${taskPath}/steps:${sid}`, expect: await nodeDigest(step) }] : []; });
  };
  /** Sets or removes a step's run condition, on the step as the latest draft has it. */
  const setWhen = (sid: string, next: Obj | null) => run(async (body) => {
    const step = stepsIn(body).find((st) => st.id === sid);
    if (!step) return [];
    const value: Obj = { ...step };
    if (next) value.when = next; else delete value.when;
    return [{ op: "put", path: `${taskPath}/steps:${sid}`, expect: await nodeDigest(step), value }];
  });
  /** Inserts at the chosen place; the step's index, or null when it was refused. */
  const insert = async (value: Obj): Promise<number | null> => {
    const at = insertAt ?? steps.length;
    const after = at === 0 ? null : String(steps[at - 1].id);
    if (!await run([{ op: "insert", path: taskPath, field: "steps", after, value }])) return null;
    setSelected(at); setInsertAt(null);
    return at;
  };
  const stepIds = childIds(task.raw);
  const shownLib = lib.filter((sn) => !query.trim() || `${str(sn.name) ?? ""}${String(sn.id)}`.includes(query.trim()));

  return (
    <>
      <header className={w.header}>
        <Link to={devPaths.home} aria-label="返回开发工作台" className={w.back}><Back /></Link>
        <div className={t.title}>
          <div className={w.muted}>任务</div>
          <h1 className={w.h1Small}>{task.name}</h1>
          <div className={w.muted}>{gameName} · {str(objs(target(draft.body, String(task.raw.targetId))?.categories).find((c) => c.id === task.raw.category)?.name) ?? CATEGORY[str(task.raw.category) ?? ""] ?? "任务"} · {steps.length} 个步骤 · {task.fields.length} 个配置项 · 草稿修订 {draft.revision}</div>
        </div>
        <Link className={w.btn} to={devPaths.config(id)}>配置项（{task.fields.length}）</Link>
        <button type="button" className={w.btn} aria-pressed={json} onClick={() => setJson(!json)}>JSON</button>
        <Link className={`${w.btn} ${w.primary}`} to={devPaths.run(id)}>试跑这个任务</Link>
      </header>
      {text && <div className={w.message}>{text}</div>}
      {json && <pre className={`${w.card} ${t.json}`}>{JSON.stringify(task.raw, null, 2)}</pre>}

      <div className={t.layout}>
        <section aria-label="步骤顺序" className={t.steps}>
          {steps.length === 0 && <div className={t.ins}><button type="button" onClick={() => setInsertAt(0)}>＋ 在这里插入步骤</button></div>}
          {steps.map((step, i) => {
            const use = str(step.use);
            const snippet = use ? lib.find((sn) => sn.id === use) : undefined;
            const program = snippet ? programOf(snippet) : programOf(step);
            const ran = fresh && (fresh.step === null || fresh.step === step.id);
            const runChip = ran ? (fresh.state === "FINISHED" ? [t.ok, "通过"] : [t.bad, "失败"]) : [t.bad, "未试跑"];
            const open = use ? devPaths.snippet(use) : devPaths.step(id, String(step.id));
            return (
              <div key={String(step.id)} className={t.stepWrap}>
                <div className={`${w.card} ${t.step}`} data-selected={i === selected}>
                  <span className={t.num}>{i + 1}</span>
                  <button type="button" className={t.stepMain} onClick={() => setSelected(i)}>
                    <span className={t.stepTitle}><b>{str(step.name) ?? String(step.id)}</b>
                      <span className={`${t.chip} ${use ? t.pub : t.own}`}>{use ? "片段" : "本任务"}</span>
                      <span className={`${t.chip} ${runChip[0]}`}>{runChip[1]}</span></span>
                    <span className={w.muted}>{use ? `片段 ${str(snippet?.name) ?? use} · ` : ""}{count(program)} 个动作</span>
                    <span className={w.muted}>读的配置项：{reads(program, task.fields).join("、") || "无"}</span>
                  </button>
                  <div className={t.tools}>
                    <Link className={w.btn} to={open} style={{ minHeight: 36 }}>{use ? "片段库" : "编辑"}</Link>
                    <span className={t.arrows}>
                      <button type="button" className={w.btn} aria-label="上移" disabled={i === 0} onClick={() => move(i, i - 1)}>↑</button>
                      <button type="button" className={w.btn} aria-label="下移" disabled={i === steps.length - 1} onClick={() => move(i, i + 1)}>↓</button>
                      <button type="button" className={w.btn} aria-label="移出任务" onClick={() => remove(i)}>✕</button>
                    </span>
                  </div>
                  <RunCondition step={step} fields={task.fields} onChange={(next) => setWhen(String(step.id), next)} />
                </div>
                <div className={t.ins}><button type="button" aria-pressed={insertAt === i + 1} onClick={() => setInsertAt(i + 1)}>＋ 在这里插入步骤</button></div>
              </div>
            );
          })}
        </section>

        <aside className={t.aside}>
          {insertAt !== null && (
            <div className={`${w.card} ${t.panel} ${t.inserting}`}>
              <div className={t.panelHead}><b>插入到第 {insertAt + 1} 步</b><button type="button" className={w.btn} style={{ minHeight: 32 }} onClick={() => setInsertAt(null)}>取消</button></div>
              <button type="button" className={`${w.btn} ${w.primary} ${t.center}`} onClick={async () => {
                const name = prompt("步骤名")?.trim();
                if (!name) return;
                const sid = uniqueId(name, stepIds, "step");
                const at = await insert({ id: sid, name, program: [] });
                if (at !== null) navigate(devPaths.step(id, sid));
              }}>新建步骤</button>
              <label className={w.muted} htmlFor="q">或从片段库选</label>
              <input id="q" className={t.search} placeholder="搜片段名" value={query} onChange={(e) => setQuery(e.target.value)} />
              {shownLib.map((sn) => {
                const sid = String(sn.id);
                const n = all.filter((x) => uses(x, sid)).length;
                return (
                  <button key={sid} type="button" className={t.lib} onClick={() => insert({ id: uniqueId(sid, stepIds), name: str(sn.name) ?? sid, use: sid })}>
                    <span className={t.grow}><b>{str(sn.name) ?? sid}</b><span className={`${w.muted} ${w.block}`}>{objs(sn.params).map((p) => str(p.name)).join(" · ") || "无参数"}</span></span>
                    <span className={`${t.chip} ${t.pub}`}>{n ? `${n} 个任务` : "没用过"}</span>
                  </button>
                );
              })}
            </div>
          )}
          {sel && (
            <div className={`${w.card} ${t.panel}`}>
              <b>选中的步骤：{str(sel.name) ?? String(sel.id)}</b>
              <div className={w.muted}>{str(sel.use) ? `整步引用片段「${str(sel.use)}」` : "本任务自己的步骤"} · {count(str(sel.use) ? programOf(lib.find((sn) => sn.id === sel.use) ?? {}) : programOf(sel))} 个动作</div>
              <div className={w.muted}>读的配置项：{reads(str(sel.use) ? programOf(lib.find((sn) => sn.id === sel.use) ?? {}) : programOf(sel), task.fields).join("、") || "无"}</div>
              <div className={w.muted}>最近试跑：{trial ? `${fresh ? (fresh.state === "FINISHED" ? "通过" : "失败") : "改动前"} · ${clock(trial.startedAt)}${trial.detail ? ` · ${trial.detail}` : ""}` : "还没试跑"}</div>
              <Link className={`${w.btn} ${t.center}`} to={str(sel.use) ? devPaths.snippet(str(sel.use)!) : devPaths.step(id, String(sel.id))}>{str(sel.use) ? "在片段库打开" : "打开步骤编辑"}</Link>
            </div>
          )}
        </aside>
      </div>
    </>
  );
}

export const Back = () => <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M14 5L7 12L14 19" /></svg>;

/** Actions in a program, nested ones included. */
export function count(program: Obj[]): number {
  return program.reduce((n, node) => n + (node.type === "action" ? 1 : 0) + childrenOf(node).reduce((m, [, child]) => m + count(child), 0), 0);
}

/** The settings a program reads, by their labels. */
export function reads(program: Obj[], fields: Obj[]): string[] {
  const ids = new Set<string>();
  const scan = (value: unknown) => {
    if (Array.isArray(value)) value.forEach(scan);
    else if (isObj(value as never)) {
      const o = value as Obj;
      if (o.type === "configuration" && typeof o.fieldId === "string") ids.add(o.fieldId);
      Object.values(o).forEach(scan);
    }
  };
  scan(program);
  return [...ids].map((fid) => str(fields.find((f) => f.id === fid)?.label) ?? fid);
}

/** The settings a run condition can test: single and multiple choices, and switches. */
const testable = (fields: Obj[]) => fields.filter((f) => f.widget === "SINGLE_CHOICE" || f.widget === "MULTI_CHOICE" || f.widget === "SWITCH");
/** A condition on [field] that holds for its first value: the editor's starting point. */
function firstCondition(field: Obj): Obj {
  if (field.widget === "SWITCH") return { field: String(field.id), equals: true };
  const first = str(objs(field.options)[0]?.value) ?? "";
  return field.widget === "MULTI_CHOICE" ? { field: String(field.id), contains: first } : { field: String(field.id), equals: first };
}

/** 运行条件: always, or a setting is / is not a value (contains / does not contain an option); otherwise the step is skipped. */
function RunCondition({ step, fields, onChange }: { step: Obj; fields: Obj[]; onChange: (next: Obj | null) => void }) {
  const choices = testable(fields);
  const when = isObj(step.when) ? step.when : null;
  const field = when ? fields.find((f) => f.id === when.field) : undefined;
  if (!when) return (
    <div className={t.cond}><span className={w.muted} style={{ fontWeight: 600 }}>运行条件</span><span className={w.muted}>总是运行</span>
      {choices.length > 0 && <button type="button" className={`${w.btn} ${t.small}`} onClick={() => onChange(firstCondition(choices[0]))}>加条件</button>}</div>
  );
  const multi = field?.widget === "MULTI_CHOICE";
  const toggle = field?.widget === "SWITCH";
  const value = multi ? str(when.contains) : when.equals === true ? "true" : when.equals === false ? "false" : str(when.equals);
  const options: [string, string][] = toggle ? [["true", "开"], ["false", "关"]] : objs(field?.options).map((o) => [str(o.value) ?? "", str(o.label) ?? ""]);
  const set = (v: string, not: boolean) => onChange({ field: String(when.field), ...(multi ? { contains: v } : { equals: toggle ? v === "true" : v }), ...(not ? { not: true } : {}) });
  return (
    <div className={t.cond}>
      <span className={w.muted} style={{ fontWeight: 600 }}>运行条件</span>
      <select aria-label="按哪个配置项" className={t.select} value={String(when.field)} onChange={(e) => { const f = choices.find((x) => x.id === e.target.value); if (f) onChange(firstCondition(f)); }}>
        {!field && <option value={String(when.field)}>{String(when.field)}（找不到）</option>}
        {choices.map((f) => <option key={String(f.id)} value={String(f.id)}>{str(f.label) ?? String(f.id)}</option>)}
      </select>
      <select aria-label="判断" className={t.select} value={when.not === true ? "not" : "is"} onChange={(e) => set(value ?? "", e.target.value === "not")}>
        <option value="is">{multi ? "含" : "是"}</option><option value="not">{multi ? "不含" : "不是"}</option>
      </select>
      <select aria-label="值" className={t.select} value={value ?? ""} onChange={(e) => set(e.target.value, when.not === true)}>
        {options.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
      </select>
      <span className={w.muted}>否则跳过</span>
      <button type="button" className={`${w.btn} ${t.small}`} onClick={() => onChange(null)}>去掉</button>
    </div>
  );
}
