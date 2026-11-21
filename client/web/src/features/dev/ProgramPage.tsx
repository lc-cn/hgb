import { useState, type ReactNode } from "react";
import { Link } from "../../router";
import type { Capability, Json, Obj } from "./api";
import { devPaths, useDev } from "./context";
import { uniqueId } from "./HomePage";
import { arr, childrenOf, isObj, nodeDigest, objs, programOf, snippets, str, tasks } from "./model";
import { Back, count } from "./TaskPage";
import w from "./Wb.module.css";
import p from "./Program.module.css";

type Kind = "look" | "act" | "flow" | "snip" | "end";
const TAG: Record<Kind, string> = { look: "看", act: "做", flow: "流程", snip: "片段", end: "结果" };
/** What each capability is called in the palette, and which group it sits in. */
const NAMES: Record<string, [Kind, string]> = {
  "screen.awaitScreen": ["look", "等页面"], "screen.locateTextIn": ["look", "找文字"], "screen.locateText": ["look", "找文字（全屏）"],
  "screen.locateImageIn": ["look", "找图标"], "screen.locateImage": ["look", "找图标（全屏）"], "screen.selectText": ["look", "选第几处文字"],
  "screen.selectImage": ["look", "选第几个图标"], "screen.readNumber": ["look", "读数字"], "screen.readFraction": ["look", "读分数"],
  "screen.matchColorIn": ["look", "看红点"], "screen.matchColor": ["look", "看颜色"], "screen.matchImage": ["look", "比图"],
  "screen.readText": ["look", "读整屏文字"], "screen.readRegionText": ["look", "读区域文字"], "screen.classifyScreen": ["look", "判页面"],
  "screen.classifyRegion": ["look", "判区域"], "screen.classifyText": ["look", "按文字判"], "screen.expectScreen": ["look", "确认页面"],
  "screen.expectText": ["look", "确认文字"], "screen.compareFrames": ["look", "画面变了吗"], "screen.deriveRegion": ["look", "按锚点算区域"],
  "screen.tap": ["act", "点击"], "screen.tapRegion": ["act", "点区域"], "screen.longPress": ["act", "长按"], "screen.swipe": ["act", "滑动"],
  "screen.dragRegion": ["act", "拖动"], "screen.back": ["act", "返回键"], "app.launch": ["act", "启动游戏"], "app.awaitForeground": ["act", "等游戏在前台"],
  "control.wait": ["act", "等一会"], "control.selectionContains": ["flow", "选项里有吗"],
  "task.result": ["end", "写结果"], "account.report": ["end", "报账号"], "notify.local": ["end", "发通知"],
};
const FLOWS: [string, string, Obj][] = [
  ["branch", "分支", { type: "branch", value: { type: "output", actionId: "", field: "" }, cases: [{ equals: "", program: [] }] }],
  ["forEach", "逐项", { type: "forEach", id: "each", items: [], program: [] }],
  ["until", "重复直到", { type: "until", id: "loop", value: { type: "output", actionId: "", field: "" }, equals: true, maximum: 10, program: [] }],
  ["repeat", "重复 N 次", { type: "repeat", count: 1, program: [] }],
  ["block", "块", { type: "block", id: "block", program: [] }],
  ["exit", "退出", { type: "exit", loop: "" }],
];
type Path = number[];

