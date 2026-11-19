const root = document.getElementById("app");
const REFRESH_MS = 5000;
let timer = null;

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
  return el("div", { class: "brand" }, el("img", { src: "logo.svg", alt: "" }), el("h1", { text: "阴阳师助手控制台" }));
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
  QUEUED: ["排队中", "info"], LEASED: ["已下发", "info"], RUNNING: ["运行中", "info"], COMPLETED: ["已完成", "ok"],
  FAILED: ["失败", "bad"], UNKNOWN: ["结果未知", "warn"], CANCELLED: ["已取消", ""],
};
const ACTIVE = new Set(["QUEUED", "LEASED", "RUNNING"]);
const time = (value) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";

function deviceCard(device, reload) {
  return el("article", { class: "card device" },
    el("div", { class: "row" }, el("b", { class: "grow", text: device.name }), el("span", { class: `pill ${device.online ? "ok" : ""}`, text: device.online ? "在线" : "离线" })),
    el("p", { class: "muted", text: device.currentTask ? `正在运行：${device.currentTask}` : device.online ? "空闲" : `最后在线：${time(device.lastSeenAt)}` }),
    el("p", { class: "muted", text: `${device.platform} · 排队 ${device.queued} 个 · 可用任务 ${device.tasks.length} 个` }),
    el("button", { class: "danger", type: "button", text: "解绑设备", onclick: async () => {
      if (!confirm(`解绑“${device.name}”？这台设备会下线，正在运行的作业结果将标记为未知。`)) return;
      await api(`/api/devices/${encodeURIComponent(device.id)}`, { method: "DELETE" }); reload();
    } }));
}

function jobRow(job, reload) {
  const [label, tone] = STATES[job.state];
  return el("div", { class: "job" },
    el("div", { class: "grow" }, el("div", { class: "name", text: job.taskName }),
      el("div", { class: "muted", text: `${job.deviceName} · ${time(job.updatedAt)}${job.detail ? ` · ${job.detail}` : ""}` })),
    el("span", { class: `pill ${tone}`, text: job.cancelRequested && ACTIVE.has(job.state) ? "取消中" : label }),
    ACTIVE.has(job.state) && !job.cancelRequested ? el("button", { class: "ghost", type: "button", text: "取消", onclick: async () => {
      await api(`/api/jobs/${encodeURIComponent(job.id)}/cancel`, { method: "POST", body: {} }); reload();
    } }) : null);
}

