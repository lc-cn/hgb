import { useState, type CSSProperties, type ReactNode } from "react";
import w from "./Wb.module.css";

/**
 * A button that asks for a name in place: it turns into a field with 确定 and 取消 (Enter and Esc work too). In
 * place of the browser's own prompt, which a page cannot style and which the browser may dismiss on its own.
 */
export function NameButton({ label, placeholder, className, style, onName }: { label: ReactNode; placeholder: string; className?: string;
  style?: CSSProperties; onName: (name: string) => unknown }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const close = () => { setOpen(false); setName(""); };
  const submit = async () => { const value = name.trim(); if (!value) return; close(); await onName(value); };
  if (!open) return <button type="button" className={className ?? w.btn} style={style} onClick={() => setOpen(true)}>{label}</button>;
  return (
    <span style={{ display: "inline-flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
      <input autoFocus aria-label={placeholder} placeholder={placeholder} value={name} onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter") void submit(); if (e.key === "Escape") close(); }}
        style={{ minHeight: 34, padding: "0 10px", borderRadius: 8, border: "1px solid var(--color-line)", background: "var(--color-control)", color: "inherit", font: "inherit", minWidth: 140 }} />
      <button type="button" className={`${w.btn} ${w.primary}`} style={{ minHeight: 34 }} disabled={!name.trim()} onClick={() => void submit()}>确定</button>
      <button type="button" className={w.btn} style={{ minHeight: 34 }} onClick={close}>取消</button>
    </span>
  );
}
