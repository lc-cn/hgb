import { useEffect, useRef, useState, type PointerEvent, type ReactNode } from "react";
import { Link, navigate } from "../../router";
import { useConsole } from "../../state/context";
import { dev, follow, type Sample, type Template } from "./api";
import { devPaths, failure, useDev } from "./context";
import { clock } from "./HomePage";
import { nodeDigest, resources } from "./model";
import { Back } from "./TaskPage";
import w from "./Wb.module.css";
import m from "./Samples.module.css";

type Tool = "region" | "template" | "offset" | "point" | "private";
const TOOLS: [Tool, string][] = [["region", "框区域"], ["template", "裁模板"], ["offset", "量偏移"], ["point", "看坐标"], ["private", "标玩家信息"]];
const overlaps = (a: number[], b: number[]) => a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];

/** ⑥ 样本标注: the samples, and on the chosen one boxes for regions and templates, offsets, points and player information. */
export function SamplesPage({ id }: { id?: string }) {
  const { game, draft, apply, me } = useDev();
  const { data } = useConsole();
  const [list, setList] = useState<Sample[] | null>(null);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [tool, setTool] = useState<Tool>("region");
  const [showTexts, setShowTexts] = useState(true);
  const [rect, setRect] = useState<number[] | null>(null);
  const [points, setPoints] = useState<[number, number][]>([]);
  const [name, setName] = useState("");
  const [confirmed, setConfirmed] = useState<string[]>([]);
  const [reason, setReason] = useState("");
  const [made, setMade] = useState<Template | null>(null);
  const [text, setText] = useState<[string, boolean] | null>(null);
  const [sampling, setSampling] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const start = useRef<[number, number] | null>(null);
  const load = () => Promise.all([dev.samples(game).then(setList), dev.templates(game).then(setTemplates)]).catch((e) => setText([failure(e), false]));
  useEffect(() => { void load(); }, [game]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setRect(null); setPoints([]); setMade(null); setConfirmed([]); }, [id, tool]);
  const sample = list?.find((x) => x.id === id) ?? list?.[0];
  const phone = data.devices.find((d) => d.platform === "android" && d.online);

  const takeOne = async () => {
    if (!phone) { setText(["没有在线的手机", false]); return; }
    setSampling(true); setText(null);
    try {
      const run = await dev.startRun({ kind: "sample", target: game, device: phone.id, name: "样本" });
      const done = await follow(run.id, () => {});
      if (done.state === "FINISHED") { await load(); setText(["已采到一张", true]); } else setText([done.detail || "没采到", false]);
    } catch (error) { setText([failure(error), false]); }
    setSampling(false);
  };

  const header = (
    <header className={w.header}>
      <Link to={devPaths.home} aria-label="返回开发工作台" className={w.back}><Back /></Link>
      <div className={m.title}><h1 className={w.h1Small}>样本库</h1><div className={w.muted}>{me?.samples ?? list?.length ?? 0} 张 · 只属于你</div></div>
      <label className={w.btn}>上传图片
        <input type="file" accept="image/*" hidden onChange={async (e) => {
          const file = e.target.files?.[0]; e.target.value = "";
          if (!file) return;
          setText(null);
          try { const made = await dev.uploadSample(game, file); await load(); navigate(devPaths.sample(made.id)); setText(["已上传；点“让手机识别”读出文字后才能裁模板", true]); }
          catch (error) { setText([failure(error), false]); }
        }} /></label>
      <button type="button" className={w.btn} disabled={sampling} onClick={takeOne}>{sampling ? "等手机采样…" : "让手机现在采一张"}</button>
    </header>
  );
  if (!list) return <>{header}<div className={w.empty}>正在加载…</div></>;
  if (!sample) return <>{header}{text && <div className={w.message}>{text[0]}</div>}<div className={w.card}><div className={w.empty}>还没有样本 · 在游戏里点悬浮球“采样”添加</div></div></>;

  const scale = () => (box.current?.clientWidth ?? sample.width) / sample.width;
  const at = (event: PointerEvent<HTMLDivElement>): [number, number] => {
    const r = box.current!.getBoundingClientRect();
    return [Math.round((event.clientX - r.left) / scale()), Math.round((event.clientY - r.top) / scale())];
  };
  const pct = (r: number[]) => ({ left: `${r[0] / sample.width * 100}%`, top: `${r[1] / sample.height * 100}%`, width: `${r[2] / sample.width * 100}%`, height: `${r[3] / sample.height * 100}%` });
  const drawing = tool === "region" || tool === "template" || tool === "private";
  const inside = rect ? Array.from(new Set(sample.texts.filter((t) => overlaps(t.box, rect)).map((t) => t.text))) : [];
  const mine = templates.filter((t) => t.sample === sample.id);

  const cut = async () => {
    if (!rect) return;
    setText(null);
    try { const t = await dev.cut(game, { id: name.trim(), sample: sample.id, rect, confirmedTexts: confirmed, reason: reason.trim() || undefined }); setMade(t); await load(); }
    catch (error) { setText([failure(error), false]); }
  };
  const addToDraft = async () => {
    if (!made || !draft) return;
    const entry = { id: made.id, assetName: made.assetName, bytes: made.bytes, sha256: made.sha256 };
    const problem = await apply(async (body) => {
      const old = resources(body).find((r) => r.id === made.id);
      return [old ? { op: "put", path: `resources:${made.id}`, expect: await nodeDigest(old), value: entry } : { op: "insert", path: "", field: "resources", value: entry }];
    });
    setText(problem ? [problem, false] : [`草稿素材里有 ${made.id} 了`, true]);
  };
  const markPrivate = async () => {
    if (!rect) return;
    try { await dev.changeSample(sample.id, { privateRegions: [...sample.privateRegions, rect] }); setRect(null); await load(); setText(["已标为玩家信息，这里不能裁模板", true]); }
    catch (error) { setText([failure(error), false]); }
  };
  const copy = (value: string) => { void navigator.clipboard?.writeText(value); setText([`已复制 ${value}`, true]); };

  const panel = (() => {
    if (tool === "point") {
      const p = points.at(-1);
      return <Panel title="坐标" mono={p ? `(${p[0]}, ${p[1]})` : "点图上一处"} body={`参考尺寸 ${sample.width}×${sample.height}`}
        action={p && <button type="button" className={`${w.btn} ${w.primary}`} onClick={() => copy(`${p[0]}, ${p[1]}`)}>复制坐标</button>} />;
    }
    if (tool === "offset") {
      const [a, b] = points;
      return <Panel title="偏移：锚点 → 目标" mono={a ? `锚点 (${a[0]}, ${a[1]})${b ? ` → (${b[0]}, ${b[1]})` : ""}` : "先点锚点，再点目标"}
        body={a && b ? `dx ${b[0] - a[0]} · dy ${b[1] - a[1]}` : "两次点击"}
        block={a && b ? `按锚点偏移点 · dx ${b[0] - a[0]} · dy ${b[1] - a[1]}` : undefined}
        action={a && b && <button type="button" className={`${w.btn} ${w.primary}`} onClick={() => copy(`${b[0] - a[0]}, ${b[1] - a[1]}`)}>复制偏移</button>} />;
    }
    if (!rect) return <Panel title={tool === "private" ? "标玩家信息" : tool === "template" ? "裁模板" : "框区域"} mono="在图上拖一个框"
      body={tool === "private" ? "昵称、ID、聊天这类框起来，之后不能从这里裁模板" : tool === "template" ? "模板尽量小：64×64 以内" : "框出等页面、找字的范围"} />;
    const mono = `[${rect.join(", ")}]`;
    if (tool === "private") return <Panel title="玩家信息" mono={mono} body="标上以后这块不会进任何模板" action={<button type="button" className={`${w.btn} ${w.primary}`} onClick={markPrivate}>标为玩家信息</button>} />;
    if (tool === "region") return <Panel title="区域" mono={mono} body={inside.length ? `读到「${inside.join("」「")}」` : "框里没读到字"}
      block={inside.length ? `等页面 · 有字「${inside[0]}」在 ${mono}` : `区域 ${mono}`}
      action={<><button type="button" className={`${w.btn} ${w.primary}`} onClick={() => copy(`[${rect.join(",")}]`)}>复制区域</button><Link className={w.btn} to={devPaths.states}>在全部样本上试</Link></>} />;
    return (
      <div className={`${w.card} ${m.panel}`}>
        <h2 className={m.h2}>模板</h2>
        <div className={m.mono}>{mono}{name.trim() ? ` → ${name.trim()}.png` : ""}</div>
        <div className={m.f}><label htmlFor="tn">模板名（字母、数字、. _ -）</label><input id="tn" value={name} onChange={(e) => setName(e.target.value)} placeholder="foster-taiko" /></div>
        {(rect[2] > 64 || rect[3] > 64) && <div className={m.f}><label htmlFor="tr">超过 64×64 的理由</label><input id="tr" value={reason} onChange={(e) => setReason(e.target.value)} /></div>}
        {inside.length > 0 && <div className={w.muted}>框里有字，逐个确认是游戏界面上的字：
          <div className={m.checks}>{inside.map((t) => <label key={t}><input type="checkbox" checked={confirmed.includes(t)} onChange={(e) => setConfirmed(e.target.checked ? [...confirmed, t] : confirmed.filter((x) => x !== t))} />{t}</label>)}</div></div>}
        {made ? <>
          <div className={m.block}><img src={dev.templateImage(game, made.id)} alt={made.id} className={m.made} /> {made.assetName} · {made.width}×{made.height} · {made.bytes} 字节</div>
          <div className={m.actions}><button type="button" className={`${w.btn} ${w.primary}`} onClick={addToDraft}>加入草稿素材</button></div>
        </> : <div className={m.actions}><button type="button" className={`${w.btn} ${w.primary}`} disabled={!name.trim()} onClick={cut}>存为模板</button></div>}
      </div>
    );
  })();

  return (
    <>
      {header}
      {text && <div className={text[1] ? `${w.muted} ${w.okText}` : w.message}>{text[0]}</div>}
      <div className={m.layout}>
        <nav aria-label="样本" className={`${w.card} ${m.list}`}>
          {list.map((x) => (
            <button key={x.id} type="button" className={m.item} aria-current={x.id === sample.id ? "true" : undefined} onClick={() => navigate(devPaths.sample(x.id))}>
              <div className={w.bold}>{x.name}</div><div className={w.muted}>{x.device || "手机"} · {clock(x.createdAt)}</div>
            </button>
          ))}
        </nav>
        <section aria-label="标注" className={m.main}>
          <div className={m.nameRow}>
            <input key={sample.id} aria-label="样本名" className={m.nameInput} defaultValue={sample.name} maxLength={64}
              onBlur={async (e) => { const name = e.target.value.trim(); if (!name || name === sample.name) return;
                try { await dev.changeSample(sample.id, { name }); await load(); } catch (error) { setText([failure(error), false]); } }} />
            <span className={w.muted}>{sample.width}×{sample.height} · {sample.device || "手机"} · {clock(sample.createdAt)}</span>
            {!sample.recognized && <button type="button" className={w.btn} disabled={sampling} onClick={async () => {
              if (!phone) { setText(["没有在线的手机", false]); return; }
              setSampling(true); setText(null);
              try {
                const run = await dev.startRun({ kind: "recognize", target: game, device: phone.id, sample: sample.id });
                const done = await follow(run.id, () => {});
                if (done.state === "FINISHED") { await load(); setText([done.detail || "已识别", true]); } else setText([done.detail || "没识别成", false]);
              } catch (error) { setText([failure(error), false]); }
              setSampling(false);
            }}>{sampling ? "手机识别中…" : "让手机识别"}</button>}
          </div>
          <div role="toolbar" aria-label="工具" className={`${w.card} ${m.toolbar}`}>
            {TOOLS.map(([t, label]) => <button key={t} type="button" aria-pressed={tool === t} className={m.tool} onClick={() => setTool(t)}>{label}</button>)}
            <span className={m.grow} />
            <label className={`${w.muted} ${m.toggle}`}><input type="checkbox" checked={showTexts} onChange={(e) => setShowTexts(e.target.checked)} />显示识别到的文字</label>
          </div>
          <div className={`${w.card} ${m.stage}`}>
            <div ref={box} className={m.canvas} data-tool={tool}
              onPointerDown={(e) => {
                if (!drawing) { const p = at(e); setPoints(tool === "offset" && points.length === 1 ? [points[0], p] : [p]); return; }
                e.currentTarget.setPointerCapture(e.pointerId); start.current = at(e); setRect(null); setMade(null);
              }}
              onPointerMove={(e) => { if (!start.current) return; const [x, y] = at(e); const [sx, sy] = start.current;
                setRect([Math.min(x, sx), Math.min(y, sy), Math.abs(x - sx), Math.abs(y - sy)]); }}
              onPointerUp={() => { start.current = null; setRect((r) => r && (r[2] < 4 || r[3] < 4) ? null : r); }}>
              <img src={dev.sampleImage(sample.id)} alt={sample.name} draggable={false} />
              {showTexts && sample.texts.map((t, i) => <div key={i} className={m.box} data-kind="text" style={pct(t.box)}><span>{t.text}</span></div>)}
              {mine.map((t) => <div key={t.id} className={m.box} data-kind="template" style={pct(t.rect)}><span>模板 {t.id}</span></div>)}
              {sample.privateRegions.map((r, i) => <div key={`p${i}`} className={m.box} data-kind="private" style={pct(r)}><span>玩家信息</span></div>)}
              {rect && <div className={m.box} data-kind="region" style={pct(rect)}><span>{tool === "template" ? "模板" : tool === "private" ? "玩家信息" : "区域"}</span></div>}
              {points.map((p, i) => <div key={`pt${i}`} className={m.point} style={{ left: `${p[0] / sample.width * 100}%`, top: `${p[1] / sample.height * 100}%` }} />)}
            </div>
            <div className={`${w.muted} ${m.legend}`}>绿：识别到的字 · 紫：区域 · 橙：模板 · 红：玩家信息
              <span className={m.grow} />
              <button type="button" className={m.delete} onClick={async () => {
                if (!confirm("删除这张样本？裁好的模板会保留")) return;
                await dev.deleteSample(sample.id); await load(); navigate(devPaths.samples);
              }}>删除样本</button></div>
          </div>
        </section>
        <aside aria-label="选中的区域" className={m.aside}>
          {rect && drawing && <RectEditor rect={rect} sample={sample} onChange={(r) => { setRect(r); setMade(null); }} />}
          {panel}
          <div className={`${w.card} ${m.panel} ${m.texts}`}>
            <h2 className={m.h2}>这张图上读到的文字</h2>
            {sample.texts.length ? sample.texts.slice(0, 40).map((t, i) => <div key={i} className={m.text}><span className={m.grow}>{t.text}</span><span className={w.muted}>{t.box[0]}, {t.box[1]}</span></div>)
              : <span className={w.muted}>{sample.recognized ? "没读到字" : "手机还没传识别结果"}</span>}
          </div>
        </aside>
      </div>
    </>
  );
}