function jobForm(devices, reload) {
  const online = devices.filter((device) => device.tasks.length > 0);
  if (online.length === 0) return el("p", { class: "card muted", text: "设备上线并上报任务后，就可以在这里下发作业。" });
  const message = el("p", { class: "message", role: "status" });
  const taskSelect = el("select", { name: "task", required: true });
  const deviceSelect = el("select", { name: "device", required: true, onchange: fill }, ...online.map((device) => el("option", { value: device.id, text: device.name })));
  function fill() {
    const device = online.find((item) => item.id === deviceSelect.value);
    taskSelect.replaceChildren(...device.tasks.map((task) => el("option", { value: `${task.targetId}\n${task.taskId}`, text: task.name })));
  }
  fill();
  const form = el("form", { class: "card", onsubmit: async (event) => {
    event.preventDefault(); message.textContent = "";
    const [targetId, taskId] = taskSelect.value.split("\n");
    try { await api("/api/jobs", { method: "POST", body: { deviceId: deviceSelect.value, targetId, taskId } }); reload(); }
    catch (error) { message.textContent = error.message; }
  } }, el("div", { class: "new-job" }, el("label", { text: "设备" }, deviceSelect), el("label", { text: "任务" }, taskSelect),
    el("button", { class: "primary", type: "submit", text: "运行" })), message);
  return form;
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
function settingsForm(devices) {
  const usable = devices.filter((device) => device.tasks.length > 0);
  if (usable.length === 0) return el("p", { class: "card muted", text: "设备上线并上报任务后，就可以在这里修改任务设置。" });
  const message = el("p", { class: "message", role: "status" });
  const editor = el("div", { class: "settings" });
  const taskSelect = el("select", { required: true });
  const deviceSelect = el("select", { required: true, onchange: fill }, ...usable.map((device) => el("option", { value: device.id, text: device.name })));
  function fill() {
    const device = usable.find((item) => item.id === deviceSelect.value);
    taskSelect.replaceChildren(...device.tasks.map((task) => el("option", { value: `${task.targetId}\n${task.taskId}`, text: task.name })));
    editor.replaceChildren();
  }
  fill();
  taskSelect.addEventListener("change", () => editor.replaceChildren());
  async function open() {
    message.textContent = ""; message.className = "message";
    const [targetId, taskId] = taskSelect.value.split("\n");
    const path = `/api/configs/${encodeURIComponent(targetId)}/${encodeURIComponent(taskId)}`;
    let view;
    try { view = await api(`${path}?deviceId=${encodeURIComponent(deviceSelect.value)}`); }
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
        const saved = await api(path, { method: "PUT", body: { deviceId: deviceSelect.value, values, revision } });
        revision = saved.revision; pending.forEach((note) => note.remove()); message.textContent = "已保存，之后下发的作业会使用这些设置"; message.className = "message ok";
      } catch (error) { message.textContent = error.message; }
      finally { save.disabled = false; }
    } });
    editor.replaceChildren(el("p", { class: "muted", text: `任务版本 ${view.version}` }), ...rows, save);
  }
  return el("div", { class: "card" }, el("div", { class: "new-job" }, el("label", { text: "设备" }, deviceSelect), el("label", { text: "任务" }, taskSelect),
    el("button", { class: "ghost", type: "button", text: "编辑设置", onclick: open })), editor, message);
}

function stopRefresh() { clearTimeout(timer); timer = null; }

async function showConsole(account) {
  const devicesBox = el("div", { class: "devices" });
  const jobsBox = el("div", { class: "card jobs" });
  const formBox = el("div");
  const settingsBox = el("div");
  const status = el("p", { class: "message", role: "status" });
  let formKey = "";
  async function reload() {
    stopRefresh();
    try {
      const [devices, jobs] = await Promise.all([api("/api/devices"), api("/api/jobs")]);
      status.textContent = "";
      devicesBox.replaceChildren(...(devices.length ? devices.map((device) => deviceCard(device, reload))
        : [el("p", { class: "card muted", text: "还没有设备。在手机上打开助手并登录这个账号，设备会自动出现在这里。" })]));
      jobsBox.replaceChildren(...(jobs.length ? jobs.map((job) => jobRow(job, reload)) : [el("p", { class: "empty", text: "还没有作业" })]));
      // Rebuilding the form while someone is choosing a task would reset their selection.
      const key = JSON.stringify(devices.map((device) => [device.id, device.tasks]));
      if (key !== formKey) { formKey = key; formBox.replaceChildren(jobForm(devices, reload)); settingsBox.replaceChildren(settingsForm(devices)); }
    } catch (error) {
      if (error.status === 401) { showAuth(); return; }
      status.textContent = error.message;
    }
    timer = setTimeout(reload, REFRESH_MS);
  }
  root.className = "shell";
  root.replaceChildren(
    el("header", { class: "top" }, el("div", { class: "grow" }, brand()), el("span", { class: "who", text: account.email ?? account.githubLogin ?? "" }),
      el("button", { class: "ghost", type: "button", text: "退出登录", onclick: async () => { await api("/api/auth/logout", { method: "POST", body: {} }); showAuth(); } })),
    status,
    el("section", {}, el("h2", { text: "设备" }), devicesBox),
    el("section", {}, el("h2", { text: "任务设置" }), settingsBox),
    el("section", {}, el("h2", { text: "下发作业" }), formBox),
    el("section", {}, el("h2", { text: "作业记录" }), jobsBox));
  await reload();
}

async function start() {
  try { await showConsole(await api("/api/me")); }
  catch (error) { if (error.status === 401) showAuth(); else root.textContent = error.message; }
}

start();
