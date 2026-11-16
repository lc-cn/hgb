/* THROWAWAY, memory-only rule simulator. No timers, network, input, channels or delivery. */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.YysEventRulesSimulator = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  var catalog = [
    { id: 'execution-failed', name: '连续执行失败', kind: 'count', recommendedThresholdCount: 1 },
    { id: 'unknown-result', name: '结果待核对', kind: 'instant' },
    { id: 'queue-wait', name: '排队等待过久', kind: 'duration', recommendedThresholdMs: 600000 },
    { id: 'no-progress', name: '执行无进展', kind: 'duration', recommendedThresholdMs: 300000 },
    { id: 'condition-lost', name: '运行条件中断', kind: 'instant' },
    { id: 'resource-shortage', name: '资源或容量受限', kind: 'duration', recommendedThresholdMs: 600000 },
    { id: 'vision-unavailable', name: '视觉服务异常', kind: 'duration', recommendedThresholdMs: 120000 },
    { id: 'task-started', name: '任务开始', kind: 'instant' },
    { id: 'task-succeeded', name: '任务成功', kind: 'instant' },
    { id: 'plan-started', name: '计划开始', kind: 'instant' },
    { id: 'plan-completed', name: '计划结束', kind: 'result' }
  ];
  var clone = function (v) { return JSON.parse(JSON.stringify(v)); };
  var nonempty = function (v) { return typeof v === 'string' && v.trim() !== ''; };
  function canonical(v) {
    if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
    if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map(function (k) { return JSON.stringify(k) + ':' + canonical(v[k]); }).join(',') + '}';
    return JSON.stringify(v);
  }
  function create() {
    var rules = catalog.map(function (c) { return Object.assign({}, c, { enabled: false, thresholdMs: null, thresholdCount: null, results: c.kind === 'result' ? ['completed', 'partial_failed', 'failed', 'cancelled'] : [] }); });
    var events = [], episodes = new Map(), commands = new Map(), seenRaw = new Set(), failureStreaks = new Map();
    var clockMs = 0, revision = 0, eventSeq = 0, episodeSeq = 0;
    function rule(id) { return rules.find(function (r) { return r.id === id; }); }
    function result(ok, status) { return { ok: ok, status: status }; }
    function command(type, p, fn) {
      p = p || {};
      if (!nonempty(p.commandId)) return result(false, 'command_id_required');
      var fingerprint;
      try { fingerprint = type + ':' + canonical(p); } catch (_) { return result(false, 'invalid_command'); }
      var prior = commands.get(p.commandId);
      if (prior) return prior.fingerprint === fingerprint ? clone(prior.result) : result(false, 'command_conflict');
      var answer = fn(p);
      commands.set(p.commandId, { fingerprint: fingerprint, result: clone(answer) });
      return answer;
    }
    function emit(r, e) {
      if (e.emitted || !r.enabled) return;
      if (r.kind === 'duration' && (r.thresholdMs === null || e.elapsedMs < r.thresholdMs)) return;
      if (r.kind === 'count' && (r.thresholdCount === null || e.count < r.thresholdCount)) return;
      if (r.kind === 'result' && !r.results.includes(e.detail.result)) return;
      var item = { id: 'rule-event-' + (++eventSeq), type: r.id, ruleId: r.id, name: r.name,
        sourceKey: e.sourceKey, atMs: clockMs, detail: Object.assign({ episodeId: e.id }, clone(e.detail || {})) };
      if (e.runId) item.runId = e.runId;
      if (r.kind === 'duration') { item.detail.elapsedMs = e.elapsedMs; item.detail.thresholdMs = r.thresholdMs; }
      if (r.kind === 'count') { item.detail.count = e.count; item.detail.thresholdCount = r.thresholdCount; }
      events.push(item); e.emitted = true; revision++;
    }
    function key(id, source) { return id + ':' + source; }
    function update(id, source, active, detail, runId, accumulating) {
      var k = key(id, source), e = episodes.get(k), r = rule(id);
      if (!active) { if (e) { episodes.delete(k); revision++; } return; }
      if (!e) {
        e = { id: 'episode-' + (++episodeSeq), ruleId: id, sourceKey: source, runId: runId,
          detail: detail || {}, elapsedMs: 0, count: 0, lastAtMs: clockMs, accumulating: accumulating !== false, emitted: false };
        episodes.set(k, e); revision++;
      } else {
        var delta = clockMs - e.lastAtMs;
        if (e.accumulating && accumulating !== false) e.elapsedMs += delta;
        e.lastAtMs = clockMs; e.accumulating = accumulating !== false;
        e.detail = detail || e.detail; e.runId = runId || e.runId;
        if (delta > 0) revision++;
      }
      emit(r, e);
      return e;
    }
    function fact(id, source, detail, runId) {
      // Discrete lifecycle facts are evaluated once at consumption, never on later configuration.
      update(id, source, true, detail, runId);
      episodes.delete(key(id, source));
    }
    function configure(input) {
      return command('configure', input, function (p) {
        var r = rule(p.ruleId);
        if (!r) return result(false, 'invalid_rule');
        if (typeof p.enabled !== 'boolean') return result(false, 'enabled_required');
        var duration = p.thresholdMs === undefined ? r.thresholdMs : p.thresholdMs;
        var count = p.thresholdCount === undefined ? r.thresholdCount : p.thresholdCount;
        var selections = p.results === undefined ? r.results : p.results;
        if (r.kind === 'duration' && (duration !== null && (!Number.isFinite(duration) || duration < 0))) return result(false, 'invalid_threshold');
        if (r.kind === 'count' && (count !== null && (!Number.isSafeInteger(count) || count < 1))) return result(false, 'invalid_threshold');
        if (p.enabled && ((r.kind === 'duration' && duration === null) || (r.kind === 'count' && count === null))) return result(false, 'threshold_required');
        if (r.kind === 'result' && (!Array.isArray(selections) || !selections.length || selections.some(function (v) { return !['completed', 'partial_failed', 'failed', 'cancelled'].includes(v); }))) return result(false, 'invalid_results');
        r.enabled = p.enabled;
        if (r.kind === 'duration') r.thresholdMs = duration;
        if (r.kind === 'count') r.thresholdCount = count;
        if (r.kind === 'result') r.results = Array.from(new Set(selections));
        revision++;
        episodes.forEach(function (e) { if (e.ruleId === r.id) emit(r, e); });
        return result(true, 'configured');
      });
    }
    function setCondition(input) {
      return command('setCondition', input, function (p) {
        if (!['condition-lost', 'resource-shortage', 'vision-unavailable'].includes(p.ruleId)) return result(false, 'invalid_condition_rule');
        if (!nonempty(p.sourceKey) || typeof p.active !== 'boolean') return result(false, 'invalid_condition');
        update(p.ruleId, p.sourceKey, p.active, { reason: nonempty(p.reason) ? p.reason : '', name: nonempty(p.name) ? p.name : p.sourceKey });
        revision++;
        return result(true, 'updated');
      });
    }
    function observe(snapshot) {
      if (!snapshot || !Number.isFinite(snapshot.logicalTimeMs) || snapshot.logicalTimeMs < clockMs) return result(false, 'invalid_clock');
      var nextClock = snapshot.logicalTimeMs;
      if (nextClock !== clockMs) { clockMs = nextClock; revision++; }
      var scheduler = snapshot.scheduler || {};
      var frozen = scheduler.manualHold === true || ['manual_hold', 'reconciling'].includes(scheduler.mode);
      var runs = Array.isArray(snapshot.runs) ? snapshot.runs : [];
      var active = snapshot.active;
      var desired = new Set();
      runs.forEach(function (run) {
        if (!run || !nonempty(run.runId)) return;
        var unknownActive = !!run.unresolved;
        if (unknownActive) desired.add(key('unknown-result', run.runId));
        update('unknown-result', run.runId, unknownActive, { reason: unknownActive ? run.unresolved.reason || '结果待核对' : '' }, run.runId);
        var waiting = run.status === 'queued' && run.firstDispatchedAtMs === null;
        if (waiting) desired.add(key('queue-wait', run.runId));
        update('queue-wait', run.runId, waiting, { name: run.name || run.runId }, run.runId, !frozen);
      });
      var progressKey = active && active.runId + ':' + active.executorGeneration + ':' + (active.cursor && active.cursor.completedSteps);
      var expectedWait = active && (active.expectedWait === true || snapshot.expectedWait === true);
      var executable = active && active.status === 'running' && !active.unresolved && !expectedWait &&
        !['preempted', 'awaiting_reconcile', 'revoking', 'stopping'].includes(active.phase);
      if (executable) {
        desired.add(key('no-progress', progressKey));
        update('no-progress', progressKey, true, { completedSteps: active.cursor && active.cursor.completedSteps, generation: active.executorGeneration }, active.runId, !frozen);
      }
      Array.from(episodes.entries()).forEach(function (pair) {
        var e = pair[1];
        if (['unknown-result', 'queue-wait', 'no-progress'].includes(e.ruleId) && !desired.has(pair[0])) {
          episodes.delete(pair[0]); revision++;
        } else if (['condition-lost', 'resource-shortage', 'vision-unavailable'].includes(e.ruleId)) {
          update(e.ruleId, e.sourceKey, true, e.detail, e.runId);
        }
      });
      (Array.isArray(snapshot.events) ? snapshot.events : []).forEach(function (raw) {
        var rawId = raw && (raw.id || raw.eventId);
        if (!nonempty(rawId) || seenRaw.has(rawId)) return;
        if (!['run_started', 'task_started', 'task_finished', 'run_finished'].includes(raw.type)) { seenRaw.add(rawId); return; }
        var run = runs.find(function (r) { return r.runId === raw.runId; });
        var ref = run && run.ref || raw.ref;
        if (!ref || !['task', 'plan'].includes(ref.kind) || !nonempty(ref.id)) return;
        seenRaw.add(rawId);
        var source = ref.kind + ':' + ref.id;
        var detail = { executionSource: source };
        if (raw.taskId !== undefined) detail.taskId = raw.taskId;
        if (raw.taskIndex !== undefined) detail.taskIndex = raw.taskIndex;
        if (raw.type === 'task_started') {
          fact('task-started', rawId, detail, raw.runId);
          return;
        }
        if (raw.type === 'task_finished') {
          if (raw.result === 'completed') fact('task-succeeded', rawId, Object.assign({ result: raw.result }, detail), raw.runId);
          return;
        }
        if (raw.type === 'run_started') {
          if (ref.kind === 'plan') fact('plan-started', rawId, detail, raw.runId);
          return;
        }
        if (!['completed', 'partial_failed', 'failed', 'cancelled'].includes(raw.result)) return;
        if (ref.kind === 'plan') fact('plan-completed', rawId, Object.assign({ result: raw.result }, detail), raw.runId);
        if (raw.result === 'completed') {
          failureStreaks.delete(source); update('execution-failed', source, false);
        } else if (['failed', 'partial_failed'].includes(raw.result)) {
          var count = (failureStreaks.get(source) || 0) + 1;
          failureStreaks.set(source, count);
          var e = update('execution-failed', source, true, { result: raw.result }, raw.runId);
          e.count = count; emit(rule('execution-failed'), e); revision++;
        }
      });
      return result(true, 'observed');
    }
    function getSnapshot() { return clone({ rules: rules, events: events, clockMs: clockMs, revision: revision }); }
    return Object.freeze({ configure: configure, observe: observe, setCondition: setCondition, getSnapshot: getSnapshot });
  }
  catalog.forEach(Object.freeze); Object.freeze(catalog);
  return Object.freeze({ catalog: catalog, create: create });
});
