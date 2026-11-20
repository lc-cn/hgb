import type { ReactNode } from "react";
import { Mark } from "../components/ui";
import { Link, paths } from "../router";
import type { Connection } from "../state/console";
import s from "./Shell.module.css";

export type Section = "devices" | "accounts" | "library" | "notices" | "me";

const icon = (d: ReactNode) => <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">{d}</svg>;
const LINKS: [Section, string, string, ReactNode][] = [
  ["devices", "设备", paths.devices, icon(<><rect x="7" y="2" width="10" height="20" rx="2" /><path d="M11 18h2" /></>)],
  ["accounts", "游戏账号", paths.accounts, icon(<><rect x="3" y="6" width="18" height="12" rx="3" /><path d="M8 10v4M6 12h4M15 11h.01M17 13h.01" /></>)],
  ["library", "任务库", paths.library, icon(<path d="M4 5h6v14H4zM14 5l5 1.5-3.5 13L11 18" />)],
  ["notices", "通知", paths.notices, icon(<><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" /><path d="M10 21h4" /></>)],
  ["me", "我的", paths.me, icon(<><circle cx="12" cy="8" r="4" /><path d="M5 20a7 7 0 0 1 14 0" /></>)],
];
const LIVE: Record<Connection, string> = { live: "实时连接中", connecting: "正在连接…", lost: "连接断开，正在重连…" };
const LIVE_SHORT: Record<Connection, string> = { live: "已连接", connecting: "连接中", lost: "已断开" };

/**
 * The frame every console page sits in, in the three widths of the design: from 1024 a full sidebar, from 600 a rail of
 * icons with short labels, and below that a top bar with a navigation bar fixed at the bottom.
 */
export function Shell({ section, connection, children }: { section: Section; connection: Connection; children: ReactNode }) {
  return (
    <div className={s.shell}>
      <header className={s.top}>
        <Link to={paths.devices} className={s.brand}><Mark size={28} /><span>护肝宝</span></Link>
        <span className={s.liveShort} role="status"><span className={s.liveDot} data-state={connection} aria-hidden="true" />{LIVE_SHORT[connection]}</span>
      </header>
      <nav className={s.nav} aria-label="主导航">
        <Link to={paths.devices} className={s.navBrand}><Mark /><span className={s.brandName}>护肝宝</span></Link>
        <div className={s.links}>
          {LINKS.map(([id, label, to, svg]) => (
            <Link key={id} to={to} className={s.link} aria-current={id === section ? "page" : undefined}>
              <span className={s.linkIcon}>{svg}</span><span className={s.linkLabel}>{label}</span>
            </Link>
          ))}
        </div>
        <div className={s.live} role="status" title={LIVE[connection]}>
          <span className={s.liveDot} data-state={connection} aria-hidden="true" /><span className={s.liveText}>{LIVE[connection]}</span>
        </div>
      </nav>
      <main className={s.main}>{children}</main>
    </div>
  );
}
