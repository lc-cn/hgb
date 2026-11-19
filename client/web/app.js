const root = document.getElementById("app");
/** The console's live connection; closed when the console is left. */
let stream = null;

class ApiFailure extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

async function api(path, { method = "GET", body } = {}) {
  const headers = { "X-YYS-Request": "1" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let reply;
  try {
    reply = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new ApiFailure(0, "network", "无法连接服务器，请检查网络");
  }
  const data = reply.status === 204 ? null : await reply.json().catch(() => null);
  if (!reply.ok) throw new ApiFailure(reply.status, data?.code ?? "error", data?.message ?? "操作失败，请稍后再试");
  return data;
}

/** Builds DOM nodes; text always goes through textContent, so server data can never become markup. */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (value === true) node.setAttribute(key, "");
    else if (value !== false && value != null) node.setAttribute(key, value);
  }
  node.append(...children.filter((child) => child != null));
  return node;
}

function field(label, name, type, extra = {}) {
  return el("label", { text: label }, el("input", { name, type, required: true, ...extra }));
}

function brand() {
  return el("div", { class: "brand" }, el("img", { src: "logo.svg", alt: "" }), el("h1", { text: "护肝宝控制台" }));
}

function authForm({ fields, submit, onSubmit, after }) {
  const message = el("p", { class: "message", role: "status" });
  const button = el("button", { class: "primary", type: "submit", text: submit });
  const form = el("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      button.disabled = true; message.textContent = ""; message.className = "message";
      try { await onSubmit(Object.fromEntries(new FormData(form)), message); }
      catch (error) { message.textContent = error.message; }
      finally { button.disabled = false; }
    },
  }, ...fields, button, message, after ?? null);
  return form;
}

function emailField() { return field("邮箱", "email", "email", { autocomplete: "email" }); }

function codeForm(email, purpose) {
  const code = field("验证码", "code", "text", { inputmode: "numeric", autocomplete: "one-time-code", pattern: "[0-9]{6}", maxlength: "6" });
  if (purpose === "reset") return authForm({
    fields: [code, field("新密码（至少 10 位）", "password", "password", { autocomplete: "new-password", minlength: "10" })], submit: "重置密码",
    onSubmit: async (values) => { await api("/api/auth/password/reset", { method: "POST", body: { email, ...values } }); showAuth("password", "密码已重置，请重新登录"); },
  });
  const path = purpose === "verify" ? "/api/auth/verify-email" : "/api/auth/code/login";
  return authForm({ fields: [code], submit: purpose === "verify" ? "完成注册" : "登录",
    onSubmit: async (values) => { await api(path, { method: "POST", body: { email, code: values.code } }); start(); } });
}

function showAuth(tab = "password", notice = "") {
  stopRefresh();
  const params = new URLSearchParams(location.search);
  const failed = params.get("error") === "github" ? "GitHub 登录未完成，请重试或改用邮箱登录" : "";
  const body = el("div");
  const tabs = [["password", "密码登录"], ["code", "验证码登录"], ["register", "注册"]];
  const bar = el("div", { class: "tabs", role: "tablist" }, ...tabs.map(([id, label]) =>
    el("button", { type: "button", role: "tab", "aria-selected": String(id === tab), text: label, onclick: () => showAuth(id) })));
  const swap = (form) => body.replaceChildren(form);
  if (tab === "password") swap(authForm({
    fields: [emailField(), field("密码", "password", "password", { autocomplete: "current-password" })], submit: "登录",
    onSubmit: async (values) => { await api("/api/auth/login", { method: "POST", body: values }); start(); },
    after: el("button", { class: "link", type: "button", text: "忘记密码", onclick: () => swap(authForm({ fields: [emailField()], submit: "发送验证码",
      onSubmit: async ({ email }) => { await api("/api/auth/password/request", { method: "POST", body: { email } }); swap(codeForm(email, "reset")); } })) }),
  }));
  if (tab === "code") swap(authForm({ fields: [emailField()], submit: "发送验证码",
    onSubmit: async ({ email }) => { await api("/api/auth/code/request", { method: "POST", body: { email } }); swap(codeForm(email, "login")); } }));
  if (tab === "register") swap(authForm({
    fields: [emailField(), field("密码（至少 10 位）", "password", "password", { autocomplete: "new-password", minlength: "10" })], submit: "注册",
    onSubmit: async (values) => { await api("/api/auth/register", { method: "POST", body: values }); swap(codeForm(values.email, "verify")); } }));
  const note = notice || failed;
  root.className = "auth";
  root.replaceChildren(el("div", { class: "auth-card" }, brand(), el("div", { class: "card" }, el("div", { class: "row" }, el("div", { class: "grow" }, bar)),
    el("div", { class: "spacer" }), body), note ? el("p", { class: `message ${notice ? "ok" : ""}`, text: note }) : null,
    el("a", { class: "ghost", href: "/api/auth/github/start", text: "使用 GitHub 登录" })));
}

