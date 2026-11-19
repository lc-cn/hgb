import type { ButtonHTMLAttributes, FormEvent, HTMLAttributes, ReactNode } from "react";
import type { Tone } from "../format";
import s from "./ui.module.css";

/** The few building blocks every page is made of; their look comes only from the theme tokens. */

const join = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(" ");

export function Card({ flush, className, ...rest }: HTMLAttributes<HTMLDivElement> & { flush?: boolean }) {
  return <div className={join(s.card, flush && s.flush, className)} {...rest} />;
}

type Variant = "primary" | "ghost" | "danger" | "link";
export function Button({ variant = "ghost", className, type = "button", ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return <button type={type} className={join(variant === "link" ? s.link : s.button, variant !== "link" && s[variant], className)} {...rest} />;
}
export function LinkButton({ href, children }: { href: string; children: ReactNode }) {
  return <a className={join(s.button, s.ghost)} href={href}>{children}</a>;
}

export function Pill({ tone = "", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={join(s.pill, tone && s[tone])}>{children}</span>;
}

export function Field({ label, children, inline }: { label: string; children: ReactNode; inline?: boolean }) {
  return <label className={inline ? s.inline : s.field}>{inline ? <span>{label}</span> : label}{children}</label>;
}
export function Check({ label, checked, onChange, disabled }: { label: string; checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean }) {
  return <label className={s.check}><input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /><span>{label}</span></label>;
}
export function Choices({ children }: { children: ReactNode }) { return <div className={s.choices}>{children}</div>; }

/** A result line under a form; empty when there is nothing to say. */
export function Message({ text, ok }: { text?: string | null; ok?: boolean }) {
  if (!text) return null;
  return <p className={join(s.message, ok && s.okMessage)} role="status">{text}</p>;
}
export function Muted({ children, className }: { children: ReactNode; className?: string }) { return <p className={join(s.muted, className)}>{children}</p>; }
export function Row({ children, className }: { children: ReactNode; className?: string }) { return <div className={join(s.row, className)}>{children}</div>; }
export function Grow({ children }: { children: ReactNode }) { return <div className={s.grow}>{children}</div>; }

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: [T, string][]; value: T; onChange: (value: T) => void }) {
  return (
    <div className={s.tabs} role="tablist">
      {tabs.map(([id, label]) => <button key={id} type="button" role="tab" aria-selected={id === value} className={s.tab} onClick={() => onChange(id)}>{label}</button>)}
    </div>
  );
}

export function Brand() {
  return <div className={s.brand}><img src="/logo.svg" alt="" /><h1>护肝宝控制台</h1></div>;
}

/** A form laid out in one column, or as a row of fields with the button at the end. */
export function Form({ inline, onSubmit, children, className }: { inline?: boolean; onSubmit: (event: FormEvent<HTMLFormElement>) => void; children: ReactNode; className?: string }) {
  return <form className={join(inline ? s.inlineForm : s.formGrid, className)} onSubmit={(event) => { event.preventDefault(); onSubmit(event); }}>{children}</form>;
}

export const ui = s;
