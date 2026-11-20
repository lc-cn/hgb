import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { EventRule, Notice, PlanOutcome } from "../../api/types";
import { Button, Card, Empty, Grow, Muted, PageHeader, PageTabs, Pill, Row, Switch } from "../../components/ui";
import { orAlert } from "../../components/useAction";
import { shortTime, type Tone } from "../../format";
import { Link, paths, useQuery } from "../../router";
import { useConsole } from "../../state/context";
import s from "./Notices.module.css";

/** Rules grouped as the design shows them; a rule the server adds later falls into 其他 until it is placed here. */
const GROUPS: [string, string, string[]][] = [
  ["任务", "每个作业", ["task-started", "task-succeeded", "execution-failed", "unknown-result", "queue-wait"]],
  ["计划", "一次计划的所有任务", ["plan-started", "plan-completed", "plan-missed"]],
  ["设备和账号", "服务端判断，设备离线也能发现", ["device-offline", "account-not-restored"]],
];
const TONES: Record<string, Tone> = { "execution-failed": "bad", "unknown-result": "warn", "account-not-restored": "warn", "plan-missed": "warn",
  "device-offline": "", "queue-wait": "warn", "plan-completed": "info", "plan-started": "info", "task-started": "info", "task-succeeded": "ok" };
const PAGE = 50;
const OUTCOMES: [PlanOutcome, string][] = [["COMPLETED", "全部完成"], ["PARTIAL_FAILED", "部分失败"], ["FAILED", "失败"], ["CANCELLED", "已取消"]];

/** 通知: what the rules noticed, the rules themselves, and (next) the channels that deliver them. */
export function NoticesPage() {
  const tab = useQuery().get("tab") ?? "log";
  return (
    <>
      <PageHeader title="通知" subtitle="规则决定什么时候记下一条事件，渠道决定把事件发到哪里。规则默认都关着；没有渠道订阅的事件也会记在这里。" />
      <PageTabs label="通知内容" value={tab} tabs={[["log", "事件记录", paths.notices], ["rules", "规则", `${paths.notices}?tab=rules`], ["channels", "渠道", `${paths.notices}?tab=channels`]]} />
      {tab === "rules" ? <Rules /> : tab === "channels" ? <Channels /> : <Log />}
    </>
  );
}

/** Reads [path] again whenever the stream says notices or rules changed. */
function useNotices<T>(path: string): [T | null, string | null] {
  const { data } = useConsole();
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    api<T>(path).then((next) => { if (current) { setValue(next); setError(null); } }).catch((failure: Error) => { if (current) setError(failure.message); });
    return () => { current = false; };
  }, [path, data.noticeVersion]);
  return [value, error];
}

/**
 * The notices, newest first, of all rules or one; the server filters and pages, continuing after the last one shown.
 * A new notice or a change of rules reads the first page again.
 */
function Log() {
  const { data } = useConsole();
  const [rule, setRule] = useState("");
  const [rules] = useNotices<EventRule[]>("/api/notifications/rules");
  const [pages, setPages] = useState<Notice[][]>([]);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const page = (after?: Notice) => api<Notice[]>(`/api/notifications/events?${new URLSearchParams({ ...(rule ? { rule } : {}), ...(after ? { after: `${after.at}:${after.id}` } : {}) })}`);
  useEffect(() => {
    let current = true;
    page().then((first) => { if (current) { setPages([first]); setMore(first.length === PAGE); setError(null); setLoaded(true); } })
      .catch((failure: Error) => { if (current) setError(failure.message); });
    return () => { current = false; };
  }, [rule, data.noticeVersion]);
  const notices = pages.flat();
  const loadMore = () => orAlert(async () => { const next = await page(notices[notices.length - 1]); setPages((now) => [...now, next]); setMore(next.length === PAGE); });
  if (error) return <Card><Empty>{error}</Empty></Card>;
  if (!loaded) return null;
  return (
    <>
      <div className={s.filter}>
        <label className={s.label}>规则
          <select value={rule} onChange={(e) => setRule(e.target.value)}>
            <option value="">全部</option>
            {(rules ?? []).map((item) => <option key={item.ruleId} value={item.ruleId}>{item.name}</option>)}
          </select>
        </label>
        <Muted as="span" className={s.keep}>保留最近 500 条</Muted>
      </div>
      <Card flush as="section" aria-label="事件记录">
        {notices.length ? notices.map((item) => (
          <article key={item.id} className={s.event}>
            <span className={s.time}>{shortTime(item.at)}</span>
            <div className={s.body}>
              <div className={s.titleLine}><Pill tone={TONES[item.ruleId] ?? ""}>{item.ruleName}</Pill><b>{item.title}</b></div>
              {item.detail && <Muted as="span">{item.detail}</Muted>}
              <Muted as="span" className={s.delivery}>没有渠道订阅这条规则</Muted>
            </div>
            {item.jobId && <Link to={paths.job(item.jobId)} className={s.open}>运行记录</Link>}
          </article>
        )) : <Empty>{rule ? "这条规则还没有记下事件" : "还没有事件。先到“规则”里打开你关心的规则。"}</Empty>}
      </Card>
      {more && <div className={s.more}><Button onClick={loadMore}>加载更多</Button></div>}
    </>
  );
}

