import { useEffect, useReducer, useRef } from "react";
import { api, ApiFailure } from "../api/client";
import type { ConsoleSnapshot, Device, GameAccount, Game, Job, Plan } from "../api/types";

/** Everything the console shows, as the server's event stream last described it. */
export interface ConsoleData {
  devices: Device[]; jobs: Job[]; accounts: GameAccount[]; plans: Plan[]; games: Game[]; timeZone: string;
  /** Bumped when a job's log grows, so an open log knows to read it again. */
  logVersion: Record<string, number>;
  /** Bumped by every snapshot: after a gap, anything read before may be stale. */
  epoch: number;
}
export type Connection = "connecting" | "live" | "lost";

type Action =
  | { type: "snapshot"; snapshot: ConsoleSnapshot }
  | { type: "device"; device: Device }
  | { type: "device-removed"; id: string }
  | { type: "jobs"; jobs: Job[] }
  | { type: "accounts"; accounts: GameAccount[] }
  | { type: "plans"; plans: Plan[] }
  | { type: "games"; games: Game[] }
  | { type: "time-zone"; timeZone: string }
  | { type: "job-log"; id: string };

const empty: ConsoleData = { devices: [], jobs: [], accounts: [], plans: [], games: [], timeZone: "Asia/Shanghai", logVersion: {}, epoch: 0 };
const byName = (a: Device, b: Device) => a.name.localeCompare(b.name, "zh-CN") || a.id.localeCompare(b.id);

function reduce(state: ConsoleData, action: Action): ConsoleData {
  switch (action.type) {
    case "snapshot": return { ...action.snapshot, devices: [...action.snapshot.devices].sort(byName), logVersion: {}, epoch: state.epoch + 1 };
    case "device": return { ...state, devices: [...state.devices.filter((d) => d.id !== action.device.id), action.device].sort(byName) };
    case "device-removed": return { ...state, devices: state.devices.filter((d) => d.id !== action.id) };
    case "jobs": return { ...state, jobs: action.jobs };
    case "accounts": return { ...state, accounts: action.accounts };
    case "plans": return { ...state, plans: action.plans };
    case "games": return { ...state, games: action.games };
    case "time-zone": return { ...state, timeZone: action.timeZone };
    case "job-log": return { ...state, logVersion: { ...state.logVersion, [action.id]: (state.logVersion[action.id] ?? 0) + 1 } };
  }
}

/**
 * Follows the server's event stream: a snapshot first, then changes. The browser retries a dropped stream by itself,
 * but not after an error status (a proxy's 502 during a deploy); then it is opened again here, a little later each time.
 * [signedOut] is called when the session turns out to be gone.
 */
export function useConsoleStream(signedOut: () => void): { data: ConsoleData; connection: Connection; loaded: boolean } {
  const [data, dispatch] = useReducer(reduce, empty);
  const [connection, setConnection] = useReducer((_: Connection, next: Connection) => next, "connecting" as Connection);
  const [loaded, setLoaded] = useReducer(() => true, false);
  const leave = useRef(signedOut); leave.current = signedOut;

  useEffect(() => {
    let stream: EventSource | null = null;
    let retryMs = 2000;
    let timer: number | undefined;
    let stopped = false;
    const json = (event: Event) => JSON.parse((event as MessageEvent<string>).data);
    function connect() {
      stream?.close();
      const current = new EventSource("/api/events");
      stream = current;
      current.addEventListener("snapshot", (event) => { retryMs = 2000; dispatch({ type: "snapshot", snapshot: json(event) }); setLoaded(); });
      current.addEventListener("device", (event) => dispatch({ type: "device", device: json(event) }));
      current.addEventListener("device-removed", (event) => dispatch({ type: "device-removed", id: json(event).id }));
      current.addEventListener("jobs", (event) => dispatch({ type: "jobs", jobs: json(event) }));
      current.addEventListener("accounts", (event) => dispatch({ type: "accounts", accounts: json(event) }));
      current.addEventListener("plans", (event) => dispatch({ type: "plans", plans: json(event) }));
      current.addEventListener("games", (event) => dispatch({ type: "games", games: json(event) }));
      current.addEventListener("time-zone", (event) => dispatch({ type: "time-zone", timeZone: json(event).timeZone }));
      current.addEventListener("job-log", (event) => dispatch({ type: "job-log", id: json(event).id }));
      current.onopen = () => setConnection("live");
      current.onerror = async () => {
        setConnection("lost");
        try { await api("/api/me"); } catch (error) { if (error instanceof ApiFailure && error.status === 401) { current.close(); leave.current(); return; } }
        if (!stopped && stream === current && current.readyState === EventSource.CLOSED) {
          timer = window.setTimeout(() => { if (!stopped && stream === current) connect(); }, retryMs);
          retryMs = Math.min(retryMs * 2, 60000);
        }
      };
    }
    connect();
    return () => { stopped = true; window.clearTimeout(timer); stream?.close(); };
  }, []);
  return { data, connection, loaded };
}
