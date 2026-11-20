import { useMemo, useState } from "react";
import { api, apiUrl, enc } from "../../api/client";
import type { Account } from "../../api/types";
import { Button, Card, Grow, Message, Muted, Row, Segments } from "../../components/ui";
import { applyAppearance, readAppearance, type Appearance } from "../../theme/appearance";
import { orAlert, useAction } from "../../components/useAction";
import { dateTime, platformName } from "../../format";
import { useConsole } from "../../state/context";
import s from "./Me.module.css";

/**
 * 我的: who is signed in and what they have, how they sign in, this browser's look and the plan time zone, the devices
 * bound to the account, help, and signing out.
 */
export function MePage() {
  const { data, account: initial, signedOut } = useConsole();
  const [account, setAccount] = useState(initial);
  const [look, setLook] = useState<Appearance>(readAppearance);
  const gameName = (id: string) => data.games.find((game) => game.id === id)?.name ?? id;
  const unlink = () => {
    if (!confirm("解绑 GitHub？之后只能用邮箱登录。")) return;
    orAlert(async () => { await api("/api/auth/github", { method: "DELETE" }); setAccount(await api<Account>("/api/me")); });
  };
  const unbind = (id: string, name: string) => {
    if (!confirm(`解绑“${name}”？这台设备会下线，正在运行的作业结果将标记为未知。`)) return;
    orAlert(() => api(`/api/devices/${enc(id)}`, { method: "DELETE" }));
  };
  const logout = () => orAlert(async () => { await api("/api/auth/logout", { method: "POST", body: {} }); signedOut(); });
  const who = account.email ?? account.githubLogin ?? "";
  const pickLook = (next: Appearance) => { setLook(next); applyAppearance(next); };
  return (
    <div className={s.page}>
      <h1 className={s.hidden}>我的</h1>
      <Card className={s.profile} as="section" aria-label="账号">
        <span className={s.avatar} aria-hidden="true">{who.slice(0, 1).toUpperCase()}</span>
        <div className={s.who}><b className={s.email}>{who}</b><Muted as="span">网页、Windows、安卓 App 用同一个账号</Muted></div>
        <dl className={s.counts}>
          <div><dd>{data.devices.length}</dd><dt>设备</dt></div>
          <div><dd>{data.accounts.length}</dd><dt>游戏账号</dt></div>
          <div><dd>{data.plans.length}</dd><dt>计划</dt></div>
        </dl>
      </Card>
      <section aria-labelledby="login" className={s.section}>
        <h2 id="login">登录方式</h2>
        <Card flush>
          <Row><Grow><b className={s.semi}>邮箱</b><br /><Muted as="span">{account.email ? `${account.email} · ${account.hasPassword ? "已设置密码" : "没有设置密码，用验证码登录"}` : "未绑定"}</Muted></Grow></Row>
          <Row>
            <Grow><b className={s.semi}>GitHub</b><br /><Muted as="span">{account.githubLogin ? `已绑定 ${account.githubLogin}` : "未绑定"}</Muted></Grow>
            {account.githubLogin ? <Button onClick={unlink} disabled={!account.email}>解除绑定</Button> : <a className={s.button} href={apiUrl("/api/auth/github/start?link=1")}>绑定 GitHub</a>}
          </Row>
        </Card>
        <Muted>至少保留一种登录方式。绑定 GitHub 并且是任务仓库的协作者，App 里会出现“开发”入口。</Muted>
      </section>
      <section aria-labelledby="prefs" className={s.section}>
        <h2 id="prefs">偏好</h2>
        <Card flush>
          <Row>
            <Grow><b className={s.semi}>外观</b><br /><Muted as="span">只对这个浏览器生效</Muted></Grow>
            <Segments role="radiogroup" label="外观" value={look} onChange={pickLook} items={[["auto", "跟随系统"], ["light", "浅色"], ["dark", "深色"]]} />
          </Row>
          <TimeZone zone={data.timeZone} />
        </Card>
      </section>
      <section aria-labelledby="devices" className={s.section}>
        <h2 id="devices">我的设备</h2>
        <Card flush>
          {data.devices.map((device) => (
            <Row key={device.id}>
              <Grow><b className={s.semi}>{device.name}</b><br /><Muted as="span">{[platformName(device.platform), [...new Set(device.apps.map((app) => gameName(app.gameId)))].join("、")].filter(Boolean).join(" · ")}</Muted></Grow>
              <Muted as="span">{device.online ? "在线" : `最后在线 ${dateTime(device.lastSeenAt)}`}</Muted>
              <Button variant="danger" onClick={() => unbind(device.id, device.name)}>解绑</Button>
            </Row>
          ))}
          <Row className={s.hintRow}><Grow><Muted as="span">添加设备：在手机或电脑上安装护肝宝，用这个账号登录，就会出现在这里。</Muted></Grow><a href="/#download">下载护肝宝</a></Row>
        </Card>
      </section>
      <section aria-labelledby="help" className={s.section}>
        <h2 id="help">帮助</h2>
        <Card flush>
          <a className={s.linkRow} href="/docs/"><b className={s.semi}>使用文档</b><Muted as="span">→</Muted></a>
          <a className={s.linkRow} href="https://github.com/lc-cn/hgb/issues" target="_blank" rel="noreferrer"><b className={s.semi}>反馈问题</b><Muted as="span">GitHub Issues →</Muted></a>
        </Card>
      </section>
      <div><Button onClick={logout}>退出登录</Button></div>
    </div>
  );
}

function TimeZone({ zone }: { zone: string }) {
  const [editing, setEditing] = useState(false);
  const [next, setNext] = useState(zone);
  const { busy, message, run } = useAction();
  const zones = useMemo(() => {
    const all = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
    return all.includes(zone) ? all : [zone, ...all];
  }, [zone]);
  if (!editing) return (
    <Row><Grow><b className={s.semi}>计划时区</b><br /><Muted as="span">{zone} · 所有计划按这个时区的时间运行</Muted></Grow><Button onClick={() => { setNext(zone); setEditing(true); }}>修改</Button></Row>
  );
  return (
    <Row>
      <form className={s.zoneForm} onSubmit={(event) => { event.preventDefault(); run(async () => {
        if (next !== zone) await api("/api/me/time-zone", { method: "PUT", body: { timeZone: next } }); setEditing(false);
      }); }}>
        <label className={s.zoneLabel}>计划时区
          {zones.length ? <select value={next} onChange={(e) => setNext(e.target.value)}>{zones.map((item) => <option key={item} value={item}>{item}</option>)}</select>
            : <input value={next} onChange={(e) => setNext(e.target.value)} placeholder="例如 Asia/Shanghai" />}
        </label>
        <Button variant="primary" type="submit" disabled={busy}>保存</Button>
        <Button onClick={() => setEditing(false)}>取消</Button>
      </form>
      <Message text={message?.text} ok={message?.ok} />
    </Row>
  );
}
