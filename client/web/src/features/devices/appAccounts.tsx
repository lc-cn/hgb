import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { App, Device, GameAccount } from "../../api/types";
import { Button, Popover, ui } from "../../components/ui";

const GEAR = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);
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

/**
 * The app's account beside its name: the account signed in now in the accent colour, then a gear. It opens a list of
 * the game's accounts like a select's options, the one signed in now selected; each row ends with 设为默认 or 取消默认.
 * Clicking a row says that account is signed in, unless the app reads it from the game itself.
 */
export function AccountMenu({ app, accounts, current, fallback, setCurrent, setDefault }: {
  app: App; accounts: GameAccount[]; current: string | null; fallback: string | null; setCurrent: (id: string | null) => void; setDefault: (id: string | null) => void;
}) {
  const reads = !!app.observed;
  const name = accounts.find((item) => item.id === current)?.name ?? app.observed?.nickname ?? "未指定";
  return (
    <Popover label={`${app.name} 的账号设置`} plain trigger={<span className={s.accountTrigger}><span className={s.accountName}>{name}</span>{GEAR}</span>}>
      {(close) => (
        <div className={s.accountPanel} role="listbox" aria-label={`${app.name} 当前登录的账号`}>
          {accounts.length === 0 && <span className={ui.menuLabel}>先在“游戏账号”里添加这个游戏的账号</span>}
          {accounts.map((account) => {
            const selected = account.id === current;
            const isDefault = account.id === fallback;
            return (
              <div key={account.id} className={selected ? `${s.accountItem} ${s.accountSelected}` : s.accountItem}>
                <button type="button" role="option" aria-selected={selected} className={s.accountChoice} disabled={reads}
                  title={reads ? "当前登录从游戏里读出" : undefined} onClick={() => { setCurrent(account.id); close(); }}>{account.name}</button>
                <button type="button" className={isDefault ? s.unsetDefault : s.setDefault} onClick={() => { setDefault(isDefault ? null : account.id); close(); }}>
                  {isDefault ? "取消默认" : "设为默认"}
                </button>
              </div>
            );
          })}
        </div>
      )}
    </Popover>
  );
}

/** What the game showed on screen, with a way to keep it as a game account when it is not one yet. */
export function Observed({ app, accounts }: { app: App; accounts: GameAccount[] }) {
  const seen = app.observed;
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState(seen?.nickname ?? "");
  const { busy, message, run } = useAction();
  const saved = !!seen && accounts.some((account) => account.uid === seen.uid);
  if (!seen || saved) return null;
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