const STATES = {
  WAITING: ["等可用应用", "info"], QUEUED: ["排队中", "info"], LEASED: ["已下发", "info"], RUNNING: ["运行中", "info"], COMPLETED: ["已完成", "ok"],
  FAILED: ["失败", "bad"], UNKNOWN: ["结果未知", "warn"], CANCELLED: ["已取消", ""], SKIPPED: ["已跳过", "warn"],
};
const ACTIVE = new Set(["WAITING", "QUEUED", "LEASED", "RUNNING"]);
const time = (value) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";

const PHASES = { CONNECTING: "连接中", IDLE: "空闲", RUNNING: "运行中", PAUSING: "正在暂停", PAUSED: "已暂停", REMOVING: "正在停止", SWITCHING: "切换中", BLOCKED_UNKNOWN: "需要处理" };

const enc = encodeURIComponent;
function accountSelect(label, value, accounts, onchange) {
  const select = el("select", { "aria-label": label, onchange: (event) => onchange(event.target.value || null) },
    el("option", { value: "", text: "未指定" }), ...accounts.map((account) => el("option", { value: account.id, text: account.name })));
  select.value = value ?? "";
  return el("label", { class: "inline" }, el("span", { class: "muted", text: label }), select);
}

/**
 * One game instance on the device: what it is doing, and which accounts it uses. Until the app can read the signed-in
 * account, the user picks it here.
 */
function appLine(device, app, accounts) {
  const doing = app.currentTask ? `${PHASES[app.phase] ?? app.phase}：${app.currentTask}` : PHASES[app.phase] ?? app.phase;
  const mine = accounts.filter((account) => account.gameId === app.gameId);
  // Both values are read from the selects at save time, so two quick changes cannot undo each other.
  const save = async () => {
    try { await api(`/api/devices/${enc(device.id)}/apps/${enc(app.key)}`, { method: "PUT",
      body: { accountId: current.querySelector("select").value || null, defaultAccountId: fallback.querySelector("select").value || null } }); }
    catch (error) { alert(error.message); }
  };
  const current = accountSelect("当前登录", app.accountId, mine, save);
  const fallback = accountSelect("默认账号", app.defaultAccountId, mine, save);
  return el("li", { class: "app-line" },
    el("div", { class: "row" }, el("span", { class: "grow", text: app.name }),
      el("span", { class: "muted", text: [doing, app.queued ? `排队 ${app.queued} 个` : ""].filter(Boolean).join(" · ") })),
    mine.length ? el("div", { class: "app-accounts" }, current, fallback)
      : el("p", { class: "muted", text: "先在“游戏账号”里添加这个游戏的账号" }));
}

function deviceCard(device, accounts) {
  const apps = device.apps ?? [];
  return el("article", { class: "card device" },
    el("div", { class: "row" }, el("b", { class: "grow", text: device.name }), el("span", { class: `pill ${device.online ? "ok" : ""}`, text: device.online ? "在线" : "离线" })),
    el("p", { class: "muted", text: device.online ? `${device.platform} · 可用任务 ${device.tasks.length} 个` : `最后在线：${time(device.lastSeenAt)}` }),
    apps.length ? el("ul", { class: "apps", "aria-label": "应用" }, ...apps.map((app) => appLine(device, app, accounts)))
      : el("p", { class: "muted", text: device.online ? "这台设备上还没有护肝宝支持的游戏" : "离线时不显示应用状态" }),
    el("button", { class: "danger", type: "button", text: "解绑设备", onclick: async () => {
      if (!confirm(`解绑“${device.name}”？这台设备会下线，正在运行的作业结果将标记为未知。`)) return;
      await api(`/api/devices/${encodeURIComponent(device.id)}`, { method: "DELETE" });
    } }));
}