function Rules() {
  const [rules, error] = useNotices<EventRule[]>("/api/notifications/rules");
  if (error) return <Card><Empty>{error}</Empty></Card>;
  if (!rules) return null;
  const placed = new Set(GROUPS.flatMap(([, , ids]) => ids));
  const groups: [string, string, EventRule[]][] = GROUPS.map(([name, hint, ids]) => [name, hint, ids.flatMap((id) => rules.filter((rule) => rule.ruleId === id))]);
  const rest = rules.filter((rule) => !placed.has(rule.ruleId));
  if (rest.length) groups.push(["其他", "", rest]);
  return (
    <>
      {groups.filter(([, , list]) => list.length).map(([name, hint, list]) => (
        <Card flush as="section" key={name} aria-label={name}>
          <h2 className={s.groupTitle}>{name}{hint && <Muted as="span"> · {hint}</Muted>}</h2>
          {list.map((rule) => <RuleRow key={rule.ruleId} rule={rule} />)}
        </Card>
      ))}
      <Muted>开启时只看之后发生的事：开始、成功、结束这类一次性的事不会补发；排队、掉线这类持续的情况按当前状态判断。同一次情况只记一条。</Muted>
    </>
  );
}

/**
 * One rule: its switch, and its threshold or plan results. Turning it on needs a threshold, which starts empty with the
 * suggestion shown as a hint; a change to an enabled rule is saved when the field is left.
 */
function RuleRow({ rule }: { rule: EventRule }) {
  const [threshold, setThreshold] = useState(rule.threshold?.toString() ?? "");
  const [results, setResults] = useState<PlanOutcome[]>(rule.results);
  // Compared by value: the rules are read again on every notice, and a new but equal list must not wipe what is being typed.
  const savedResults = rule.results.join(",");
  useEffect(() => { setThreshold(rule.threshold?.toString() ?? ""); setResults(rule.results); }, [rule.threshold, savedResults]);
  const hasValue = rule.kind === "COUNT" || rule.kind === "DURATION";
  const save = (enabled: boolean, nextResults = results) => orAlert(() => api(`/api/notifications/rules/${enc(rule.ruleId)}`, { method: "PUT",
    body: { enabled, threshold: hasValue && threshold.trim() ? Number(threshold) : null, results: nextResults } }));
  const flip = (outcome: PlanOutcome) => {
    const next = results.includes(outcome) ? results.filter((item) => item !== outcome) : [...results, outcome];
    // An enabled rule keeps at least one result: leaving none would show a filter the server does not have.
    if (rule.enabled && !next.length) { alert("至少保留一种结果；不想收到就关掉这条规则"); return; }
    setResults(next);
    if (rule.enabled) save(true, next);
  };
  return (
    <Row className={s.rule}>
      <Grow className={s.ruleText}><b>{rule.name}</b><Muted as="span">{rule.description}</Muted></Grow>
      {hasValue && (
        <label className={s.value}>{rule.kind === "COUNT" ? "连续" : "超过"}
          <input inputMode="numeric" value={threshold} placeholder={rule.suggested?.toString() ?? ""} aria-label={`${rule.name}：${rule.kind === "COUNT" ? "连续次数" : "分钟"}`}
            onChange={(e) => setThreshold(e.target.value.replace(/\D/g, ""))} onBlur={() => {
              if (!rule.enabled) return;
              // An enabled rule needs its threshold: an emptied field goes back to the one in use.
              if (!threshold) setThreshold(rule.threshold?.toString() ?? "");
              else if (Number(threshold) !== rule.threshold) save(true);
            }} />
          {rule.kind === "COUNT" ? "次" : "分钟"}
        </label>
      )}
      {rule.kind === "RESULT" && (
        <span role="group" aria-label="计划结果" className={s.chips}>
          {OUTCOMES.map(([id, label]) => <button key={id} type="button" className={s.chip} aria-pressed={results.includes(id)} onClick={() => flip(id)}>{label}</button>)}
        </span>
      )}
      <Switch checked={rule.enabled} label={`开启规则：${rule.name}`} onChange={(on) => {
        // Turning on with the field empty takes the suggestion, shown in the field so the user sees what was set.
        const value = hasValue ? threshold.trim() || rule.suggested?.toString() || "" : "";
        if (on && hasValue && !value) { alert("先填写阈值"); return; }
        if (hasValue) setThreshold(value);
        orAlert(() => api(`/api/notifications/rules/${enc(rule.ruleId)}`, { method: "PUT",
          body: { enabled: on, threshold: hasValue && value ? Number(value) : null, results } }));
      }} />
    </Row>
  );
}

/** The channels arrive with the next step; until then every notice is kept here and nothing is sent. */
function Channels() {
  return (
    <Card>
      <Empty>渠道在下一步接入：钉钉、企业微信、Bark、邮件、Webhook，以及推给 App 的本机通知。接入前事件只记在“事件记录”里，不会发出去。</Empty>
    </Card>
  );
}
