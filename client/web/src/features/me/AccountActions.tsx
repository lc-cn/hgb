import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { Account, EmailBindResult, MergeOffer } from "../../api/types";
import { Button, Card, Form, Message, Muted } from "../../components/ui";
import { useAction } from "../../components/useAction";
import s from "./Me.module.css";

/** Binds an email to an account that has none: a code to the address, then the code. */
export function BindEmail({ done, merge }: { done: (account: Account) => void; merge: (offer: MergeOffer) => void }) {
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const { busy, message, run } = useAction();
  const send = () => run(async () => { await api("/api/me/email/request", { method: "POST", body: { email } }); setSent(true); return `验证码已发到 ${email}`; });
  const verify = () => run(async () => {
    const result = await api<EmailBindResult>("/api/me/email/verify", { method: "POST", body: { email, code } });
    if (result.merge) merge(result.merge); else if (result.account) done(result.account);
  });
  return (
    <Form inline onSubmit={sent ? verify : send} className={s.inlineFlow}>
      <input type="email" required autoComplete="email" placeholder="邮箱" aria-label="邮箱" value={email} disabled={sent} onChange={(e) => setEmail(e.target.value)} />
      {sent && <input inputMode="numeric" required autoComplete="one-time-code" placeholder="验证码" aria-label="验证码" value={code} onChange={(e) => setCode(e.target.value)} />}
      <Button type="submit" variant="primary" disabled={busy}>{sent ? "绑定" : "发送验证码"}</Button>
      {sent && <Button variant="link" onClick={() => { setSent(false); setCode(""); }}>换个邮箱</Button>}
      <Message text={message?.text} ok={message?.ok} />
    </Form>
  );
}

/** What the other account holds, and the choice to merge it into this one. */
export function MergeCard({ token, offer: given, settled }: { token: string; offer?: MergeOffer; settled: (account: Account | null) => void }) {
  const [offer, setOffer] = useState(given);
  const { busy, message, run } = useAction();
  useEffect(() => { if (!offer) run(async () => setOffer(await api<MergeOffer>(`/api/me/merge/${enc(token)}`))); }, [offer, run, token]);
  const merge = () => run(async () => settled(await api<Account>(`/api/me/merge/${enc(token)}`, { method: "POST" })));
  const decline = () => run(async () => { await api(`/api/me/merge/${enc(token)}`, { method: "DELETE" }); settled(null); });
  const other = offer?.other;
  const name = other ? (other.email ?? `GitHub ${other.githubLogin}`) : "";
  return (
    <Card as="section" aria-label="合并账号" className={s.notice}>
      {other ? <>
        <b className={s.semi}>{name} 属于另一个账号</b>
        <Muted>{other.devices} 台设备 · {other.gameAccounts} 个游戏账号 · {other.plans} 个计划。合并后都归到当前账号，那个账号随之删除；同一台设备、同名的游戏账号只留当前账号的一份。</Muted>
        <div className={s.actions}>
          <Button variant="primary" disabled={busy} onClick={merge}>合并到当前账号</Button>
          <Button disabled={busy} onClick={decline}>不合并</Button>
        </div>
      </> : !message && <Muted>正在读取另一个账号…</Muted>}
      <Message text={message?.text} ok={message?.ok} />
      {!other && message && <div className={s.actions}><Button onClick={() => settled(null)}>关闭</Button></div>}
    </Card>
  );
}

/** Deletes the account for good: the word 注销, and the emailed code when it has an email. */
export function DeleteAccount({ account, deleted }: { account: Account; deleted: () => void }) {
  const [open, setOpen] = useState(false);
  const [word, setWord] = useState("");
  const [code, setCode] = useState("");
  const { busy, message, run } = useAction();
  if (!open) return <Button variant="link" className={s.dangerLink} onClick={() => setOpen(true)}>注销账号</Button>;
  const send = () => run(async () => { await api("/api/me/delete/request", { method: "POST" }); return `验证码已发到 ${account.email}`; });
  const remove = () => run(async () => { await api("/api/me/delete", { method: "POST", body: { confirm: word, code: code || null } }); deleted(); });
  return (
    <Card as="section" aria-label="注销账号" className={s.notice}>
      <b className={s.semi}>注销账号</b>
      <Muted>账号和其中的设备、游戏账号、计划、作业记录、通知都会删除，不能恢复。已登录的 App 会退出。</Muted>
      <Form inline onSubmit={remove} className={s.inlineFlow}>
        {account.email && <>
          <input inputMode="numeric" required autoComplete="one-time-code" placeholder="邮箱验证码" aria-label="邮箱验证码" value={code} onChange={(e) => setCode(e.target.value)} />
          <Button disabled={busy} onClick={send}>发送验证码</Button>
        </>}
        <input required placeholder="输入“注销”" aria-label="输入注销确认" value={word} onChange={(e) => setWord(e.target.value)} />
        <Button type="submit" variant="danger" disabled={busy || word.trim() !== "注销"}>注销</Button>
        <Button variant="link" onClick={() => setOpen(false)}>取消</Button>
        <Message text={message?.text} ok={message?.ok} />
      </Form>
    </Card>
  );
}
