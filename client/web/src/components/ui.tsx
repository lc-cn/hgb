import { useEffect, useRef, useState, type ButtonHTMLAttributes, type FormEvent, type HTMLAttributes, type ReactNode } from "react";
import type { Tone } from "../format";
import { Link } from "../router";
import s from "./ui.module.css";

/** The building blocks every page is made of, after the approved design; their look comes only from the theme tokens. */

export const join = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(" ");

export function Card({ flush, className, as: Tag = "div", ...rest }: HTMLAttributes<HTMLElement> & { flush?: boolean; as?: "div" | "section" | "article" }) {
  return <Tag className={join(s.card, flush && s.flush, className)} {...rest} />;
}
/** A card's heading row, as in "排在这个应用后面". */
export function CardTitle({ id, children }: { id?: string; children: ReactNode }) { return <h2 id={id} className={s.cardTitle}>{children}</h2>; }

type Variant = "primary" | "ghost" | "danger" | "link";
export function Button({ variant = "ghost", small, className, type = "button", ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; small?: boolean }) {
  return <button type={type} className={join(variant === "link" ? s.link : s.button, variant !== "link" && s[variant], small && s.small, className)} {...rest} />;
}
export function ButtonLink({ to, primary, children }: { to: string; primary?: boolean; children: ReactNode }) {
  return <Link to={to} className={join(s.button, s.large, primary ? s.primary : s.ghost)}>{children}</Link>;
}

/** A status pill. [dot] adds the leading dot the design uses for live states. */
export function Pill({ tone = "", dot, className, children }: { tone?: Tone; dot?: boolean; className?: string; children: ReactNode }) {
  return <span className={join(s.pill, s[`pill_${tone || "neutral"}`], className)}>{dot && <span className={s.dot} aria-hidden="true" />}{children}</span>;
}
/** Result text in a list, coloured by tone. */
export function Result({ tone = "", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={join(s.result, s[`text_${tone || "neutral"}`])}>{children}</span>;
}

export function Field({ label, children, inline, className }: { label: string; children: ReactNode; inline?: boolean; className?: string }) {
  return <label className={join(inline ? s.inline : s.field, className)}><span>{label}</span>{children}</label>;
}
export function Check({ label, checked, onChange, disabled }: { label: string; checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean }) {
  return <label className={s.check}><input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /><span>{label}</span></label>;
}
export function Choices({ children }: { children: ReactNode }) { return <div className={s.choices}>{children}</div>; }

/** The design's on/off switch. */
export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (checked: boolean) => void; label: string; disabled?: boolean }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className={join(s.switch, checked && s.on)}
    onClick={() => onChange(!checked)}><i /></button>;
}

/** A result line under a form; empty when there is nothing to say. */
export function Message({ text, ok }: { text?: string | null; ok?: boolean }) {
  if (!text) return null;
  return <p className={join(s.message, ok && s.okMessage)} role="status">{text}</p>;
}
export function Muted({ children, className, as: Tag = "p" }: { children: ReactNode; className?: string; as?: "p" | "span" | "div" }) {
  return <Tag className={join(s.muted, className)}>{children}</Tag>;
}
/** A list row inside a card. */
export function Row({ children, className, top }: { children: ReactNode; className?: string; top?: boolean }) {
  return <div className={join(s.row, top && s.top, className)}>{children}</div>;
}
export function RowLink({ to, children }: { to: string; children: ReactNode }) { return <Link to={to} className={join(s.row, s.rowLink)}>{children}</Link>; }
export function Grow({ children, className }: { children: ReactNode; className?: string }) { return <div className={join(s.grow, className)}>{children}</div>; }
export function Empty({ children }: { children: ReactNode }) { return <p className={s.empty}>{children}</p>; }

/** Underlined tabs inside a page. Each tab is a link so it has its own address. */
export function PageTabs({ label, tabs, value }: { label: string; tabs: [string, string, string][]; value: string }) {
  return (
    <div className={s.tabs} role="tablist" aria-label={label}>
      {tabs.map(([id, text, to]) => <Link key={id} to={to} role="tab" aria-selected={id === value} className={s.tab}>{text}</Link>)}
    </div>
  );
}
/** Tabs switched in place, for the sign-in card. */
export function Tabs<T extends string>({ tabs, value, onChange, label }: { tabs: [T, string][]; value: T; onChange: (value: T) => void; label?: string }) {
  return (
    <div className={s.tabs} role="tablist" aria-label={label}>
      {tabs.map(([id, text]) => <button key={id} type="button" role="tab" aria-selected={id === value} className={s.tab} onClick={() => onChange(id)}>{text}</button>)}
    </div>
  );
}
/** The filled buttons the design uses to pick a game or an app. */
export function Segments<T extends string>({ items, value, onChange, label, role = "tablist" }: { items: [T, string][]; value: T; onChange: (value: T) => void; label: string; role?: "tablist" | "radiogroup" }) {
  const item = role === "tablist" ? "tab" : "radio";
  return (
    <div className={s.segments} role={role} aria-label={label}>
      {items.map(([id, text]) => (
        <button key={id} type="button" role={item} {...(item === "tab" ? { "aria-selected": id === value } : { "aria-checked": id === value })}
          className={join(s.segment, id === value && s.segmentOn)} onClick={() => onChange(id)}>{text}</button>
      ))}
    </div>
  );
}

