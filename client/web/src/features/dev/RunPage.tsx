import { useState } from "react";
import { Link } from "../../router";
import { useConsole } from "../../state/context";
import { dev, follow, type DevRun, type Json, type Obj } from "./api";
import { devPaths, failure, useDev } from "./context";
import { clock } from "./HomePage";
import { objs, str, tasks } from "./model";
import { Back } from "./TaskPage";
import w from "./Wb.module.css";
import r from "./Run.module.css";

/** The task reported itself completed; a run that merely ended did not pass. */
const completed = (run: DevRun) => run.state === "FINISHED" && run.facts.some((f) => f.type === "task-completed");
const ok = (result: string) => ["PRODUCED", "VERIFIED", "COMPLETED"].includes(result);

/** ⑧ 试跑: runs the task (or one step) of the draft on a phone and shows what every action did. */
export function RunPage({ id }: { id: string }) {
  const { draft, trials, reloadTrials, game } = useDev();
  const { data } = useConsole();
  const phones = data.devices.filter((d) => d.platform === "android");
  const [device, setDevice] = useState(phones.find((d) => d.online)?.id ?? phones[0]?.id ?? "");
  const [step, setStep] = useState(() => new URLSearchParams(window.location.search).get("step") ?? "");
  const [preset, setPreset] = useState(0);
  const [run, setRun] = useState<DevRun | null>(null);
  const [pick, setPick] = useState<number | null>(null);
  const [text, setText] = useState<string | null>(null);
  const task = draft ? tasks(draft.body).find((x) => x.id === id) : undefined;
  if (!draft || !task) return <div className={w.card}><div className={w.empty}>草稿里没有这个任务</div></div>;
  const presets = testValues(task.fields);
  const busy = run?.state === "SENT" || run?.state === "RUNNING";
  const last = trials.find((x) => x.task === id);
  const name = (sid: string) => str(task.steps.find((st) => st.id === sid)?.name) ?? sid;
  const phoneName = (pid: string) => phones.find((d) => d.id === pid)?.name ?? "手机";
  const start = async () => {
    setText(null); setRun(null); setPick(null);
    try {
      const started = await dev.startRun({ kind: "trial", target: game, device, task: id, step: step || undefined, configuration: presets[preset]?.[1] ?? {} });
      setRun(started);
      const done = await follow(started.id, setRun);
      if (done) { const failed = done.traces.findIndex((x) => !ok(x.result)); setPick(failed >= 0 ? failed : done.traces.length - 1); }
      await reloadTrials();
    } catch (error) { setText(failure(error)); }
  };
  const [statusText, tone] = busy ? [`正在 ${phoneName(device)} 上跑`, r.running]
    : run ? (completed(run) ? [`通过：${run.detail || "完成"}`, r.done] : [`没通过：${run.detail || "失败"}`, r.failed])
    : last ? (last.state === "FINISHED" ? [`上次通过（${clock(last.endedAt)}）：${last.detail || "完成"}`, r.done] : [`上次没通过（${clock(last.endedAt)}）：${last.detail}`, r.failed])
    : ["还没试跑", r.idle];
  const traces = run?.traces ?? [];
  let at = run?.createdAt ?? 0;
  const events = traces.map((x) => { at += x.elapsedMs; return { ...x, at }; });
  const sel = pick !== null ? events[pick] : undefined;
  return (
    <>
      <header className={r.header}>
        <Link to={devPaths.task(id)} aria-label="返回任务编辑" className={w.back}><Back /></Link>
        <div className={r.title}><h1 className={w.h1Small}>试跑 · {task.name}</h1><div className={w.muted}>草稿修订 {draft.revision}</div></div>
        <div className={r.field}><label htmlFor="dev">在哪台手机上</label>
          <select id="dev" value={device} onChange={(e) => setDevice(e.target.value)}>
            {phones.length ? phones.map((d) => <option key={d.id} value={d.id}>{d.name} · {d.online ? (d.phase === "IDLE" ? "空闲" : "忙") : "离线"}</option>) : <option value="">没有手机</option>}
          </select></div>
        <div className={r.field}><label htmlFor="scope">范围</label>
          <select id="scope" value={step} onChange={(e) => setStep(e.target.value)}>
            <option value="">整个任务</option>
            {task.steps.map((st, i) => <option key={String(st.id)} value={String(st.id)}>只跑第 {i + 1} 步</option>)}
          </select></div>
        <div className={r.field}><label htmlFor="tv">测试值</label>
          <select id="tv" value={preset} onChange={(e) => setPreset(Number(e.target.value))}>
            {presets.map(([label], i) => <option key={i} value={i}>{label}</option>)}
          </select></div>
        <div className={r.field}><label htmlFor="mode">方式</label><select id="mode"><option>一直跑</option></select></div>
        <button type="button" className={`${w.btn} ${w.primary}`} disabled={busy || !device} onClick={start}>{busy ? "试跑中…" : "开始试跑"}</button>
      </header>
      {text && <div className={w.message}>{text}</div>}
      <div role="status" className={`${r.status} ${tone}`}>{statusText}</div>

      <div className={r.layout}>
        <section aria-label="时间线" className={`${w.card} ${r.timeline}`}>
          {events.length ? events.map((e, i) => (
            <button key={i} type="button" className={r.event} aria-pressed={pick === i} onClick={() => setPick(i)}>
              <span className={`${w.muted} ${r.at}`}>{clockSeconds(e.at)}</span>
              <span aria-hidden="true" className={r.dot} data-tone={ok(e.result) ? "ok" : e.result === "SKIPPED" ? "info" : "bad"} />
              <span><span className={w.bold}>{name(e.step)} · {e.action}</span><span className={`${w.muted} ${w.block}`}>{e.capability.replace(/^\w+\./, "")} · {e.reason ?? e.result}</span></span>
              <span className={w.muted}>{(e.elapsedMs / 1000).toFixed(1)}s</span>
            </button>
          )) : <div className={w.empty}>{busy ? "等手机开始…" : "开始试跑后，这里按时间列出每个动作"}</div>}
        </section>
        <aside aria-label="选中的一步" className={r.aside}>
          {sel ? (
            <div className={`${w.card} ${r.panel}`}>
              <h2 className={r.h2}>{name(sel.step)} · {sel.action}{ok(sel.result) ? "" : "（没成）"}</h2>
              <div className={w.muted}>{sel.capability} · 用时 {(sel.elapsedMs / 1000).toFixed(1)}s</div>
              {!ok(sel.result) && <div className={r.why}>{sel.reason ?? sel.result}</div>}
              {conditions(sel.trace).map((c, i) => <div key={i} className={w.muted}>{c}</div>)}
              <div className={r.actions}><Link className={w.btn} to={devPaths.step(id, sel.step)}>跳到这个动作</Link></div>
            </div>
          ) : <div className={`${w.card} ${r.panel}`}><span className={w.muted}>点左边的一行看这个动作的详情</span></div>}
        </aside>
      </div>
    </>
  );
}

