import { useEffect, useState } from "react";
import { useConsole } from "../../state/context";
import { Link, navigate } from "../../router";
import { dev, type Sample, type TrialRecord } from "./api";
import { devPaths, failure, useDev } from "./context";
import { arr, childIds, childrenOf, isObj, objs, programOf, snippets, str, tasks, usedSnippets, type TaskDef } from "./model";
import type { Obj } from "./api";
import w from "./Wb.module.css";

/** ① 首页: the draft at a glance, its tasks with how each stands, and the snippet library. */
export function HomePage() {
  const { draft, game, gameName, trials, digests, apply, me } = useDev();
  const { data } = useConsole();
  const [filter, setFilter] = useState<"all" | "changed" | "fail">("all");
  const [latest, setLatest] = useState<Sample | null>(null);
  const [text, setText] = useState<string | null>(null);
  useEffect(() => { dev.samples(game).then((all) => setLatest(all[0] ?? null), () => {}); }, [game]);
  if (!draft) return null;
  const list = tasks(draft.body);
  const lib = snippets(draft.body);
  const rows = list.map((t) => standing(t, draft.body, draft.bases, digests, trials.find((x) => x.task === t.id), data.devices));
  const changed = rows.filter((r) => r.changed);
  const shown = rows.filter((r) => filter === "all" || (filter === "changed" ? r.changed : r.tone === "fail"));
  const phone = data.devices.find((d) => d.platform === "android" && d.online) ?? data.devices.find((d) => d.platform === "android");
  const used = features(draft.body);
  const create = async () => {
    const name = prompt("任务名")?.trim();
    if (!name) return;
    const id = uniqueId(name, childIds(draft.body), "task");
    const problem = await apply([{ op: "insert", path: "", field: "tasks", after: list.at(-1)?.id ?? null,
      value: { id, targetId: game, name, category: "daily", priority: 50, fields: [], steps: [] } }]);
    if (problem) setText(problem); else navigate(devPaths.task(id));
  };
  return (
    <>
      <header className={w.header}>
        <div className={w.headerText}>
          <h1 className={w.h1}>开发工作台 · {gameName}</h1>
          <div className={w.muted}>草稿基于 {draft.baseVersion} · 修订 {draft.revision}{me?.maintainer ? " · 维护者" : ""}</div>
        </div>
        <button type="button" className={w.btn} onClick={create}><Plus />新建任务</button>
        <Link className={`${w.btn} ${w.primary}`} to={devPaths.publish}>校验并发布</Link>
      </header>
      {text && <div className={w.message}>{text}</div>}

      <div className={w.stats}>
        <div className={`${w.card} ${w.stat}`}>
          <span className={w.muted}>未发布的改动</span><span className={w.figure}>{changed.length} 个任务</span>
          <span className={w.muted}>{changed.length ? changed.map((r) => `${r.task.name}（${r.isNew ? "新" : "改过"}）`).join(" · ") : "和发布版一致"}</span>
        </div>
        <Link className={`${w.card} ${w.stat}`} to={devPaths.samples}>
          <span className={w.muted}>样本库</span><span className={w.figure}>{me?.samples ?? 0} 张</span>
          <span className={w.muted}>{latest ? `最近：${latest.name} · ${ago(latest.createdAt)} · ${latest.device || "手机悬浮球"}` : "在游戏里点悬浮球“采样”添加"}</span>
        </Link>
        <div className={`${w.card} ${w.stat}`}>
          <span className={w.muted}>发布需要的 App</span><span className={w.figure}>0.2.0 以上</span>
          <span className={w.muted}>{used.length ? `用到${used.join("、")}；旧 App 会整包拒收` : "旧 App 会整包拒收"}</span>
        </div>
        <div className={`${w.card} ${w.stat}`}>
          <span className={w.muted}>试跑用的手机</span><span className={w.figure}>{phone?.name ?? "没有手机"}</span>
          {phone?.online ? <span className={w.good}>在线 · {phone.phase === "IDLE" ? "空闲" : phone.currentTask ? `正在跑 ${phone.currentTask}` : "忙"}</span>
            : <span className={w.muted}>{phone ? "离线 · 打开 App 的悬浮球开发工具" : "在手机上登录 App"}</span>}
        </div>
      </div>

      <section aria-labelledby="tasks" className={w.section}>
        <div className={w.sectionHead}>
          <h2 id="tasks" className={w.h2}>任务</h2>
          <div role="tablist" aria-label="筛选" className={w.filters}>
            {([["all", "全部"], ["changed", "有改动"], ["fail", "试跑失败"]] as const).map(([id, label]) => (
              <button key={id} type="button" role="tab" aria-selected={filter === id} className={w.filter} onClick={() => setFilter(id)}>{label}</button>
            ))}
          </div>
        </div>
        <div className={`${w.card} ${w.clip}`}>
          <div className={`${w.tasksGrid} ${w.thead}`}><span>任务</span><span>状态</span><span>最近一次试跑</span><span>配置项</span></div>
          {shown.length ? shown.map((r) => (
            <Link key={r.task.id} to={devPaths.task(r.task.id)} className={`${w.tasksGrid} ${w.trow}`}>
              <span><span className={w.bold}>{r.task.name}</span><span className={`${w.muted} ${w.block}`}>{r.sub}</span></span>
              <span><span className={`${w.tag} ${w[r.tone]}`}>{r.label}</span></span>
              <span className={w.muted}>{r.run}</span>
              <span className={w.muted}>{r.fields}</span>
            </Link>
          )) : <div className={w.empty}>{list.length ? "没有符合的任务" : "还没有任务"}</div>}
        </div>
      </section>

      <section aria-labelledby="steplib" className={w.section} id="steplib">
        <div className={w.sectionHead}>
          <h2 id="steplib-title" className={w.h2}>片段库</h2>
          <Link className={w.btn} to={devPaths.game}>游戏设置</Link>
          <button type="button" className={w.btn} onClick={async () => {
            const name = prompt("片段编号（字母、数字、-）")?.trim();
            if (!name) return;
            const id = uniqueId(name, childIds(draft.body), "snippet");
            const problem = await apply([{ op: "insert", path: "", field: "snippets", after: str(lib.at(-1)?.id) ?? null, value: { id, program: [] } }]);
            if (problem) setText(problem); else navigate(devPaths.snippet(id));
          }}>新建片段</button>
        </div>
        <div className={w.muted}>带参数，可整步引用，也可插在步骤里</div>
        <div className={`${w.card} ${w.clip}`}>
          {lib.length ? lib.map((sn) => {
            const id = String(sn.id ?? "");
            const params = objs(sn.params).map((p) => str(p.name) ?? "");
            const users = list.filter((t) => uses(t, id));
            return (
              <Link key={id} to={devPaths.snippet(id)} className={`${w.libGrid} ${w.trow}`}>
                <span className={w.bold}>{str(sn.name) ?? id}</span>
                <span className={w.muted}>{params.length ? params.join(" · ") : "无参数"}</span>
                <span className={w.muted}>{users.length === 1 ? users[0].name : users.length ? `${users.length} 个任务` : "没有任务用"}</span>
              </Link>
            );
          }) : <div className={w.empty}>还没有片段</div>}
        </div>
      </section>
    </>
  );
}

