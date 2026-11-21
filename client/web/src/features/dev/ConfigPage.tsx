import { useState } from "react";
import { Link } from "../../router";
import type { Json, Obj } from "./api";
import { devPaths, useDev } from "./context";
import { uniqueId } from "./HomePage";
import { childIds, childrenOf, isObj, nodeDigest, objs, programOf, snippets, str, tasks } from "./model";
import { Back } from "./TaskPage";
import w from "./Wb.module.css";
import c from "./Config.module.css";

const WIDGETS: [string, string][] = [["SINGLE_CHOICE", "单选 → 文字"], ["MULTI_CHOICE", "多选 → 文字数组"], ["NUMBER", "数字 → 数字"], ["RANGE", "范围 → 数字"],
  ["SWITCH", "开关 → 真/假"], ["TIME", "时间 → 文字"], ["DATE", "日期 → 文字"], ["DATE_RANGE", "日期范围 → 文字"], ["TEXT", "文字 → 文字"]];
const MARK = ["", "①", "②", "③", "④", "⑤"];
interface Use { mark: number; where: string; effect: string; href: string }

/** ⑤ 配置项: the task's settings, where each one is read, and the settings page as the user will see it. */
export function ConfigPage({ id }: { id: string }) {
  const { draft, apply } = useDev();
  const [pick, setPick] = useState(0);
  const [test, setTest] = useState<Obj>({});
  const [text, setText] = useState<string | null>(null);
  const task = draft ? tasks(draft.body).find((x) => x.id === id) : undefined;
  if (!draft || !task) return <div className={w.card}><div className={w.empty}>草稿里没有这个任务</div></div>;
  const fields = task.fields;
  const f = fields[Math.min(pick, fields.length - 1)];
  const lib = snippets(draft.body);
  const usesOf = (fid: string) => findUses(task.raw, task.steps, lib, fid, id);
  const values: Obj = Object.fromEntries(fields.map((x) => [String(x.id), test[String(x.id)] ?? x.recommended ?? null]));
  const visible = (x: Obj) => { const v = isObj(x.visibleWhen) ? x.visibleWhen : null; if (!v) return true; const cur = values[String(v.field)];
    return Array.isArray(cur) ? cur.includes(v.contains as Json) : cur === v.contains; };
  /** Changes one property of the selected field, on the field as the latest draft has it; nothing when it is already so. */
  const change = (fid: string, edit: (field: Obj) => Obj) => {
    setText(null);
    void apply(async (body) => {
      const now = tasks(body).find((x) => x.id === id)?.fields.find((x) => x.id === fid);
      if (!now) return [];
      const next = edit(now);
      return JSON.stringify(next) === JSON.stringify(now) ? [] : [{ op: "put", path: `tasks:${id}/fields:${fid}`, expect: await nodeDigest(now), value: next }];
    }).then((problem) => { if (problem) setText(problem); });
  };
  const set = (key: string, value: Json | undefined) => {
    if (!f) return;
    change(String(f.id), (now) => { const next = { ...now }; if (value === undefined || value === "") delete next[key]; else next[key] = value; return next; });
  };
  const add = async () => {
    const label = prompt("配置项名称")?.trim();
    if (!label) return;
    const fid = uniqueId(label, childIds(task.raw), "field");
    const problem = await apply([{ op: "insert", path: `tasks:${id}`, field: "fields", after: fields.length ? String(fields.at(-1)!.id) : null, value: { id: fid, label, widget: "SWITCH" } }]);
    if (problem) setText(problem); else setPick(fields.length);
  };
  const groups = Array.from(new Set(fields.map((x) => str(x.section) ?? "未分组")));
  return (
    <>
      <header className={w.header}>
        <Link to={devPaths.task(id)} aria-label="返回任务编辑" className={w.back}><Back /></Link>
        <div className={c.title}><div className={w.muted}>任务「{task.name}」</div><h1 className={w.h1Small}>配置项</h1></div>
      </header>
      {text && <div className={w.message}>{text}</div>}
      <section className={`${w.card} ${c.life}`} aria-labelledby="life">
        <h2 id="life" className={c.h2}>配置值从哪来、什么时候定下来</h2>
        <ol className={c.steps}>
          <li><b>1 · 用户保存</b><div className={w.muted}>按游戏账号存服务端</div></li>
          <li><b>2 · 作业带上</b><div className={w.muted}>必填没填就不下发</div></li>
          <li><b>3 · 运行快照</b><div className={w.muted}>开始时定死，中途改不影响</div></li>
          <li><b>试跑</b><div className={w.muted}>用右侧的测试值</div></li>
        </ol>
      </section>

      <div className={c.layout}>
        <nav aria-label="字段" className={`${w.card} ${c.nav}`}>
          {groups.map((g) => (
            <div key={g} className={c.group}>
              <div className={`${w.muted} ${c.groupName}`}>分组：{g}</div>
              {fields.map((x, i) => (str(x.section) ?? "未分组") === g && (
                <button key={String(x.id)} type="button" className={c.field} aria-current={i === pick ? "true" : undefined} style={{ marginLeft: x.visibleWhen ? 12 : 0 }} onClick={() => setPick(i)}>
                  <div className={w.bold}>{str(x.label)}</div>
                  <div className={w.muted}>{WIDGETS.find(([k]) => k === x.widget)?.[1] ?? str(x.widget)} · 用在 {Array.from(new Set(usesOf(String(x.id)).map((u) => MARK[u.mark]))).join("") || "—"}</div>
                </button>
              ))}
            </div>
          ))}
          <button type="button" className={`${w.btn} ${c.add}`} onClick={add}>加配置项</button>
        </nav>

        {f ? (
          <section aria-label="字段属性" className={c.main}>
            <div key={String(f.id)} className={`${w.card} ${c.form}`}>
              <div className={c.pair}>
                <div className={c.f} style={{ flex: "1 1 200px" }}><label htmlFor="lb"><span className={c.no}>1</span>名称</label><input id="lb" defaultValue={str(f.label)} onBlur={(e) => e.target.value !== f.label && set("label", e.target.value)} /></div>
                <div className={c.f} style={{ flex: "1 1 200px" }}><label htmlFor="wd"><span className={c.no}>3</span>控件</label>
                  <select id="wd" value={str(f.widget)} onChange={(e) => set("widget", e.target.value)}>{WIDGETS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}</select></div>
              </div>
              <div className={c.f}><label htmlFor="ds"><span className={c.no}>2</span>说明</label><input id="ds" defaultValue={str(f.description) ?? ""} onBlur={(e) => e.target.value !== (f.description ?? "") && set("description", e.target.value)} /></div>
              {(f.widget === "SINGLE_CHOICE" || f.widget === "MULTI_CHOICE") && (
                <div className={c.f}><span className={c.lb}><span className={c.no}>4</span>选项（显示 · 存的值）</span>
                  {[...objs(f.options), { label: "", value: "" }].map((o, i) => (
                    <div key={i} className={c.option}>
                      <input aria-label="选项文字" defaultValue={str(o.label)} placeholder={i === objs(f.options).length ? "加一个选项" : ""} onBlur={(e) => editOption(i, "label", e.target.value)} />
                      <input aria-label="选项值" className={c.mono} defaultValue={str(o.value)} onBlur={(e) => editOption(i, "value", e.target.value)} />
                    </div>
                  ))}
                </div>
              )}
              {(f.widget === "NUMBER" || f.widget === "RANGE") && (
                <div className={c.pair}>
                  <div className={c.f} style={{ flex: "1 1 100px" }}><label htmlFor="un"><span className={c.no}>4</span>单位</label><input id="un" defaultValue={str(f.unit) ?? ""} onBlur={(e) => set("unit", e.target.value)} /></div>
                  <div className={c.f} style={{ flex: "1 1 80px" }}><label htmlFor="mn">最小</label><input id="mn" inputMode="numeric" defaultValue={f.minimum === undefined ? "" : String(f.minimum)} onBlur={(e) => set("minimum", e.target.value === "" ? undefined : Number(e.target.value))} /></div>
                  <div className={c.f} style={{ flex: "1 1 80px" }}><label htmlFor="mx">最大</label><input id="mx" inputMode="numeric" defaultValue={f.maximum === undefined ? "" : String(f.maximum)} onBlur={(e) => set("maximum", e.target.value === "" ? undefined : Number(e.target.value))} /></div>
                </div>
              )}
              <div className={c.pair}>
                <div className={c.f} style={{ flex: "1 1 140px" }}><label htmlFor="rc"><span className={c.no}>5</span>推荐值</label>
                  <input id="rc" className={c.mono} defaultValue={f.recommended === undefined ? "" : JSON.stringify(f.recommended)} onBlur={(e) => { try { set("recommended", e.target.value === "" ? undefined : JSON.parse(e.target.value) as Json); } catch { set("recommended", e.target.value); } }} /></div>
                <div className={c.f} style={{ flex: "1 1 140px" }}><label htmlFor="sc"><span className={c.no}>6</span>分组</label><input id="sc" defaultValue={str(f.section) ?? ""} onBlur={(e) => set("section", e.target.value)} /></div>
                <div className={c.f} style={{ flex: "1 1 220px" }}><label htmlFor="vw"><span className={c.no}>7</span>只在…时显示</label>
                  <select id="vw" value={isObj(f.visibleWhen) ? `${str(f.visibleWhen.field)}=${str(f.visibleWhen.contains)}` : ""}
                    onChange={(e) => { const [field, contains] = e.target.value.split("="); set("visibleWhen", e.target.value ? { field, contains } : undefined); }}>
                    <option value="">总是显示</option>
                    {fields.filter((x) => x !== f && objs(x.options).length).flatMap((x) => objs(x.options).map((o) => (
                      <option key={`${String(x.id)}=${str(o.value)}`} value={`${String(x.id)}=${str(o.value)}`}>{str(x.label)} 选了「{str(o.label)}」</option>)))}
                  </select></div>
              </div>
              <label className={`${w.muted} ${c.check}`}><input type="checkbox" checked={f.required === true} onChange={(e) => set("required", e.target.checked || undefined)} />必填</label>
            </div>
            <div className={`${w.card} ${c.uses}`}>
              <b className={c.h3}>影响哪里</b>
              {usesOf(String(f.id)).map((u, i) => (
                <Link key={i} to={u.href} className={c.use}><span className={c.mark}>{MARK[u.mark]}</span>
                  <span className={c.grow}><b>{u.where}</b><span className={`${w.muted} ${w.block}`}>{u.effect}</span></span></Link>
              ))}
              {usesOf(String(f.id)).length === 0 && <div className={c.warn}>没有地方读这个配置项：发布前删掉，或在步骤里用上</div>}
              {isObj(f.visibleWhen) && <div className={c.ok}>守护 ✓ 只在「{str(fields.find((x) => x.id === (f.visibleWhen as Obj).field)?.label)}」选了对应项时显示</div>}
            </div>
          </section>
        ) : <section className={c.main}><div className={w.card}><div className={w.empty}>还没有配置项</div></div></section>}

        <aside aria-label="用户看到的设置页" className={c.aside}>
          <div className={c.phone}>
            <div className={c.phoneHead}>{task.name} · 设置（测试值）</div>
            <div className={c.phoneBody}>
              {groups.map((g) => (
                <div key={g} className={c.phoneGroup}>
                  {g !== "未分组" && <div className={c.section}>{g}</div>}
                  {fields.filter((x) => (str(x.section) ?? "未分组") === g && visible(x)).map((x) => (
                    <div key={String(x.id)} className={c.pc} style={x.visibleWhen ? { marginLeft: 14, borderStyle: "dashed" } : undefined}>
                      <div className={c.pcHead}><b className={c.grow}>{str(x.label)}</b>{x.required === true && <span className={c.required}>必填</span>}
                        {x.widget === "SWITCH" && <button type="button" className={c.sw} aria-pressed={values[String(x.id)] === true} aria-label={str(x.label)}
                          onClick={() => setTest({ ...test, [String(x.id)]: values[String(x.id)] !== true })}><span /></button>}</div>
                      {str(x.description) && <div className={c.desc}>{str(x.description)}</div>}
                      {objs(x.options).length > 0 && <div className={c.opts}>{objs(x.options).map((o) => {
                        const cur = values[String(x.id)];
                        const on = Array.isArray(cur) ? cur.includes(o.value as Json) : cur === o.value;
                        return <button key={str(o.value)} type="button" className={c.opt} aria-pressed={on} onClick={() => setTest({ ...test, [String(x.id)]: x.widget === "MULTI_CHOICE"
                          ? (on ? (cur as Json[]).filter((v) => v !== o.value) : [...(Array.isArray(cur) ? cur : []), o.value as Json]) : o.value as Json })}>{str(o.label)}</button>;
                      })}</div>}
                      {x.recommended !== undefined && <div className={c.rec}>推荐：{label(x, x.recommended)}</div>}
                    </div>
                  ))}
                </div>
              ))}
              {!fields.length && <div className={c.desc}>这个任务没有设置</div>}
            </div>
          </div>
          <div className={c.ok} role="status">按这组测试值试跑：{fields.filter(visible).map((x) => `${str(x.label)} ${label(x, values[String(x.id)])}`).join(" · ") || "无"}</div>
        </aside>
      </div>

      <section className={`${w.card} ${c.life}`} aria-labelledby="five">
        <h2 id="five" className={c.h2}>配置值影响执行的地方</h2>
        <table className={c.table}>
          <thead><tr><th scope="col">#</th><th scope="col">用在哪</th><th scope="col">在哪里写</th><th scope="col">运行时</th></tr></thead>
          <tbody>
            <tr><td><span className={c.mark}>②</span></td><td>分支</td><td>步骤编辑页：分支，值来自配置项</td><td>走匹配的情况；都不匹配且没有“其他” → 结果未确认</td></tr>
            <tr><td><span className={c.mark}>③</span></td><td>动作的输入</td><td>步骤编辑页属性：输入来源选“配置项”</td><td>直接用快照里的值，类型必须一致</td></tr>
            <tr><td><span className={c.mark}>④</span></td><td>次数 / 上限</td><td>重复 N 次的 N、重复直到的最多次数</td><td>0 次 = 不执行；上限 1–10000</td></tr>
            <tr><td><span className={c.mark}>⑤</span></td><td>被动触发</td><td>任务属性：看到这些字就自动运行</td><td>绑定的多选为空时不触发</td></tr>
          </tbody>
        </table>
        <div className={w.muted}>“只在…时显示”的配置项，只能在父条件成立的地方读（守护）。</div>
      </section>
    </>
  );

  function editOption(i: number, key: "label" | "value", value: string) {
    change(String(f!.id), (now) => {
      const list = objs(now.options).map((o) => ({ ...o }));
      if (i === list.length) { if (!value.trim()) return now; list.push({ label: value, value: key === "value" ? value : uniqueId(value, list.map((o) => String(o.value)), "option") }); }
      else if (!list[i] || list[i][key] === value) return now;
      else if (!value.trim() && key === "label") list.splice(i, 1);
      else list[i][key] = value;
      return { ...now, options: list };
    });
  }
}

