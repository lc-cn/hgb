'use strict';
// Review simulator contracts only; this does not exercise Android or the game.
const assert = require('node:assert/strict');
const { create } = require('../execution-simulator.js');
let seq = 0, passed = 0;
const command = (p = {}) => ({ commandId: `test-${++seq}`, ...p });
const task = (id, priority, steps = ['领取', '核对']) => ({
  id, name: id, priority, steps, configVersion: 'config-1', packageVersion: 'package-1',
  configValues: { count: 2 },
});
const ready = { status: 'ready', observationId: 'initial-observation' };
const enqueue = (e, id, priority, extras = {}) => e.enqueueTask(command({ ...task(id, priority), eligibility: ready, ...extras }));
const plan = (e, extras = {}) => e.enqueuePlan(command({ id: 'plan', name: '领取计划', planRevision: 'plan-1', tasks: [task('reward', 10), task('card', 20)], eligibility: ready, ...extras }));
const snap = e => e.getSnapshot();
const run = (e, id) => snap(e).runs.find(x => x.runId === id);
const ok = r => { assert.equal(r.ok, true, JSON.stringify(r)); return r; };
const finishStep = e => {
  const r = snap(e).active;
  const input = ok(e.step(command({ runId: r.runId })));
  return ok(e.checkpoint(command({ runId: r.runId, inputId: input.inputId, outcome: 'success', quiescent: true, resumable: true, observationId: `observation-${seq}` })));
};
const reconcile = e => {
  const ticket = snap(e).scheduler.reconcile;
  assert.ok(ticket, 'explicit fresh-observation ticket');
  return ok(e.confirmResume(command({ reconcileId: ticket.id, confirmed: true, observationId: `new-observation-${++seq}` })));
};
function test(name, fn) {
  try { fn(); passed++; process.stdout.write(`PASS ${name}\n`); }
  catch (error) { process.stderr.write(`FAIL ${name}\n`); throw error; }
}

test('plan priority is the whole locked sum, not current child priority', () => {
  const e = create(), p = ok(plan(e)), b = ok(enqueue(e, 'soul', 25));
  assert.equal(snap(e).active.runId, p.runId);
  assert.equal(snap(e).active.priority, 30);
  assert.equal(snap(e).queue[0].runId, b.runId);
  finishStep(e); finishStep(e);
  assert.equal(snap(e).active.currentTaskId, 'card');
  assert.equal(snap(e).active.priority, 30);
});

test('preemption waits for quiescent safe checkpoint, then resumes exact cursor', () => {
  const e = create(), p = ok(plan(e));
  const input = ok(e.step(command({ runId: p.runId })));
  const h = ok(enqueue(e, 'high', 40, { steps: ['一次处理'] }));
  assert.equal(snap(e).active.runId, p.runId);
  assert.equal(snap(e).scheduler.pendingHandoff.toRunId, h.runId);
  assert.equal(e.step(command({ runId: p.runId })).ok, false);
  ok(e.checkpoint(command({ runId: p.runId, inputId: input.inputId, outcome: 'success', quiescent: true, resumable: true })));
  assert.equal(snap(e).active.runId, h.runId);
  assert.equal(run(e, p.runId).cursor.stepIndex, 1);
  finishStep(e); reconcile(e);
  assert.equal(snap(e).active.runId, p.runId);
  assert.equal(snap(e).active.cursor.stepIndex, 1);
});

test('equal priority does not preempt and remains FIFO', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), b = ok(enqueue(e, 'b', 20)), c = ok(enqueue(e, 'c', 20));
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(snap(e).scheduler.pendingHandoff, null);
  assert.deepEqual(snap(e).queue.map(x => x.runId), [b.runId, c.runId]);
});

test('nested preemption returns through priority order', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10)), b = ok(enqueue(e, 'b', 20, { steps: ['b'] })), c = ok(enqueue(e, 'c', 30, { steps: ['c'] }));
  assert.equal(snap(e).active.runId, c.runId);
  finishStep(e); reconcile(e);
  assert.equal(snap(e).active.runId, b.runId);
  finishStep(e); reconcile(e);
  assert.equal(snap(e).active.runId, a.runId);
});

test('manual pause holds every queued job; continue freshly reconciles highest', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10));
  ok(e.pause(command({ runId: a.runId })));
  const h = ok(enqueue(e, 'h', 40));
  assert.equal(snap(e).scheduler.manualHold, true);
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(e.step(command({ runId: a.runId })).ok, false);
  ok(e.resume(command()));
  assert.equal(e.step(command({ runId: a.runId })).ok, false);
  reconcile(e);
  assert.equal(snap(e).active.runId, h.runId);
  assert.equal(run(e, a.runId).status, 'preempted');
});