export function Breadcrumb({ items }: { items: [string, string | null][] }) {
  return (
    <nav aria-label="位置" className={s.crumbs}>
      {items.map(([text, to], i) => <span key={i}>{i > 0 && " / "}{to ? <Link to={to}>{text}</Link> : <span aria-current={i === items.length - 1 ? "page" : undefined}>{text}</span>}</span>)}
    </nav>
  );
}
/** A page's title row; [aside] sits at the end, [subtitle] under the title. */
export function PageHeader({ title, subtitle, aside }: { title: ReactNode; subtitle?: ReactNode; aside?: ReactNode }) {
  return (
    <header className={s.header}>
      <div><h1 className={s.title}>{title}</h1>{subtitle && <p className={s.subtitle}>{subtitle}</p>}</div>
      {aside && <div className={s.aside}>{aside}</div>}
    </header>
  );
}
/** A label-value list like 应用 / 上次结果. */
export function Facts({ items, className }: { items: [string, ReactNode][]; className?: string }) {
  return <dl className={join(s.facts, className)}>{items.map(([term, value]) => <div key={term} className={s.fact}><dt>{term}</dt><dd>{value}</dd></div>)}</dl>;
}

/** The logo, "夜班": a crescent and a star on the primary colour; it keeps watch while you sleep. */
export function Mark({ size = 32 }: { size?: number }) {
  return (
    <svg className={s.mark} width={size} height={size} viewBox="0 0 48 48" aria-hidden="true">
      <rect width="48" height="48" rx="12" fill="var(--color-primary)" />
      <path d="M27 9.5 A15 15 0 1 0 39 31 A12 12 0 1 1 27 9.5 Z" fill="var(--color-on-primary)" />
      <path d="M33.5 11 L34.8 14.3 L38 15.1 L34.8 16.1 L33.5 19.2 L32.2 16.1 L29 15.1 L32.2 14.3 Z" fill="var(--color-logo-star)" />
    </svg>
  );
}
export function Brand() {
  return <div className={s.brand}><Mark /><span>护肝宝</span></div>;
}

/** A form laid out in one column, or as a row of fields with the button at the end. */
export function Form({ inline, onSubmit, children, className }: { inline?: boolean; onSubmit: (event: FormEvent<HTMLFormElement>) => void; children: ReactNode; className?: string }) {
  return <form className={join(inline ? s.inlineForm : s.formGrid, className)} onSubmit={(event) => { event.preventDefault(); onSubmit(event); }}>{children}</form>;
}

export const ui = s;

/**
 * A button that opens a small panel under it (an app's accounts, a row's ⋯ actions). Clicking outside or pressing
 * Escape closes it; [children] gets a function that closes it after an action.
 */
export function Popover({ label, trigger, children, align = "start", plain }: { label: string; trigger: ReactNode; children: (close: () => void) => ReactNode; align?: "start" | "end"; plain?: boolean }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (event: MouseEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); };
    const key = (event: KeyboardEvent) => { if (event.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", away); document.addEventListener("keydown", key);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", key); };
  }, [open]);
  return (
    <span ref={box} className={s.popover}>
      <button type="button" className={plain ? s.plainTrigger : s.iconButton} aria-label={label} aria-expanded={open} onClick={() => setOpen(!open)}>{trigger}</button>
      {open && <div className={join(s.panel, align === "end" && s.panelEnd)} role="menu">{children(() => setOpen(false))}</div>}
    </span>
  );
}
/** A row's less used actions behind ⋯. */
export function MoreMenu({ label, actions }: { label: string; actions: [string, () => void, boolean?][] }) {
  return (
    <Popover label={label} align="end" trigger={<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>}>
      {(close) => actions.map(([text, run, danger]) => (
        <button key={text} type="button" role="menuitem" className={join(s.menuItem, danger && s.menuDanger)} onClick={() => { close(); run(); }}>{text}</button>
      ))}
    </Popover>
  );
}
/**
 * A choice shown as its value in the accent colour with a small arrow; it opens the options one per row, as a select
 * does, the chosen one highlighted. Used wherever a page picks one of a few (an account, a place, a filter).
 */
export function TextSelect<T extends string>({ value, options, onChange, label }: { value: T; options: [T, string][]; onChange: (value: T) => void; label: string }) {
  const text = options.find(([id]) => id === value)?.[1] ?? "—";
  return (
    <Popover label={label} plain trigger={<span className={s.textSelect}>{text}<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg></span>}>
      {(close) => (
        <div role="listbox" aria-label={label} className={s.options}>
          {options.map(([id, name]) => (
            <button key={id} type="button" role="option" aria-selected={id === value} className={join(s.option, id === value && s.optionOn)}
              onClick={() => { close(); if (id !== value) onChange(id); }}>{name}</button>
          ))}
        </div>
      )}
    </Popover>
  );
}
