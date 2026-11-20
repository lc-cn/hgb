import { useState } from "react";
import { api, enc } from "../../api/client";
import type { Game, GameAccount } from "../../api/types";
import { Button, Card, Empty, Field, Form, Message, MoreMenu, Muted, PageHeader, Segments } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { shortTime, type Tone } from "../../format";
import { Link, paths } from "../../router";
import type { ConsoleData } from "../../state/console";
import { useConsole } from "../../state/context";
import s from "./Accounts.module.css";

/** Where the account is now, from the jobs and the apps signed in to it. */
export function accountNow(account: GameAccount, data: ConsoleData): [string, Tone] {
  const running = data.jobs.find((job) => job.accountId === account.id && job.state === "RUNNING");
  if (running) return [`正在 ${running.deviceName ?? "设备"} 上运行`, "info"];
  const signedIn = data.devices.flatMap((device) => device.apps.filter((app) => app.accountId === account.id).map((app) => device.apps.length > 1 ? `${device.name} · ${app.name}` : device.name));
  if (signedIn.length) return [`登录在 ${signedIn.join("、")}`, "ok"];
  return ["空闲", ""];
}

/** 游戏账号: the accounts of one game at a time, with adding, renaming and removing. */
export function GameAccountsPage() {
  const { data } = useConsole();
  const [adding, setAdding] = useState(false);
  const [chosen, setChosen] = useState(data.games[0]?.id ?? "");
  const gameId = data.games.some((game) => game.id === chosen) ? chosen : data.games[0]?.id ?? "";
  const accounts = data.accounts.filter((account) => account.gameId === gameId);
  return (
    <>
      <PageHeader title="游戏账号" aside={<Button aria-expanded={adding} onClick={() => setAdding(!adding)}>{adding ? "收起" : "添加"}</Button>} />
      {adding && <AddAccount games={data.games} gameId={gameId} />}
      {data.games.length === 0 ? <Card><Empty>设备上线并上传任务包后，就能为它的游戏添加账号。</Empty></Card> : (
        <>
          {data.games.length > 1 && <Segments label="游戏" value={gameId} onChange={setChosen}
            items={data.games.map((game) => [game.id, `${game.name} · ${data.accounts.filter((item) => item.gameId === game.id).length} 个`])} />}
          <Card flush>
            {accounts.length === 0 ? <Empty>这个游戏还没有账号。</Empty> : (
              <div className={s.tableBox}>
                <table className={s.table}>
                  <thead><tr><th>名称</th><th>现在</th><th>最近使用</th><th><span className="visually-hidden">操作</span></th></tr></thead>
                  <tbody>{accounts.map((account) => <AccountRow key={account.id} account={account} />)}</tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}

function AccountRow({ account }: { account: GameAccount }) {
  const { data } = useConsole();
  const [editing, setEditing] = useState(false);
  const [now, tone] = accountNow(account, data);
  const last = data.jobs.find((job) => job.accountId === account.id);
  const remove = () => {
    if (!confirm(`移除“${account.name}”？它的任务设置和计划也会删除，过去的运行记录保留。`)) return;
    orAlert(() => api(`/api/accounts/${enc(account.id)}`, { method: "DELETE" }));
  };
  if (editing) return <tr><td colSpan={4}><EditAccount account={account} done={() => setEditing(false)} /></td></tr>;
  return (
    <tr>
      <td><Link to={paths.account(account.id)} className={s.name}>{account.name}</Link><br /><Muted as="span">{[account.uid ? `编号 ${account.uid}` : null, account.nickname].filter(Boolean).join(" · ") || "未填编号"}</Muted></td>
      <td className={tone === "info" ? s.runCell : undefined}>{now}</td>
      <td className={s.mutedCell}>{last ? `${shortTime(last.updatedAt)}${last.deviceName ? ` · ${last.deviceName}` : ""}` : "从未使用"}</td>
      <td className={s.ops}><MoreMenu label={`${account.name} 的操作`} actions={[["重命名", () => setEditing(true)], ["移除", remove, true]]} /></td>
    </tr>
  );
}

function EditAccount({ account, done }: { account: GameAccount; done: () => void }) {
  const [name, setName] = useState(account.name);
  const [uid, setUid] = useState(account.uid ?? "");
  const [nickname, setNickname] = useState(account.nickname ?? "");
  const { busy, message, run } = useAction();
  return (
    <>
      <Form inline onSubmit={() => run(async () => { await api(`/api/accounts/${enc(account.id)}`, { method: "PUT", body: { name, uid: uid || null, nickname: nickname || null } }); done(); })}>
        <Field label="名称"><input type="text" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="编号"><input type="text" maxLength={40} value={uid} onChange={(e) => setUid(e.target.value)} /></Field>
        <Field label="游戏昵称"><input type="text" maxLength={40} value={nickname} onChange={(e) => setNickname(e.target.value)} /></Field>
        <div className={s.formButtons}><Button variant="primary" type="submit" disabled={busy}>保存</Button><Button onClick={done}>取消</Button></div>
      </Form>
      <Message text={message?.text} ok={message?.ok} />
    </>
  );
}

function AddAccount({ games, gameId }: { games: Game[]; gameId: string }) {
  const [game, setGame] = useState(gameId);
  const [name, setName] = useState(""); const [uid, setUid] = useState(""); const [nickname, setNickname] = useState("");
  const { busy, message, run } = useAction();
  const target = games.some((item) => item.id === game) ? game : games[0]?.id ?? "";
  return (
    <Card as="section" aria-label="如何添加" className={s.how}>
      <Muted>也可以在设备页把游戏里读到的账号直接保存下来。护肝宝不会问你游戏密码。</Muted>
      {games.length > 0 && (
        <>
          <Form inline onSubmit={() => run(async () => {
            await api<GameAccount>("/api/accounts", { method: "POST", body: { gameId: target, name, uid: uid || null, nickname: nickname || null } });
            setName(""); setUid(""); setNickname(""); return "已添加";
          })}>
            {games.length > 1 && <Field label="游戏"><select value={target} onChange={(e) => setGame(e.target.value)}>{games.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></Field>}
            <Field label="名称"><input type="text" placeholder="如主号" required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} /></Field>
            <Field label="编号"><input type="text" placeholder="可不填" maxLength={40} value={uid} onChange={(e) => setUid(e.target.value)} /></Field>
            <Field label="游戏昵称"><input type="text" placeholder="可不填" maxLength={40} value={nickname} onChange={(e) => setNickname(e.target.value)} /></Field>
            <div className={s.formButtons}><Button variant="primary" type="submit" disabled={busy}>添加账号</Button></div>
          </Form>
          <Message text={message?.text} ok={message?.ok} />
        </>
      )}
    </Card>
  );
}
