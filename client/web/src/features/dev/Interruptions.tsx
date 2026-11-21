import { useState } from "react";
import type { Json, Obj } from "./api";
import { useDev } from "./context";
import { uniqueId } from "./HomePage";
import { arr, isObj, nodeDigest, objs, snippets, states, str, target } from "./model";
import w from "./Wb.module.css";
import g from "./Game.module.css";

/**
 * 干扰页 (technical design 1.6): pages that can come up over any other, a lost connection or a popup. Every page wait
 * of the game clears them first with their snippet, at most so many times, then waits on.
 */
export function Interruptions() {
  const { draft, game, apply } = useDev();
  const [editing, setEditing] = useState<string | "new" | null>(null);
  const [text, setText] = useState<string | null>(null);
  if (!draft) return null;
  const app = target(draft.body, game);
  const library = isObj(app?.library) ? app!.library : null;
  const pages = objs(library?.interruptions);
  const lib = snippets(draft.body);
  const known = states(draft.body, game);
  const describe = (page: Obj) => {
    const state = page.state;
    const seen = typeof state === "string" ? `状态 ${str(known.find((s) => s.id === state)?.name) ?? state}`
      : isObj(state) ? `有字「${arr(state.text).map((c) => (isObj(c) ? str(c.text) : str(c)) ?? "").join("」「")}」` : "";
    return `${seen} · 跑片段「${str(lib.find((sn) => sn.id === page.use)?.name) ?? str(page.use)}」`;
  };
  /** Writes [next] as the page [id] (new when absent), on the game as the latest draft has it. */
  const save = async (id: string | null, next: Obj | null) => {
    setText(null);
    const problem = await apply(async (body) => {
      const now = target(body, game);
      const list = objs(isObj(now?.library) ? now!.library.interruptions : undefined);
      const old = id ? list.find((p) => p.id === id) : undefined;
      if (old && next) return [{ op: "put", path: `targets:${game}/interruptions:${id}`, expect: await nodeDigest(old), value: next }];
      if (old) return [{ op: "delete", path: `targets:${game}/interruptions:${id}`, expect: await nodeDigest(old) }];
      if (next) return [{ op: "insert", path: `targets:${game}`, field: "library.interruptions", after: str(list.at(-1)?.id) ?? null, value: next }];
      return [];
    });
    if (problem) setText(problem); else setEditing(null);
  };
  return (
    <section className={`${w.card} ${g.card}`} aria-labelledby="g3">
      <div className={g.headRow}><h2 id="g3" className={`${g.h2} ${g.grow}`}>干扰页</h2>
        <button type="button" className={w.btn} style={{ minHeight: 34 }} onClick={() => setEditing("new")}>加干扰页</button></div>
      <div className={w.muted}>任何“等页面”遇到都会先处理，再接着等</div>
      {pages.map((page) => editing === page.id
        ? <PageForm key={String(page.id)} page={page} lib={lib} known={known} onSave={(next) => save(String(page.id), next)} onDelete={() => save(String(page.id), null)} onCancel={() => setEditing(null)} />
        : (
          <button key={String(page.id)} type="button" className={`${g.row} ${g.rowButton}`} onClick={() => setEditing(String(page.id))}>
            <b className={g.grow}>{str(page.name) ?? String(page.id)}</b><span className={w.muted}>{describe(page)}</span>
            <span className={`${w.chip} ${w.same}`}>≤ {typeof page.maximum === "number" ? page.maximum : 3} 次</span>
          </button>
        ))}
      {pages.length === 0 && editing !== "new" && <div className={w.muted}>还没有干扰页</div>}
      {editing === "new" && <PageForm lib={lib} known={known} taken={pages.map((p) => String(p.id))} onSave={(next) => save(null, next)} onCancel={() => setEditing(null)} />}
      {text && <div className={w.message}>{text}</div>}
    </section>
  );
}