/** Test values to run with: the recommended ones, then each choice of a single-choice setting changed from them. */
function testValues(fields: Obj[]): [string, Obj][] {
  if (!fields.length) return [["无配置项", {}]];
  const value = (f: Obj): Json => f.recommended ?? f.default ?? (f.widget === "SWITCH" ? false : objs(f.options)[0]?.value ?? null);
  const base: Obj = Object.fromEntries(fields.map((f) => [String(f.id), value(f)]));
  const label = (o: Obj) => fields.map((f) => { const v = o[String(f.id)]; const opt = objs(f.options).find((x) => x.value === v);
    return `${str(f.label) ?? f.id} ${str(opt?.label) ?? (v === true ? "开" : v === false ? "关" : JSON.stringify(v))}`; }).join(" · ");
  const list: [string, Obj][] = [[`推荐值 · ${label(base)}`, base]];
  for (const f of fields) for (const opt of objs(f.options)) {
    if (opt.value === base[String(f.id)]) continue;
    const o = { ...base, [String(f.id)]: opt.value ?? null };
    list.push([label(o), o]);
  }
  return list;
}

/** What a page wait read: each state's conditions that held or missed. */
function conditions(trace: Obj | undefined): string[] {
  if (!trace || !Array.isArray(trace.conditions)) return [];
  return objs(trace.conditions).slice(0, 12).map((c) => `${str(c.state) ?? ""} · ${str(c.kind) ?? ""} ${str(c.expect) ?? ""} ${c.pass === true ? "✓" : c.pass === false ? "✗" : "没读"}`);
}

const clockSeconds = (at: number) => new Date(at).toTimeString().slice(0, 8);
