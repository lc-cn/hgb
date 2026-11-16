/*
 * THROWAWAY REVIEW SIMULATOR — not an Android/game executor.
 * Event driven only: no network, real input, wall clock, timers, or persistence.
 * All scheduling, run cursors and input ownership live in this file.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.YysExecutionSimulator = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var TERMINAL = ['completed', 'partial_failed', 'failed', 'cancelled'];
  var copy = function (value) { return JSON.parse(JSON.stringify(value)); };
  var text = function (value) { return typeof value === 'string' && value.trim() !== ''; };
  var version = function (value) {
    return text(value) || (typeof value === 'number' && Number.isFinite(value));
  };
  function canonical(value) {
    if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
    if (value && typeof value === 'object') {
      return '{' + Object.keys(value).sort().map(function (key) {
        return JSON.stringify(key) + ':' + canonical(value[key]);
      }).join(',') + '}';
    }
    return JSON.stringify(value);
  }
  function rejected(code, message) { return { ok: false, status: code, message: message }; }
  function metadata(value) {
    if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
    if (typeof value === 'number') return Number.isFinite(value) ? value : null;
    if (Array.isArray(value)) return value.map(metadata);
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.keys(value).filter(function (key) {
        return !/^(api[_-]?key|key|token|password|secret|authorization|credentials?)$/i.test(key);
      }).map(function (key) { return [key, metadata(value[key])]; }));
    }
    return null;
  }

  function create() {
    var runs = [];
    var events = [];
    var commands = new Map();
    var occurrences = new Map();
    var observers = new Set();
    var publicationQueue = [];
    var publishing = false;
    var activeId = null;
    var inputOwner = null;
    var revision = 0;
    var eventSeq = 0;
    var enqueueSeq = 0;
    var runSeq = 0;
    var inputSeq = 0;
    var checkpointSeq = 0;
    var reconcileSeq = 0;
    var generationSeq = 0;
    var removalSeq = 0;
    var mode = 'running';
    var manualHold = false;
    var holdReason = null;
    var pendingHandoff = null;
    var reconcileTicket = null;
    var lastReconcileObservation = null;
    var pendingRemoval = null;
    var logicalTimeMs = 0;
    var observedIds = new Set();

    function event(type, data) {
      var id = 'event-' + (++eventSeq);
      var e = Object.assign({ id: id, eventId: id, seq: eventSeq, type: type }, data || {});
      events.push(e);
      return e;
    }
    function find(id) { return runs.find(function (r) { return r.runId === id; }) || null; }
    function active() { return find(activeId); }
    function compare(a, b) { return b.priority - a.priority || a.enqueueSeq - b.enqueueSeq; }
    function isTerminal(r) { return TERMINAL.includes(r.status); }
    function hasUnknown() { return runs.some(function (r) { return r.unresolved !== null; }); }
    function ready(r) { return r.eligibility.status === 'ready' && !r.unresolved && !r.cancelRequested; }
    function pendingRuns() {
      return runs.filter(function (r) { return ['queued', 'preempted'].includes(r.status); }).sort(compare);
    }
    function candidates(includeActive) {
      var items = pendingRuns().filter(ready);
      var a = active();
      if (includeActive && a && !isTerminal(a) && ready(a) && !items.includes(a)) items.push(a);
      return items.sort(compare);
    }
    function safe(r) {
      return !inputOwner && !!r.checkpoint && r.checkpoint.resumable &&
        r.atCheckpoint && !r.unresolved && !r.cancelRequested;
    }
    function currentTask(r) { return r.tasks[r.cursor.taskIndex] || null; }
    function currentStep(r) {
      var t = currentTask(r);
      return t ? t.steps[r.cursor.stepIndex] || null : null;
    }
    function workerContextError(r, p) {
      if (p.generation !== undefined && p.generation !== r.executorGeneration) return rejected('stale_executor', '执行器代际已变化。');
      if (p.inputId !== undefined && (!inputOwner || inputOwner.runId !== r.runId || inputOwner.inputId !== p.inputId)) return rejected('input_conflict', '该输入已结算或不属于当前步骤。');
      if ((p.taskIndex !== undefined && p.taskIndex !== r.cursor.taskIndex) ||
          (p.stepIndex !== undefined && p.stepIndex !== r.cursor.stepIndex) ||
          (p.taskId !== undefined && (!currentTask(r) || p.taskId !== currentTask(r).id))) return rejected('cursor_conflict', '回调不属于当前子任务或步骤。');
      return null;
    }
    function runPhase(r) {
      if (pendingRemoval && pendingRemoval.runId === r.runId) return 'revoking';
      if (r.cancelRequested && inputOwner && inputOwner.runId === r.runId) return 'stopping';
      if (isTerminal(r)) return r.status;
      if (r.unresolved) return 'unknown';
      if (r.runId === activeId && manualHold) {
        return inputOwner && inputOwner.runId === r.runId ? 'pausing' : 'paused';
      }
      if (reconcileTicket && (r.runId === activeId ||
          (r.status === 'preempted' && reconcileTicket.candidateRunIds.includes(r.runId)))) {
        return 'awaiting_reconcile';
      }
      if (r.status === 'preempted' || r.status === 'queued') return r.status;
      if (inputOwner && inputOwner.runId === r.runId) return 'input_pending';
      if (!currentTask(r)) return 'awaiting_completion';
      return 'ready';
    }
    function runSnapshot(r) {
      var result = copy(r);
      result.phase = runPhase(r);
      result.taskCursor = result.cursor.taskIndex;
      result.stepCursor = result.cursor.stepIndex;
      result.currentTaskId = currentTask(r) ? currentTask(r).id : null;
      result.currentStepId = currentStep(r) ? currentStep(r).id : null;
      result.completedTasks = r.tasks.filter(function (t) { return t.result !== 'pending'; }).length;
      result.totalTasks = r.tasks.length;
      result.totalSteps = r.tasks.reduce(function (n, t) { return n + t.steps.length; }, 0);
      return result;
    }
    function getSnapshot() {
      return {
        schemaVersion: 1,
        revision: revision,
        eventSeq: eventSeq,
        logicalTimeMs: logicalTimeMs,
        scheduler: {
          mode: mode,
          manualHold: manualHold,
          holdReason: holdReason,
          pendingHandoff: pendingHandoff ? copy(pendingHandoff) : null,
          reconcile: reconcileTicket ? copy(reconcileTicket) : null,
          removal: pendingRemoval ? copy(pendingRemoval) : null,
          blockedReason: hasUnknown() ? 'unknown_result' :
            (mode === 'blocked' ? 'waiting_conditions' : null)
        },
        active: active() ? runSnapshot(active()) : null,
        queue: pendingRuns().map(runSnapshot),
        preempted: runs.filter(function (r) { return r.status === 'preempted'; }).sort(compare).map(runSnapshot),
        inputOwner: inputOwner ? copy(inputOwner) : null,
        runs: runs.map(runSnapshot),
        events: copy(events)
      };
    }
    function publish() {
      publicationQueue.push(getSnapshot());
      if (publishing) return;
      publishing = true;
      try {
        while (publicationQueue.length) {
          var snapshot = publicationQueue.shift();
          Array.from(observers).forEach(function (listener) {
            try { listener(copy(snapshot)); } catch (_) { /* A view cannot control execution by throwing. */ }
          });
        }
      } finally { publishing = false; }
    }
    function perform(type, input, fn) {
      input = input || {};
      if (!text(input.commandId)) return rejected('command_id_required', '每个事件需要 commandId。');
      var fingerprint;
      try { fingerprint = type + ':' + canonical(input); }
      catch (_) { return rejected('invalid_command', '命令必须是可序列化的演示数据。'); }
      var previous = commands.get(input.commandId);
      if (previous) {
        if (previous.fingerprint !== fingerprint) return rejected('command_conflict', '同一 commandId 不能用于不同事件。');
        return copy(previous.result);
      }
      var before = eventSeq;
      var result = fn(input);
      commands.set(input.commandId, { fingerprint: fingerprint, result: copy(result) });
      if (eventSeq !== before) { revision++; publish(); }
      return copy(result);
    }
    function normalizeEligibility(e) {
      if (!e || !['ready', 'blocked', 'unknown'].includes(e.status)) {
        return { status: 'unknown', reason: '等待新的现场观察', observationId: null };
      }
      if (text(e.observationId)) observedIds.add(e.observationId);
      return { status: e.status, reason: text(e.reason) ? e.reason : '', observationId: text(e.observationId) ? e.observationId : null };
    }
    function normalizeTask(t) {
      if (!t || !text(t.id)) return { error: rejected('invalid_task', '任务需要稳定身份。') };
      if (!Number.isSafeInteger(t.priority)) return { error: rejected('invalid_priority', '优先级需要可精确表示的整数，不设默认值。') };
      if (!version(t.configVersion) || !version(t.packageVersion)) {
        return { error: rejected('invalid_lock', '需要明确任务配置和任务包版本。') };
      }
      if (!Array.isArray(t.steps) || !t.steps.length) return { error: rejected('invalid_steps', '任务需要至少一个已定义操作步骤。') };
      var steps = t.steps.map(function (s, index) {
        if (typeof s === 'string') return { id: 'step-' + (index + 1), label: s };
        return s && text(s.id) ? { id: s.id, label: text(s.label) ? s.label : s.id } : null;
      });
      if (steps.some(function (s) { return !s; }) || new Set(steps.map(function (s) { return s.id; })).size !== steps.length) {
        return { error: rejected('invalid_steps', '步骤身份应明确且在任务内唯一。') };
      }
      return { task: {
        id: t.id, name: text(t.name) ? t.name : t.id, priority: t.priority,
        configVersion: t.configVersion, packageVersion: t.packageVersion,
        configValues: metadata(t.configValues || {}), steps: steps, result: 'pending', failure: null,
        started: false, startedAtMs: null
      } };
    }
    function makeCheckpoint(r, resumable, observationId) {
      if (text(observationId)) observedIds.add(observationId);
      r.checkpoint = {
        id: 'checkpoint-' + (++checkpointSeq), cursor: copy(r.cursor),
        resumable: resumable === true, observationId: observationId || null
      };
      r.atCheckpoint = resumable === true;
    }
    function makeUnknown(r, inputId, reason, observationId) {
      if (text(observationId)) observedIds.add(observationId);
      return {
        inputId: inputId, cursor: copy(r.cursor),
        observationId: r.lastObservationId || r.eligibility.observationId,
        observationBarrier: Array.from(observedIds), reason: reason
      };
    }
    function startCurrentTask(r) {
      var t = currentTask(r);
      if (!t || t.started || manualHold || inputOwner || !ready(r) || r.runId !== activeId) return;
      t.started = true; t.startedAtMs = logicalTimeMs;
      event('task_started', { runId: r.runId, taskId: t.id, taskIndex: r.cursor.taskIndex });
    }
    function activate(r) {
      activeId = r.runId;
      r.status = 'running';
      r.executorGeneration = ++generationSeq;
      if (r.firstDispatchedAtMs === null) {
        r.firstDispatchedAtMs = logicalTimeMs;
        event('run_started', { runId: r.runId, ref: copy(r.ref) });
      }
      mode = 'running';
      pendingHandoff = null;
      event('run_dispatched', { runId: r.runId, cursor: copy(r.cursor), priority: r.priority });
      startCurrentTask(r);
    }
    function makeReconcile(kind) {
      var list = candidates(true);
      reconcileTicket = {
        id: 'reconcile-' + (++reconcileSeq), kind: kind,
        candidateRunIds: list.map(function (r) { return r.runId; }),
        observationBarrier: Array.from(observedIds),
        checkpointIds: Object.fromEntries(list.map(function (r) { return [r.runId, r.checkpoint.id]; }))
      };
      mode = 'reconciling';
      event('reconcile_required', { reconcileId: reconcileTicket.id, kind: kind });
    }
    function schedule() {
      if (manualHold || reconcileTicket || pendingRemoval) return;
      if (hasUnknown()) { mode = 'blocked'; pendingHandoff = null; return; }
      var a = active();
      var list = candidates(false);
      if (a) {
        if (a.cancelRequested || isTerminal(a)) return;
        if (list.length && list[0].priority > a.priority) {
          var target = list[0];
          var changed = !pendingHandoff || pendingHandoff.toRunId !== target.runId;
          pendingHandoff = { fromRunId: a.runId, toRunId: target.runId, reason: 'higher_priority' };
          mode = 'handoff';
          if (changed) event('handoff_requested', copy(pendingHandoff));
          if (!safe(a)) return;
          a.status = 'preempted';
          activeId = null;
          event('run_preempted', { runId: a.runId, checkpointId: a.checkpoint.id, byRunId: target.runId });
          if (target.hasDispatched) makeReconcile('automatic');
          else { target.hasDispatched = true; activate(target); }
        } else {
          pendingHandoff = null; mode = ready(a) ? 'running' : 'blocked';
          if (mode === 'running') startCurrentTask(a);
        }
        return;
      }
      if (inputOwner) { mode = 'blocked'; return; }
      if (!list.length) { mode = pendingRuns().length ? 'blocked' : 'running'; return; }
      if (list[0].hasDispatched) makeReconcile('automatic');
      else { list[0].hasDispatched = true; activate(list[0]); }
    }
    function terminal(r, result, deferSchedule) {
      r.status = result;
      r.terminalSeq = eventSeq + 1;
      if (r.runId === activeId) activeId = null;
      pendingHandoff = null;
      event('run_finished', { runId: r.runId, result: result, cursor: copy(r.cursor) });
      if (!deferSchedule) schedule();
    }
    function finishIfDone(r) {
      if (currentTask(r)) return false;
      var failed = r.tasks.filter(function (t) { return t.result === 'failed'; }).length;
      terminal(r, failed === r.tasks.length ? 'failed' : failed ? 'partial_failed' : 'completed');
      return true;
    }
    function advanceSuccess(r) {
      r.cursor.stepIndex++;
      r.cursor.completedSteps++;
      var task = currentTask(r);
      if (r.cursor.stepIndex >= task.steps.length) {
        task.result = 'completed';
        event('task_finished', { runId: r.runId, taskIndex: r.cursor.taskIndex, taskId: task.id, result: 'completed' });
        r.cursor.taskIndex++;
        r.cursor.stepIndex = 0;
      }
    }
    function occurrenceIdentity(value) {
      if (value === undefined || value === null) return null;
      if (text(value)) return 'key:' + value;
      if (value && text(value.scheduleId) && text(value.occurrenceId)) return canonical({ scheduleId: value.scheduleId, occurrenceId: value.occurrenceId });
      return false;
    }

    function enqueue(input) {
      return perform('enqueue', input, function (p) {
        var ref = p.ref;
        if (!ref || !['task', 'plan'].includes(ref.kind) || !text(ref.id)) return rejected('invalid_ref', '执行项需要 task 或 plan 身份。');
        var occurrenceKey = occurrenceIdentity(p.occurrenceKey);
        if (occurrenceKey === false) return rejected('invalid_occurrence', '到点事件需要稳定 scheduleId 和 occurrenceId。');
        if (occurrenceKey && occurrences.has(occurrenceKey)) {
          return { ok: true, status: 'duplicate_occurrence', runId: occurrences.get(occurrenceKey) };
        }
        var existing = runs.find(function (r) {
          return !isTerminal(r) && r.ref.kind === ref.kind && r.ref.id === ref.id;
        });
        if (existing) return Object.assign(rejected('already_present', '已在执行队列中'), { runId: existing.runId });
        if (p.accountBinding !== undefined &&
            (!p.accountBinding || !text(p.accountBinding.key) || !text(p.accountBinding.name))) {
          return rejected('invalid_account_binding', '账号归属需要明确身份和名称。');
        }
        var taskSpecs = ref.kind === 'task' ? [Object.assign({}, p, { id: ref.id })] : p.tasks;
        if (!Array.isArray(taskSpecs) || !taskSpecs.length) return rejected('invalid_plan', '计划至少需要一个已锁定任务。');
        if (ref.kind === 'plan' && !version(p.planRevision)) return rejected('invalid_lock', '需要明确计划配置版本。');
        var normalized = taskSpecs.map(normalizeTask);
        var invalid = normalized.find(function (t) { return t.error; });
        if (invalid) return invalid.error;
        var tasks = normalized.map(function (t) { return t.task; });
        var priority = tasks.reduce(function (total, t) { return total + t.priority; }, 0);
        if (!Number.isSafeInteger(priority)) return rejected('invalid_priority', '计划优先级总和不能精确表示。');
        var r = {
          runId: 'run-' + (++runSeq), ref: { kind: ref.kind, id: ref.id },
          name: text(p.name) ? p.name : ref.id, priority: priority,
          accountBinding: p.accountBinding ? { key: p.accountBinding.key, name: p.accountBinding.name } : null,
          enqueueSeq: ++enqueueSeq, status: 'queued', tasks: tasks,
          enqueuedAtMs: logicalTimeMs, firstDispatchedAtMs: null,
          planRevision: ref.kind === 'plan' ? p.planRevision : null,
          planConstraints: ref.kind === 'plan' ? metadata(p.planConstraints || {}) : null,
          configVersions: Object.fromEntries(tasks.map(function (t) { return [t.id, t.configVersion]; })),
          packageVersions: Object.fromEntries(tasks.map(function (t) { return [t.id, t.packageVersion]; })),
          taskLocks: tasks.map(function (t, index) { return { taskIndex: index, taskId: t.id, configVersion: t.configVersion, packageVersion: t.packageVersion, priority: t.priority }; }),
          cursor: { taskIndex: 0, stepIndex: 0, completedSteps: 0 },
          eligibility: normalizeEligibility(p.eligibility || p.admission),
          occurrenceKey: occurrenceKey, cancelRequested: false, cancelReason: null, hasDispatched: false,
          checkpoint: null, atCheckpoint: true, unresolved: null,
          failures: [], lastObservationId: null
        };
        r.executorGeneration = 0;
        makeCheckpoint(r, true, null);
        runs.push(r);
        if (occurrenceKey) occurrences.set(occurrenceKey, r.runId);
        event('run_enqueued', { runId: r.runId, ref: copy(r.ref), priority: priority, enqueueSeq: r.enqueueSeq });
        schedule();
        return { ok: true, status: r.runId === activeId ? 'started' : 'enqueued', runId: r.runId };
      });
    }
    function setEligibility(input) {
      return perform('setEligibility', input, function (p) {
        var r = find(p.runId);
        if (!r || isTerminal(r)) return rejected('invalid_run', '运行不存在或已结束。');
        if (!['ready', 'blocked', 'unknown'].includes(p.status)) return rejected('invalid_eligibility', '需要明确现场条件状态。');
        if (p.status === 'ready' && !text(p.observationId)) return rejected('observation_required', '就绪状态需要新的观察身份。');
        r.eligibility = normalizeEligibility(p);
        event('eligibility_changed', { runId: r.runId, status: p.status, observationId: r.eligibility.observationId });
        schedule();
        return { ok: true, status: 'updated', runId: r.runId };
      });
    }
    function pause(input) {
      return perform('pause', input, function () {
        manualHold = true; holdReason = 'pause'; mode = 'manual_hold';
        reconcileTicket = null; pendingHandoff = null;
        event('manual_hold', { reason: 'pause', runId: activeId });
        return { ok: true, status: inputOwner ? 'pausing' : 'paused', runId: activeId };
      });
    }
    function stop(input) {
      return perform('stop', input, function (p) {
        manualHold = true; holdReason = 'stop'; mode = 'manual_hold';
        reconcileTicket = null; pendingHandoff = null;
        event('manual_hold', { reason: 'stop', runId: activeId });
        var r = active();
        if (p.runId && p.runId !== activeId) return rejected('run_conflict', '当前运行已经变化；队列仍保持人工暂停。');
        if (!r) return { ok: true, status: 'held', runId: null };
        if (!r.cancelRequested) r.cancelReason = '用户停止本轮';
        r.cancelRequested = true;
        event('run_cancel_requested', { runId: r.runId });
        if (!inputOwner && !pendingRemoval) terminal(r, 'cancelled');
        return { ok: true, status: inputOwner ? 'stopping' : 'cancelled', runId: r.runId };
      });
    }
    function resume(input) {
      return perform('resume', input, function () {
        if (pendingRemoval) return rejected('revocation_pending', '等待独立输入通道确认撤销静默。');
        if (inputOwner) return rejected('input_not_quiescent', '等待旧输入静默后才能请求继续。');
        if (hasUnknown()) return rejected('needs_result_reconciliation', '存在未知结果，需要先核对；不会按失败跳过。');
        manualHold = false; holdReason = null;
        makeReconcile('manual');
        return { ok: true, status: 'reconciling', reconcileId: reconcileTicket.id };
      });
    }
    function confirmResume(input) {
      return perform('confirmResume', input, function (p) {
        if (manualHold || !reconcileTicket) return rejected('no_reconcile_request', '没有等待确认的恢复请求。');
        if (p.reconcileId !== reconcileTicket.id) return rejected('reconcile_conflict', '核对请求已经变化。');
        if (!text(p.observationId) || p.observationId === lastReconcileObservation ||
            reconcileTicket.observationBarrier.includes(p.observationId) || p.confirmed !== true) {
          return rejected('new_observation_required', '需要显式的新观察与检查点核对确认。');
        }
        if (inputOwner || hasUnknown()) return rejected('unsafe_resume', '输入或未知结果尚未核对。');
        var list = candidates(true);
        if (!list.length) {
          reconcileTicket = null; mode = pendingRuns().length || activeId ? 'blocked' : 'running';
          event('resume_waiting', { observationId: p.observationId });
          return { ok: true, status: 'waiting', runId: null };
        }
        var selected = list[0];
        if (!safe(selected)) return rejected('checkpoint_not_resumable', '目标运行没有可安全恢复的检查点。');
        var a = active();
        if (a && a.runId !== selected.runId) {
          if (!safe(a)) return rejected('checkpoint_not_resumable', '旧运行尚未到达安全检查点。');
          a.status = 'preempted';
          event('run_preempted', { runId: a.runId, checkpointId: a.checkpoint.id, byRunId: selected.runId });
        }
        selected.lastObservationId = p.observationId;
        selected.checkpoint.observationId = p.observationId;
        selected.hasDispatched = true;
        lastReconcileObservation = p.observationId;
        observedIds.add(p.observationId);
        reconcileTicket = null;
        activate(selected);
        return { ok: true, status: 'resumed', runId: selected.runId };
      });
    }
    function step(input) {
      return perform('step', input, function (p) {
        var r = active();
        if (!r || (p.runId && r.runId !== p.runId)) return rejected('run_conflict', '当前运行不匹配。');
        if (p.generation !== undefined && p.generation !== r.executorGeneration) return rejected('stale_executor', '执行器代际已变化。');
        if (manualHold || mode === 'reconciling' || pendingRemoval || hasUnknown() || r.cancelRequested) return rejected('input_gate_closed', '当前不允许提交新的游戏操作。');
        if (inputOwner) return rejected('input_busy', '旧输入尚未静默。');
        if (!ready(r)) return rejected('waiting_conditions', r.eligibility.reason || '等待现场条件。');
        var s = currentStep(r), t = currentTask(r);
        if (!s || !t) return rejected('not_complete', '没有待提交步骤，请核对运行结果。');
        inputOwner = {
          inputId: 'input-' + (++inputSeq), runId: r.runId,
          generation: r.executorGeneration,
          taskIndex: r.cursor.taskIndex, taskId: t.id,
          stepIndex: r.cursor.stepIndex, stepId: s.id
        };
        r.atCheckpoint = false;
        event('input_submitted', copy(inputOwner));
        return Object.assign({ ok: true, status: 'input_pending' }, copy(inputOwner));
      });
    }
    function checkpoint(input) {
      return perform('checkpoint', input, function (p) {
        var r = find(p.runId);
        if (!r || r.runId !== activeId) return rejected('run_conflict', '检查点不属于当前运行。');
        var contextError = workerContextError(r, p);
        if (contextError) return contextError;
        if (pendingRemoval && pendingRemoval.runId === r.runId) return rejected('stale_executor', '旧执行器已撤销，需独立输入通道确认。');
        if (p.quiescent !== true) return rejected('input_not_quiescent', '检查点需明确旧输入已静默。');
        var owner = inputOwner;
        if (owner && (owner.runId !== r.runId || p.inputId !== owner.inputId || owner.generation !== r.executorGeneration ||
            (p.generation !== undefined && p.generation !== owner.generation))) return rejected('input_conflict', '输入回执身份或代际不匹配。');
        if (!owner && p.outcome) return rejected('input_required', '没有对应输入，不能凭空推进步骤。');
        if (owner && !['success', 'no_effect', 'unknown'].includes(p.outcome)) return rejected('outcome_required', '需要明确输入的业务核对结果。');
        if (owner) {
          inputOwner = null;
          event('input_quiescent', { runId: r.runId, inputId: owner.inputId, outcome: p.outcome });
          if (p.outcome === 'unknown') {
            r.unresolved = makeUnknown(r, owner.inputId, text(p.reason) ? p.reason : '业务结果未知', p.observationId);
            event('result_unknown', { runId: r.runId, inputId: owner.inputId, cursor: copy(r.cursor) });
          } else if (r.unresolved) {
            /* Generic checkpoints cannot erase an unknown business outcome. */
          } else if (p.outcome === 'success') advanceSuccess(r);
        }
        makeCheckpoint(r, p.resumable === true && !r.unresolved, p.observationId);
        event('checkpoint_recorded', { runId: r.runId, checkpointId: r.checkpoint.id, resumable: r.checkpoint.resumable });
        if (r.cancelRequested) terminal(r, 'cancelled');
        else if (r.unresolved) { r.status = 'unknown'; mode = manualHold ? 'manual_hold' : 'blocked'; pendingHandoff = null; }
        else if (!finishIfDone(r)) schedule();
        return { ok: true, status: r.unresolved ? 'unknown' : r.status, runId: r.runId, checkpointId: r.checkpoint.id };
      });
    }
    function complete(input) {
      return perform('complete', input, function (p) {
        var r = find(p.runId);
        if (!r) return rejected('invalid_run', '运行不存在。');
        var contextError = workerContextError(r, p);
        if (contextError) return contextError;
        if (pendingRemoval && pendingRemoval.runId === r.runId) return rejected('stale_executor', '旧执行器已撤销。');
        if (isTerminal(r)) return { ok: true, status: r.status, runId: r.runId };
        if (r.runId !== activeId) return rejected('run_conflict', '只能结算当前运行。');
        if (inputOwner || r.unresolved) return rejected('unsafe_completion', '输入或业务结果尚未核对。');
        if (currentTask(r)) return rejected('not_complete', '仍有未完成步骤；完成事件不能跳过任务操作。');
        finishIfDone(r);
        return { ok: true, status: r.status, runId: r.runId };
      });
    }
    function fail(input) {
      return perform('fail', input, function (p) {
        var r = find(p.runId);
        if (!r || r.runId !== activeId) return rejected('run_conflict', '失败事件不属于当前运行。');
        var contextError = workerContextError(r, p);
        if (contextError) return contextError;
        if (pendingRemoval && pendingRemoval.runId === r.runId) return rejected('stale_executor', '旧执行器已撤销。');
        if (p.confirmed !== true || !text(p.reason)) return rejected('failure_confirmation_required', '需要明确且已确认的失败原因。');
        if (r.unresolved) return rejected('needs_result_reconciliation', '未知结果不能转成失败后跳过。');
        var scope = p.scope || 'task';
        if (!['task', 'run'].includes(scope)) return rejected('invalid_scope', '失败范围需要 task 或 run。');
        if (scope === 'task' && r.ref.kind === 'plan' && !currentTask(r)) return rejected('not_complete', '没有当前子任务可标记失败。');
        if (inputOwner) {
          if (p.inputId !== inputOwner.inputId || p.quiescent !== true) return rejected('input_not_quiescent', '旧输入需匹配并确认静默。');
          event('input_quiescent', { runId: r.runId, inputId: inputOwner.inputId, outcome: 'confirmed_failure' });
          inputOwner = null;
        }
        r.failures.push({ scope: scope, taskIndex: r.cursor.taskIndex, reason: p.reason, eventSeq: eventSeq + 1 });
        event('failure_recorded', { runId: r.runId, scope: scope, taskIndex: r.cursor.taskIndex, reason: p.reason });
        if (r.cancelRequested) { terminal(r, 'cancelled'); return { ok: true, status: r.status, runId: r.runId }; }
        if (scope === 'run' || r.ref.kind === 'task') {
          var standalone = currentTask(r);
          if (standalone) { standalone.result = 'failed'; standalone.failure = p.reason; }
          terminal(r, 'failed');
        } else {
          var t = currentTask(r);
          if (!t) return rejected('not_complete', '没有当前子任务可标记失败。');
          t.result = 'failed'; t.failure = p.reason;
          event('task_finished', { runId: r.runId, taskIndex: r.cursor.taskIndex, taskId: t.id, result: 'failed' });
          r.cursor.taskIndex++; r.cursor.stepIndex = 0;
          makeCheckpoint(r, true, p.observationId);
          if (!finishIfDone(r)) schedule();
        }
        return { ok: true, status: r.status, runId: r.runId };
      });
    }
    function unknown(input) {
      return perform('unknown', input, function (p) {
        var r = find(p.runId);
        if (!r || r.runId !== activeId) return rejected('run_conflict', '未知结果不属于当前运行。');
        var contextError = workerContextError(r, p);
        if (contextError) return contextError;
        if (pendingRemoval && pendingRemoval.runId === r.runId) return rejected('stale_executor', '旧执行器已撤销。');
        if (inputOwner && (p.inputId !== inputOwner.inputId || inputOwner.runId !== r.runId)) return rejected('input_conflict', '未知结果输入身份不匹配。');
        r.unresolved = r.unresolved || makeUnknown(r, inputOwner ? inputOwner.inputId : null,
          text(p.reason) ? p.reason : '结果未知，需要核对', p.observationId);
        if (inputOwner && p.quiescent === true) {
          event('input_quiescent', { runId: r.runId, inputId: inputOwner.inputId, outcome: 'unknown' });
          inputOwner = null;
        }
        r.atCheckpoint = false;
        r.status = 'unknown';
        mode = manualHold ? 'manual_hold' : 'blocked';
        pendingHandoff = null;
        reconcileTicket = null;
        event('result_unknown', { runId: r.runId, cursor: copy(r.cursor), inputId: r.unresolved.inputId });
        return { ok: true, status: 'unknown', runId: r.runId };
      });
    }
    function removeQueued(input) {
      return perform('removeQueued', input, function (p) {
        var r = find(p.runId);
        if (!r) return rejected('invalid_run', '队列项不存在。');
        if (r.runId === activeId) {
          if (pendingRemoval) return { ok: true, status: 'revoking', runId: r.runId, revocationId: pendingRemoval.id };
          if (!r.cancelRequested) r.cancelReason = text(p.reason) ? p.reason : '用户移除';
          var revokedGeneration = r.executorGeneration;
          r.executorGeneration = ++generationSeq;
          r.cancelRequested = true;
          reconcileTicket = null; pendingHandoff = null;
          event('executor_revoked', { runId: r.runId, generation: revokedGeneration, nextGeneration: r.executorGeneration });
          if (!inputOwner) {
            event('queue_item_removed', { runId: r.runId, priorStatus: r.status, reason: r.cancelReason });
            terminal(r, 'cancelled');
            return { ok: true, status: 'cancelled', runId: r.runId };
          }
          pendingRemoval = { id: 'revocation-' + (++removalSeq), runId: r.runId,
            revokedGeneration: revokedGeneration, inputId: inputOwner.inputId, source: 'dispatcher' };
          mode = manualHold ? 'manual_hold' : 'revoking';
          event('removal_quiescence_required', { runId: r.runId, revocationId: pendingRemoval.id, inputId: inputOwner.inputId });
          return { ok: true, status: 'revoking', runId: r.runId, revocationId: pendingRemoval.id };
        }
        if (r.status === 'cancelled') return { ok: true, status: 'cancelled', runId: r.runId };
        if (!['queued', 'preempted'].includes(r.status)) return rejected('not_waiting', '仅可移除等待或自动挂起的执行项。');
        if (r.unresolved || (inputOwner && inputOwner.runId === r.runId)) return rejected('needs_reconciliation', '存在在途输入或未知结果，不能通过删行清除。');
        r.cancelReason = text(p.reason) ? p.reason : '用户移除';
        r.cancelRequested = true;
        event('queue_item_removed', { runId: r.runId, priorStatus: r.status, reason: r.cancelReason });
        if (reconcileTicket) {
          var kind = reconcileTicket.kind;
          terminal(r, 'cancelled', true);
          reconcileTicket = null;
          if (candidates(true).length) makeReconcile(kind);
          else {
            mode = pendingRuns().length || activeId ? 'blocked' : 'running';
            event('reconcile_invalidated', { reason: 'candidate_removed', runId: r.runId });
          }
        } else terminal(r, 'cancelled');
        return { ok: true, status: 'cancelled', runId: r.runId };
      });
    }
    function confirmRemovalQuiescent(input) {
      return perform('confirmRemovalQuiescent', input, function (p) {
        var pending = pendingRemoval;
        if (!pending || p.revocationId !== pending.id || p.runId !== pending.runId) return rejected('revocation_conflict', '撤销请求身份已经变化。');
        if (p.source !== 'dispatcher' || p.quiescent !== true) return rejected('dispatcher_confirmation_required', '必须由独立输入通道确认旧输入静默。');
        if (!['success', 'no_effect', 'unknown'].includes(p.outcome)) return rejected('outcome_required', '需明确在途动作核对结果；无法确认应为 unknown。');
        var r = find(p.runId);
        if (!inputOwner || inputOwner.inputId !== pending.inputId) return rejected('input_conflict', '撤销中的输入身份不匹配。');
        var owner = inputOwner;
        inputOwner = null; pendingRemoval = null;
        event('input_quiescent', { runId: r.runId, inputId: owner.inputId, outcome: p.outcome, source: 'dispatcher' });
        if (p.outcome === 'unknown' && !r.unresolved) {
          r.unresolved = makeUnknown(r, owner.inputId, '移除前在途动作结果未知', p.observationId);
          event('result_unknown', { runId: r.runId, inputId: owner.inputId, cursor: copy(r.cursor) });
        } else if (p.outcome === 'success' && !r.unresolved) advanceSuccess(r);
        event('queue_item_removed', { runId: r.runId, priorStatus: r.status, reason: r.cancelReason });
        terminal(r, 'cancelled');
        return { ok: true, status: r.unresolved ? 'cancelled_unknown' : 'cancelled', runId: r.runId };
      });
    }
    function resolveOutcome(input) {
      return perform('resolveOutcome', input, function (p) {
        var r = find(p.runId);
        if (!r || !r.unresolved) return rejected('no_unknown_result', '没有待核对结果。');
        if (p.inputId !== undefined && p.inputId !== r.unresolved.inputId) return rejected('input_conflict', '核对对象与未知输入不匹配。');
        if (pendingRemoval && pendingRemoval.runId === r.runId) return rejected('revocation_pending', '先由独立输入通道确认撤销静默。');
        if (!['success', 'no_effect'].includes(p.outcome) || p.confirmed !== true ||
            !text(p.observationId) || p.observationId === r.unresolved.observationId ||
            r.unresolved.observationBarrier.includes(p.observationId)) return rejected('reconciliation_required', '需新观察明确核对已生效或未生效。');
        if (inputOwner) {
          if (inputOwner.runId !== r.runId || inputOwner.inputId !== p.inputId || p.quiescent !== true) return rejected('input_not_quiescent', '先确认相关输入静默。');
          event('input_quiescent', { runId: r.runId, inputId: inputOwner.inputId, outcome: p.outcome });
          inputOwner = null;
        }
        var unresolved = copy(r.unresolved);
        if (p.outcome === 'success' && unresolved.inputId && currentTask(r) &&
            canonical(unresolved.cursor) === canonical(r.cursor)) advanceSuccess(r);
        r.unresolved = null;
        r.lastObservationId = p.observationId;
        makeCheckpoint(r, true, p.observationId);
        event('unknown_reconciled', { runId: r.runId, inputId: unresolved.inputId, outcome: p.outcome, observationId: p.observationId });
        if (r.cancelRequested && !isTerminal(r)) {
          terminal(r, 'cancelled');
          return { ok: true, status: 'cancelled', runId: r.runId };
        }
        if (!isTerminal(r)) {
          r.status = r.runId === activeId ? 'running' : 'preempted';
          if (r.runId === activeId && finishIfDone(r)) return { ok: true, status: r.status, runId: r.runId };
        }
        schedule();
        return { ok: true, status: 'reconciled', runId: r.runId };
      });
    }
    function advanceWait(input) {
      return perform('advanceWait', input, function (p) {
        if (typeof p.elapsedMs !== 'number' || !Number.isFinite(p.elapsedMs) || p.elapsedMs < 0 || !Number.isFinite(logicalTimeMs + p.elapsedMs)) return rejected('invalid_elapsed', '逻辑时间增量需要明确的非负毫秒数。');
        logicalTimeMs += p.elapsedMs;
        event('logical_time_advanced', { elapsedMs: p.elapsedMs, logicalTimeMs: logicalTimeMs });
        return { ok: true, status: 'advanced', logicalTimeMs: logicalTimeMs };
      });
    }

    return Object.freeze({
      enqueue: enqueue,
      enqueueTask: function (p) { return enqueue(Object.assign({}, p, { ref: { kind: 'task', id: p.id || p.taskId || (p.ref && p.ref.id) } })); },
      enqueuePlan: function (p) { return enqueue(Object.assign({}, p, { ref: { kind: 'plan', id: p.id || p.planId || (p.ref && p.ref.id) } })); },
      clockDue: enqueue,
      setEligibility: setEligibility,
      pause: pause, stop: stop, resume: resume, confirmResume: confirmResume,
      step: step, checkpoint: checkpoint, complete: complete, fail: fail, unknown: unknown,
      removeQueued: removeQueued, remove: removeQueued,
      confirmRemovalQuiescent: confirmRemovalQuiescent,
      resolveOutcome: resolveOutcome, reconcileUnknown: resolveOutcome,
      advanceTime: advanceWait, advanceWait: advanceWait,
      getSnapshot: getSnapshot,
      onChange: function (fn) {
        if (typeof fn !== 'function') throw new TypeError('onChange requires a function');
        observers.add(fn);
        fn(getSnapshot());
        return function () { observers.delete(fn); };
      }
    });
  }

  return Object.freeze({ version: '1.0.0-prototype', create: create });
});
