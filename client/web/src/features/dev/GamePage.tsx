import { useEffect, useState } from "react";
import { Link } from "../../router";
import { dev, type Obj, type Sample } from "./api";
import { devPaths, useDev } from "./context";
import { uses } from "./HomePage";
import { arr, inlineStates, isObj, nodeDigest, objs, programOf, snippets, states, str, target, tasks } from "./model";
import { Back } from "./TaskPage";
import { Interruptions } from "./Interruptions";
import w from "./Wb.module.css";
import g from "./Game.module.css";

const IDENTITY: Record<string, string> = { NONE: "不区分账号", NICKNAME: "按昵称识别", UID: "按游戏 ID 识别" };

/** ② 游戏设置: the game the tasks run in, how its screens are read, and its libraries. */
export function GamePage() {
  const { draft, game, gameName, apply } = useDev();
  const [samples, setSamples] = useState<Sample[]>([]);
  const [text, setText] = useState<[string, boolean] | null>(null);
  useEffect(() => { dev.samples(game).then(setSamples, () => {}); }, [game]);
  const app = draft ? target(draft.body, game) : undefined;
  const [name, setName] = useState(str(app?.name) ?? "");
  const [packages, setPackages] = useState(arr(app?.androidPackages).join("、"));
  if (!draft || !app) return <div className={w.card}><div className={w.empty}>草稿里没有这个游戏</div></div>;
  const profile = objs(draft.body.visionProfiles).find((v) => v.targetId === game);
  const size = referenceSize(draft.body);
  const ratios = Object.entries(samples.reduce<Record<string, number>>((m, x) => { const k = ratio(x.width, x.height); m[k] = (m[k] ?? 0) + 1; return m; }, {}));
  const lib = snippets(draft.body);
  const all = tasks(draft.body);
  const top = lib.map((sn) => [str(sn.name) ?? String(sn.id), all.filter((t) => uses(t, String(sn.id))).length] as const).sort((a, b) => b[1] - a[1])[0];
  const stateCount = states(draft.body, game).length + inlineStates(draft.body).length;
  const save = async () => {
    const list = packages.split(/[、,，\s]+/).map((x) => x.trim()).filter(Boolean);
    const problem = await apply(async (body) => {
      const now = target(body, game);
      if (!now || (name.trim() === str(now.name) && list.join() === arr(now.androidPackages).join())) return [];
      return [{ op: "put", path: `targets:${game}`, expect: await nodeDigest(now), value: { ...now, name: name.trim(), androidPackages: list } }];
    });
    setText(problem ? [problem, false] : ["已保存", true]);
  };
  return (
    <>
      <header className={w.header}>
        <Link to={devPaths.home} aria-label="返回开发工作台" className={w.back}><Back /></Link>
        <h1 className={`${w.h1Small} ${g.grow}`}>游戏设置 · {gameName}</h1>
      </header>
      {text && <div className={text[1] ? `${w.muted} ${w.okText}` : w.message}>{text[0]}</div>}
      <div className={g.grid}>
        <section className={`${w.card} ${g.card}`} aria-labelledby="g1">
          <h2 id="g1" className={g.h2}>目标应用</h2>
          <div className={g.pair}>
            <div className={g.f} style={{ flex: "1 1 140px" }}><label htmlFor="gn">名称</label><input id="gn" value={name} onChange={(e) => setName(e.target.value)} onBlur={save} /></div>
            <div className={g.f} style={{ flex: "2 1 200px" }}><label htmlFor="gp">包名</label><input id="gp" value={packages} onChange={(e) => setPackages(e.target.value)} onBlur={save} /></div>
          </div>
          <div className={w.muted}>分身、多开识别为同一游戏的不同应用</div>
        </section>
        <section className={`${w.card} ${g.card}`} aria-labelledby="g2">
          <h2 id="g2" className={g.h2}>识别</h2>
          <div className={g.row}><span className={g.grow}>参考尺寸</span><b>{size ?? "未用到"}</b></div>
          <div className={g.row}><span className={g.grow}>识别模型</span><b>{str(profile?.recognizer) ?? "没有"}</b></div>
          <div className={g.row}><span className={g.grow}>验证过的屏幕比例</span>
            {ratios.length ? ratios.map(([k, n]) => <span key={k} className={`${w.chip} ${n > 1 ? w.pass : w.none}`}>{k} · {n} 张</span>) : <span className={w.muted}>还没有样本</span>}</div>
        </section>
        <Interruptions />
        <section className={`${w.card} ${g.card}`} aria-labelledby="g4">
          <h2 id="g4" className={g.h2}>账号</h2>
          <div className={g.row}><span className={g.grow}><b>识别当前账号</b><span className={`${w.muted} ${w.block}`}>{IDENTITY[str(app.identityPolicy) ?? ""] ?? str(app.identityPolicy)}</span></span></div>
        </section>
        <section className={`${w.card} ${g.card}`} aria-labelledby="g5">
          <h2 id="g5" className={g.h2}>状态库</h2>
          <div className={g.row}><span className={g.grow}>{stateCount} 个状态</span><Link className={w.btn} to={devPaths.states} style={{ minHeight: 34 }}>打开</Link></div>
        </section>
        <section className={`${w.card} ${g.card}`} aria-labelledby="g6">
          <h2 id="g6" className={g.h2}>片段库</h2>
          <div className={g.row}><span className={g.grow}>{lib.length} 个片段{top ? ` · ${top[0]} 用在 ${top[1]} 个任务` : ""}</span><Link className={w.btn} to={`${devPaths.home}#steplib`} style={{ minHeight: 34 }}>打开</Link></div>
        </section>
      </div>
    </>
  );
}

/** The reference size the page waits are written for, the most common one. */
function referenceSize(body: Obj): string | null {
  const count: Record<string, number> = {};
  const walk = (program: Obj[]) => program.forEach((node) => {
    const a = isObj(node.action) ? node.action : null;
    const inputs = a && isObj(a.inputs) ? a.inputs : null;
    if (inputs && typeof inputs.referenceWidth === "number") { const k = `${inputs.referenceWidth} × ${inputs.referenceHeight}`; count[k] = (count[k] ?? 0) + 1; }
    for (const v of Object.values(node)) if (Array.isArray(v)) walk(objs(v));
    for (const c of objs(node.cases)) walk(objs(c.program));
  });
  for (const t of tasks(body)) for (const st of t.steps) walk(programOf(st));
  for (const sn of snippets(body)) walk(programOf(sn));
  return Object.entries(count).sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
}

const ratio = (w0: number, h0: number) => { const gcd = (a: number, b: number): number => b ? gcd(b, a % b) : a; const d = gcd(w0, h0); return `${w0 / d}:${h0 / d}`; };