/** One interruption page being written: how it is recognized, which snippet clears it, how often at most. */
function PageForm({ page, lib, known, taken = [], onSave, onDelete, onCancel }: { page?: Obj; lib: Obj[]; known: Obj[]; taken?: string[];
  onSave: (next: Obj) => void; onDelete?: () => void; onCancel: () => void }) {
  const written = page?.state;
  const [name, setName] = useState(str(page?.name) ?? "");
  const [byState, setByState] = useState(typeof written === "string" || (!page && known.length > 0));
  const [stateId, setStateId] = useState(typeof written === "string" ? written : str(known[0]?.id) ?? "");
  const [words, setWords] = useState(isObj(written) ? arr(written.text).map((c) => (isObj(c) ? str(c.text) : str(c)) ?? "").join("、") : "");
  const firstWithin = isObj(written) ? arr(written.text).map((c) => isObj(c) ? arr(c.within) : []).find((r) => r.length === 4) : undefined;
  const [within, setWithin] = useState((firstWithin ?? [0, 0, 2400, 1080]).join(","));
  const [use, setUse] = useState(str(page?.use) ?? str(lib[0]?.id) ?? "");
  const [maximum, setMaximum] = useState(String(typeof page?.maximum === "number" ? page.maximum : 3));
  const list = words.split(/[、,，\s]+/).map((x) => x.trim()).filter(Boolean);
  const region = within.split(/[,，\s]+/).map(Number).filter((n) => Number.isFinite(n));
  const ready = name.trim() && use && (byState ? stateId : list.length > 0 && region.length === 4 && region[2] > 0 && region[3] > 0)
    && Number(maximum) >= 1 && Number(maximum) <= 10;
  const submit = () => {
    const id = str(page?.id) ?? uniqueId(name, taken, "page");
    // Written in full: a page wait looks for every word within a region, with a confidence to clear.
    const state: Json = byState ? stateId : { id, text: list.map((t) => ({ text: t, minimumConfidence: 0.8, within: region })) };
    onSave({ id, name: name.trim(), state, use, ...(Number(maximum) !== 3 ? { maximum: Number(maximum) } : {}) });
  };
  return (
    <div className={g.form}>
      <div className={g.pair}>
        <div className={g.f} style={{ flex: "1 1 140px" }}><label htmlFor="pn">名称</label><input id="pn" value={name} onChange={(e) => setName(e.target.value)} placeholder="断线重连" /></div>
        <div className={g.f} style={{ flex: "0 1 100px" }}><label htmlFor="pm">最多处理</label><input id="pm" type="number" min={1} max={10} value={maximum} onChange={(e) => setMaximum(e.target.value)} /></div>
      </div>
      <div className={g.f}><span className={g.lb}>怎么认出它</span>
        <div className={g.pair}>
          <label className={g.radio}><input type="radio" checked={!byState} onChange={() => setByState(false)} />屏幕上有这些字</label>
          <label className={g.radio}><input type="radio" checked={byState} disabled={!known.length} onChange={() => setByState(true)} />状态库里的状态</label>
        </div>
        {byState ? <select aria-label="状态" value={stateId} onChange={(e) => setStateId(e.target.value)}>
          {known.map((s) => <option key={String(s.id)} value={String(s.id)}>{str(s.name) ?? String(s.id)}</option>)}</select>
          : <div className={g.pair}>
            <input aria-label="字" style={{ flex: "2 1 160px" }} value={words} onChange={(e) => setWords(e.target.value)} placeholder="已断开游戏服务器" />
            <input aria-label="在哪个区域找（x,y,宽,高）" title="在哪个区域找：x,y,宽,高；可在样本库框区域后复制" style={{ flex: "1 1 140px" }} value={within} onChange={(e) => setWithin(e.target.value)} />
          </div>}
      </div>
      <div className={g.f}><label htmlFor="pu">用哪个片段处理</label>
        <select id="pu" value={use} onChange={(e) => setUse(e.target.value)}>
          {!lib.length && <option value="">先在片段库建一个处理片段</option>}
          {lib.map((sn) => <option key={String(sn.id)} value={String(sn.id)}>{str(sn.name) ?? String(sn.id)}</option>)}
        </select></div>
      <div className={g.pair}>
        <button type="button" className={`${w.btn} ${w.primary}`} disabled={!ready} onClick={submit}>保存</button>
        <button type="button" className={w.btn} onClick={onCancel}>取消</button>
        {onDelete && <button type="button" className={w.btn} style={{ color: "var(--color-danger)" }} onClick={() => { if (confirm("删掉这个干扰页？")) onDelete(); }}>删除</button>}
      </div>
    </div>
  );
}
