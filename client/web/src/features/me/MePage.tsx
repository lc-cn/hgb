import { useMemo, useState } from "react";
import { api, apiUrl, enc } from "../../api/client";
import type { Account } from "../../api/types";
import { Button, Card, Grow, MoreMenu, Muted, Row, TextSelect } from "../../components/ui";
import { applyAppearance, readAppearance, type Appearance } from "../../theme/appearance";
import { orAlert } from "../../components/useAction";
import { platformName, shortTime } from "../../format";
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
        <div className={s.who}><b className={s.email}>{who}</b></div>
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
      </section>
      <section aria-labelledby="prefs" className={s.section}>
        <h2 id="prefs">偏好</h2>
        <Card flush>
          <Row>
            <Grow><b className={s.semi}>外观</b></Grow>
            <TextSelect label="外观" value={look} onChange={pickLook} options={[["auto", "跟随系统"], ["light", "浅色"], ["dark", "深色"]]} />
          </Row>
          <TimeZone zone={data.timeZone} />
        </Card>
      </section>
      <section aria-labelledby="devices" className={s.section}>
        <h2 id="devices">我的设备</h2>
        <Card flush>
          {data.devices.map((device) => (
            <Row key={device.id}>
              <Grow><b className={s.semi}>{device.name}</b><br /><Muted as="span">{platformName(device.platform)} · {device.online ? "在线" : `最后在线 ${shortTime(device.lastSeenAt)}`}</Muted></Grow>
              <MoreMenu label={`${device.name} 的操作`} actions={[["解绑", () => unbind(device.id, device.name), true]]} />
            </Row>
          ))}
          <Row className={s.hintRow}><Grow><Muted as="span">在其他设备上安装护肝宝并登录这个账号即可添加</Muted></Grow><a href="/#download">下载</a></Row>
        </Card>
      </section>
      <section aria-labelledby="help" className={s.section}>
        <h2 id="help">帮助</h2>
        <Card flush>
          <a className={s.linkRow} href="/docs/"><b className={s.semi}>使用文档</b><Muted as="span">→</Muted></a>
          <a className={s.linkRow} href={`https://github.com/${import.meta.env.VITE_CODE_REPO}/issues`} target="_blank" rel="noreferrer"><b className={s.semi}>反馈问题</b><Muted as="span">GitHub Issues →</Muted></a>
        </Card>
      </section>
      <div><Button onClick={logout}>退出登录</Button></div>
    </div>
  );
}

/** The plan time zone, saved as soon as it is picked; every plan runs by it. */
function TimeZone({ zone }: { zone: string }) {
  const zones = useMemo(() => {
    const all = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
    return all.includes(zone) ? all : [zone, ...all];
  }, [zone]);
  return (
    <Row>
      <Grow><b className={s.semi}>计划时区</b></Grow>
      <TextSelect label="计划时区" value={zone} options={zones.map((item): [string, string] => [item, item])}
        onChange={(next) => orAlert(() => api("/api/me/time-zone", { method: "PUT", body: { timeZone: next } }))} />
    </Row>
  );
}
