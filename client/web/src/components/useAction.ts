import { useCallback, useState } from "react";

/**
 * Runs one user action at a time and keeps what to tell the user about it: [busy] while it runs, then its success
 * text or the error's message.
 */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);
  const run = useCallback(async (action: () => Promise<string | void>) => {
    setBusy(true); setMessage(null);
    try { const done = await action(); if (done) setMessage({ text: done, ok: true }); }
    catch (error) { setMessage({ text: error instanceof Error ? error.message : "操作失败，请稍后再试", ok: false }); }
    finally { setBusy(false); }
  }, []);
  return { busy, message, run, clear: () => setMessage(null) };
}

/** For a quick action with nowhere else to show its failure. */
export async function orAlert(action: () => Promise<unknown>) {
  try { await action(); } catch (error) { alert(error instanceof Error ? error.message : "操作失败，请稍后再试"); }
}