test('manual stop cancels only current occurrence and keeps all other work held', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), b = ok(enqueue(e, 'b', 10));
  ok(e.stop(command({ runId: a.runId })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.equal(snap(e).scheduler.manualHold, true);
  assert.equal(snap(e).active, null);
  assert.equal(snap(e).queue[0].runId, b.runId);
  ok(e.resume(command())); reconcile(e);
  assert.equal(snap(e).active.runId, b.runId);
});

test('confirmed child failure continues remaining children, keeps sum, ends partial failure', () => {
  const e = create(), p = ok(plan(e)), b = ok(enqueue(e, 'b', 25));
  ok(e.fail(command({ runId: p.runId, scope: 'task', confirmed: true, reason: '已确定不可领取' })));
  assert.equal(snap(e).active.currentTaskId, 'card');
  assert.equal(snap(e).active.priority, 30);
  assert.equal(run(e, p.runId).tasks[0].result, 'failed');
  finishStep(e); finishStep(e);
  assert.equal(run(e, p.runId).status, 'partial_failed');
  assert.equal(snap(e).active.runId, b.runId);
});

test('confirmed standalone failure dispatches next but never clears manual hold', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), b = ok(enqueue(e, 'b', 10));
  ok(e.fail(command({ runId: a.runId, confirmed: true, reason: '已确认失败' })));
  assert.equal(snap(e).active.runId, b.runId);
  ok(e.pause(command()));
  ok(e.fail(command({ runId: b.runId, confirmed: true, reason: '暂停前已确认失败' })));
  assert.equal(snap(e).scheduler.manualHold, true);
});

test('unknown is neither failure nor an implicit retry', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), input = ok(e.step(command({ runId: a.runId })));
  ok(e.checkpoint(command({ runId: a.runId, inputId: input.inputId, outcome: 'unknown', quiescent: true, resumable: false })));
  ok(enqueue(e, 'h', 40));
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(e.fail(command({ runId: a.runId, confirmed: true, reason: '不能猜失败' })).ok, false);
  assert.equal(e.step(command({ runId: a.runId })).ok, false);
  assert.equal(e.resume(command()).ok, false);
  assert.ok(run(e, a.runId).unresolved);
});

test('same command and occurrence are idempotent, another command cannot duplicate a live entity', () => {
  const e = create(), args = command({ ...task('a', 10), eligibility: ready });
  const a = ok(e.enqueueTask(args)), repeat = ok(e.enqueueTask(args));
  assert.equal(repeat.runId, a.runId);
  const duplicate = enqueue(e, 'a', 10);
  assert.equal(duplicate.ok, false);
  assert.equal(duplicate.status, 'already_present');
  assert.equal(duplicate.runId, a.runId);
  assert.equal(snap(e).runs.length, 1);
  const due = command({ id: 'p', planRevision: '1', tasks: [task('b', 1)], eligibility: ready, occurrenceKey: { scheduleId: 'schedule', occurrenceId: '2026-11-15T10:00' } });
  const first = ok(e.enqueuePlan(due));
  const second = ok(e.enqueuePlan({ ...due, commandId: 'another-delivery' }));
  assert.equal(first.runId, second.runId);
  assert.equal(snap(e).runs.length, 2);
});

test('queued and preempted tasks cannot be added again', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10));
  ok(enqueue(e, 'h', 40));
  assert.equal(run(e, a.runId).status, 'preempted');
  assert.equal(enqueue(e, 'a', 10).status, 'already_present');
  const b = ok(enqueue(e, 'b', 5));
  assert.equal(enqueue(e, 'b', 5).runId, b.runId);
  assert.equal(snap(e).runs.length, 3);
});

test('plans cannot be duplicated while running, queued or paused', () => {
  const e = create(), p = ok(plan(e));
  assert.equal(plan(e).status, 'already_present');
  ok(e.pause(command()));
  assert.equal(plan(e).runId, p.runId);
  const queued = ok(plan(e, { id: 'another-plan', name: '另一个计划' }));
  assert.equal(plan(e, { id: 'another-plan' }).runId, queued.runId);
  assert.equal(snap(e).runs.length, 2);
  assert.equal(snap(e).scheduler.manualHold, true);
});

