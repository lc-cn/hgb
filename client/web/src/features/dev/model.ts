import type { Json, Obj } from "./api";

/** Reading the compact draft, and the node digests the server checks patches against (Drafts.kt nodeDigest). */

const sorted = (value: Json): Json => {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sorted((value as Obj)[key])]));
  return value;
};
async function digestOf(value: Json): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(sorted(value)));
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}
/** What put and delete expect: the node with everything under it. */
export const nodeDigest = (node: Json) => digestOf(node);
/** What set expects: the node's own fields, without lists and without the nodes under it. */
export const shallowDigest = (node: Obj) =>
  digestOf(Object.fromEntries(Object.entries(node).filter(([, v]) => !Array.isArray(v) && !(v && typeof v === "object" && idOf(v as Obj) !== null))));

export const isObj = (value: Json | undefined): value is Obj => !!value && typeof value === "object" && !Array.isArray(value);
export const str = (value: Json | undefined) => (typeof value === "string" ? value : undefined);
export const arr = (value: Json | undefined): Json[] => (Array.isArray(value) ? value : []);
export const objs = (value: Json | undefined): Obj[] => arr(value).filter(isObj);

/** A node's id: its own, or for an action wrapper the action's. */
export function idOf(node: Obj): string | null {
  return str(node.id) ?? (isObj(node.action) ? str(node.action.id) ?? null : null);
}

export interface TaskDef { id: string; name: string; steps: Obj[]; fields: Obj[]; raw: Obj }
export const tasks = (body: Obj): TaskDef[] => objs(body.tasks).map((t) => ({ id: str(t.id) ?? "", name: str(t.name) ?? str(t.id) ?? "", steps: objs(t.steps), fields: objs(t.fields), raw: t }));
export const snippets = (body: Obj): Obj[] => objs(body.snippets);
export const target = (body: Obj, id: string): Obj | undefined => objs(body.targets).find((t) => t.id === id);
export const states = (body: Obj, game: string): Obj[] => {
  const library = target(body, game)?.library;
  return isObj(library) ? objs(library.states) : [];
};
export const resources = (body: Obj): Obj[] => objs(body.resources);

/** States written inline in the steps' and snippets' page waits, each distinct one once, with where it was first found. */
export function inlineStates(body: Obj): { key: string; state: Obj; where: string }[] {
  const found = new Map<string, { key: string; state: Obj; where: string }>();
  const walk = (program: Obj[], where: string) => {
    for (const node of program) {
      const action = isObj(node.action) ? node.action : null;
      if (action?.capability === "screen.awaitScreen" && isObj(action.inputs))
        for (const st of objs(action.inputs.states)) {
          const content = JSON.stringify(st);
          if (str(st.id) && !found.has(content)) found.set(content, { key: `${where}#${str(st.id)}#${found.size}`, state: st, where });
        }
      for (const [, child] of childrenOf(node)) walk(child, where);
    }
  };
  for (const t of tasks(body)) for (const st of t.steps) walk(programOf(st), `${t.name} · ${str(st.name) ?? str(st.id) ?? ""}`);
  for (const sn of snippets(body)) walk(programOf(sn), `片段 ${str(sn.id) ?? ""}`);
  return [...found.values()];
}

/** The program of a step or snippet: its own program, or (old form) its action list as plain actions. */
export function programOf(owner: Obj): Obj[] {
  if (Array.isArray(owner.program)) return objs(owner.program);
  return objs(owner.actions).map((action) => ({ type: "action", action }));
}

/** The children programs of a flow node, labelled. */
export function childrenOf(node: Obj): [string, Obj[]][] {
  switch (node.type) {
    case "branch": return objs(node.cases).map((c, i) => [`= ${JSON.stringify(c.equals ?? `情况 ${i + 1}`)}`, objs(c.program)]);
    case "until": case "repeat": case "forEach": case "block": return [["", objs(node.program)]];
    default: return [];
  }
}

/**
 * The patch path of [node] under an owner path: the ids of every node with one on the way down, its own last.
 * Nodes without an id (most branches) are passed through by the server.
 */
export function pathTo(ownerPath: string, program: Obj[], wanted: Obj): string | null {
  for (const node of program) {
    const id = idOf(node);
    const here = id ? `${ownerPath}/${id}` : ownerPath;
    if (node === wanted) return id ? here : null;
    for (const [, child] of childrenOf(node)) {
      const found = pathTo(here, child, wanted);
      if (found) return found;
    }
  }
  return null;
}

/** A one-line description of a flow node for the program list. */
export function describe(node: Obj): string {
  const value = (v: Json | undefined) => isObj(v) && v.type === "output" ? `${str(v.actionId)}.${str(v.field)}` : isObj(v) && v.type === "configuration" ? `设置 ${str(v.fieldId)}` : JSON.stringify(v);
  switch (node.type) {
    case "action": return "";
    case "branch": return `看 ${value(node.value)}`;
    case "until": return `重复直到 ${value(node.value)} = ${JSON.stringify(node.equals)}，最多 ${JSON.stringify(node.maximum)} 次`;
    case "repeat": return `重复 ${JSON.stringify(node.count)} 次`;
    case "forEach": return `逐项 ${arr(node.items).length} 项`;
    case "block": return "块";
    case "snippet": return `片段 ${str(node.use)}`;
    case "exit": return `退出 ${str(node.loop)}`;
    default: return String(node.type ?? "");
  }
}
export const KIND: Record<string, string> = { action: "动作", branch: "分支", until: "直到", repeat: "重复", forEach: "逐项", block: "块", snippet: "片段", exit: "退出" };

/** Ids of the snippets [root] calls, directly or through other snippets (every `use`, as the server's DevRuns.used). */
export function usedSnippets(body: Obj, root: Json): string[] {
  const all = new Map(snippets(body).map((sn) => [String(sn.id), sn]));
  const seen = new Set<string>();
  const scan = (e: Json | undefined) => {
    if (Array.isArray(e)) e.forEach(scan);
    else if (isObj(e)) for (const [key, value] of Object.entries(e)) {
      if (key === "use" && typeof value === "string" && !seen.has(value)) { seen.add(value); scan(all.get(value)); }
      scan(value);
    }
  };
  scan(root);
  return [...seen];
}

/** What a trial of [task] ran: the task, its game's interruption pages, and the snippets they call, by id (DevRuns.trialDigest). */
export function trialDigest(body: Obj, task: Obj): Promise<string> {
  const all = new Map(snippets(body).map((sn) => [String(sn.id), sn]));
  const game = target(body, String(task.targetId ?? ""));
  const pages = arr(isObj(game?.library) ? game!.library.interruptions : undefined);
  const used = Object.fromEntries(usedSnippets(body, [task, pages]).filter((id) => all.has(id)).map((id) => [id, all.get(id)!]));
  return nodeDigest(pages.length ? { task, snippets: used, interruptions: pages } : { task, snippets: used });
}

/** Ids of the nodes directly under [node], through id-less objects (Drafts.kt Nodes.children): what an insert must not repeat. */
export function childIds(node: Obj): string[] {
  const out: string[] = [];
  const walk = (o: Obj) => {
    for (const [key, value] of Object.entries(o)) {
      if (key === "action") continue;
      if (Array.isArray(value)) value.filter(isObj).forEach((child) => { const id = idOf(child); if (id) out.push(id); else walk(child); });
      else if (isObj(value) && idOf(value) === null) walk(value);
    }
  };
  walk(node);
  return out;
}
