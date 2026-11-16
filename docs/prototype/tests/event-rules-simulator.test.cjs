'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {create,catalog} = require('../event-rules-simulator.js');
let passed=0;
function test(name,fn){fn();passed++;console.log('PASS '+name);}
let commandSeq=0;
const command = p => ({commandId:'c'+(++commandSeq),...p});
const run = (id, extra={}) => ({runId:id,name:id,ref:{kind:'task',id:'task-'+id},status:'queued',firstDispatchedAtMs:null,unresolved:null,executorGeneration:1,cursor:{completedSteps:0},...extra});
const snapshot = (at,runs=[],extra={}) => ({logicalTimeMs:at,runs,events:[],active:null,scheduler:{mode:'running',manualHold:false},...extra});
const events = (e,id) => e.getSnapshot().events.filter(x=>x.ruleId===id);
const enable=(e,id,extra={})=>e.configure(command({ruleId:id,enabled:true,...extra}));
test('catalog suggestions are proposals; defaults are disabled with no implicit thresholds',()=>{
 assert.equal(catalog.length,11);const e=create();assert(e.getSnapshot().rules.every(r=>!r.enabled));
 assert.equal(enable(e,'queue-wait').status,'threshold_required');
 e.observe(snapshot(0,[run('a')]));e.observe(snapshot(999999,[run('a')]));assert.equal(events(e,'queue-wait').length,0);
});
test('queue threshold event fires once without altering scheduler or requiring any subscription',()=>{
 const e=create();enable(e,'queue-wait',{thresholdMs:100});const s=snapshot(0,[run('a')]);e.observe(s);
 e.observe(snapshot(99,[run('a')]));assert.equal(events(e,'queue-wait').length,0);
 const input=snapshot(100,[run('a')]);const before=JSON.stringify(input);e.observe(input);assert.equal(JSON.stringify(input),before);
 assert.equal(events(e,'queue-wait').length,1);e.observe(snapshot(200,[run('a')]));assert.equal(events(e,'queue-wait').length,1);
 assert.equal(input.runs[0].status,'queued');assert.equal(events(e,'queue-wait')[0].runId,'a');
});
test('removed and previously dispatched queued items cannot newly trigger queue-wait',()=>{
 const e=create();enable(e,'queue-wait',{thresholdMs:100});e.observe(snapshot(0,[run('a'),run('b',{firstDispatchedAtMs:0})]));
 e.observe(snapshot(50,[run('a',{status:'cancelled'}),run('b',{firstDispatchedAtMs:0})]));
 e.observe(snapshot(1000,[run('a',{status:'cancelled'}),run('b',{firstDispatchedAtMs:0})]));assert.equal(events(e,'queue-wait').length,0);
});
test('manual hold and reconciliation freeze queue waiting duration',()=>{
 const e=create();enable(e,'queue-wait',{thresholdMs:100});e.observe(snapshot(0,[run('a')]));e.observe(snapshot(40,[run('a')]));
 const held={scheduler:{mode:'manual_hold',manualHold:true}};e.observe(snapshot(40,[run('a')],held));e.observe(snapshot(1040,[run('a')],held));
 const reconciling={scheduler:{mode:'reconciling',manualHold:false}};e.observe(snapshot(1040,[run('a')],reconciling));e.observe(snapshot(2040,[run('a')],reconciling));
 e.observe(snapshot(2040,[run('a')]));e.observe(snapshot(2099,[run('a')]));assert.equal(events(e,'queue-wait').length,0);
 e.observe(snapshot(2100,[run('a')]));assert.equal(events(e,'queue-wait').length,1);
});
test('failure counts whole confirmed runs per ref, dedupes raw events and success clears streak',()=>{
 const e=create();enable(e,'execution-failed',{thresholdCount:2});
 const r=(id,result)=>run(id,{ref:{kind:'plan',id:'p'},status:result});
 const raw=(id,result)=>({id:'finish-'+id,type:'run_finished',runId:id,result});
 let s=snapshot(0,[r('a','failed')],{events:[raw('a','failed')]});e.observe(s);e.observe(s);assert.equal(events(e,'execution-failed').length,0);
 e.observe(snapshot(0,[r('b','unknown')],{events:[raw('b','unknown')]}));assert.equal(events(e,'execution-failed').length,0);
 e.observe(snapshot(0,[r('c','partial_failed')],{events:[raw('c','partial_failed')]}));assert.equal(events(e,'execution-failed').length,1);
 assert.equal(events(e,'execution-failed')[0].detail.count,2);
 e.observe(snapshot(0,[r('d','completed')],{events:[raw('d','completed')]}));
 e.observe(snapshot(0,[r('e','failed')],{events:[raw('e','failed')]}));assert.equal(events(e,'execution-failed').length,1);
 e.observe(snapshot(0,[r('f','failed')],{events:[raw('f','failed')]}));assert.equal(events(e,'execution-failed').length,2);
});
test('cancelled unknown remains observable, repeated snapshot emits once, recovery permits new episode',()=>{
 const e=create();enable(e,'unknown-result');const a=run('a',{status:'cancelled',unresolved:{reason:'pending'}});
 e.observe(snapshot(0,[a]));e.observe(snapshot(0,[a]));assert.equal(events(e,'unknown-result').length,1);
 e.observe(snapshot(0,[run('a',{status:'cancelled'})]));e.observe(snapshot(0,[a]));assert.equal(events(e,'unknown-result').length,2);
});
test('no progress uses run/generation/cursor and excludes expected waits and manual hold',()=>{
 const e=create();enable(e,'no-progress',{thresholdMs:100});const a=run('a',{status:'running',phase:'ready',firstDispatchedAtMs:0});
 const see=(at,active,extra={})=>e.observe(snapshot(at,[active],{active,...extra}));
 see(0,a);see(50,a);see(50,a,{scheduler:{manualHold:true,mode:'manual_hold'}});see(1050,a,{scheduler:{manualHold:true,mode:'manual_hold'}});
 see(1050,a);see(1099,a);assert.equal(events(e,'no-progress').length,0);see(1100,a);assert.equal(events(e,'no-progress').length,1);
 const b={...a,cursor:{completedSteps:1},expectedWait:true};see(1100,b);see(9999,b);assert.equal(events(e,'no-progress').length,1);
 const c={...b,expectedWait:false,executorGeneration:2};see(9999,c);see(10099,c);assert.equal(events(e,'no-progress').length,2);
});
test('preempted runs never accumulate active no-progress time',()=>{
 const e=create();enable(e,'no-progress',{thresholdMs:10});const a=run('a',{status:'preempted',phase:'preempted'});
 e.observe(snapshot(0,[a],{active:a}));e.observe(snapshot(100,[a],{active:a}));assert.equal(events(e,'no-progress').length,0);
});
test('host conditions are independent episodes and threshold edits never resend an emitted episode',()=>{
 const e=create();enable(e,'resource-shortage',{thresholdMs:100});e.setCondition(command({ruleId:'resource-shortage',sourceKey:'storage',active:true,reason:'full'}));
 e.observe(snapshot(99));assert.equal(events(e,'resource-shortage').length,0);e.observe(snapshot(100));assert.equal(events(e,'resource-shortage').length,1);
 e.configure(command({ruleId:'resource-shortage',enabled:false}));enable(e,'resource-shortage',{thresholdMs:1});e.observe(snapshot(1000));assert.equal(events(e,'resource-shortage').length,1);
 e.setCondition(command({ruleId:'resource-shortage',sourceKey:'storage',active:false}));e.setCondition(command({ruleId:'resource-shortage',sourceKey:'storage',active:true}));e.observe(snapshot(1001));assert.equal(events(e,'resource-shortage').length,2);
 assert.equal(e.setCondition(command({ruleId:'queue-wait',sourceKey:'bad',active:true})).ok,false);
});
test('instant condition, result selection and channel subscription attribution are separate',()=>{
 const e=create();enable(e,'condition-lost');enable(e,'plan-completed',{results:['partial_failed']});
 e.setCondition(command({ruleId:'condition-lost',sourceKey:'projection',active:true}));
 e.observe(snapshot(0,[run('a',{ref:{kind:'plan',id:'pa'},status:'completed'}),run('b',{ref:{kind:'plan',id:'pb'},status:'partial_failed'})],{events:[{id:'a-end',type:'run_finished',runId:'a',result:'completed'},{id:'b-end',type:'run_finished',runId:'b',result:'partial_failed'}]}));
 const all=e.getSnapshot().events;assert.equal(all.length,2);assert.equal(events(e,'plan-completed')[0].runId,'b');
 // External consumer owns subscriptions; rules produce events even with none.
 const channel={id:'local',subscribedRuleIds:['condition-lost']};const deliveries=all.filter(x=>channel.subscribedRuleIds.includes(x.ruleId)).map(x=>({eventId:x.id,channelId:channel.id}));
 assert.deepEqual(deliveries,[{eventId:all[0].id,channelId:'local'}]);assert.equal(e.getSnapshot().events.length,2);
});
test('commands are idempotent, snapshots detached, reverse clocks rejected, UMD loads',()=>{
 const e=create();const c=command({ruleId:'condition-lost',enabled:true});assert.deepEqual(e.configure(c),e.configure(c));
 assert.equal(e.configure({...c,enabled:false}).status,'command_conflict');const s=e.getSnapshot();s.rules[0].enabled=true;assert.equal(e.getSnapshot().rules[0].enabled,false);
 e.observe(snapshot(100));assert.equal(e.observe(snapshot(99)).status,'invalid_clock');
 const context={};vm.runInNewContext(fs.readFileSync(require.resolve('../event-rules-simulator.js'),'utf8'),context);assert.equal(typeof context.YysEventRulesSimulator.create,'function');
});
test('all explicit condition families recover to new episodes; saved rules never mutate scheduler',()=>{
 const scheduler=require('../execution-simulator.js').create(); const before=JSON.stringify(scheduler.getSnapshot()); const e=create();
 enable(e,'condition-lost');enable(e,'vision-unavailable',{thresholdMs:10});
 for(const id of ['condition-lost','vision-unavailable']){
  e.setCondition(command({ruleId:id,sourceKey:'device',active:true}));
 }
 e.observe(snapshot(10));assert.equal(events(e,'vision-unavailable').length,1);assert.equal(events(e,'condition-lost').length,1);
 for(const id of ['condition-lost','vision-unavailable']){
  e.setCondition(command({ruleId:id,sourceKey:'device',active:false}));e.setCondition(command({ruleId:id,sourceKey:'device',active:true}));
 }
 e.observe(snapshot(20));assert.equal(events(e,'vision-unavailable').length,2);assert.equal(events(e,'condition-lost').length,2);
 assert.equal(JSON.stringify(scheduler.getSnapshot()),before);
});
test('result attribution waits for known run ref and task/plan failure streaks are independent',()=>{
 const e=create();enable(e,'execution-failed',{thresholdCount:2});const raw={id:'end-a',type:'run_finished',runId:'a',result:'failed'};
 e.observe(snapshot(0,[],{events:[raw]}));assert.equal(events(e,'execution-failed').length,0);
 const a=run('a',{ref:{kind:'task',id:'same'},status:'failed'});e.observe(snapshot(0,[a],{events:[raw]}));
 const b=run('b',{ref:{kind:'plan',id:'same'},status:'failed'});e.observe(snapshot(0,[b],{events:[{...raw,id:'end-b',runId:'b'}]}));assert.equal(events(e,'execution-failed').length,0);
 const c=run('c',{ref:{kind:'task',id:'same'},status:'failed'});e.observe(snapshot(0,[c],{events:[{...raw,id:'end-c',runId:'c'}]}));assert.equal(events(e,'execution-failed').length,1);assert.equal(events(e,'execution-failed')[0].sourceKey,'task:same');
});
test('normal task and plan facts produce separate subscribed event families and resume never starts again',()=>{
 const e=create();for(const id of ['task-started','task-succeeded','plan-started','plan-completed'])enable(e,id);
 const a=run('a',{status:'completed'});const p=run('p',{ref:{kind:'plan',id:'daily'},status:'completed'});
 const raw=[
  {id:'a-start',type:'run_started',runId:'a',ref:a.ref},
  {id:'a-ts',type:'task_started',runId:'a',taskId:'task-a',taskIndex:0},
  {id:'a-tf',type:'task_finished',runId:'a',taskId:'task-a',taskIndex:0,result:'completed'},
  {id:'a-end',type:'run_finished',runId:'a',result:'completed'},
  {id:'p-start',type:'run_started',runId:'p',ref:p.ref},
  {id:'p-ts0',type:'task_started',runId:'p',taskId:'one',taskIndex:0},
  {id:'p-tf0',type:'task_finished',runId:'p',taskId:'one',taskIndex:0,result:'completed'},
  {id:'p-dispatch-resume',type:'run_dispatched',runId:'p'},
  {id:'p-ts1',type:'task_started',runId:'p',taskId:'two',taskIndex:1},
  {id:'p-tf1',type:'task_finished',runId:'p',taskId:'two',taskIndex:1,result:'completed'},
  {id:'p-end',type:'run_finished',runId:'p',result:'completed'}
 ];
 const s=snapshot(0,[a,p],{events:raw});e.observe(s);e.observe(s);
 assert.equal(events(e,'task-started').length,3);assert.equal(events(e,'task-succeeded').length,3);
 assert.equal(events(e,'plan-started').length,1);assert.equal(events(e,'plan-completed').length,1);
 assert.deepEqual(events(e,'task-started').filter(x=>x.runId==='p').map(x=>x.detail.taskIndex),[0,1]);
 const subscription=['plan-started','plan-completed'];const deliveries=e.getSnapshot().events.filter(x=>subscription.includes(x.ruleId));assert.equal(deliveries.length,2);assert(deliveries.every(x=>x.runId==='p'));
});
test('cancelled never means task success, while plan completion has explicit result filters',()=>{
 const e=create();enable(e,'task-succeeded');enable(e,'plan-completed');
 assert.deepEqual(e.getSnapshot().rules.find(r=>r.id==='plan-completed').results,['completed','partial_failed','failed','cancelled']);
 const p=run('p',{ref:{kind:'plan',id:'p'},status:'cancelled'});
 e.observe(snapshot(0,[p],{events:[{id:'child-cancel',type:'task_finished',runId:'p',taskId:'x',taskIndex:0,result:'cancelled'},{id:'plan-cancel',type:'run_finished',runId:'p',result:'cancelled'}]}));
 assert.equal(events(e,'task-succeeded').length,0);assert.equal(events(e,'plan-completed').length,1);
 e.configure(command({ruleId:'plan-completed',enabled:true,results:['completed']}));
 const q=run('q',{ref:{kind:'plan',id:'q'},status:'failed'});e.observe(snapshot(0,[q],{events:[{id:'q-fail',type:'run_finished',runId:'q',result:'failed'}]}));assert.equal(events(e,'plan-completed').length,1);
 const r=run('r',{ref:{kind:'plan',id:'r'},status:'completed'});e.observe(snapshot(0,[r],{events:[{id:'r-ok',type:'run_finished',runId:'r',result:'completed'}]}));assert.equal(events(e,'plan-completed').length,2);
});
test('real scheduler raw facts integrate to rule events without guessing cursor or duplicate resume starts',()=>{
 const scheduler=require('../execution-simulator.js').create();const e=create();
 for(const id of ['task-started','task-succeeded','plan-started','plan-completed'])enable(e,id);
 const unsubscribe=scheduler.onChange(s=>e.observe(s));
 const task=(id,priority)=>({id,priority,configVersion:'cfg1',packageVersion:'pkg1',steps:['one']});
 const p=scheduler.enqueuePlan(command({id:'p',planRevision:'p1',tasks:[task('child-a',10),task('child-b',20)],eligibility:{status:'ready',observationId:'initial-p'}}));assert(p.ok);
 const a=scheduler.enqueueTask(command({...task('single',5),eligibility:{status:'ready',observationId:'initial-single'}}));assert(a.ok);
 const finish=()=>{const r=scheduler.getSnapshot().active;const input=scheduler.step(command({runId:r.runId,generation:r.executorGeneration}));assert(input.ok);const cp=scheduler.checkpoint(command({runId:r.runId,generation:input.generation,inputId:input.inputId,outcome:'success',quiescent:true,resumable:true}));assert(cp.ok);};
 const input=scheduler.step(command({runId:p.runId}));assert(input.ok);scheduler.pause(command({}));
 scheduler.checkpoint(command({runId:p.runId,inputId:input.inputId,generation:input.generation,outcome:'success',quiescent:true,resumable:true}));
 assert.equal(events(e,'task-started').length,1);assert.equal(events(e,'task-succeeded').length,1);
 const resume=scheduler.resume(command({}));assert(resume.ok);const confirmed=scheduler.confirmResume(command({reconcileId:resume.reconcileId,confirmed:true,observationId:'fresh-resume-p'}));assert(confirmed.ok);
 assert.equal(events(e,'plan-started').length,1);assert.equal(events(e,'task-started').length,2);
 finish();finish();e.observe(scheduler.getSnapshot());
 assert.equal(events(e,'task-started').length,3);assert.equal(events(e,'task-succeeded').length,3);
 assert.equal(events(e,'plan-started').length,1);assert.equal(events(e,'plan-completed').length,1);
 assert.deepEqual(events(e,'task-succeeded').filter(x=>x.runId===p.runId).map(x=>x.detail.taskId),['child-a','child-b']);
 const subscribed=['plan-started','plan-completed'];assert.equal(e.getSnapshot().events.filter(x=>subscribed.includes(x.ruleId)).length,2);unsubscribe();
});
test('enabling lifecycle rules does not replay facts observed while disabled; new facts still emit',()=>{
 const e=create();const p=run('old',{ref:{kind:'plan',id:'p'},status:'completed'});
 const facts=(id)=>[{id:id+'-rs',type:'run_started',runId:id},{id:id+'-ts',type:'task_started',runId:id,taskId:'child',taskIndex:0},{id:id+'-tf',type:'task_finished',runId:id,taskId:'child',taskIndex:0,result:'completed'},{id:id+'-rf',type:'run_finished',runId:id,result:'completed'}];
 const old=snapshot(0,[p],{events:facts('old')});e.observe(old);assert.equal(e.getSnapshot().events.length,0);
 for(const id of ['task-started','task-succeeded','plan-started','plan-completed'])enable(e,id);
 assert.equal(e.getSnapshot().events.length,0);e.observe(old);assert.equal(e.getSnapshot().events.length,0);
 const fresh=run('new',{ref:{kind:'plan',id:'p'},status:'completed'});e.observe(snapshot(0,[p,fresh],{events:[...facts('old'),...facts('new')]}));
 assert.equal(e.getSnapshot().events.length,4);assert(e.getSnapshot().events.every(x=>x.runId==='new'));
});
test('enabling a currently sustained condition still evaluates its accrued episode',()=>{
 const e=create();e.observe(snapshot(0,[run('a',{unresolved:{reason:'pending'}})]));
 e.setCondition(command({ruleId:'resource-shortage',sourceKey:'capacity',active:true}));
 e.observe(snapshot(100,[run('a',{unresolved:{reason:'pending'}})]));enable(e,'unknown-result');enable(e,'resource-shortage',{thresholdMs:100});
 assert.equal(events(e,'unknown-result').length,1);assert.equal(events(e,'resource-shortage').length,1);
});
console.log(`\n${passed} event-rule scenarios passed.`);
