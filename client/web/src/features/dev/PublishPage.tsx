import { useEffect, useState } from "react";
import { Link } from "../../router";
import { useConsole } from "../../state/context";
import { dev, type CheckResult } from "./api";
import { devPaths, failure, useDev } from "./context";
import { standing } from "./HomePage";
import { resources, snippets, tasks } from "./model";
import { Back } from "./TaskPage";
import w from "./Wb.module.css";
import p from "./Publish.module.css";

const MIN_APP = "0.2.0";
type Mark = "pass" | "fail" | "warn";
interface Item { mark: Mark; title: string; detail: string; link?: string }

/** ⑨ 校验并发布: every check the draft must pass, then the version; only maintainers publish. */
export function PublishPage() {
  const { draft, game, gameName, me, trials, digests } = useDev();
  const { data } = useConsole();
  const [check, setCheck] = useState<CheckResult | null>(null);
  const [checkText, setCheckText] = useState<string | null>(null);
  const [version, setVersion] = useState("");
  const [changelog, setChangelog] = useState("");
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState<[string, boolean] | null>(null);
  useEffect(() => {
    if (!draft) return;
    let live = true;
    setCheck(null); setCheckText(null);
    dev.check(draft.body).then((c) => { if (live) setCheck(c); }, (e) => { if (live) setCheckText(failure(e)); });
    return () => { live = false; };
  }, [draft]);
  if (!draft) return null;
  const list = tasks(draft.body);
  const changed = list.map((t) => standing(t, draft.body, draft.bases, digests, trials.find((x) => x.task === t.id), data.devices)).filter((s) => s.changed);
  const untried = changed.filter((s) => s.tone !== "pass");
  const items: Item[] = [
    check ? (check.ok ? { mark: "pass", title: "结构、类型、产出未失效", detail: `0 个问题 · 含 ${snippets(draft.body).length} 个片段的参数和引用` }
      : { mark: "fail", title: "结构、类型、产出未失效", detail: check.issues[0] ? `${check.issues.length} 个问题 · ${check.issues[0].location}：${check.issues[0].reason}` : "没通过" })
      : { mark: checkText ? "fail" : "warn", title: "结构、类型、产出未失效", detail: checkText ?? "检查中…" },
    untried.length ? { mark: "fail", title: "改动任务试跑通过", detail: `${untried.map((s) => s.task.name).join("、")} ${untried.some((s) => s.tone === "fail") ? "没通过" : "未试跑"}`, link: devPaths.run(untried[0].task.id) }
      : { mark: "pass", title: "改动任务试跑通过", detail: changed.length ? "全部通过" : "没有改动的任务" },
    { mark: "warn", title: "最低 App 版本", detail: MIN_APP },
  ];
  if (check && !check.ok) items.splice(1, 0, ...check.issues.slice(1, 6).map((i): Item => ({ mark: "fail", title: i.location, detail: i.reason })));
  const blocked = items.filter((i) => i.mark === "fail").length;
  const maintainer = me?.maintainer === true;
  const publish = async () => {
    setBusy(true); setText(null);
    try {
      const plan = await dev.publish(game, { version: version.trim(), changelog, minApp: MIN_APP, dryRun: true });
      const kb = (n: number) => `${(n / 1024).toFixed(0)} KB`;
      if (confirm(`发布 ${plan.version}？任务包 ${kb(plan.bundleBytes)}，新上传 ${plan.uploads.length} 个素材（${kb(plan.uploadBytes)}），复用 ${plan.reused.length} 个。发布后所有用户都能拉到。`)) {
        const done = await dev.publish(game, { version: version.trim(), changelog, minApp: MIN_APP, dryRun: false });
        setText([`已发布 ${done.version}`, true]);
      }
    } catch (error) { setText([failure(error), false]); }
    setBusy(false);
  };
  const label = !maintainer ? "只有维护者能发布" : blocked ? `还有 ${blocked} 项没通过` : busy ? "发布中…" : "发布";
  return (
    <>
      <header className={w.header}>
        <Link to={devPaths.home} aria-label="返回开发工作台" className={w.back}><Back /></Link>
        <div className={w.headerText}><h1 className={w.h1Small}>校验并发布 · {gameName}</h1>
          <div className={w.muted}>发布到任务仓库的 Release · {maintainer ? "你的 GitHub 账号有写权限" : "需要任务仓库的写权限"}</div></div>
      </header>
      <div className={p.layout}>
        <section className={`${w.card} ${p.checks}`} aria-labelledby="check">
          <h2 id="check" className={p.h2}>校验</h2>
          {items.map((c, i) => (
            <div key={i} className={p.item} data-mark={c.mark}>
              <span aria-hidden="true" className={p.mark}>{c.mark === "pass" ? "✓" : c.mark === "fail" ? "✕" : "!"}</span>
              <span className={p.grow}><span className={w.bold}>{c.title}</span><span className={`${w.muted} ${w.block}`}>{c.detail}</span></span>
              {c.link && <Link to={c.link} className={p.link}>去试跑</Link>}
            </div>
          ))}
        </section>
        <section className={`${w.card} ${p.version}`} aria-labelledby="ver">
          <h2 id="ver" className={p.h2}>版本</h2>
          <div className={p.field}><label htmlFor="v">版本号</label><input id="v" value={version} placeholder="v1.2.0" onChange={(e) => setVersion(e.target.value)} /></div>
          <div className={p.field}><label htmlFor="n">更新说明（用户在任务库里看到）</label><textarea id="n" rows={5} value={changelog} onChange={(e) => setChangelog(e.target.value)} /></div>
          <div className={p.note}>需要 App {MIN_APP}+，旧 App 继续用当前版本</div>
          <div className={w.muted}>{list.length} 个任务 · {resources(draft.body).length} 个资源</div>
          <button type="button" className={`${w.btn} ${w.primary} ${p.center}`} disabled={!maintainer || blocked > 0 || busy || !version.trim() || !changelog.trim()} onClick={publish}>{label}</button>
          {text && <div role="status" className={text[1] ? p.done : w.message}>{text[0]}</div>}
        </section>
      </div>
    </>
  );
}
