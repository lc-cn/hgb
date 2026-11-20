import { useState } from "react";
import { Card, Empty, Muted, PageHeader, Row, Segments } from "../../components/ui";
import { dateTime, platformName } from "../../format";
import { useConsole } from "../../state/context";
import s from "./Library.module.css";

/** 任务库: each game's tasks as the server knows them, and which devices carry the game. */
export function LibraryPage() {
  const { data } = useConsole();
  const [chosen, setChosen] = useState(data.games[0]?.id ?? "");
  const game = data.games.find((item) => item.id === chosen) ?? data.games[0];
  const devices = game ? data.devices.filter((device) => device.apps.some((app) => app.gameId === game.id) || device.tasks.some((task) => task.targetId === game.id)) : [];
  return (
    <>
      <PageHeader title="任务库" subtitle="每个游戏有自己的任务。任务列表和设置项来自设备最近上传的任务包。" />
      {!game ? <Card><Empty>还没有任务。设备上线并上传任务包后会出现在这里。</Empty></Card> : (
        <>
          <Segments label="游戏" value={game.id} onChange={setChosen} items={data.games.map((item) => [item.id, item.name])} />
          <section aria-labelledby="tasks" className={s.section}>
            <div className={s.head}><h2 id="tasks">任务</h2><Muted as="span">{game.tasks.length} 个</Muted></div>
            <Card flush>
              {game.tasks.length ? game.tasks.map((task) => (
                <Row key={task.taskId}><span className={s.name}>{task.name}</span><Muted as="span">{task.taskId}</Muted></Row>
              )) : <Empty>这个游戏还没有任务</Empty>}
            </Card>
          </section>
          <section aria-labelledby="sync" className={s.section}>
            <h2 id="sync">装了{game.name}的设备</h2>
            <Card flush>
              {devices.length ? devices.map((device) => {
                const count = device.tasks.filter((task) => task.targetId === game.id).length;
                return (
                  <Row key={device.id}>
                    <span className={s.name}>{device.name}<Muted as="span" className={s.sub}>{platformName(device.platform)}</Muted></span>
                    <span className={device.online ? s.ok : s.off}>{device.online ? `在线 · 上报了 ${count} 个任务` : `离线 · 最后在线 ${dateTime(device.lastSeenAt)}`}</span>
                  </Row>
                );
              }) : <Empty>还没有设备装了这个游戏</Empty>}
            </Card>
          </section>
        </>
      )}
    </>
  );
}
