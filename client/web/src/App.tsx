import { useCallback, useEffect, useState } from "react";
import { api, ApiFailure } from "./api/client";
import type { Account } from "./api/types";
import { AuthPage } from "./features/auth/AuthPage";
import { ConsolePage } from "./features/ConsolePage";

type Session = { kind: "loading" } | { kind: "signed-out" } | { kind: "signed-in"; account: Account } | { kind: "failed"; message: string };

/** Signed in, the console; otherwise the sign-in page. The session is the server's cookie; this only asks who it is. */
export function App() {
  const [session, setSession] = useState<Session>({ kind: "loading" });
  const check = useCallback(async () => {
    try { setSession({ kind: "signed-in", account: await api<Account>("/api/me") }); }
    catch (error) {
      if (error instanceof ApiFailure && error.status === 401) setSession({ kind: "signed-out" });
      else setSession({ kind: "failed", message: error instanceof Error ? error.message : "无法连接服务器" });
    }
  }, []);
  useEffect(() => { void check(); }, [check]);
  switch (session.kind) {
    case "loading": return <p style={{ padding: 48, textAlign: "center", color: "var(--color-muted)" }}>正在加载…</p>;
    case "failed": return <p style={{ padding: 48, textAlign: "center" }}>{session.message}</p>;
    case "signed-out": return <AuthPage signedIn={check} />;
    case "signed-in": return <ConsolePage key={session.account.userId} account={session.account} signedOut={() => setSession({ kind: "signed-out" })} />;
  }
}