/** ④ 步骤编辑 / 片段编辑: a palette of actions, the program as numbered blocks, and the selected block's properties. */
export function ProgramPage({ task, step, snippet }: { task?: string; step?: string; snippet?: string }) {
  const { draft, catalog, apply } = useDev();
  const [sel, setSel] = useState<Path | null>(null);
  const [hole, setHole] = useState<Path | null>(null);
  const [query, setQuery] = useState("");
  const [json, setJson] = useState(false);
  const [text, setText] = useState<string | null>(null);
  if (!draft) return null;
  const owningTask = task ? tasks(draft.body).find((t) => t.id === task) : undefined;
  const owner: Obj | undefined = snippet ? snippets(draft.body).find((sn) => sn.id === snippet) : owningTask?.steps.find((st) => st.id === step);
  if (!owner) return <div className={w.card}><div className={w.empty}>草稿里没有这个{snippet ? "片段" : "步骤"}</div></div>;
  const ownerPath = snippet ? `snippets:${snippet}` : `tasks:${task}/steps:${step}`;
  const program = programOf(owner);
  const lib = snippets(draft.body);
  const stepIndex = owningTask ? owningTask.steps.indexOf(owner) + 1 : 0;
  const node = sel ? nodeAt(program, sel) : null;

  /** The step or snippet as the latest draft has it. */
  const ownerIn = (body: Obj): Obj | undefined => snippet ? snippets(body).find((sn) => sn.id === snippet)
    : tasks(body).find((t) => t.id === task)?.steps.find((st) => st.id === step);
  /** Changes the program as the latest draft has it, replacing the step or snippet whole; refused if it changed elsewhere. */
  const commit = async (edit: (program: Obj[]) => Obj[], select?: Path | null) => {
    setText(null);
    const problem = await apply(async (body) => {
      const now = ownerIn(body);
      if (!now) return [];
      const rest = Object.fromEntries(Object.entries(now).filter(([k]) => k !== "actions"));
      return [{ op: "put", path: ownerPath, expect: await nodeDigest(now), value: { ...rest, program: edit(programOf(now)) } as Obj }];
    });
    if (problem) setText(problem); else if (select !== undefined) setSel(select);
  };
  /** Adds the block [make] builds (given the ids already used in the latest program, so quick adds do not repeat one). */
  const add = (make: (taken: string[]) => Obj) => {
    // At the end of the chosen hole, after the selected block, or at the end.
    if (hole) { const at = hole; return commit((pr) => setList(pr, at, [...listAt(pr, at), make(allIds(pr))]), [...at, listAt(program, at).length]); }
    if (sel) { const parent = sel.slice(0, -1); const at = sel.at(-1)! + 1;
      return commit((pr) => { const list = listAt(pr, parent); return setList(pr, parent, [...list.slice(0, at), make(allIds(pr)), ...list.slice(at)]); }, [...parent, at]); }
    return commit((pr) => [...pr, make(allIds(pr))], [program.length]);
  };
  const addAction = (c: Capability) => add((taken) => ({ type: "action", action: { id: uniqueId(c.id.split(".").at(-1) ?? "action", taken), capability: c.id, contractVersion: c.version, inputs: {}, bindings: {} } }));
  const palette: [string, [Kind, string, () => void][]][] = [
    ["看画面", catalog.filter((c) => NAMES[c.id]?.[0] === "look").map((c): [Kind, string, () => void] => ["look", NAMES[c.id][1], () => void addAction(c)])],
    ["动手", catalog.filter((c) => NAMES[c.id]?.[0] === "act").map((c): [Kind, string, () => void] => ["act", NAMES[c.id][1], () => void addAction(c)])],
    ["流程", [...FLOWS.map(([, label, made]): [Kind, string, () => void] => ["flow", label, () => void add((taken) => withId(made, taken))]),
      ...catalog.filter((c) => NAMES[c.id]?.[0] === "flow").map((c): [Kind, string, () => void] => ["flow", NAMES[c.id][1], () => void addAction(c)])]],
    ["片段", lib.filter((sn) => sn.id !== snippet).map((sn): [Kind, string, () => void] => ["snip", str(sn.name) ?? String(sn.id),
      () => void add((taken) => ({ type: "snippet", id: uniqueId(String(sn.id), taken), use: String(sn.id), with: {} }))])],
    ["收尾", catalog.filter((c) => NAMES[c.id]?.[0] === "end").map((c): [Kind, string, () => void] => ["end", NAMES[c.id][1], () => void addAction(c)])],
  ];
  const q = query.trim();
  return (
    <>
      <header className={w.header}>
        <Link to={snippet ? `${devPaths.home}#steplib` : devPaths.task(task!)} aria-label="返回" className={w.back}><Back /></Link>
        <div className={p.title}>
          <div className={w.muted}>{snippet ? "片段库" : `${owningTask!.name} › 第 ${stepIndex} 步`}</div>
          <h1 className={w.h1Small}>{snippet ? str(owner.name) ?? snippet : str(owner.name) ?? step}</h1>
          <div className={w.muted}>{objs(owner.params).length ? `参数 ${objs(owner.params).map((x) => str(x.name)).join("、")} · ` : ""}{count(program)} 个动作</div>
        </div>
        <Link className={w.btn} to={devPaths.states}>判定台</Link>
        <button type="button" className={w.btn} aria-pressed={json} onClick={() => setJson(!json)}>JSON</button>
        {!snippet && <Link className={`${w.btn} ${w.primary}`} to={`${devPaths.run(task!)}?step=${encodeURIComponent(step!)}`}>试跑本步</Link>}
      </header>
      {text && <div className={w.message}>{text}</div>}
      {json && <pre className={`${w.card} ${p.json}`}>{JSON.stringify(owner, null, 2)}</pre>}
      <div className={p.layout}>
        <nav aria-label="动作目录" className={`${w.card} ${p.palette}`}>
          <b>动作</b>
          <input aria-label="搜动作" placeholder="搜动作" className={p.search} value={query} onChange={(e) => setQuery(e.target.value)} />
          {palette.map(([group, items]) => {
            const shown = items.filter(([, label]) => !q || label.includes(q));
            return shown.length > 0 && (
              <div key={group} className={p.group}>
                <div className={p.grp}>{group}</div>
                {shown.map(([kind, label, onAdd], i) => <button key={i} type="button" className={p.pal} onClick={onAdd}><span className={`${p.k} ${p[kind]}`}>{TAG[kind]}</span><span>{label}</span></button>)}
              </div>
            );
          })}
          <div className={w.muted}>{hole ? "加在选中的空位末尾" : sel ? "加在选中的块后面" : "加在最后"}</div>
        </nav>

        <section aria-label="动作编排" className={`${w.card} ${p.tree}`}>
          <Tree program={program} at={[]} prefix="" sel={sel} hole={hole} pick={(x) => { setSel(x); setHole(null); }} pickHole={(x) => { setHole(x); setSel(null); }} />
        </section>

        <aside aria-label="属性" className={`${w.card} ${p.inspector}`}>
          {node && sel ? (
            <Inspector key={sel.join("/")} node={node} num={numberOf(program, sel)} program={program} fields={owningTask?.fields ?? []}
              catalog={catalog} lib={lib} onChange={(edit) => void commit((pr) => { const now = nodeAt(pr, sel); return now ? replaceAt(pr, sel, edit(now)) : pr; })}
              onMove={(d) => { const parent = sel.slice(0, -1); const i = sel.at(-1)!; const j = i + d;
                if (j < 0 || j >= listAt(program, parent).length) return;
                void commit((pr) => { const copy = [...listAt(pr, parent)]; if (j >= copy.length) return pr; [copy[i], copy[j]] = [copy[j], copy[i]]; return setList(pr, parent, copy); }, [...parent, j]); }}
              onRemove={() => { if (confirm("删掉这个块？里面的也一起删")) { const parent = sel.slice(0, -1); const i = sel.at(-1)!;
                void commit((pr) => setList(pr, parent, listAt(pr, parent).filter((_, k) => k !== i)), null); } }} />
          ) : <span className={w.muted}>点中间的块看和改它的属性；点“＋ 加动作”选空位，再从左边加</span>}
        </aside>
      </div>
    </>
  );
}

