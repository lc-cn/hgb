import { useState } from "react";
import { api, enc } from "../../api/client";
import type { Channel, ChannelKind, ChannelSave, Delivery, EventRule } from "../../api/types";
import { Button, Card, Empty, Message, Muted, Pill, Switch } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { shortTime, type Tone } from "../../format";
import { useConsole } from "../../state/context";
import s from "./Notices.module.css";

interface FieldSpec { id: string; label: string; hint: string; note: string; secret: boolean }
/** Each kind's settings, as the server checks them. */
const KINDS: [ChannelKind, string, FieldSpec[]][] = [
  ["DINGTALK", "钉钉", [
    { id: "url", label: "机器人 Webhook 地址", hint: "https://oapi.dingtalk.com/robot/send?access_token=…", note: "群设置 → 机器人 → 自定义；安全设置选“加签”或关键词“护肝宝”", secret: true },
    { id: "sign", label: "加签密钥（可不填）", hint: "SEC…", note: "保存后不再显示", secret: true }]],
  ["WECOM", "企业微信", [{ id: "url", label: "机器人 Webhook 地址", hint: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…", note: "群聊 → 添加群机器人", secret: true }]],
  ["BARK", "Bark", [
    { id: "server", label: "服务器（可不填）", hint: "https://api.day.app", note: "自建服务器填自己的地址", secret: false },
    { id: "key", label: "设备密钥", hint: "在 Bark App 里复制", note: "保存后不再显示", secret: true }]],
  ["EMAIL", "邮件", []],
  ["LOCAL", "本机通知", [{ id: "device", label: "设备", hint: "", note: "设备在线时由服务端推给护肝宝 App 弹出；设备自己发现的事件断网也会先弹出", secret: false }]],
  ["WEBHOOK", "Webhook", [
    { id: "url", label: "地址", hint: "https://", note: "以 JSON 发送事件：source、rule、title、detail、at、jobId", secret: true },
    { id: "header", label: "认证头（可不填）", hint: "Authorization: Bearer …", note: "保存后不再显示", secret: true }]],
];
const KIND_NAMES = Object.fromEntries(KINDS.map(([kind, name]) => [kind, name])) as Record<ChannelKind, string>;
const DELIVERY: Record<Delivery["state"], Tone> = { SENT: "ok", PENDING: "warn", FAILED: "bad" };

/** One delivery in words: “钉钉 · 运维群 已送达”, “重试中 2/4”, “4 次都失败：…”. */
export function deliveryText(delivery: Delivery): string {
  if (delivery.state === "SENT") return `${delivery.channelName} 已送达`;
  if (delivery.state === "PENDING") return delivery.attempts ? `${delivery.channelName} 发送失败，${shortTime(delivery.nextAt)} 重试（第 ${delivery.attempts + 1}/4 次）` : `${delivery.channelName} 发送中`;
  return `${delivery.channelName} ${delivery.attempts > 1 ? `${delivery.attempts} 次都失败` : "发送失败"}${delivery.error ? `：${delivery.error}` : ""}`;
}
export function DeliveryPill({ delivery }: { delivery: Delivery }) { return <Pill tone={DELIVERY[delivery.state]}>{deliveryText(delivery)}</Pill>; }

/**
 * 渠道: where notices are sent. Every kind is sent by the server; a secret is shown only as “已填写”, and left blank
 * when editing it keeps the saved one. A test uses the draft and saves nothing.
 */
export function Channels({ channels, rules }: { channels: Channel[]; rules: EventRule[] }) {
  const [editing, setEditing] = useState<Channel | "new" | null>(null);
  const names = Object.fromEntries(rules.map((rule) => [rule.ruleId, rule.name]));
  const remove = (channel: Channel) => {
    if (!confirm(`删除“${channel.name}”？还没发出的通知不会再发到这里。`)) return;
    orAlert(() => api(`/api/notifications/channels/${enc(channel.id)}`, { method: "DELETE" }));
  };
  const toggle = (channel: Channel, enabled: boolean) => orAlert(() => api(`/api/notifications/channels/${enc(channel.id)}`, { method: "PUT",
    body: { kind: channel.kind, name: channel.name, enabled, rules: channel.rules, fields: channel.fields } }));
  return (
    <>
      <div className={s.filter}>
        <Muted as="span" className={s.grow}>渠道由服务端发送，设备掉线也能收到。发送失败会在 1、5、15 分钟后自动重试，都失败可以在事件记录里重发；投递结果不影响任务本身。</Muted>
        {editing === null && <Button variant="primary" onClick={() => setEditing("new")}>添加渠道</Button>}
      </div>
      {editing !== null && <ChannelForm key={editing === "new" ? "new" : editing.id} channel={editing === "new" ? null : editing} rules={rules} done={() => setEditing(null)} />}
      {channels.length ? (
        <div className={s.channels}>
          {channels.map((channel) => (
            <Card key={channel.id} as="article" className={s.channel}>
              <div className={s.channelHead}>
                <span className={s.grow}><b className={s.channelName}>{channel.name}</b><Muted as="span">{KIND_NAMES[channel.kind]} · {channel.target}</Muted></span>
                <Switch checked={channel.enabled} label={`启用渠道：${channel.name}`} onChange={(on) => toggle(channel, on)} />
              </div>
              <div className={s.chips}>
                {channel.rules.length ? channel.rules.map((id) => <Pill key={id} tone="info">{names[id] ?? id}</Pill>) : <Muted as="span">还没有订阅规则</Muted>}
              </div>
              <Muted as="span">{channel.last ? `${shortTime(channel.last.at)}${channel.last.test ? " 测试" : ""} · ${deliveryText(channel.last)}` : "还没有发过"}</Muted>
              <div className={s.channelActions}>
                <Button onClick={() => setEditing(channel)}>编辑</Button>
                <Button variant="danger" onClick={() => remove(channel)}>删除</Button>
              </div>
            </Card>
          ))}
        </div>
      ) : editing === null && <Card><Empty>还没有渠道。添加一个，就能把开着的规则推到钉钉、企业微信、Bark、邮箱或你自己的 Webhook。</Empty></Card>}
    </>
  );
}

function ChannelForm({ channel, rules, done }: { channel: Channel | null; rules: EventRule[]; done: () => void }) {
  const { data } = useConsole();
  const [kind, setKind] = useState<ChannelKind>(channel?.kind ?? "DINGTALK");
  const [name, setName] = useState(channel?.name ?? "");
  const [values, setValues] = useState<Record<string, string>>(channel?.fields ?? {});
  const [cleared, setCleared] = useState<string[]>([]);
  const [subscribed, setSubscribed] = useState<string[]>(channel?.rules ?? rules.filter((rule) => rule.enabled).map((rule) => rule.ruleId));
  const { busy, message, run } = useAction();
  const spec = KINDS.find(([id]) => id === kind)![2];
  // Only the fields of the chosen kind are sent; a blank secret of a saved channel of this kind keeps the saved one.
  const draft = (): ChannelSave => ({ kind, name: name.trim() || KIND_NAMES[kind], enabled: channel?.enabled ?? true, rules: subscribed,
    fields: Object.fromEntries(spec.map((field) => [field.id, values[field.id] ?? ""])), clear: cleared });
  const saved = (field: FieldSpec) => channel?.kind === kind && channel.filled.includes(field.id) && !cleared.includes(field.id);
  // Switching kind starts the fields over, so a value typed for one kind is never saved into another.
  const pick = (next: ChannelKind) => { setKind(next); setValues({}); setCleared([]); };
  const test = () => run(async () => {
    const result = await api<{ ok: boolean; message: string }>("/api/notifications/channels/test", { method: "POST", body: { id: channel?.id ?? null, save: draft() } });
    if (!result.ok) throw new Error(`测试没发出去：${result.message}`);
    return "测试消息已发出，去看看收到没有";
  });
  const save = () => run(async () => {
    await api(channel ? `/api/notifications/channels/${enc(channel.id)}` : "/api/notifications/channels", { method: channel ? "PUT" : "POST", body: draft() });
    done();
  });
  const visible = rules.filter((rule) => rule.enabled || subscribed.includes(rule.ruleId));
  return (
    <Card as="section" aria-labelledby="channel-form" className={s.form}>
      <div className={s.formHead}>
        <h2 id="channel-form" className={s.formTitle}>{channel ? `编辑“${channel.name}”` : "添加渠道"}</h2>
        <Button onClick={test} disabled={busy}>测试发送</Button>
        <Button variant="primary" onClick={save} disabled={busy}>保存</Button>
        <Button onClick={done}>取消</Button>
      </div>
      <Message text={message?.text} ok={message?.ok} />
      <div role="radiogroup" aria-label="渠道类型" className={s.kinds}>
        {KINDS.map(([id, label]) => <button key={id} type="button" role="radio" aria-checked={kind === id} className={s.kind} onClick={() => pick(id)}>{label}</button>)}
      </div>
      <div className={s.fields}>
        <label className={s.field}><span>名称</span><input value={name} maxLength={40} placeholder={KIND_NAMES[kind]} onChange={(e) => setName(e.target.value)} /></label>
        {spec.map((field) => (
          <label key={field.id} className={s.field}>
            <span className={s.fieldHead}>{field.label}
              {saved(field) && field.label.includes("可不填") && <Button variant="link" onClick={() => setCleared((now) => [...now, field.id])}>清除</Button>}
              {cleared.includes(field.id) && <Muted as="span">保存后清除</Muted>}
            </span>
            {kind === "LOCAL" && field.id === "device" ? (
              <select value={values.device ?? ""} onChange={(e) => setValues((now) => ({ ...now, device: e.target.value }))}>
                <option value="">选一台设备</option>
                {data.devices.map((device) => <option key={device.id} value={device.id}>{device.name}</option>)}
              </select>
            ) : (
              <input type={field.secret ? "password" : "text"} autoComplete="off" value={values[field.id] ?? ""} placeholder={saved(field) ? "已填写，留空不改" : field.hint}
                onChange={(e) => setValues((now) => ({ ...now, [field.id]: e.target.value }))} />
            )}
            <Muted as="span">{field.note}</Muted>
          </label>
        ))}
        {kind === "EMAIL" && <Muted>发到这个账号已验证的邮箱，由护肝宝的发信服务发送，不需要发件账号。</Muted>}
      </div>
      <fieldset className={s.subscribe}>
        <legend>订阅哪些规则</legend>
        <div className={s.chips}>
          {visible.map((rule) => <button key={rule.ruleId} type="button" className={s.chip} aria-pressed={subscribed.includes(rule.ruleId)}
            onClick={() => setSubscribed((now) => now.includes(rule.ruleId) ? now.filter((id) => id !== rule.ruleId) : [...now, rule.ruleId])}>{rule.name}</button>)}
        </div>
        <Muted as="span">{visible.length ? "只列出开着的规则；关着的规则不会产生事件。" : "还没有开着的规则，先到“规则”里打开。"}</Muted>
      </fieldset>
    </Card>
  );
}
