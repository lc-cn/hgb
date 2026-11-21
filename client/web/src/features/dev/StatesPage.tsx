import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "../../router";
import { useConsole } from "../../state/context";
import { dev, follow, type Json, type Obj, type Sample } from "./api";
import { devPaths, failure, useDev } from "./context";
import { arr, childrenOf, isObj, objs, programOf, snippets, states, str, tasks } from "./model";
import { Back } from "./TaskPage";
import w from "./Wb.module.css";
import st from "./States.module.css";

interface Wait { key: string; label: string; states: Obj[] }
interface Cell { held: boolean; score?: number }

/** ⑦ 状态库与判定台: the states, and one page wait's candidates judged on every sample by the phone's recognizer. */
export function StatesPage() {
  const { draft, game, gameName } = useDev();
  const { data } = useConsole();
  const [pick, setPick] = useState(0);
  const [waitKey, setWaitKey] = useState<string | null>(null);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [cells, setCells] = useState<Record<string, Cell[] | string>>({});
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState<string | null>(null);
  // Each judging pass is numbered; one that was replaced (another wait chosen) stops writing into the table.
  const pass = useRef(0);
  useEffect(() => { dev.samples(game).then(setSamples, () => {}); }, [game]);
  const waits = useMemo(() => draft ? pageWaits(draft.body) : [], [draft]);
  if (!draft) return null;
  const library = states(draft.body, game);
  const list: Obj[] = [...library, ...waits.flatMap((x) => x.states).filter((s, i, all) => all.findIndex((o) => JSON.stringify(o) === JSON.stringify(s)) === i)];
  const sel = list[Math.min(pick, list.length - 1)];
  const wait = waits.find((x) => x.key === waitKey) ?? waits[0];
  const phone = data.devices.find((d) => d.platform === "android" && d.online);
  const judgeAll = async () => {
    if (!wait || !phone) { setText(phone ? "没有等页面" : "没有在线的手机"); return; }
    const n = ++pass.current;
    setBusy(true); setText(null); setCells({});
    for (const sample of samples) {
      if (n !== pass.current) break;
      try {
        const run = await dev.startRun({ kind: "judge", target: game, device: phone.id, sample: sample.id, states: wait.states as Json[] });
        const done = await follow(run.id, () => {});
        const trace = done.traces.find((t) => t.capability === "screen.awaitScreen")?.trace;
        const held = str(trace?.state);
        const scores = objs(trace?.conditions);
        if (n === pass.current) setCells((c) => ({ ...c, [sample.id]: wait.states.map((s) => ({ held: str(s.id) === held, score: best(scores, str(s.id)) })) }));
      } catch (error) { if (n === pass.current) setCells((c) => ({ ...c, [sample.id]: failure(error) })); }
    }
    if (n === pass.current) setBusy(false);
  };
  const rows = samples.map((sample) => {
    const got = cells[sample.id];
    const hits = Array.isArray(got) ? got.filter((c) => c.held).length : 0;
    const verdict = typeof got === "string" ? [got, "bad"] : !got ? ["—", ""] : hits > 1 ? ["冲突", "bad"] : hits === 0 ? ["漏判：一个都不成立", "bad"] : ["✓", "ok"];
    return { sample, got, verdict };
  });
  const bad = rows.filter((r) => r.verdict[1] === "bad").length;
  const judged = rows.filter((r) => r.got).length;
  return (
    <>
      <header className={w.header}>
        <Link to={devPaths.game} aria-label="返回游戏设置" className={w.back}><Back /></Link>
        <div className={st.title}><div className={w.muted}>{gameName}</div><h1 className={w.h1Small}>状态库与判定台</h1></div>
      </header>
      {text && <div className={w.message}>{text}</div>}
      <div className={st.layout}>
        <nav aria-label="状态库" className={`${w.card} ${st.tree}`}>
          {list.length ? list.map((s, i) => (
            <button key={i} type="button" className={st.st} aria-current={i === pick ? "true" : undefined} style={{ marginLeft: str(s.base) ? 12 : 0 }} onClick={() => setPick(i)}>
              <b>{str(s.name) ?? str(s.id)}</b><span className={w.muted}>{str(s.base) ? `基于 ${str(s.base)}` : i < library.length ? "状态库" : "写在等页面里"}</span>
            </button>
          )) : <div className={w.empty}>还没有状态</div>}
        </nav>
        <section className={st.main}>
          <div className={st.waits}>
            <span className={w.muted}>判定一个“等页面”的候选：</span>
            <select className={st.select} value={wait?.key ?? ""} disabled={busy} onChange={(e) => { pass.current++; setWaitKey(e.target.value); setCells({}); }}>
              {waits.map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}
            </select>
            <button type="button" className={`${w.btn} ${w.primary}`} style={{ minHeight: 36 }} disabled={busy || !wait || !samples.length} onClick={judgeAll}>
              {busy ? `判定中 ${judged}/${samples.length}…` : `在 ${samples.length} 张样本上判`}</button>
          </div>
          <div role="status" className={st.banner} data-tone={!judged ? "idle" : bad ? "bad" : "ok"}>
            {!judged ? (phone ? `用 ${phone.name} 的识别逐张判定` : "需要一台在线的手机来判定") : bad ? `${bad} 张有问题` : "全部正确"}</div>
          <div className={`${w.card} ${st.scroll}`}>
            <table className={st.table}>
              <thead><tr><th scope="col">样本</th><th scope="col">应是</th>{wait?.states.map((s, i) => <th key={i} scope="col">{str(s.name) ?? str(s.id)}</th>)}<th scope="col">结论</th></tr></thead>
              <tbody>
                {rows.map(({ sample, got, verdict }) => (
                  <tr key={sample.id} data-bad={verdict[1] === "bad"}>
                    <th scope="row">{sample.name}<span className={`${w.muted} ${w.block}`}>{ratio(sample.width, sample.height)}</span></th>
                    <td>—</td>
                    {wait?.states.map((_, i) => {
                      const c = Array.isArray(got) ? got[i] : undefined;
                      return <td key={i} data-held={c?.held}>{c ? `${c.held ? "成立" : "—"}${c.score !== undefined ? ` ${c.score.toFixed(2)}` : ""}` : ""}</td>;
                    })}
                    <td data-verdict={verdict[1]}>{verdict[0]}</td>
                  </tr>
                ))}
                {!samples.length && <tr><td colSpan={3 + (wait?.states.length ?? 0)} className={w.empty}>还没有样本</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
        {sel && (
          <aside className={`${w.card} ${st.aside}`} aria-label="状态条件">
            <div className={w.muted}>状态</div><b className={st.name}>{str(sel.name) ?? str(sel.id)}</b>
            {str(sel.base) && <div className={w.muted}>基于 {str(sel.base)}</div>}
            {conditionsOf(sel).map(([kind, tone, label], i) => <div key={i} className={st.cond}><span className={st.k} data-tone={tone}>{kind}</span><span className={st.grow}>{label}</span></div>)}
            <div className={w.muted}>被 {waits.filter((x) => x.states.some((s) => s.id === sel.id)).length} 个等页面用到</div>
          </aside>
        )}
      </div>
    </>
  );
}

/** Every page wait in the draft with its candidate states, labelled by where it is. */
function pageWaits(body: Obj): Wait[] {
  const found: Wait[] = [];
  const walk = (program: Obj[], where: string) => program.forEach((node) => {
    const a = isObj(node.action) ? node.action : null;
    if (a?.capability === "screen.awaitScreen" && isObj(a.inputs)) {
      const list = objs(a.inputs.states);
      if (list.length) found.push({ key: `${where}/${str(a.id)}`, label: `${where} · ${str(a.id)}（${list.map((s) => str(s.id)).join("、")}）`, states: list });
    }
    childrenOf(node).forEach(([, child]) => walk(child, where));
  });
  for (const t of tasks(body)) for (const s of t.steps) walk(programOf(s), `${t.name}·${str(s.name) ?? str(s.id)}`);
  for (const sn of snippets(body)) walk(programOf(sn), `片段 ${str(sn.id)}`);
  return found;
}

/** A state's conditions as [kind, tone, label]. */
function conditionsOf(s: Obj): [string, string, string][] {
  const label = (c: Json) => typeof c === "string" ? `「${c}」` : isObj(c) ? (str(c.text) ? `「${str(c.text)}」` : str(c.template) ?? JSON.stringify(c).slice(0, 40)) : String(c);
  return [
    ...arr(s.text).map((c): [string, string, string] => ["有", "has", `字${label(c)}`]),
    ...arr(s.present).map((c): [string, string, string] => ["有", "has", `图 ${label(c)}`]),
    ...arr(s.absentText).map((c): [string, string, string] => ["没有", "not", `字${label(c)}`]),
    ...arr(s.absent).map((c): [string, string, string] => ["没有", "not", `图 ${label(c)}`]),
  ];
}

/** The best template score the trace read for [state], if it read one. */
function best(conditions: Obj[], state: string | undefined): number | undefined {
  const scores = conditions.filter((c) => c.state === state && typeof c.score === "number").map((c) => c.score as number);
  return scores.length ? Math.max(...scores) : undefined;
}

const ratio = (a: number, b: number) => { const g = (x: number, y: number): number => y ? g(y, x % y) : x; const d = g(a, b); return `${a / d}:${b / d}`; };
