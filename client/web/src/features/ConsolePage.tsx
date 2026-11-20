import { useState, type ReactNode } from "react";
import type { Account } from "../api/types";
import { ButtonLink, Card, Empty, Message, ui } from "../components/ui";
import { match, paths, usePath } from "../router";
import { Shell, type Section } from "../shell/Shell";
import { useConsoleStream } from "../state/console";
import { ConsoleContext } from "../state/context";
import { AccountDetailPage } from "./accounts/AccountDetailPage";
import { GameAccountsPage } from "./accounts/GameAccountsPage";
import { AppPage } from "./devices/AppPage";
import { DevicePage } from "./devices/DevicePage";
import { DevicesPage } from "./devices/DevicesPage";
import { JobPage } from "./jobs/JobPage";
import { LibraryPage } from "./library/LibraryPage";
import { MePage } from "./me/MePage";
import { NoticesPage } from "./notices/NoticesPage";

/** The signed-in console: one page per address, all kept live by the event stream. */
export function ConsolePage({ account, signedOut }: { account: Account; signedOut: () => void }) {
  const { data, connection, loaded } = useConsoleStream(signedOut);
  const path = usePath();
  const [section, page] = route(path);
  // Binding GitHub from 我的 comes back here; a failure says so once, and is dropped from the address.
  const [linkFailed] = useState(() => {
    const failed = new URLSearchParams(location.search).get("error") === "github";
    if (failed) history.replaceState(null, "", location.pathname);
    return failed;
  });
  return (
    <ConsoleContext.Provider value={{ data, account, signedOut }}>
      <Shell section={section} connection={connection}>
        {linkFailed && <Message text="绑定 GitHub 没有完成，请在“我的”里重试" />}
        {loaded ? page : <Empty>正在加载…</Empty>}
      </Shell>
    </ConsoleContext.Provider>
  );
}

function route(path: string): [Section, ReactNode] {
  let p: Record<string, string> | null;
  if (path === "/" || path === paths.devices) return ["devices", <DevicesPage />];
  if ((p = match("/devices/:id", path))) return ["devices", <DevicePage key={p.id} id={p.id} />];
  if ((p = match("/devices/:id/apps/:key", path))) return ["devices", <AppPage key={p.id + p.key} deviceId={p.id} appKey={p.key} />];
  if (path === paths.accounts) return ["accounts", <GameAccountsPage />];
  if ((p = match("/accounts/:id", path))) return ["accounts", <AccountDetailPage key={p.id} id={p.id} tab="tasks" />];
  if ((p = match("/accounts/:id/plans", path))) return ["accounts", <AccountDetailPage key={p.id} id={p.id} tab="plans" />];
  if ((p = match("/accounts/:id/runs", path))) return ["accounts", <AccountDetailPage key={p.id} id={p.id} tab="runs" />];
  if ((p = match("/jobs/:id", path))) return ["devices", <JobPage key={p.id} id={p.id} />];
  if (path === paths.library) return ["library", <LibraryPage />];
  if (path === paths.notices) return ["notices", <NoticesPage />];
  if (path === paths.me) return ["me", <MePage />];
  return ["devices", <NotFound text="没有这个页面" />];
}

export function NotFound({ text, back = paths.devices, backText = "回到设备" }: { text: string; back?: string; backText?: string }) {
  return <Card><Empty>{text}</Empty><div className={ui.center}><ButtonLink to={back}>{backText}</ButtonLink></div></Card>;
}
