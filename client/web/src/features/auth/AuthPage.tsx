import { useState } from "react";
import { api } from "../../api/client";
import { Brand, Button, Card, Field, Form, LinkButton, Message, Tabs } from "../../components/ui";
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
  return (
    <main className={s.page}>
      <div className={s.box}>
        <Brand />
        <Card className={s.card}>
          <Tabs tabs={[["password", "密码登录"], ["code", "验证码登录"], ["register", "注册"]]} value={tab} onChange={choose} />
          {step.kind === "code" ? <CodeForm key={step.purpose + step.email} {...step} signedIn={signedIn}
            reset={() => { setTab("password"); setStep({ kind: "start" }); setNotice("密码已重置，请重新登录"); }} />
            : step.kind === "forgot" ? <EmailForm submit="发送验证码" send={async (email) => { await api("/api/auth/password/request", { method: "POST", body: { email } }); setStep({ kind: "code", email, purpose: "reset" }); }} />
            : tab === "password" ? <PasswordForm signedIn={signedIn} forgot={() => setStep({ kind: "forgot" })} />
            : tab === "code" ? <EmailForm submit="发送验证码" send={async (email) => { await api("/api/auth/code/request", { method: "POST", body: { email } }); setStep({ kind: "code", email, purpose: "login" }); }} />
            : <RegisterForm registered={(email) => setStep({ kind: "code", email, purpose: "verify" })} />}
        </Card>
        <Message text={notice || failed} ok={!!notice} />
        <LinkButton href="/api/auth/github/start">使用 GitHub 登录</LinkButton>
      </div>
    </main>
  );
}

function PasswordForm({ signedIn, forgot }: { signedIn: () => void; forgot: () => void }) {
  const [email, setEmail] = useState(""); const [password, setPassword] = useState("");
  const { busy, message, run } = useAction();
  return (
    <Form onSubmit={() => run(async () => { await api("/api/auth/login", { method: "POST", body: { email, password } }); signedIn(); })}>
      <Field label="邮箱"><input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      <Field label="密码"><input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
      <Button variant="primary" type="submit" disabled={busy}>登录</Button>
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
      <Button variant="primary" type="submit" disabled={busy}>{submit}</Button>
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
      <Button variant="primary" type="submit" disabled={busy}>注册</Button>
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
      <Button variant="primary" type="submit" disabled={busy}>{purpose === "reset" ? "重置密码" : purpose === "verify" ? "完成注册" : "登录"}</Button>
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}