test('completion or removal releases entity identity for a new submission', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10, { steps: ['a'] }));
  finishStep(e);
  assert.equal(run(e, a.runId).status, 'completed');
  const again = ok(enqueue(e, 'a', 10));
  assert.notEqual(again.runId, a.runId);
  ok(e.remove(command({ runId: again.runId })));
  const third = ok(enqueue(e, 'a', 10));
  assert.notEqual(third.runId, again.runId);
});

test('distinct schedule occurrences cannot add a second live plan', () => {
  const e = create(), p = ok(plan(e, { occurrenceKey: 'day-one' }));
  assert.equal(plan(e, { occurrenceKey: 'day-two' }).status, 'already_present');
  assert.equal(snap(e).runs.length, 1);
  ok(e.remove(command({ runId: p.runId })));
  assert.equal(plan(e, { occurrenceKey: 'day-one' }).runId, p.runId);
  const next = ok(plan(e, { occurrenceKey: 'day-two' }));
  assert.notEqual(next.runId, p.runId);
});

test('snapshots cannot mutate priority, config or package locks', () => {
  const e = create(), t = task('a', 10), args = command({ ...t, eligibility: ready });
  const a = ok(e.enqueueTask(args));
  args.configValues.count = 999; args.steps[0] = '改写';
  const view = snap(e); view.runs[0].priority = 999; view.runs[0].tasks[0].packageVersion = 'evil';
  assert.equal(run(e, a.runId).priority, 10);
  assert.equal(run(e, a.runId).tasks[0].configValues.count, 2);
  assert.equal(run(e, a.runId).tasks[0].steps[0].label, '领取');
  assert.equal(run(e, a.runId).tasks[0].packageVersion, 'package-1');
});

test('temporarily unknown scene admits request then dispatches on fresh readiness', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10, { eligibility: { status: 'unknown' } }));
  assert.equal(snap(e).active, null);
  assert.equal(snap(e).queue[0].runId, a.runId);
  ok(e.setEligibility(command({ runId: a.runId, status: 'ready', observationId: 'fresh' })));
  assert.equal(snap(e).active.runId, a.runId);
});

test('view errors do not own scheduler behavior', () => {
  const e = create(); let observations = 0;
  e.onChange(() => { observations++; if (observations > 1) throw new Error('view failed'); });
  const a = ok(enqueue(e, 'a', 10));
  assert.equal(snap(e).active.runId, a.runId);
  assert.ok(observations >= 2);
});

test('removing current without input immediately dispatches next; stop remains different', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), b = ok(enqueue(e, 'b', 10));
  ok(e.remove(command({ runId: a.runId })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.equal(snap(e).scheduler.manualHold, false);
  assert.equal(snap(e).active.runId, b.runId);
});

test('stuck worker removal fences it and uses independent dispatcher acknowledgment', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), input = ok(e.step(command({ runId: a.runId })));
  const b = ok(enqueue(e, 'b', 10));
  const removal = ok(e.remove(command({ runId: a.runId })));
  assert.equal(removal.status, 'revoking');
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(e.checkpoint(command({ runId: a.runId, inputId: input.inputId, quiescent: true, resumable: true, outcome: 'success' })).ok, false);
  assert.equal(e.fail(command({ runId: a.runId, confirmed: true, reason: '迟到失败', inputId: input.inputId, quiescent: true })).ok, false);
  assert.equal(e.confirmRemovalQuiescent(command({ runId: a.runId, revocationId: removal.revocationId, source: 'worker', quiescent: true, outcome: 'no_effect' })).ok, false);
  ok(e.confirmRemovalQuiescent(command({ runId: a.runId, revocationId: removal.revocationId, source: 'dispatcher', quiescent: true, outcome: 'no_effect' })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.equal(snap(e).active.runId, b.runId);
  assert.equal(snap(e).inputOwner, null);
  assert.equal(e.step(command({ runId: a.runId, generation: input.generation })).ok, false);
  assert.equal(e.unknown(command({ runId: a.runId, generation: input.generation })).ok, false);
});

test('removing pending handoff target does not strand the current job', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10)), input = ok(e.step(command({ runId: a.runId })));
  const h = ok(enqueue(e, 'h', 40));
  ok(e.remove(command({ runId: h.runId })));
  assert.equal(snap(e).scheduler.pendingHandoff, null);
  ok(e.checkpoint(command({ runId: a.runId, inputId: input.inputId, quiescent: true, resumable: true, outcome: 'success' })));
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(snap(e).active.cursor.stepIndex, 1);
});

