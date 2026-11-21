import { api, apiUrl, enc } from "../../api/client";

/** The developer workbench's server API (/api/dev). Drafts are the compact published form, edited by node patches. */

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Obj = { [key: string]: Json };

export interface DraftSummary { target: string; baseVersion: string; revision: number; updatedAt: number }
/** [bases]: node digests of the tasks and snippets as imported, keyed `tasks/<id>` and `snippets/<id>`. */
export interface DraftView { target: string; baseVersion: string; revision: number; body: Obj; bases: Record<string, string> }
/** A task's newest finished trial; [digest] is the task's node digest when it ran. */
export interface TrialRecord { task: string; step: string | null; device: string; state: string; detail: string; startedAt: number; endedAt: number; digest: string }
export interface DraftPatch { op: "set" | "put" | "delete" | "insert"; path: string; value?: Json; field?: string; after?: string | null; expect?: string }
export interface PatchConflict { path: string; reason: string }
export interface PatchResult { revision: number; conflicts: PatchConflict[] }
export interface Capability { id: string; version: number; inputs: Record<string, string>; outputs: Record<string, string>; role: string; sendsInput: boolean; optional: string[] }
export interface CheckIssue { location: string; reason: string }
export interface CheckResult { ok: boolean; issues: CheckIssue[]; tasks: number; publishedBytes: number; expandedBytes: number }
export interface DevMe { maintainer: boolean | null; samples: number; sampleBytes: number; sampleQuota: number }
export interface SampleText { text: string; box: number[] }
export interface Sample { id: string; target: string; name: string; device: string; width: number; height: number; bytes: number; createdAt: number;
  texts: SampleText[]; privateRegions: number[][]; recognized: boolean }
export interface Template { id: string; assetName: string; bytes: number; sha256: string; width: number; height: number; sample: string | null; rect: number[]; sameAs: string[] }
export interface DevTrace { step: string; action: string; capability: string; result: string; reason?: string; elapsedMs: number; trace?: Obj }
export interface DevRun { id: string; kind: string; device: string; target: string; state: string; detail: string; createdAt: number; updatedAt: number; task?: string | null; step?: string | null;
  facts: { type: string; detail: string }[]; traces: DevTrace[] }
export interface PublishResult { version: string; published: boolean; bundleBytes: number; uploads: { id: string; assetName: string; bytes: number }[];
  reused: { id: string; assetName: string; bytes: number; release?: string }[]; uploadBytes: number }

export const dev = {
  me: () => api<DevMe>("/api/dev/me"),
  catalog: () => api<Capability[]>("/api/dev/catalog"),
  drafts: () => api<DraftSummary[]>("/api/dev/drafts"),
  draft: (target: string) => api<DraftView>(`/api/dev/drafts/${enc(target)}`),
  importLatest: (target: string) => api<PatchResult>(`/api/dev/drafts/${enc(target)}/import-latest`, { method: "POST", body: {} }),
  patch: (target: string, patches: DraftPatch[]) => api<PatchResult>(`/api/dev/drafts/${enc(target)}/patches`, { method: "POST", body: { source: "web", patches } }),
  check: (body: Obj) => api<CheckResult>("/api/dev/check", { method: "POST", body }),
  trials: (target: string) => api<TrialRecord[]>(`/api/dev/games/${enc(target)}/trials`),
  samples: (target: string) => api<Sample[]>(`/api/dev/games/${enc(target)}/samples`),
  /** A screenshot from elsewhere as a sample: a PNG as it is, anything else redrawn as one (a redrawn PNG is often much larger). */
  uploadSample: async (target: string, file: File) => {
    const name = file.name.replace(/\.[^.]+$/, "").slice(0, 64) || "上传的图";
    const path = `/api/dev/games/${enc(target)}/samples?name=${enc(name)}&device=${enc("上传")}`;
    if (file.type === "image/png") return api<Sample>(path, { method: "POST", body: file });
    const bitmap = await createImageBitmap(file);
    const canvas = Object.assign(document.createElement("canvas"), { width: bitmap.width, height: bitmap.height });
    canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
    const png = await new Promise<Blob>((done, fail) => canvas.toBlob((b) => b ? done(b) : fail(new Error("图片转换失败")), "image/png"));
    return api<Sample>(path, { method: "POST", body: png });
  },
  sampleImage: (id: string) => apiUrl(`/api/dev/samples/${enc(id)}/image`),
  changeSample: (id: string, change: { name?: string; privateRegions?: number[][] }) => api<Sample>(`/api/dev/samples/${enc(id)}`, { method: "PATCH", body: change }),
  deleteSample: (id: string) => api(`/api/dev/samples/${enc(id)}`, { method: "DELETE" }),
  templates: (target: string) => api<Template[]>(`/api/dev/games/${enc(target)}/templates`),
  templateImage: (target: string, id: string) => apiUrl(`/api/dev/games/${enc(target)}/templates/${enc(id)}/image`),
  cut: (target: string, cut: { id: string; sample: string; rect: number[]; confirmedTexts: string[]; reason?: string }) =>
    api<Template>(`/api/dev/games/${enc(target)}/templates`, { method: "POST", body: cut }),
  deleteTemplate: (target: string, id: string) => api(`/api/dev/games/${enc(target)}/templates/${enc(id)}`, { method: "DELETE" }),
  startRun: (start: { kind: string; target: string; device: string; task?: string; step?: string; sample?: string; states?: Json[]; name?: string; configuration?: Obj }) =>
    api<DevRun>("/api/dev/runs", { method: "POST", body: start }),
  run: (id: string) => api<DevRun>(`/api/dev/runs/${enc(id)}`),
  publish: (target: string, request: { version: string; changelog: string; minApp: string; dryRun: boolean }) =>
    api<PublishResult>(`/api/dev/drafts/${enc(target)}/publish`, { method: "POST", body: request }),
};

/** Waits for a run to finish, polling; [seen] gets each state as it comes. */
export async function follow(id: string, seen: (run: DevRun) => void, limitMs = 12 * 60_000): Promise<DevRun> {
  const until = Date.now() + limitMs;
  for (;;) {
    const run = await dev.run(id);
    seen(run);
    if (run.state !== "SENT" && run.state !== "RUNNING") return run;
    if (Date.now() > until) return run;
    await new Promise((done) => setTimeout(done, 2000));
  }
}