const EVENT_KINDS = {
  "enqueued": "已排队", "step-started": "开始步骤", "step-observed": "步骤结果", "step-completed": "步骤完成", "task-completed": "任务完成",
  "task-failed": "任务失败", "task-restarted": "任务重来", "run-ended": "运行结束", "cancelled": "已取消", "input-unconfirmed": "输入未确认",
  "result-resolved": "结果已确认", "revocation-observed": "停止确认", "action": "动作",
};
const ACTION_RESULTS = { PRODUCED: "完成", VERIFIED: "核对通过", UNAVAILABLE: "未通过", INTERRUPTED: "中断" };
const clock = (value) => new Date(value).toLocaleTimeString("zh-CN", { hour12: false });

/** Makes the executor's wording readable without changing what it says. */
function eventDetail(event) {
  if (event.kind === "action") {
    const [result, ...rest] = event.detail.split("：");
    return [ACTION_RESULTS[result] ?? result, rest.join("：")].filter(Boolean).join("：");
  }
  return event.detail.replace(/^Completed\(evidence=(.*)\)$/, "完成：$1").replace(/^Unknown\(reason=(.*)\)$/, "结果未知：$1")
    .replace(/^Failed\(reason=(.*)\)$/, "失败：$1").replace(/^Interrupted$/, "已中断");
}

async function jobLog(job, box) {
  let events;
  try { events = await api(`/api/jobs/${encodeURIComponent(job.id)}/events`); }
  catch (error) { box.replaceChildren(el("p", { class: "muted", text: error.message })); return; }
  if (events.length === 0) { box.replaceChildren(el("p", { class: "muted", text: ACTIVE.has(job.state) ? "设备还没有上报明细" : "这次作业没有明细" })); return; }
  box.replaceChildren(...events.map((event) => el("div", { class: `log-line ${event.kind === "action" ? "" : "fact"}` },
    el("span", { class: "muted", text: clock(event.at) }),
    el("span", { text: [event.stepName, EVENT_KINDS[event.kind] ?? event.kind, event.action].filter(Boolean).join(" · ") }),
    el("span", { class: "muted", text: [eventDetail(event), event.elapsedMs ? `${event.elapsedMs} ms` : ""].filter(Boolean).join(" · ") }))));
}

function jobRow(job, reload, expanded) {
  const [label, tone] = STATES[job.state];
  const log = el("div", { class: "job-log" });
  const open = expanded.has(job.id);
  if (open) jobLog(job, log);
  const command = (path) => async () => {
    try { await api(`/api/jobs/${enc(job.id)}/${path}`, { method: "POST", body: {} }); } catch (error) { alert(error.message); }
  };
  const where = job.deviceName ?? (job.state === "WAITING" ? `最晚 ${clock(job.latestStartAt)} 前开始` : null);
  return el("div", { class: "job-wrap" }, el("div", { class: "job" },
    el("div", { class: "grow" }, el("div", { class: "name", text: job.planName ? `${job.taskName} · 计划“${job.planName}”` : job.taskName }),
      el("div", { class: "muted", text: [job.accountName, where, time(job.updatedAt), job.detail].filter(Boolean).join(" · ") })),
    el("span", { class: `pill ${tone}`, text: job.cancelRequested && ACTIVE.has(job.state) ? "取消中" : job.state === "RUNNING" && job.pauseRequested ? "暂停中" : label }),
    job.state === "RUNNING" && !job.cancelRequested ? el("button", { class: "ghost", type: "button", text: job.pauseRequested ? "继续" : "暂停",
      onclick: command(job.pauseRequested ? "resume" : "pause") }) : null,
    el("button", { class: "ghost", type: "button", text: open ? "收起" : "明细", "aria-expanded": String(open), onclick: () => {
      if (expanded.has(job.id)) expanded.delete(job.id); else expanded.add(job.id);
      reload();
    } }),
    ACTIVE.has(job.state) && !job.cancelRequested ? el("button", { class: "ghost", type: "button", text: job.state === "RUNNING" ? "停止" : "取消",
      onclick: command("cancel") }) : null), open ? log : null);
}

/**
 * Runs a task now for one game account. "自动分配" lets the server pick an app signed in to the account; choosing an
 * app sends it there, which works only while that app is signed in to the account (switching comes later).
 */
