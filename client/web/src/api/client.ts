/** A refused or failed request, with the server's code and its message for the user. */
export class ApiFailure extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}

/**
 * Calls the server. Every request carries the header the server requires of browser writes, so another site cannot
 * make the user's browser change anything.
 */
export async function api<T = unknown>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const { method = "GET", body } = options;
  const headers: Record<string, string> = { "X-YYS-Request": "1" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let reply: Response;
  try {
    reply = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new ApiFailure(0, "network", "无法连接服务器，请检查网络");
  }
  const data = reply.status === 204 ? null : await reply.json().catch(() => null);
  if (!reply.ok) throw new ApiFailure(reply.status, data?.code ?? "error", data?.message ?? "操作失败，请稍后再试");
  return data as T;
}

export const enc = encodeURIComponent;
