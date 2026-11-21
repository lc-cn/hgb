import { createContext, useContext } from "react";
import { ApiFailure } from "../../api/client";
import type { Capability, DevMe, DraftPatch, DraftView, Obj, TrialRecord } from "./api";

/** Patches, or a function making them from the latest draft (when an edit must build on the one before it). */
export type Edit = DraftPatch[] | ((body: Obj) => DraftPatch[] | Promise<DraftPatch[]>);

/** What every workbench page shares: the game, its draft, the action catalog and who is writing. */
export interface DevContextValue {
  game: string; gameName: string; draft: DraftView | null; catalog: Capability[]; me: DevMe | null;
  /** Each task's newest trial, and the current node digest of every task and snippet (`tasks/<id>`, `snippets/<id>`). */
  trials: TrialRecord[]; digests: Record<string, string>; reloadTrials: () => Promise<void>;
  reload: () => Promise<void>; apply: (edit: Edit) => Promise<string | null>;
}
export const DevContext = createContext<DevContextValue | null>(null);
export const useDev = () => { const value = useContext(DevContext); if (!value) throw new Error("useDev outside the workbench"); return value; };
export const devPaths = {
  home: "/dev", task: (id: string) => `/dev/tasks/${encodeURIComponent(id)}`,
  step: (task: string, step: string) => `/dev/tasks/${encodeURIComponent(task)}/steps/${encodeURIComponent(step)}`,
  snippet: (id: string) => `/dev/snippets/${encodeURIComponent(id)}`,
  config: (task: string) => `/dev/tasks/${encodeURIComponent(task)}/config`, run: (task: string) => `/dev/tasks/${encodeURIComponent(task)}/run`, game: "/dev/game",
  samples: "/dev/samples", sample: (id: string) => `/dev/samples/${encodeURIComponent(id)}`, states: "/dev/states", publish: "/dev/publish",
};
export const failure = (error: unknown) => error instanceof ApiFailure ? error.message : "操作失败，请稍后再试";