const Plus = () => <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>;

type Tone = "pass" | "fail" | "none" | "same";
export interface Standing { task: TaskDef; changed: boolean; isNew: boolean; fresh: TrialRecord | null; tone: Tone; label: string; sub: string; run: string; fields: string }

/** How a task stands: changed since the draft's base, and whether its newest trial ran on what it is now. */
export function standing(task: TaskDef, body: Obj, bases: Record<string, string>, digests: Record<string, string>, trial: TrialRecord | undefined,
  devices: { id: string; name: string }[]): Standing {
  const now = digests[`tasks/${task.id}`];
  const base = bases[`tasks/${task.id}`];
  const isNew = base === undefined;
  // A change to a snippet it calls changes what the task does.
  const parts = usedSnippets(body, task.raw).filter((id) => digests[`snippets/${id}`] !== undefined && digests[`snippets/${id}`] !== bases[`snippets/${id}`]);
  const changed = now !== undefined && (base !== now || parts.length > 0);
  // Only a trial of the whole task, on what the task and its snippets are now, counts.
  const fresh = trial && trial.step === null && trial.digest === digests[`trial/${task.id}`] ? trial : null;
  const [tone, label]: [Tone, string] = fresh ? (fresh.state === "FINISHED" ? ["pass", "试跑通过"] : ["fail", "试跑失败"])
    : changed ? ["none", "未试跑"] : ["same", "与发布版一致"];
  const whole = task.steps.filter((st) => typeof st.use === "string").length;
  const steps = `${task.steps.length} 个步骤${whole ? `（${whole} 个片段）` : ""}`;
  const sub = isNew ? `新任务 · ${steps}` : base !== now ? `改过 · ${steps}` : changed ? `片段改过 · ${steps}` : steps;
  const device = (id: string) => devices.find((d) => d.id === id)?.name ?? "手机";
  const run = fresh ? `${device(fresh.device)} · ${clock(fresh.startedAt)} · ${duration(fresh.endedAt - fresh.startedAt)}${fresh.detail ? ` · ${fresh.detail}` : ""}`
    : trial?.step ? "只试跑过单步" : trial ? "改动后还没试跑" : "还没试跑";
  const fields = task.fields.length === 0 ? "无" : task.fields.length === 1 ? str(task.fields[0].label) ?? str(task.fields[0].id) ?? "1 个" : `${task.fields.length} 个`;
  return { task, changed, isNew, fresh, tone, label, sub, run, fields };
}