test('removing a preempted job invalidates old resume ticket and selects remaining candidate', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10)), h = ok(enqueue(e, 'h', 40, { steps: ['h'] }));
  finishStep(e);
  const oldTicket = snap(e).scheduler.reconcile.id;
  const b = ok(enqueue(e, 'b', 20));
  ok(e.remove(command({ runId: a.runId })));
  assert.equal(e.confirmResume(command({ reconcileId: oldTicket, confirmed: true, observationId: 'old-ticket-observation' })).ok, false);
  reconcile(e);
  assert.equal(snap(e).active.runId, b.runId);
  assert.equal(run(e, h.runId).status, 'completed');
});

test('removing during manual hold never silently restarts automation', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), b = ok(enqueue(e, 'b', 10));
  ok(e.pause(command())); ok(e.remove(command({ runId: a.runId })));
  assert.equal(snap(e).active, null);
  assert.equal(snap(e).scheduler.manualHold, true);
  assert.equal(snap(e).queue[0].runId, b.runId);
});

test('cancelled unknown consumption retains evidence and blocks until explicit new observation', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), input = ok(e.step(command({ runId: a.runId }))), b = ok(enqueue(e, 'b', 10));
  const removal = ok(e.remove(command({ runId: a.runId })));
  ok(e.confirmRemovalQuiescent(command({ runId: a.runId, revocationId: removal.revocationId, source: 'dispatcher', quiescent: true, outcome: 'unknown' })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.ok(run(e, a.runId).unresolved);
  assert.equal(snap(e).active, null);
  assert.equal(snap(e).scheduler.blockedReason, 'unknown_result');
  assert.equal(e.resolveOutcome(command({ runId: a.runId, confirmed: true, outcome: 'success', observationId: 'initial-observation' })).ok, false);
  ok(e.resolveOutcome(command({ runId: a.runId, confirmed: true, outcome: 'success', observationId: 'new-game-evidence' })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.equal(run(e, a.runId).unresolved, null);
  assert.equal(snap(e).active.runId, b.runId);
  assert.equal(run(e, a.runId).cursor.completedSteps, 1);
  assert.equal(e.checkpoint(command({ runId: a.runId, inputId: input.inputId, quiescent: true, resumable: true, outcome: 'success' })).ok, false);
});

test('logical time remains a scheduler fact and does not configure notifications', () => {
  const e = create(); ok(enqueue(e, 'active', 20)); const q = ok(enqueue(e, 'wait', 10));
  ok(e.advanceTime(command({ elapsedMs: 600000 })));
  assert.equal(snap(e).logicalTimeMs, 600000);
  assert.equal(run(e, q.runId).status, 'queued');
  assert.equal(run(e, q.runId).priority, 10);
  assert.equal(snap(e).events.filter(x => x.type === 'wait_threshold_reached').length, 0);
  assert.equal(typeof e.configureWaitNotification, 'undefined');
});

test('unknown reconciliation rejects observations known before the uncertain operation', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20, { eligibility: { status: 'ready', observationId: 'old-a' } }));
  ok(enqueue(e, 'b', 10, { eligibility: { status: 'ready', observationId: 'old-b' } }));
  const input = ok(e.step(command({ runId: a.runId })));
  ok(e.unknown(command({ runId: a.runId, inputId: input.inputId, quiescent: true })));
  assert.equal(e.resolveOutcome(command({ runId: a.runId, confirmed: true, outcome: 'success', observationId: 'old-b' })).ok, false);
  assert.ok(run(e, a.runId).unresolved);
  assert.equal(run(e, a.runId).cursor.completedSteps, 0);
  ok(e.resolveOutcome(command({ runId: a.runId, confirmed: true, outcome: 'success', observationId: 'after-unknown' })));
  assert.equal(run(e, a.runId).cursor.completedSteps, 1);
});

test('stop intent survives unknown reconciliation and does not strand continue', () => {
  const e = create(), a = ok(enqueue(e, 'a', 20)), input = ok(e.step(command({ runId: a.runId }))), b = ok(enqueue(e, 'b', 10));
  ok(e.unknown(command({ runId: a.runId, inputId: input.inputId, quiescent: false })));
  ok(e.stop(command({ runId: a.runId })));
  ok(e.resolveOutcome(command({ runId: a.runId, inputId: input.inputId, confirmed: true, outcome: 'success', quiescent: true, observationId: 'confirmed-after-stop' })));
  assert.equal(run(e, a.runId).status, 'cancelled');
  assert.equal(snap(e).active, null);
  assert.equal(snap(e).scheduler.manualHold, true);
  ok(e.resume(command())); reconcile(e);
  assert.equal(snap(e).active.runId, b.runId);
});

