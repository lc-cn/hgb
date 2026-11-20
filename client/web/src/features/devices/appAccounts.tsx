import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { App, Device, GameAccount } from "../../api/types";
import { Button } from "../../components/ui";
import { useAction } from "../../components/useAction";
import s from "./Devices.module.css";

/**
 * Which accounts an app uses: the one signed in now and the default it returns to. Both are kept here and sent
 * together, so two quick changes cannot undo each other; a refused change shows the server's values again.
 */
export function useAppAccounts(device: Device, app: App) {
  const [current, setCurrent] = useState(app.accountId);
  const [fallback, setFallback] = useState(app.defaultAccountId);
  useEffect(() => { setCurrent(app.accountId); setFallback(app.defaultAccountId); }, [app.accountId, app.defaultAccountId]);
  const save = (accountId: string | null, defaultAccountId: string | null) => {
    setCurrent(accountId); setFallback(defaultAccountId);
    api(`/api/devices/${enc(device.id)}/apps/${enc(app.key)}`, { method: "PUT", body: { accountId, defaultAccountId } }).catch((error: Error) => {
      setCurrent(app.accountId); setFallback(app.defaultAccountId); alert(error.message);
    });
  };
  return {
    current, fallback,
    setCurrent: (id: string | null) => save(id, fallback),
    setDefault: (id: string | null) => save(current, id),
  };
}

export function AccountSelect({ label, value, accounts, onChange, none }: { label: string; value: string | null; accounts: GameAccount[]; onChange: (id: string | null) => void; none: string }) {
  return (
    <select aria-label={label} value={value ?? ""} onChange={(e) => onChange(e.target.value || null)}>
      <option value="">{none}</option>
      {accounts.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}
    </select>
  );
}

/**
 * The account signed in to the app. Once the app reads it on screen the server keeps it in step and it is shown
 * read-only; for games it cannot read, the user picks it.
 */
export function CurrentAccount({ app, accounts, current, setCurrent }: { app: App; accounts: GameAccount[]; current: string | null; setCurrent: (id: string | null) => void }) {
  if (app.observed) return <b className={s.ink}>{accounts.find((item) => item.id === app.accountId)?.name ?? "未保存的账号"}</b>;
  if (accounts.length === 0) return <span>未指定</span>;
  return <AccountSelect label={`${app.name} 当前登录的账号`} value={current} accounts={accounts} onChange={setCurrent} none="未指定" />;
}

export function defaultHint(accounts: GameAccount[], defaultId: string | null): string {
  const name = accounts.find((item) => item.id === defaultId)?.name;
  return name ? `默认${name}：计划不会让这个应用切到别的账号；在这里切号运行完会切回${name}。`
    : "没设默认账号：空闲时服务端可以把别的账号的计划切号交给这个应用，运行完不切回。";
}

/** What the game showed on screen, with a way to keep it as a game account when it is not one yet. */
export function Observed({ app, accounts }: { app: App; accounts: GameAccount[] }) {
  const seen = app.observed;
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState(seen?.nickname ?? "");
  const { busy, message, run } = useAction();
  if (!seen) return null;
  const saved = accounts.some((account) => account.uid === seen.uid);
  return (
    <div className={s.observed}>
      <span>游戏里：{seen.nickname}（编号 {seen.uid}）{app.switchable ? " · 可切号" : ""}</span>
      {!saved && !naming && <Button small onClick={() => setNaming(true)}>保存为游戏账号</Button>}
      {!saved && naming && (
        <form className={s.nameForm} onSubmit={(event) => { event.preventDefault(); run(async () => {
          await api("/api/accounts", { method: "POST", body: { gameId: app.gameId, name, uid: seen.uid, nickname: seen.nickname } }); setNaming(false);
        }); }}>
          <input aria-label="账号名称" placeholder="名称，如主号" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
          <Button small variant="primary" type="submit" disabled={busy}>保存</Button>
          <Button small onClick={() => setNaming(false)}>取消</Button>
        </form>
      )}
      {message && <span role="status" className={s.bad}>{message.text}</span>}
    </div>
  );
}