/** Where [fid] is read: branches on it, action inputs bound to it, counts and limits, the passive trigger. */
function findUses(raw: Obj, steps: Obj[], lib: Obj[], fid: string, task: string): Use[] {
  const found: Use[] = [];
  const bound = (v: unknown) => isObj(v as never) && (v as Obj).type === "configuration" && (v as Obj).fieldId === fid;
  const walk = (program: Obj[], where: string, href: string) => program.forEach((node) => {
    const a = isObj(node.action) ? node.action : null;
    if (a && isObj(a.bindings)) for (const [input, b] of Object.entries(a.bindings)) if (bound(b)) found.push({ mark: 3, where: `${where} · ${str(a.id)}`, effect: `输入 ${input} 用这个值`, href });
    if (node.type === "branch" && bound(node.value)) found.push({ mark: 2, where: `${where} · 分支`, effect: "按它的值走哪一支", href });
    if ((node.type === "repeat" && bound(node.count)) || (node.type === "until" && bound(node.maximum))) found.push({ mark: 4, where: `${where} · ${node.type === "repeat" ? "重复 N 次" : "重复直到"}`, effect: "次数 / 上限", href });
    childrenOf(node).forEach(([, child]) => walk(child, where, href));
  });
  for (const st of steps) {
    const use = str(st.use);
    const sn = use ? lib.find((x) => x.id === use) : undefined;
    walk(sn ? programOf(sn) : programOf(st), `第 ${steps.indexOf(st) + 1} 步 ${str(st.name) ?? str(st.id)}`, use ? devPaths.snippet(use) : devPaths.step(task, String(st.id)));
  }
  const trigger = isObj(raw.trigger) ? raw.trigger : null;
  if (trigger && trigger.enabledBy === fid) found.push({ mark: 5, where: "被动触发", effect: "选了内容才会看字自动运行", href: devPaths.task(task) });
  return found;
}

const label = (f: Obj, v: Json | undefined): string => {
  if (v === undefined || v === null) return "未选";
  if (typeof v === "boolean") return v ? "开" : "关";
  if (Array.isArray(v)) return v.map((x) => label(f, x)).join("、") || "未选";
  return str(objs(f.options).find((o) => o.value === v)?.label) ?? `${String(v)}${str(f.unit) ?? ""}`;
};
