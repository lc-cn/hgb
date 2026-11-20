import { useState } from "react";
import { api, apiUrl } from "../../api/client";
import { Brand, Button, Field, Form, Mark, Message, Tabs, ui } from "../../components/ui";
import { useAction } from "../../components/useAction";
import s from "./AuthPage.module.css";

type Tab = "password" | "code" | "register";
/** What the form is asking for: an email first, then the code sent to it. */
type Step = { kind: "start" } | { kind: "forgot" } | { kind: "code"; email: string; purpose: "verify" | "login" | "reset" };

/** Sign-in, sign-up and password reset by email, or GitHub. [signedIn] opens the console. */
export function AuthPage({ signedIn, notice: initialNotice }: { signedIn: () => void; notice?: string }) {
  const [tab, setTab] = useState<Tab>("password");
  const [step, setStep] = useState<Step>({ kind: "start" });
  const [notice, setNotice] = useState(initialNotice ?? "");
  // Read once, then dropped from the address, so signing out later does not show it again.
  const [failed] = useState(() => {
    const github = new URLSearchParams(location.search).get("error") === "github";
    if (github) history.replaceState(null, "", location.pathname);
    return github ? "GitHub 登录未完成，请重试或改用邮箱登录" : "";
  });
  const choose = (next: Tab) => { setTab(next); setStep({ kind: "start" }); setNotice(""); };
  const [title, sub] = step.kind === "forgot" ? ["重置密码", "验证码会发到你的邮箱"]
    : step.kind === "code" ? ["输入验证码", `验证码已发到 ${step.email}，10 分钟内有效`]
    : tab === "password" ? ["欢迎回来", "用邮箱和密码登录"] : tab === "code" ? ["用验证码登录", "验证码发到你的邮箱，10 分钟内有效"] : ["注册护肝宝", "注册后会给邮箱发一个验证码确认"];
  return (
    <div className={s.page}>
      <aside className={s.hero} aria-label="护肝宝">
        <a href="/" className={s.heroBrand} aria-label="回到护肝宝首页"><Mark size={36} /><span>护肝宝</span></a>
        <div className={s.heroText}>
          <h1>今晚的日常，<br />交给它值班。</h1>
          <p>网页、Windows 和安卓 App 用同一个账号。登录后能看到你所有设备正在做什么。</p>
        </div>
      </aside>
      <main className={s.main}>
        <div className={s.box}>
          <div className={s.narrowBrand}><Brand /></div>
          <div><h2 className={s.title}>{title}</h2><p className={s.sub}>{sub}</p></div>
          <Tabs label="登录方式" tabs={[["password", "密码登录"], ["code", "验证码登录"], ["register", "注册"]]} value={tab} onChange={choose} />
          {step.kind === "code" ? <CodeForm key={step.purpose + step.email} {...step} signedIn={signedIn}
            reset={() => { setTab("password"); setStep({ kind: "start" }); setNotice("密码已重置，请重新登录"); }} />
            : step.kind === "forgot" ? <EmailForm submit="发送验证码" send={async (email) => { await api("/api/auth/password/request", { method: "POST", body: { email } }); setStep({ kind: "code", email, purpose: "reset" }); }} />
            : tab === "password" ? <PasswordForm signedIn={signedIn} forgot={() => setStep({ kind: "forgot" })} />
            : tab === "code" ? <EmailForm submit="发送验证码" send={async (email) => { await api("/api/auth/code/request", { method: "POST", body: { email } }); setStep({ kind: "code", email, purpose: "login" }); }} />
            : <RegisterForm registered={(email) => setStep({ kind: "code", email, purpose: "verify" })} />}
          <Message text={notice || failed} ok={!!notice} />
          <div className={s.or} aria-hidden="true"><span />或者<span /></div>
          <a className={`${ui.button} ${ui.large} ${ui.ghost} ${s.github}`} href={apiUrl("/api/auth/github/start")}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-1.7c-2.8.6-3.4-1.3-3.4-1.3-.4-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.3 1.1 2.9.8.1-.6.3-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5a3.9 3.9 0 0 1 1-2.7c-.1-.3-.5-1.3.1-2.7 0 0 .8-.3 2.8 1a9.6 9.6 0 0 1 5 0c1.9-1.3 2.8-1 2.8-1 .6 1.4.2 2.4.1 2.7a3.9 3.9 0 0 1 1 2.7c0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.8V21c0 .3.2.6.7.5A10 10 0 0 0 12 2z" /></svg>
            使用 GitHub 登录</a>
        </div>
      </main>
    </div>
  );
}

function PasswordForm({ signedIn, forgot }: { signedIn: () => void; forgot: () => void }) {
  const [email, setEmail] = useState(""); const [password, setPassword] = useState("");
  const { busy, message, run } = useAction();
  return (
    <Form onSubmit={() => run(async () => { await api("/api/auth/login", { method: "POST", body: { email, password } }); signedIn(); })}>
      <Field label="邮箱"><input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      <Field label="密码"><input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
      <Button variant="primary" className={s.submit} type="submit" disabled={busy}>登录</Button>
      <Message text={message?.text} ok={message?.ok} />
      <Button variant="link" onClick={forgot}>忘记密码</Button>
    </Form>
  );
}

function EmailForm({ submit, send }: { submit: string; send: (email: string) => Promise<void> }) {
  const [email, setEmail] = useState("");
  const { busy, message, run } = useAction();
  return (
    <Form onSubmit={() => run(() => send(email))}>
      <Field label="邮箱"><input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      <Button variant="primary" className={s.submit} type="submit" disabled={busy}>{submit}</Button>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}

function RegisterForm({ registered }: { registered: (email: string) => void }) {
  const [email, setEmail] = useState(""); const [password, setPassword] = useState("");
  const { busy, message, run } = useAction();
  return (
    <Form onSubmit={() => run(async () => { await api("/api/auth/register", { method: "POST", body: { email, password } }); registered(email); })}>
      <Field label="邮箱"><input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      <Field label="密码（至少 10 位）"><input type="password" required minLength={10} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
      <Button variant="primary" className={s.submit} type="submit" disabled={busy}>注册</Button>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}

function CodeForm({ email, purpose, signedIn, reset }: { email: string; purpose: "verify" | "login" | "reset"; signedIn: () => void; reset: () => void }) {
  const [code, setCode] = useState(""); const [password, setPassword] = useState("");
  const { busy, message, run } = useAction();
  const submit = () => run(async () => {
    if (purpose === "reset") { await api("/api/auth/password/reset", { method: "POST", body: { email, code, password } }); reset(); return; }
    await api(purpose === "verify" ? "/api/auth/verify-email" : "/api/auth/code/login", { method: "POST", body: { email, code } });
    signedIn();
  });
  return (
    <Form onSubmit={submit}>
      <Field label="验证码"><input required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} /></Field>
      {purpose === "reset" && <Field label="新密码（至少 10 位）"><input type="password" required minLength={10} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>}
      <Button variant="primary" className={s.submit} type="submit" disabled={busy}>{purpose === "reset" ? "重置密码" : purpose === "verify" ? "完成注册" : "登录"}</Button>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}