function jobForm(devices, accounts, games) {
  const tasks = games.flatMap((game) => game.tasks.map((task) => ({ ...task, game })));
  if (tasks.length === 0) return el("p", { class: "card muted", text: "设备上线并上报任务后，就可以在这里运行任务。" });
  if (accounts.length === 0) return el("p", { class: "card muted", text: "先在“游戏账号”里添加账号，任务会用这个账号的设置运行。" });
  const message = el("p", { class: "message", role: "status" });
  const taskSelect = el("select", { name: "task", required: true, onchange: fill },
    ...tasks.map((task, i) => el("option", { value: String(i), text: games.length > 1 ? `${task.game.name} · ${task.name}` : task.name })));
  const accountSelect = el("select", { name: "account", required: true });
  const whereSelect = el("select", { name: "where" });
  function fill() {
    const task = tasks[Number(taskSelect.value)];
    const mine = accounts.filter((account) => account.gameId === task.targetId);
    accountSelect.replaceChildren(...(mine.length ? mine.map((account) => el("option", { value: account.id, text: account.name }))
      : [el("option", { value: "", text: "这个游戏还没有账号" })]));
    const apps = devices.filter((device) => device.online).flatMap((device) => device.apps.filter((app) => app.gameId === task.targetId)
      .map((app) => [JSON.stringify([device.id, app.key]), device.apps.length > 1 ? `${device.name} · ${app.name}` : device.name]));
    whereSelect.replaceChildren(el("option", { value: "", text: "自动分配（登录着这个账号的应用）" }), ...apps.map(([value, text]) => el("option", { value, text })));
  }
  fill();
  return el("form", { class: "card", onsubmit: async (event) => {
    event.preventDefault(); message.textContent = ""; message.className = "message";
    const task = tasks[Number(taskSelect.value)];
    const [deviceId, appKey] = whereSelect.value ? JSON.parse(whereSelect.value) : [null, null];
    try {
      await api("/api/jobs", { method: "POST", body: { targetId: task.targetId, taskId: task.taskId, accountId: accountSelect.value, deviceId, appKey } });
      message.textContent = "已提交，进度见作业记录"; message.className = "message ok";
    } catch (error) { message.textContent = error.message; }
  } }, el("div", { class: "new-job" }, el("label", { text: "任务" }, taskSelect), el("label", { text: "账号" }, accountSelect),
    el("label", { text: "在哪运行" }, whereSelect), el("button", { class: "primary", type: "submit", text: "运行" })), message);
}

/** One input per setting the task declares; returns [node, read] where read() gives the value to save or undefined. */
function settingInput(def, value) {
  const disabled = def.readOnly === true;
  const options = def.options ?? [];
  switch (def.widget) {
    case "SINGLE_CHOICE": {
      const select = el("select", { disabled }, el("option", { value: "", text: "请选择" }),
        ...options.map((option) => el("option", { value: option.value, text: option.label })));
      select.value = typeof value === "string" ? value : "";
      return [select, () => select.value || undefined];
    }
    case "MULTI_CHOICE": {
      const chosen = new Set(Array.isArray(value) ? value : []);
      const boxes = options.map((option) => [option.value, el("input", { type: "checkbox", disabled, checked: chosen.has(option.value) })]);
      return [el("div", { class: "choices" }, ...boxes.map(([, box], i) => el("label", { class: "check" }, box, el("span", { text: options[i].label })))),
        () => boxes.filter(([, box]) => box.checked).map(([id]) => id)];
    }
    case "SWITCH": {
      const box = el("input", { type: "checkbox", disabled, checked: value === true });
      return [el("label", { class: "check" }, box, el("span", { text: "开启" })), () => box.checked];
    }
    case "NUMBER": case "RANGE": {
      const input = el("input", { type: "number", disabled, min: def.minimum, max: def.maximum, step: def.step ?? "any" });
      input.value = typeof value === "number" ? String(value) : "";
      return [input, () => (input.value === "" ? undefined : Number(input.value))];
    }
    case "TEXT": case "TIME": case "DATE": {
      const input = el("input", { type: def.widget === "TEXT" ? "text" : def.widget.toLowerCase(), disabled });
      input.value = typeof value === "string" ? value : "";
      return [input, () => input.value || undefined];
    }
    default: return [el("p", { class: "muted", text: "这个设置暂时只能在 App 里修改" }), () => value];
  }
}