test('continue requires observation acquired after pause, not the admission observation', () => {
  const e = create(); ok(enqueue(e, 'a', 10, { eligibility: { status: 'ready', observationId: 'before-pause' } }));
  ok(e.pause(command())); ok(e.resume(command()));
  const ticket = snap(e).scheduler.reconcile.id;
  const stale = e.confirmResume(command({ reconcileId: ticket, confirmed: true, observationId: 'before-pause' }));
  assert.equal(stale.ok, false);
  assert.equal(snap(e).scheduler.mode, 'reconciling');
  assert.equal(e.step(command({ runId: snap(e).active.runId })).ok, false);
  reconcile(e);
});

test('a preempted executor cannot overwrite checkpoint after that run resumes', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10));
  const generation = snap(e).active.executorGeneration;
  ok(enqueue(e, 'h', 40, { steps: ['h'] })); finishStep(e); reconcile(e);
  assert.notEqual(snap(e).active.executorGeneration, generation);
  const checkpointId = snap(e).active.checkpoint.id;
  const late = e.checkpoint(command({ runId: a.runId, generation, quiescent: true, resumable: false, observationId: 'stale' }));
  assert.equal(late.ok, false);
  assert.equal(snap(e).active.checkpoint.id, checkpointId);
  assert.equal(snap(e).active.checkpoint.resumable, true);
});

test('a settled child input cannot mark the next child failed', () => {
  const e = create(), p = ok(plan(e, { tasks: [task('a', 10, ['a']), task('b', 20, ['b'])] }));
  const input = ok(e.step(command({ runId: p.runId })));
  ok(e.checkpoint(command({ runId: p.runId, inputId: input.inputId, generation: input.generation, outcome: 'success', quiescent: true, resumable: true })));
  assert.equal(snap(e).active.currentTaskId, 'b');
  const late = e.fail(command({ runId: p.runId, inputId: input.inputId, generation: input.generation, taskId: 'a', taskIndex: 0, confirmed: true, reason: '迟到的A失败' }));
  assert.equal(late.ok, false);
  assert.equal(snap(e).active.currentTaskId, 'b');
  assert.equal(snap(e).active.tasks[1].result, 'pending');
});

test('normal lifecycle starts once, automatic resume does not announce another start', () => {
  const e = create(), a = ok(enqueue(e, 'a', 10));
  const waiting = ok(enqueue(e, 'waiting', 5));
  assert.equal(snap(e).events.filter(x => x.type === 'run_started' && x.runId === waiting.runId).length, 0);
  ok(enqueue(e, 'h', 40, { steps: ['h'] })); finishStep(e); reconcile(e);
  assert.equal(snap(e).active.runId, a.runId);
  assert.equal(snap(e).events.filter(x => x.type === 'run_started' && x.runId === a.runId).length, 1);
  assert.equal(snap(e).events.filter(x => x.type === 'task_started' && x.runId === a.runId).length, 1);
});

test('plan child start follows ownership and success, not just advancing a paused cursor', () => {
  const e = create(), p = ok(plan(e, { tasks: [task('a', 10, ['a']), task('b', 20, ['b'])] }));
  const input = ok(e.step(command({ runId: p.runId })));
  ok(e.pause(command()));
  ok(e.checkpoint(command({ runId: p.runId, inputId: input.inputId, outcome: 'success', quiescent: true, resumable: true })));
  assert.equal(run(e, p.runId).cursor.taskIndex, 1);
  assert.equal(snap(e).events.filter(x => x.type === 'task_started' && x.taskId === 'b').length, 0);
  ok(e.resume(command())); reconcile(e);
  assert.equal(snap(e).events.filter(x => x.type === 'task_started' && x.taskId === 'b').length, 1);
  finishStep(e);
  assert.equal(snap(e).events.filter(x => x.type === 'task_finished' && x.runId === p.runId && x.result === 'completed').length, 2);
  assert.equal(snap(e).events.filter(x => x.type === 'run_started' && x.runId === p.runId).length, 1);
});

test('account ownership is copied at admission and excludes session credentials', () => {
  const e = create(), accountBinding = { key: 'account-a', name: '账号 A', token: 'not-a-real-token' };
  const a = ok(enqueue(e, 'a', 10, { accountBinding }));
  accountBinding.key = 'account-b';
  const view = run(e, a.runId);
  assert.deepEqual(view.accountBinding, { key: 'account-a', name: '账号 A' });
  view.accountBinding.key = 'mutated';
  assert.equal(run(e, a.runId).accountBinding.key, 'account-a');
});

process.stdout.write(`\n${passed} simulator scenarios passed.\n`);
