import { Fragment, useState, type ReactNode } from "react";
import { Mark } from "../components/ui";
import { Link, paths } from "../router";
import type { Connection } from "../state/console";
import s from "./Shell.module.css";

export type Section = "devices" | "accounts" | "library" | "notices" | "me" | "dev";

const icon = (d: ReactNode) => <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">{d}</svg>;
/** [section, label, short label for the rail and the bottom bar, path, icon]; a group heading goes before "dev". */
const LINKS: [Section, string, string, string, ReactNode][] = [
  ["devices", "设备", "设备", paths.devices, icon(<><rect x="7" y="2" width="10" height="20" rx="2" /><path d="M11 18h2" /></>)],
  ["accounts", "游戏账号", "游戏账号", paths.accounts, icon(<><rect x="3" y="6" width="18" height="12" rx="3" /><path d="M8 10v4M6 12h4M15 11h.01M17 13h.01" /></>)],
  ["library", "任务库", "任务库", paths.library, icon(<path d="M4 5h6v14H4zM14 5l5 1.5-3.5 13L11 18" />)],
  ["notices", "通知", "通知", paths.notices, icon(<><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" /><path d="M10 21h4" /></>)],
  ["me", "我的", "我的", paths.me, icon(<><circle cx="12" cy="8" r="4" /><path d="M5 20a7 7 0 0 1 14 0" /></>)],
  // 写任务: anyone signed in may write tasks for themselves (open-contribution.md).
  ["dev", "开发工作台", "开发", paths.dev, icon(<path d="M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16" />)],
];
const COLLAPSED = "hgb.nav.collapsed";
const LIVE: Record<Connection, string> = { live: "实时连接中", connecting: "正在连接…", lost: "连接断开，正在重连…" };
const LIVE_SHORT: Record<Connection, string> = { live: "已连接", connecting: "连接中", lost: "已断开" };

/**
 * The frame every console page sits in, in the three widths of the design: from 1024 a full sidebar, from 600 a rail of
 * icons with short labels, and below that a top bar with a navigation bar fixed at the bottom.
 */
export function Shell({ section, connection, children }: { section: Section; connection: Connection; children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem(COLLAPSED) === "1"; } catch { return false; } });
  const toggle = () => { const next = !collapsed; setCollapsed(next); try { localStorage.setItem(COLLAPSED, next ? "1" : "0"); } catch { /* remembered for this page only */ } };
  return (
    <div className={s.shell} data-collapsed={collapsed} data-wide={section === "dev"}>
      <header className={s.top}>
        <Link to={paths.devices} className={s.brand}><Mark size={28} /><span>护肝宝</span></Link>
        <span className={s.liveShort} role="status"><span className={s.liveDot} data-state={connection} aria-hidden="true" />{LIVE_SHORT[connection]}</span>
      </header>
      <nav className={s.nav} aria-label="主导航">
        <Link to={paths.devices} className={s.navBrand}><Mark /><span className={s.brandName}>护肝宝</span></Link>
        <div className={s.links}>
          {LINKS.map(([id, label, short, to, svg]) => (
            <Fragment key={id}>
              {id === "dev" && <div className={s.group}>开发者</div>}
              <Link to={to} className={s.link} aria-current={id === section ? "page" : undefined}>
                <span className={s.linkIcon}>{svg}</span><span className={s.linkLabel}>{label}</span><span className={s.linkShort}>{short}</span>
              </Link>
            </Fragment>
          ))}
        </div>
        <button type="button" className={s.collapse} aria-expanded={!collapsed} aria-label={collapsed ? "展开侧栏" : "收起侧栏"} title={collapsed ? "展开侧栏" : "收起侧栏"} onClick={toggle}>
          <span className={s.linkIcon}>{icon(<path d={collapsed ? "M9 6l6 6-6 6" : "M15 6l-6 6 6 6"} />)}</span><span className={s.linkLabel}>收起侧栏</span>
        </button>
        <div className={s.live} role="status" title={LIVE[connection]}>
          <span className={s.liveDot} data-state={connection} aria-hidden="true" /><span className={s.liveText}>{LIVE[connection]}</span>
        </div>
      </nav>
      <main className={s.main}>{children}</main>
    </div>
  );
}
