import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { ApiFailure } from "../../api/client";
import { Link, match, usePath } from "../../router";
import { useConsole } from "../../state/context";
import { dev, type Capability, type DevMe, type DraftView, type TrialRecord } from "./api";
import { DevContext, devPaths, failure, useDev, type Edit } from "./context";
import { ConfigPage } from "./ConfigPage";
import { GamePage } from "./GamePage";
import { HomePage } from "./HomePage";
import { nodeDigest, snippets, str, target, tasks, trialDigest } from "./model";
import { ProgramPage } from "./ProgramPage";
import { PublishPage } from "./PublishPage";
import { RunPage } from "./RunPage";
import { SamplesPage } from "./SamplesPage";
import { StatesPage } from "./StatesPage";
import { TaskPage } from "./TaskPage";
import w from "./Wb.module.css";

/** The developer workbench in the console (developer-workbench.md, open-contribution.md step 1), as drawn on the design canvas. */
export function DevWorkbench() {
  const { data } = useConsole();
  const path = usePath();
  const [game, setGame] = useState<string | null>(null);
  const [draft, setDraft] = useState<DraftView | null>(null);
  const [catalog, setCatalog] = useState<Capability[]>([]);
  const [me, setMe] = useState<DevMe | null>(null);
  const [trials, setTrials] = useState<TrialRecord[]>([]);
  const [digests, setDigests] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // The draft as last read, for edits computed from it; reads are numbered so a late answer cannot replace a newer one.
  const latest = useRef<DraftView | null>(null);
  const reads = useRef(0);
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const load = useCallback(async (id: string) => {
    const n = ++reads.current;
    try { const view = await dev.draft(id); if (n === reads.current) { latest.current = view; setDraft(view); setProblem(null); } }
    catch (error) { if (n !== reads.current) return; if (error instanceof ApiFailure && error.status === 404) { latest.current = null; setDraft(null); } else setProblem(failure(error)); }
  }, []);
  const loadTrials = useCallback(async (id: string) => { try { setTrials(await dev.trials(id)); } catch { /* the list stays as it was */ } }, []);
  const gameIds = data.games.map((g) => g.id).join(",");
  useEffect(() => {
    (async () => {
      try {
        const [drafts, cat, mine] = await Promise.all([dev.drafts(), dev.catalog(), dev.me()]);
        setCatalog(cat); setMe(mine);
        const id = drafts[0]?.target ?? gameIds.split(",")[0] ?? "onmyoji";
        setGame(id); await Promise.all([load(id), loadTrials(id)]);
      } catch (error) { setProblem(failure(error)); }
      setLoaded(true);
    })();
  }, [load, loadTrials, gameIds]);
  // What every task and snippet is now, to tell what changed since the base and whether a trial ran on it.
  useEffect(() => {
    if (!draft) return;
    let live = true;
    const nodes = [...tasks(draft.body).map((t) => [`tasks/${t.id}`, t.raw] as const), ...snippets(draft.body).map((sn) => [`snippets/${String(sn.id)}`, sn] as const)];
    Promise.all([...nodes.map(async ([key, node]) => [key, await nodeDigest(node)] as const),
      ...tasks(draft.body).map(async (t) => [`trial/${t.id}`, await trialDigest(draft.body, t.raw)] as const)])
      .then((pairs) => { if (live) setDigests(Object.fromEntries(pairs)); });
    return () => { live = false; };
  }, [draft]);
  const reload = useCallback(async () => { if (game) await load(game); }, [game, load]);
  const reloadTrials = useCallback(async () => { if (game) await loadTrials(game); }, [game, loadTrials]);
  /**
   * Sends patches one batch at a time, each after the draft has been read back from the one before; [edit] may be a
   * function of that latest draft, so quick successive edits build on each other. On a conflict says so.
   */
  const apply = useCallback((edit: Edit) => {
    const run = queue.current.then(async (): Promise<string | null> => {
      if (!game || !latest.current) return "还没有草稿";
      try {
        const patches = typeof edit === "function" ? await edit(latest.current.body) : edit;
        if (!patches.length) return null;
        const result = await dev.patch(game, patches);
        await load(game);
        return result.conflicts.length ? `别处改过了，已重新读取：${result.conflicts[0].reason}` : null;
      } catch (error) { await load(game); return failure(error); }
    });
    queue.current = run.catch(() => null);
    return run;
  }, [game, load]);
  if (!loaded) return <div className={w.empty}>正在加载…</div>;
  if (!game) return <div className={w.card}><div className={w.empty}>{problem}</div></div>;
  const gameName = (draft && str(target(draft.body, game)?.name)) || data.games.find((g) => g.id === game)?.name || game;
  return (
    <DevContext.Provider value={{ game, gameName, draft, catalog, me, trials, digests, reload, reloadTrials, apply }}>
      <div className={w.page}>
        <SubNav path={path} game={gameName} />
        {problem && <div className={w.message}>{problem}</div>}
        {draft ? route(path) : <NoDraft />}
      </div>
    </DevContext.Provider>
  );
}