/** Settings live in the cloud; a job takes a copy when it is created. Fields come from the version the device runs. */
/** Settings belong to a game account. Fields come from the newest uploaded version that has the task. */
function settingsForm(account, game) {
  const tasks = game?.tasks ?? [];
  if (tasks.length === 0) return el("p", { class: "muted", text: "这个游戏还没有可设置的任务。" });
  const message = el("p", { class: "message", role: "status" });
  const editor = el("div", { class: "settings" });
  const taskSelect = el("select", { required: true, "aria-label": "任务", onchange: () => editor.replaceChildren() },
    ...tasks.map((task) => el("option", { value: task.taskId, text: task.name })));
  async function open() {
    message.textContent = ""; message.className = "message";
    const path = `/api/accounts/${enc(account.id)}/configs/${enc(account.gameId)}/${enc(taskSelect.value)}`;
    let view;
    try { view = await api(path); }
    catch (error) { message.textContent = error.message; return; }
    if (view.fields.length === 0) { editor.replaceChildren(el("p", { class: "muted", text: "这个任务没有可修改的设置。" })); return; }
    const readers = []; const pending = [];
    const rows = view.fields.map((def) => {
      const saved = Object.prototype.hasOwnProperty.call(view.values, def.id);
      const [input, read] = settingInput(def, saved ? view.values[def.id] : def.recommended);
      readers.push([def.id, read]);
      return el("div", { class: "setting" },
        el("div", { class: "name", text: `${def.label}${def.required ? "（必填）" : ""}` }),
        def.description ? el("div", { class: "muted", text: def.description }) : null,
        !saved && def.recommended !== undefined ? (pending[pending.length] = el("div", { class: "muted", text: "已填入推荐值，保存后生效" })) : null,
        input);
    });
    let revision = view.revision;
    const save = el("button", { class: "primary", type: "button", text: "保存设置", onclick: async () => {
      const values = {};
      for (const [id, read] of readers) { const value = read(); if (value !== undefined) values[id] = value; }
      save.disabled = true; message.textContent = "";
      try {
        const saved = await api(path, { method: "PUT", body: { values, revision } });
        revision = saved.revision; pending.forEach((note) => note.remove()); message.textContent = `已保存，之后${account.name}的作业和计划都用这些设置`; message.className = "message ok";
      } catch (error) { message.textContent = error.message; }
      finally { save.disabled = false; }
    } });
    editor.replaceChildren(el("p", { class: "muted", text: `任务版本 ${view.version}` }), ...rows, save);
  }
  return el("div", {}, el("div", { class: "new-job" }, el("label", { text: "任务" }, taskSelect),
    el("button", { class: "ghost", type: "button", text: "编辑设置", onclick: open })), editor, message);
}