/** The drawn box in sample pixels, adjustable by number, with what is inside it enlarged. */
function RectEditor({ rect, sample, onChange }: { rect: number[]; sample: Sample; onChange: (rect: number[]) => void }) {
  const zoom = Math.min(4, 280 / Math.max(rect[2], 1), 160 / Math.max(rect[3], 1));
  const set = (i: number, v: string) => {
    const n = Math.round(Number(v));
    if (!Number.isFinite(n)) return;
    const next = [...rect]; next[i] = n;
    next[0] = Math.max(0, Math.min(next[0], sample.width - 1)); next[1] = Math.max(0, Math.min(next[1], sample.height - 1));
    next[2] = Math.max(1, Math.min(next[2], sample.width - next[0])); next[3] = Math.max(1, Math.min(next[3], sample.height - next[1]));
    onChange(next);
  };
  return (
    <div className={`${w.card} ${m.panel}`}>
      <div className={m.rect}>{(["x", "y", "宽", "高"] as const).map((label, i) => (
        <label key={label} className={m.f}><span>{label}</span><input type="number" value={rect[i]} onChange={(e) => set(i, e.target.value)} /></label>
      ))}</div>
      <div className={m.zoom} role="img" aria-label="框里的内容（放大）" style={{ width: rect[2] * zoom, height: rect[3] * zoom,
        backgroundImage: `url(${dev.sampleImage(sample.id)})`, backgroundSize: `${sample.width * zoom}px ${sample.height * zoom}px`,
        backgroundPosition: `${-rect[0] * zoom}px ${-rect[1] * zoom}px` }} />
    </div>
  );
}

function Panel({ title, mono, body, block, action }: { title: string; mono: string; body: string; block?: string; action?: ReactNode }) {
  return (
    <div className={`${w.card} ${m.panel}`}>
      <h2 className={m.h2}>{title}</h2>
      <div className={m.mono}>{mono}</div>
      <div className={w.muted}>{body}</div>
      {block && <div className={`${m.block} ${m.mono}`}>{block}</div>}
      {action && <div className={m.actions}>{action}</div>}
    </div>
  );
}