function route(path: string): ReactNode {
  let p: Record<string, string> | null;
  if ((p = match("/dev/tasks/:task/steps/:step", path))) return <ProgramPage key={path} task={p.task} step={p.step} />;
  if ((p = match("/dev/tasks/:task/run", path))) return <RunPage key={p.task} id={p.task} />;
  if ((p = match("/dev/tasks/:task/config", path))) return <ConfigPage key={p.task} id={p.task} />;
  if ((p = match("/dev/tasks/:task", path))) return <TaskPage key={p.task} id={p.task} />;
  if ((p = match("/dev/snippets/:id", path))) return <ProgramPage key={path} snippet={p.id} />;
  if ((p = match("/dev/samples/:id", path))) return <SamplesPage id={p.id} />;
  if (path === devPaths.samples) return <SamplesPage />;
  if (path === devPaths.states) return <StatesPage />;
  if (path === devPaths.publish) return <PublishPage />;
  if (path === devPaths.game) return <GamePage />;
  return <HomePage />;
}

function SubNav({ path, game }: { path: string; game: string }) {
  const items: [string, string, boolean][] = [
    ["任务", devPaths.home, path === devPaths.home || path.startsWith("/dev/tasks")],
    ["片段库", `${devPaths.home}#steplib`, path.startsWith("/dev/snippets")],
    ["状态库", devPaths.states, path === devPaths.states],
    ["样本库", devPaths.samples, path.startsWith(devPaths.samples)],
    ["游戏设置", devPaths.game, path === devPaths.game],
    ["发布", devPaths.publish, path === devPaths.publish],
  ];
  return (
    <nav className={w.subnav} aria-label="开发工作台">
      <span className={w.subnavTitle}>开发 · {game}</span>
      {items.map(([label, to, on]) => <Link key={label} to={to} className={w.subnavLink} aria-current={on ? "page" : undefined}>{label}</Link>)}
    </nav>
  );
}

function NoDraft() {
  const { game, reload } = useDev();
  const [busy, setBusy] = useState(false); const [text, setText] = useState<string | null>(null);
  return (
    <div className={w.card}>
      <div className={w.empty}>还没有这个游戏的草稿</div>
      <div style={{ display: "flex", justifyContent: "center", paddingBottom: 24, flexDirection: "column", alignItems: "center", gap: 8 }}>
        <button type="button" className={`${w.btn} ${w.primary}`} disabled={busy} onClick={async () => {
          setBusy(true); setText(null);
          try { await dev.importLatest(game); await reload(); } catch (error) { setText(failure(error)); }
          setBusy(false);
        }}>{busy ? "导入中…" : "从最新发布版本开始"}</button>
        {text && <div className={w.message}>{text}</div>}
      </div>
    </div>
  );
}
