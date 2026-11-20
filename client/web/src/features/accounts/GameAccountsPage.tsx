import { useState } from "react";
import { api, enc } from "../../api/client";
import type { Game, GameAccount } from "../../api/types";
import { Button, Card, Empty, Field, Form, Message, Muted, PageHeader, Segments } from "../../components/ui";
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
      <PageHeader title="游戏账号" subtitle="游戏账号属于你的护肝宝账号，任何一台设备都能用。任务设置、计划和运行记录都跟着账号走。"
        aside={<Button aria-expanded={adding} onClick={() => setAdding(!adding)}>添加游戏账号</Button>} />
      {adding && <AddAccount games={data.games} gameId={gameId} />}
      {data.games.length === 0 ? <Card><Empty>设备上线并上传任务包后，就能为它的游戏添加账号。</Empty></Card> : (
        <>
          <Segments label="游戏" value={gameId} onChange={setChosen}
            items={data.games.map((game) => [game.id, `${game.name} · ${data.accounts.filter((item) => item.gameId === game.id).length} 个`])} />
          <Card flush>
            {accounts.length === 0 ? <Empty>这个游戏还没有账号。</Empty> : (
              <div className={s.tableBox}>
                <table className={s.table}>
                  <thead><tr><th>名称</th><th>编号 · 游戏昵称</th><th>现在</th><th>最近使用</th><th><span className="visually-hidden">操作</span></th></tr></thead>
                  <tbody>{accounts.map((account) => <AccountRow key={account.id} account={account} />)}</tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
      <Card as="section" aria-labelledby="rules" className={s.rules}>
        <h2 id="rules" className={s.rulesTitle}>运行时怎么选账号</h2>
        <ul>
          <li><b>内容跟着账号走</b>：点进账号管理它的任务设置、计划和运行记录，与在哪台设备、哪个应用运行无关。</li>
          <li><b>计划由服务端计时</b>：到点先找登录着这个号的应用，推进它的队列；没有就找空闲、没设默认账号、能切号的应用切号运行；都没有就等，超过最晚开始时间就不执行。</li>
          <li><b>默认账号按应用设</b>：设了默认账号的应用不会被计划拿去切号；在它上面切号运行完会切回默认账号。</li>
          <li>同一个账号同时只能在一个应用上运行，跨设备也一样。</li>
        </ul>
      </Card>
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
  if (editing) return <tr><td colSpan={5}><EditAccount account={account} done={() => setEditing(false)} /></td></tr>;
  return (
    <tr>
      <td><Link to={paths.account(account.id)} className={s.name}>{account.name}</Link><br /><Muted as="span">设置 · 计划 · 运行记录</Muted></td>
      <td className={s.mutedCell}>{[account.uid ? `编号 ${account.uid}` : "未填编号", account.nickname].filter(Boolean).join(" · ")}</td>
      <td className={tone === "info" ? s.runCell : undefined}>{now}</td>
      <td className={s.mutedCell}>{last ? `${shortTime(last.updatedAt)}${last.deviceName ? ` · ${last.deviceName}` : ""}` : "从未使用"}</td>
      <td className={s.ops}><Button small onClick={() => setEditing(true)}>重命名</Button> <Button small variant="danger" onClick={remove}>移除</Button></td>
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
      <b className={s.semi}>在设备上添加</b>
      <ol>
        <li>在手机上打开游戏，手动登录要添加的账号。</li>
        <li>打开护肝宝，它会读出游戏里的编号和昵称。</li>
        <li>在设备页点“保存为游戏账号”。护肝宝只记下账号是谁，不会问你游戏密码。</li>
      </ol>
      {games.length > 0 && (
        <>
          <b className={s.semi}>或者手动添加</b>
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