function Tree({ program, at, prefix, sel, hole, pick, pickHole, offset = 0 }: { program: Obj[]; at: Path; prefix: string; sel: Path | null; hole: Path | null;
  pick: (p: Path) => void; pickHole: (p: Path) => void; offset?: number }) {
  return (
    <>
      {program.map((node, i) => {
        const here = [...at, i];
        const [kind, label, pill] = describeNode(node);
        const num = `${prefix}${offset + i + 1}`;
        const on = sel?.join("/") === here.join("/");
        const slots = childrenOf(node);
        let child = 0;
        return (
          <div key={i} className={p.item}>
            <button type="button" className={p.node} aria-pressed={on} onClick={() => pick(here)}>
              <span className={p.num}>{num}</span><span className={`${p.k} ${p[kind]}`}>{TAG[kind]}</span><span className={p.label}>{label}</span>
              {pill && <span className={p.pill}>{pill}</span>}
            </button>
            {slots.length > 0 && (
              <div className={p.children}>
                {slots.map(([slotLabel, list], s) => {
                  const start = child; child += list.length;
                  return (
                    <div key={s}>
                      {slotLabel && <div className={p.case}>{slotLabel.replace(/^= "?/, "").replace(/"$/, "")}</div>}
                      <Tree program={list} at={[...here, s]} prefix={`${num}.`} sel={sel} hole={hole} pick={pick} pickHole={pickHole} offset={start} />
                      <button type="button" className={p.hole} aria-pressed={hole?.join("/") === [...here, s].join("/")} onClick={() => pickHole([...here, s])}>＋ 加动作</button>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      {at.length === 0 && <button type="button" className={p.hole} aria-pressed={hole?.length === 0} onClick={() => pickHole([])}>＋ 加动作</button>}
    </>
  );
}

/** A block's tag, its one-line label and a short pill. */
function describeNode(node: Obj): [Kind, string, string] {
  const a = isObj(node.action) ? node.action : null;
  if (a) {
    const [kind, name] = NAMES[String(a.capability)] ?? ["act", String(a.capability)];
    const inputs = isObj(a.inputs) ? a.inputs : {};
    const detail = inputs.text !== undefined ? `「${String(inputs.text)}」` : inputs.template !== undefined ? ` ${String(inputs.template)}`
      : Array.isArray(inputs.states) ? `：${objs(inputs.states).map((s) => str(s.id)).join("、")}` : "";
    return [kind, `${name}${detail}`, str(a.id) ?? ""];
  }
  const v = (b: Json | undefined) => isObj(b) ? (b.type === "configuration" ? `配置项 ${str(b.fieldId)}` : `${str(b.actionId)}.${str(b.field)}`) : JSON.stringify(b);
  switch (node.type) {
    case "branch": return ["flow", `分支：按 ${v(node.value)}`, ""];
    case "until": return ["flow", `重复直到 ${v(node.value)} = ${JSON.stringify(node.equals)}`, `最多 ${v(node.maximum)}`];
    case "repeat": return ["flow", `重复 ${v(node.count)} 次`, ""];
    case "forEach": return ["flow", `逐项：${arr(node.items).length} 项`, str(node.id) ?? ""];
    case "block": return ["flow", "块", str(node.id) ?? ""];
    case "exit": return ["flow", `退出：${str(node.loop)}`, ""];
    case "snippet": return ["snip", `片段：${str(node.use)}`, Object.keys(isObj(node.with) ? node.with : {}).join(" · ")];
    default: return ["flow", String(node.type), ""];
  }
}

function Inspector({ node, num, program, fields, catalog, lib, onChange, onMove, onRemove }: { node: Obj; num: string; program: Obj[]; fields: Obj[];
  catalog: Capability[]; lib: Obj[]; onChange: (edit: (node: Obj) => Obj) => void; onMove: (d: number) => void; onRemove: () => void }) {
  const [kind, label] = describeNode(node);
  const a = isObj(node.action) ? node.action : null;
  const tools = (
    <div className={p.tools}>
      <button type="button" className={w.btn} aria-label="上移" onClick={() => onMove(-1)}>↑</button>
      <button type="button" className={w.btn} aria-label="下移" onClick={() => onMove(1)}>↓</button>
      <button type="button" className={`${w.btn} ${p.danger}`} onClick={onRemove}>删除</button>
    </div>
  );
  if (!a) {
    const props = Object.entries(node).filter(([k]) => !["type", "program", "cases", "use"].includes(k));
    return (
      <>
        <div><div className={w.muted}>{num} · {TAG[kind]}</div><b className={p.h}>{label}</b></div>
        {node.type === "snippet" && <Field name="片段"><select className={p.val} value={str(node.use)} onChange={(e) => { const use = e.target.value; onChange((n) => ({ ...n, use })); }}>
          {lib.map((sn) => <option key={String(sn.id)} value={String(sn.id)}>{str(sn.name) ?? String(sn.id)}</option>)}</select></Field>}
        {props.map(([k, v]) => (k === "value" ? <Source key={k} name="看哪个值" value={v} program={program} fields={fields} catalog={catalog} onChange={(x) => onChange((n) => ({ ...n, [k]: x ?? null }))} />
          : <JsonField key={k} name={PROP[k] ?? k} value={v} onChange={(x) => onChange((n) => ({ ...n, [k]: x }))} />))}
        {node.type === "branch" && <Field name="情况">
          {objs(node.cases).map((c, i) => (
            <div key={i} className={p.caseRow}>
              <input className={p.val} defaultValue={JSON.stringify(c.equals)} aria-label="等于" onBlur={(e) => { const equals = parse(e.target.value); if (JSON.stringify(equals) === JSON.stringify(c.equals)) return;
                onChange((n) => ({ ...n, cases: objs(n.cases).map((x, k) => k === i ? { ...x, equals } : x) })); }} />
              <button type="button" className={w.btn} aria-label="删掉这个情况" onClick={() => onChange((n) => ({ ...n, cases: objs(n.cases).filter((_, k) => k !== i) }))}>✕</button>
            </div>
          ))}
          <button type="button" className={`${w.btn} ${p.dashed}`} onClick={() => onChange((n) => ({ ...n, cases: [...objs(n.cases), { equals: "", program: [] }] }))}>加情况</button>
        </Field>}
        {tools}
      </>
    );
  }
  const contract = catalog.find((c) => c.id === a.capability && c.version === a.contractVersion);
  const inputs = isObj(a.inputs) ? a.inputs : {};
  const bindings = isObj(a.bindings) ? a.bindings : {};
  const names = Array.from(new Set([...Object.keys(contract?.inputs ?? {}), ...Object.keys(inputs), ...Object.keys(bindings)]));
  const setInput = (name: string, value: Json | undefined, binding: Json | undefined) => onChange((n) => {
    const now = isObj(n.action) ? n.action : a;
    const ni = { ...(isObj(now.inputs) ? now.inputs : {}) }; const nb = { ...(isObj(now.bindings) ? now.bindings : {}) };
    if (value === undefined) delete ni[name]; else ni[name] = value;
    if (binding === undefined) delete nb[name]; else nb[name] = binding;
    return { ...n, action: { ...now, inputs: ni, bindings: nb } };
  });
  return (
    <>
      <div><div className={w.muted}>{num} · {TAG[kind]} · {str(a.id)}</div><b className={p.h}>{label}</b></div>
      {!contract && <div className={w.message}>这台服务器不认识这个动作</div>}
      {names.map((name) => {
        const type = contract?.inputs[name] ?? "";
        const optional = contract?.optional?.includes(name);
        const binding = bindings[name];
        return <Source key={name} name={name} type={`${type.toLowerCase()}${optional ? " · 可省略" : ""}`} value={isObj(binding) ? binding : inputs[name]} fixed
          program={program} fields={fields} catalog={catalog} self={str(a.id)}
          onChange={(x) => isObj(x) && (x.type === "output" || x.type === "configuration") ? setInput(name, undefined, x) : setInput(name, x, undefined)} />;
      })}
      {contract && Object.keys(contract.outputs).length > 0 && <div className={w.muted}>产出：{Object.entries(contract.outputs).map(([k, t]) => `${k}（${t.toLowerCase()}）`).join("、")}</div>}
      {tools}
    </>
  );
}

const PROP: Record<string, string> = { equals: "等于", maximum: "最多次数", count: "次数", items: "逐项的内容", loop: "退出哪个循环", id: "编号", with: "参数", or: "或者", index: "第几项" };

/** One input: where its value comes from (an earlier output, a setting, or written here) and the value. */
function Source({ name, type, value, program, fields, catalog, self, fixed, onChange }: { name: string; type?: string; value: Json | undefined; program: Obj[]; fields: Obj[];
  catalog: Capability[]; self?: string; fixed?: boolean; onChange: (value: Json | undefined) => void }) {
  const kind = isObj(value) && value.type === "output" ? "output" : isObj(value) && value.type === "configuration" ? "configuration" : "fixed";
  const outputs = allActions(program).filter((x) => str(x.id) !== self).flatMap((x) => Object.keys(catalog.find((c) => c.id === x.capability)?.outputs ?? {}).map((f) => `${str(x.id)}.${f}`));
  const sources: [string, string][] = [["output", "产出"], ["configuration", "配置项"], ...(fixed ? [["fixed", "固定值"] as [string, string]] : [])];
  return (
    <div className={p.input}>
      <span className={p.lb}>{name}{type ? <span className={p.type}> {type}</span> : null}</span>
      <div className={p.src} role="radiogroup" aria-label={`${name} 来源`}>
        {sources.map(([k, label]) => <button key={k} type="button" role="radio" aria-checked={kind === k} onClick={() => {
          if (k === kind) return;
          // The nearest output of the same name (observation, target, …), else the nearest output at all.
          if (k === "output") { const pick = [...outputs].reverse().find((o) => o.endsWith(`.${name}`)) ?? outputs.at(-1);
            onChange({ type: "output", actionId: pick?.split(".")[0] ?? "", field: pick?.split(".")[1] ?? "" }); }
          else if (k === "configuration") onChange({ type: "configuration", fieldId: String(fields[0]?.id ?? "") });
          else onChange(undefined);
        }}>{label}</button>)}
      </div>
      {kind === "output" && isObj(value) ? (
        <select className={p.val} value={`${str(value.actionId)}.${str(value.field)}`} onChange={(e) => { const [actionId, field] = e.target.value.split("."); onChange({ type: "output", actionId, field }); }}>
          {!outputs.includes(`${str(value.actionId)}.${str(value.field)}`) && <option value={`${str(value.actionId)}.${str(value.field)}`}>{str(value.actionId)}.{str(value.field)}（找不到）</option>}
          {outputs.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      ) : kind === "configuration" && isObj(value) ? (
        <select className={p.val} value={str(value.fieldId)} onChange={(e) => onChange({ type: "configuration", fieldId: e.target.value })}>
          {!fields.some((f) => f.id === value.fieldId) && <option value={str(value.fieldId)}>{str(value.fieldId)}（找不到）</option>}
          {fields.map((f) => <option key={String(f.id)} value={String(f.id)}>{str(f.label) ?? String(f.id)}</option>)}
        </select>
      ) : (
        <textarea className={`${p.val} ${p.code}`} rows={value !== undefined && value !== null && typeof value === "object" && !flat(value) ? 4 : 1} defaultValue={value === undefined ? "" : show(value)} placeholder="（不填）"
          onBlur={(e) => { const next = e.target.value.trim() === "" ? undefined : parse(e.target.value); if (JSON.stringify(next) !== JSON.stringify(value)) onChange(next); }} />
      )}
    </div>
  );
}

function JsonField({ name, value, onChange }: { name: string; value: Json; onChange: (value: Json) => void }) {
  return <Field name={name}><textarea className={`${p.val} ${p.code}`} rows={typeof value === "object" && value !== null && !flat(value) ? 4 : 1} defaultValue={show(value)}
    onBlur={(e) => { const next = parse(e.target.value); if (JSON.stringify(next) !== JSON.stringify(value)) onChange(next); }} /></Field>;
}
const Field = ({ name, children }: { name: string; children: ReactNode }) => <div className={p.input}><span className={p.lb}>{name}</span>{children}</div>;

const flat = (v: Json) => Array.isArray(v) && v.every((x) => x === null || typeof x !== "object");
const show = (v: Json) => typeof v === "string" ? v : JSON.stringify(v, null, flat(v) ? undefined : 1);
/** Text as a value: JSON when it reads as JSON, otherwise the text itself. */
const parse = (text: string): Json => { try { return JSON.parse(text) as Json; } catch { return text; } };

// ---- The program as a tree addressed by index paths: [node, slot, node, slot, …]. A list path has even length. ----

function listAt(program: Obj[], path: Path): Obj[] {
  if (path.length === 0) return program;
  const node = program[path[0]];
  return node ? listAt(childrenOf(node)[path[1]]?.[1] ?? [], path.slice(2)) : [];
}
function nodeAt(program: Obj[], path: Path): Obj | null {
  return listAt(program, path.slice(0, -1))[path.at(-1)!] ?? null;
}
function setList(program: Obj[], path: Path, list: Obj[]): Obj[] {
  if (path.length === 0) return list;
  const [i, slot, ...rest] = path;
  return program.map((node, k) => k !== i ? node : withChild(node, slot, setList(childrenOf(node)[slot]?.[1] ?? [], rest, list)));
}
function replaceAt(program: Obj[], path: Path, next: Obj): Obj[] {
  const parent = path.slice(0, -1);
  return setList(program, parent, listAt(program, parent).map((n, i) => i === path.at(-1) ? next : n));
}
function withChild(node: Obj, slot: number, list: Obj[]): Obj {
  if (node.type === "branch") return { ...node, cases: objs(node.cases).map((c, i) => i === slot ? { ...c, program: list } : c) };
  return { ...node, program: list };
}
/** "4.2.1": children are numbered on across a branch's cases. */
function numberOf(program: Obj[], path: Path): string {
  const parts: number[] = [];
  for (let k = 0; k < path.length; k += 2) {
    const before = k === 0 ? 0 : childrenOf(nodeAt(program, path.slice(0, k - 1))!).slice(0, path[k - 1]).reduce((n, [, l]) => n + l.length, 0);
    parts.push(before + path[k] + 1);
  }
  return parts.join(".");
}
function allActions(program: Obj[]): Obj[] {
  return program.flatMap((node) => [...(isObj(node.action) ? [node.action] : []), ...childrenOf(node).flatMap(([, l]) => allActions(l))]);
}
function allIds(program: Obj[]): string[] {
  return program.flatMap((node) => [...[str(node.id), isObj(node.action) ? str(node.action.id) : undefined].filter((x): x is string => !!x), ...childrenOf(node).flatMap(([, l]) => allIds(l))]);
}
const withId = (made: Obj, ids: string[]): Obj => typeof made.id === "string" ? { ...made, id: uniqueId(made.id, ids) } : made;
