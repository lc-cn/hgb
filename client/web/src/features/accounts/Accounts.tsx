import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { Device, Game, GameAccount, Plan } from "../../api/types";
import { Button, Card, Field, Form, Grow, Message, Muted, Row } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { PlanList } from "./Plans";
import { SettingsForm } from "./Settings";
import s from "./Accounts.module.css";

/** Game accounts, grouped by game, and for the chosen one its settings and plans. */
export function AccountsPanel({ accounts, games, plans, devices }: { accounts: GameAccount[]; games: Game[]; plans: Plan[]; devices: Device[] }) {
  const [selected, setSelected] = useState<string | null>(null);
  // Derived on each render: an account just created shows once the stream brings it, one deleted elsewhere disappears.
  const account = accounts.find((item) => item.id === selected);
  return (
    <Card>
      <div className={s.layout}>
        <div className={s.list}>
          {games.map((game) => {
            const mine = accounts.filter((item) => item.gameId === game.id);
            if (mine.length === 0) return null;
            return (
              <div key={game.id} className={s.group}>
                <Muted>{game.name}</Muted>
                {mine.map((item) => (
                  <button key={item.id} type="button" className={item.id === selected ? `${s.account} ${s.on}` : s.account}
                    aria-pressed={item.id === selected} onClick={() => setSelected(item.id)}>
                    <span>{item.name}</span>{item.uid && <span className={s.uid}>UID {item.uid}</span>}
                  </button>
                ))}
              </div>
            );
          })}
          {accounts.length === 0 && <Muted>还没有游戏账号。任务设置、计划和运行记录都归属于账号。</Muted>}
        </div>
        {account && <AccountDetail key={account.id} account={account} game={games.find((game) => game.id === account.gameId)} plans={plans} devices={devices}
          deleted={() => setSelected(null)} />}
      </div>
      <h4 className={s.heading}>添加账号</h4>
      <AddAccount games={games} added={setSelected} />
    </Card>
  );
}

function AddAccount({ games, added }: { games: Game[]; added: (id: string) => void }) {
  const [gameId, setGameId] = useState(games[0]?.id ?? "");
  const [name, setName] = useState(""); const [uid, setUid] = useState("");
  const { busy, message, run } = useAction();
  if (games.length === 0) return <Muted>设备上线并上传任务包后，就能为它的游戏添加账号。</Muted>;
  const game = games.some((item) => item.id === gameId) ? gameId : games[0].id;
  return (
    <>
      <Form inline className={s.addForm} onSubmit={() => run(async () => {
        const created = await api<GameAccount>("/api/accounts", { method: "POST", body: { gameId: game, name, uid: uid || null } });
        setName(""); setUid(""); added(created.id);
      })}>
        <Field label="游戏"><select aria-label="游戏" value={game} onChange={(e) => setGameId(e.target.value)}>
          {games.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></Field>
        <Field label="名称"><input type="text" placeholder="账号名称，如主号" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="UID"><input type="text" placeholder="UID（可不填）" maxLength={40} value={uid} onChange={(e) => setUid(e.target.value)} /></Field>
        <Button type="submit" disabled={busy}>添加账号</Button>
      </Form>
      <Message text={message?.text} ok={message?.ok} />
    </>
  );
}

function AccountDetail({ account, game, plans, devices, deleted }: { account: GameAccount; game?: Game; plans: Plan[]; devices: Device[]; deleted: () => void }) {
  const [name, setName] = useState(account.name);
  const [uid, setUid] = useState(account.uid ?? "");
  useEffect(() => { setName(account.name); setUid(account.uid ?? ""); }, [account.name, account.uid]);
  const remove = () => {
    if (!confirm(`删除“${account.name}”？它的任务设置和计划也会删除，过去的作业记录保留。`)) return;
    orAlert(async () => { await api(`/api/accounts/${enc(account.id)}`, { method: "DELETE" }); deleted(); });
  };
  return (
    <div className={s.detail}>
      <Row><Grow><h3>{`${game?.name ?? account.gameId} · ${account.name}`}</h3></Grow><Button variant="danger" onClick={remove}>删除账号</Button></Row>
      <Form inline className={s.addForm} onSubmit={() => orAlert(() => api(`/api/accounts/${enc(account.id)}`, { method: "PUT", body: { name, uid: uid || null } }))}>
        <Field label="名称"><input type="text" aria-label="账号名称" maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="UID"><input type="text" aria-label="UID" maxLength={40} value={uid} onChange={(e) => setUid(e.target.value)} /></Field>
        <Button type="submit">保存</Button>
      </Form>
      <h4 className={s.heading}>任务设置</h4>
      <SettingsForm account={account} game={game} />
      <h4 className={s.heading}>计划</h4>
      <PlanList account={account} game={game} plans={plans} devices={devices} />
    </div>
  );
}