/** Whether [task] calls snippet [id] anywhere: as a whole step or inside a program. */
export function uses(task: TaskDef, id: string): boolean {
  const walk = (program: unknown[]): boolean => program.some((node) => isObj(node as never) && (
    (node as { type?: unknown; use?: unknown }).type === "snippet" && (node as { use?: unknown }).use === id ||
    childrenOf(node as never).some(([, child]) => walk(child))));
  return task.steps.some((st) => st.use === id || walk(programOf(st)));
}

/** The newer kinds of flow the draft uses, which only App 0.2.0 and later read. */
function features(body: Parameters<typeof tasks>[0]): string[] {
  const kinds = new Set<string>();
  const walk = (program: unknown[]) => program.forEach((node) => {
    if (!isObj(node as never)) return;
    kinds.add(String((node as { type?: unknown }).type));
    childrenOf(node as never).forEach(([, child]) => walk(child));
  });
  for (const t of tasks(body)) for (const st of t.steps) { if (typeof st.use === "string") kinds.add("snippet"); walk(programOf(st)); }
  for (const sn of snippets(body)) walk(programOf(sn));
  if (arr(body.snippets).length) kinds.add("snippet");
  for (const t of tasks(body)) if (t.steps.some((st) => isObj(st.when as never))) kinds.add("when");
  if (objs(body.targets).some((g) => isObj(g.library) && arr(g.library.interruptions).length)) kinds.add("interruptions");
  return ([["snippet", "片段"], ["forEach", "逐项"], ["until", "重复直到"], ["branch", "分支"], ["when", "运行条件"], ["interruptions", "干扰页"]] as const).filter(([k]) => kinds.has(k)).map(([, label]) => label);
}

/** An id from [name] (letters and digits kept, [fallback] for a name without any) that none of [taken] has. */
export function uniqueId(name: string, taken: string[], fallback = "node"): string {
  const base = name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || fallback;
  let id = base; let n = 2;
  while (taken.includes(id)) id = `${base}-${n++}`;
  return id;
}

export const clock = (at: number) => {
  const d = new Date(at); const today = new Date();
  const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return d.toDateString() === today.toDateString() ? hm : `${d.getMonth() + 1}-${d.getDate()} ${hm}`;
};
export const duration = (ms: number) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return s >= 60 ? `${Math.floor(s / 60)} 分 ${String(s % 60).padStart(2, "0")} 秒` : `${s} 秒`;
};
const ago = (at: number) => {
  const m = Math.round((Date.now() - at) / 60000);
  return m < 1 ? "刚刚" : m < 60 ? `${m} 分钟前` : m < 1440 ? `${Math.round(m / 60)} 小时前` : clock(at);
};
export { failure };