const WEEK = ["一", "二", "三", "四", "五", "六", "日"];
const clockText = (minute) => `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
function planRule(plan) {
  const days = plan.days.length === 7 ? "每天" : `每周${plan.days.map((day) => WEEK[day - 1]).join("、")}`;
  return `${days} ${clockText(plan.minuteOfDay)}`;
}

/** Creates a plan for [account], or edits [plan]. Nothing is filled in on the user's behalf beyond the task list. */
function planEditor(account, game, devices, plan, close) {
  const message = el("p", { class: "message", role: "status" });
  const name = el("input", { type: "text", required: true, maxlength: "40", value: plan?.name ?? "" });
  const at = el("input", { type: "time", required: true, value: plan ? clockText(plan.minuteOfDay) : "" });
  const late = el("input", { type: "number", min: "1", max: "1440", required: true, value: String(plan?.latestStartMinutes ?? 30) });
  const enabled = el("input", { type: "checkbox", checked: plan?.enabled ?? true });
  const days = WEEK.map((label, i) => [i + 1, el("input", { type: "checkbox", checked: plan ? plan.days.includes(i + 1) : false })]);
  const chosen = new Set((plan?.tasks ?? []).map((task) => task.taskId));
  const tasks = (game?.tasks ?? []).map((task) => [task, el("input", { type: "checkbox", checked: chosen.has(task.taskId) })]);
  const where = new Set(plan?.deviceIds ?? []);
  const boxes = devices.map((device) => [device.id, el("input", { type: "checkbox", checked: where.has(device.id) })]);
  const check = (box, label) => el("label", { class: "check" }, box, el("span", { text: label }));
  return el("form", { class: "plan-editor", onsubmit: async (event) => {
    event.preventDefault(); message.textContent = "";
    const [hour, minute] = at.value.split(":").map(Number);
    const body = { name: name.value, days: days.filter(([, box]) => box.checked).map(([day]) => day), minuteOfDay: hour * 60 + minute,
      tasks: tasks.filter(([, box]) => box.checked).map(([task]) => task), deviceIds: boxes.filter(([, box]) => box.checked).map(([id]) => id),
      latestStartMinutes: Number(late.value), enabled: enabled.checked, revision: plan?.revision ?? 0 };
    try {
      if (plan) await api(`/api/plans/${enc(plan.id)}`, { method: "PUT", body });
      else await api(`/api/accounts/${enc(account.id)}/plans`, { method: "POST", body });
      close();
    } catch (error) { message.textContent = error.message; }
  } },
    el("label", { text: "计划名称" }, name),
    el("div", { class: "name", text: "运行周期" }), el("div", { class: "choices" }, ...days.map(([day, box]) => check(box, `周${WEEK[day - 1]}`))),
    el("label", { text: "运行时间" }, at),
    el("div", { class: "name", text: "按顺序运行的任务" }), el("div", { class: "choices" }, ...tasks.map(([task, box]) => check(box, task.name))),
    el("div", { class: "name", text: "可以在哪些设备上运行（都不选就是所有装了这个游戏的设备）" }),
    el("div", { class: "choices" }, ...boxes.map(([id, box]) => check(box, devices.find((device) => device.id === id).name))),
    el("label", { text: "到点后最晚多少分钟内开始，超过就跳过" }, late),
    check(enabled, "启用"),
    el("div", { class: "row" }, el("button", { class: "primary", type: "submit", text: plan ? "保存计划" : "新建计划" }),
      el("button", { class: "ghost", type: "button", text: "取消", onclick: close })), message);
}

/**
 * Game accounts, grouped by game, and for the chosen one its settings and plans. [view] holds what the stream sent;
 * the list and the plan rows are redrawn on change, the editors only when the user opens them.
 */
function accountsPanel(view) {
  let selected = null;
  let editing = false;
  const list = el("div", { class: "accounts" });
  const detail = el("div", { class: "account-detail" });
  const plansBox = el("div");
  const editorBox = el("div");
  const message = el("p", { class: "message", role: "status" });

  function addForm() {
    if (view.games.length === 0) return el("p", { class: "muted", text: "设备上线并上传任务包后，就能为它的游戏添加账号。" });
    const game = el("select", { "aria-label": "游戏" }, ...view.games.map((item) => el("option", { value: item.id, text: item.name })));
    const name = el("input", { type: "text", placeholder: "账号名称，如主号", required: true, maxlength: "40" });
    const uid = el("input", { type: "text", placeholder: "UID（可不填）", maxlength: "40" });
    return el("form", { class: "new-job", onsubmit: async (event) => {
      event.preventDefault(); message.textContent = "";
      try { selected = (await api("/api/accounts", { method: "POST", body: { gameId: game.value, name: name.value, uid: uid.value || null } })).id; showDetail(); }
      catch (error) { message.textContent = error.message; }
    } }, el("label", { text: "游戏" }, game), el("label", { text: "名称" }, name), el("label", { text: "UID" }, uid),
      el("button", { class: "ghost", type: "submit", text: "添加账号" }));
  }
  function renderList() {
    const groups = view.games.map((game) => [game, view.accounts.filter((account) => account.gameId === game.id)]);
    list.replaceChildren(...groups.filter(([, accounts]) => accounts.length).map(([game, accounts]) => el("div", { class: "account-group" },
      el("div", { class: "muted", text: game.name }),
      ...accounts.map((account) => el("button", { type: "button", class: `account ${account.id === selected ? "on" : ""}`, "aria-pressed": String(account.id === selected),
        onclick: () => { selected = account.id; renderList(); showDetail(); } },
        el("span", { text: account.name }), account.uid ? el("span", { class: "muted", text: `UID ${account.uid}` }) : null)))),
      ...(view.accounts.length ? [] : [el("p", { class: "muted", text: "还没有游戏账号。任务设置、计划和运行记录都归属于账号。" })]));
  }
  function renderPlans() {
    const account = view.accounts.find((item) => item.id === selected);
    if (!account) { plansBox.replaceChildren(); return; }
    const mine = view.plans.filter((plan) => plan.accountId === account.id);
    const game = view.games.find((item) => item.id === account.gameId);
    // Changes that arrive while the editor is open are drawn when it closes.
    const open = (plan) => { editing = true; editorBox.replaceChildren(planEditor(account, game, view.devices, plan, () => { editing = false; editorBox.replaceChildren(); renderPlans(); })); };
    plansBox.replaceChildren(
      ...mine.map((plan) => el("div", { class: "job" },
        el("div", { class: "grow" }, el("div", { class: "name", text: plan.name }),
          el("div", { class: "muted", text: [planRule(plan), plan.tasks.map((task) => task.name).join("、"),
            plan.deviceIds.length ? `${plan.deviceIds.length} 台设备` : "所有设备", `最晚 ${plan.latestStartMinutes} 分钟内开始`].join(" · ") })),
        el("span", { class: `pill ${plan.enabled ? "ok" : ""}`, text: plan.enabled ? "已启用" : "已停用" }),
        el("button", { class: "ghost", type: "button", text: "编辑", onclick: () => open(plan) }),
        el("button", { class: "ghost", type: "button", text: "删除", onclick: async () => {
          if (confirm(`删除计划“${plan.name}”？`)) try { await api(`/api/plans/${enc(plan.id)}`, { method: "DELETE" }); } catch (error) { alert(error.message); }
        } }))),
      ...(mine.length ? [] : [el("p", { class: "muted", text: "这个账号还没有计划。" })]),
      el("button", { class: "ghost", type: "button", text: "新建计划", onclick: () => open(null) }));
  }
  function showDetail() {
    const account = view.accounts.find((item) => item.id === selected);
    if (!account) { detail.replaceChildren(); return; }
    const game = view.games.find((item) => item.id === account.gameId);
    const name = el("input", { type: "text", value: account.name, maxlength: "40", "aria-label": "账号名称" });
    const uid = el("input", { type: "text", value: account.uid ?? "", maxlength: "40", "aria-label": "UID" });
    editing = false; editorBox.replaceChildren();
    detail.replaceChildren(
      el("div", { class: "row" }, el("h3", { class: "grow", text: `${game?.name ?? account.gameId} · ${account.name}` }),
        el("button", { class: "danger", type: "button", text: "删除账号", onclick: async () => {
          if (!confirm(`删除“${account.name}”？它的任务设置和计划也会删除，过去的作业记录保留。`)) return;
          try { await api(`/api/accounts/${enc(account.id)}`, { method: "DELETE" }); selected = null; showDetail(); } catch (error) { alert(error.message); }
        } })),
      el("form", { class: "new-job", onsubmit: async (event) => {
        event.preventDefault();
        try { await api(`/api/accounts/${enc(account.id)}`, { method: "PUT", body: { name: name.value, uid: uid.value || null } }); } catch (error) { alert(error.message); }
      } }, el("label", { text: "名称" }, name), el("label", { text: "UID" }, uid), el("button", { class: "ghost", type: "submit", text: "保存" })),
      el("h4", { text: "任务设置" }), settingsForm(account, game),
      el("h4", { text: "计划" }), plansBox, editorBox);
    renderPlans();
  }
  const node = el("div", { class: "card" }, el("div", { class: "account-layout" }, el("div", {}, list), detail), el("h4", { text: "添加账号" }), el("div", {}), message);
  const addBox = node.children[2];
  return {
    node,
    /** Called when accounts, plans, games or devices change. */
    refresh(what) {
      renderList();
      if (what === "games" || what === "init") addBox.replaceChildren(addForm());
      // A new or renamed account appears here once the server confirms it; the open detail follows, unless a plan is being edited.
      if (what === "accounts") { if (!view.accounts.some((item) => item.id === selected)) selected = null; if (!editing) showDetail(); }
      if (what === "plans" && !editing) renderPlans();
    },
  };
}

function stopRefresh() { stream?.close(); stream = null; }

async function showConsole(account) {
  const devicesBox = el("div", { class: "devices" });
  const jobsBox = el("div", { class: "card jobs" });
  const formBox = el("div");
  const status = el("p", { class: "message", role: "status", text: "正在连接…" });
  const zone = el("span", { class: "muted" });
  let formKey = "";
  const expanded = new Set();
  let devices = new Map();
  let jobs = [];
  const view = { accounts: [], plans: [], games: [], devices: [] };
  const panel = accountsPanel(view);

  // Each part is redrawn only when its own data changes, so open forms and expanded logs stay as they are.
  let devicesStale = false;
  function renderDevices() {
    const list = [...devices.values()].sort((a, b) => a.name.localeCompare(b.name, "zh-CN") || a.id.localeCompare(b.id));
    view.devices = list;
    // A select someone has open is not pulled from under them; the cards are redrawn when it loses focus.
    if (devicesBox.contains(document.activeElement)) devicesStale = true;
    else {
      devicesStale = false;
      devicesBox.replaceChildren(...(list.length ? list.map((device) => deviceCard(device, view.accounts))
        : [el("p", { class: "card muted", text: "还没有设备。在手机上打开护肝宝并登录这个账号，设备会自动出现在这里。" })]));
    }
    // Rebuilding the form while someone is choosing a task would reset their selection.
    const key = JSON.stringify([list.map((device) => [device.id, device.online, device.apps.map((app) => app.key)]), view.accounts, view.games]);
    if (key !== formKey && !formBox.contains(document.activeElement)) { formKey = key; formBox.replaceChildren(jobForm(list, view.accounts, view.games)); }
  }
  devicesBox.addEventListener("focusout", () => setTimeout(() => { if (devicesStale && !devicesBox.contains(document.activeElement)) renderDevices(); }));
  function showZone(value) {
    zone.replaceChildren(`计划时区：${value} `, el("button", { class: "link", type: "button", text: "修改", onclick: async () => {
      const next = prompt("计划按哪个时区计时？例如 Asia/Shanghai", value);
      if (next && next !== value) try { await api("/api/me/time-zone", { method: "PUT", body: { timeZone: next } }); } catch (error) { alert(error.message); }
    } }));
  }
  function renderJobs() {
    jobsBox.replaceChildren(...(jobs.length ? jobs.map((job) => jobRow(job, renderJobs, expanded)) : [el("p", { class: "empty", text: "还没有作业" })]));
  }
  function live(ok, text) { status.textContent = text; status.className = `message ${ok ? "ok" : ""}`; }

  // The browser retries a dropped stream by itself, but not after an error status (a proxy's 502 during a deploy):
  // then the stream is closed and is opened again here, a little later each time.
  let retryMs = 2000;
  function connect() {
  stopRefresh();
  stream = new EventSource("/api/events");
  stream.addEventListener("snapshot", (event) => {
    retryMs = 2000;
    const snapshot = JSON.parse(event.data);
    devices = new Map(snapshot.devices.map((device) => [device.id, device]));
    jobs = snapshot.jobs;
    Object.assign(view, { accounts: snapshot.accounts, plans: snapshot.plans, games: snapshot.games });
    renderDevices(); renderJobs(); panel.refresh("init"); showZone(snapshot.timeZone);
  });
  for (const name of ["accounts", "plans", "games"]) stream.addEventListener(name, (event) => {
    view[name] = JSON.parse(event.data);
    if (name === "accounts" || name === "games") renderDevices();
    panel.refresh(name);
  });
  stream.addEventListener("time-zone", (event) => showZone(JSON.parse(event.data).timeZone));
  stream.addEventListener("device", (event) => { const device = JSON.parse(event.data); devices.set(device.id, device); renderDevices(); });
  stream.addEventListener("device-removed", (event) => { devices.delete(JSON.parse(event.data).id); renderDevices(); });
  stream.addEventListener("jobs", (event) => { jobs = JSON.parse(event.data); renderJobs(); });
  // New log lines only matter for a job whose log is open.
  stream.addEventListener("job-log", (event) => { if (expanded.has(JSON.parse(event.data).id)) renderJobs(); });
  stream.onopen = () => live(true, "实时连接中");
  stream.onerror = async () => {
    live(false, "与服务器的连接断开了，正在重连…");
    try { await api("/api/me"); } catch (error) { if (error.status === 401) { stopRefresh(); showAuth(); return; } }
    if (stream?.readyState === EventSource.CLOSED) {
      const closed = stream;
      setTimeout(() => { if (stream === closed) connect(); }, retryMs); // not if the console was left meanwhile
      retryMs = Math.min(retryMs * 2, 60000);
    }
  };
  }
  connect();
  root.className = "shell";
  root.replaceChildren(
    el("header", { class: "top" }, el("div", { class: "grow" }, brand()), el("span", { class: "who", text: account.email ?? account.githubLogin ?? "" }),
      el("button", { class: "ghost", type: "button", text: "退出登录", onclick: async () => { await api("/api/auth/logout", { method: "POST", body: {} }); showAuth(); } })),
    el("div", { class: "row" }, el("div", { class: "grow" }, status), zone),
    el("section", {}, el("h2", { text: "设备" }), devicesBox),
    el("section", {}, el("h2", { text: "游戏账号" }), el("p", { class: "muted", text: "任务设置、计划和运行记录都属于游戏账号，不属于某台设备。" }), panel.node),
    el("section", {}, el("h2", { text: "下发作业" }), formBox),
    el("section", {}, el("h2", { text: "作业记录" }), jobsBox));
}

async function start() {
  try { await showConsole(await api("/api/me")); }
  catch (error) { if (error.status === 401) showAuth(); else root.textContent = error.message; }
}

start();
