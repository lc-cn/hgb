'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {create}=require('../game-session-simulator.js');
let count=0,sequence=0;
const command=extra=>({commandId:'test-'+(++sequence),...extra});
const initial={uid:'demo-account-a',name:'账号 A',server:'demo-server',channel:'demo-channel',gameVersion:'demo-game-1',deviceKey:'demo-device-1'};
function test(name,fn){fn();count++;console.log('PASS '+name);}
function root(e){assert(e.setCapabilities(command({mode:'root-demo'})).ok);}
function observe(e,extra={}){assert(e.reviewObservedAccount(command({...initial,...extra,observationId:'observe-'+(++sequence)})).ok);}
function capture(e){const op=e.begin(command({kind:'capture'}));assert.equal(op.status,'waiting_quiescence');const done=e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true}));assert(done.ok);return done.profileId;}
function settle(e,id,extra={}){return e.confirmIdentity(command({operationId:id,uid:initial.uid,server:initial.server,channel:initial.channel,observationId:'identity-'+(++sequence),...extra}));}
function close(e){const op=e.begin(command({kind:'close'}));assert(op.ok,op.message);assert(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).ok);assert.equal(e.getSnapshot().gameStatus,'stopped');}
function twoProfiles(){const e=create();root(e);const a=capture(e);observe(e,{uid:'demo-b',name:'账号 B'});const b=capture(e);observe(e,{uid:'demo-b',name:'账号 B'});close(e);return{e,a,b};}
function start(e,accountId='current',extra={}){const op=e.begin(command({kind:'start',accountId,...extra}));assert(op.ok,op.message);assert(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).ok);return op.operationId;}
test('default metadata is an explicit fixture, without Root evidence or saved sessions',()=>{
 const s=create().getSnapshot();assert.equal(s.gameStatus,'running');assert.equal(s.currentAccount.uid,initial.uid);assert.equal(s.capabilities.mode,'unknown');assert.equal(s.capabilities.simulated,true);assert.equal(s.capabilities.canRestore,false);assert.deepEqual(s.accounts,[]);assert.equal(s.selectedAccountId,'current');assert.equal(s.autoStart,false);
});
test('unknown/normal capability cannot stop or capture another app',()=>{
 const e=create();assert.equal(e.begin(command({kind:'close'})).status,'capabilities_unknown');assert.equal(e.setCapabilities(command({mode:'normal-demo'})).ok,true);assert.equal(e.begin(command({kind:'close'})).status,'root_required');assert.equal(e.begin(command({kind:'capture'})).status,'root_required');assert.equal(e.getSnapshot().gameStatus,'running');assert.equal(e.getSnapshot().operation,null);
});
test('ordinary current-account start requires quiescence and a fresh identity, not launch alone',()=>{
 const e=create();e.setCapabilities(command({mode:'normal-demo'}));const op=e.begin(command({kind:'start',accountId:'current'}));assert.equal(op.status,'waiting_quiescence');assert.equal(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:false})).status,'input_not_quiescent');assert.equal(e.getSnapshot().gameStatus,'running');assert(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).ok);assert.equal(e.getSnapshot().operation.identityVerified,false);assert.equal(e.getSnapshot().gameStatus,'awaiting_identity');const matched=settle(e,op.operationId);assert.equal(matched.status,'identity-verified-demo');assert.equal(matched.requiresHostResume,true);assert.equal(e.getSnapshot().identityStatus,'verified-demo');
});
test('Root capture stops the simulated game and saves only an unverified local profile',()=>{
 const e=create();root(e);const id=capture(e),s=e.getSnapshot();assert.equal(s.gameStatus,'stopped');assert.equal(s.operation.result,'captured-unverified');assert.equal(s.accounts[0].id,id);assert.equal(s.accounts[0].validation,'unverified');assert.equal(s.accounts[0].snapshotRevision,1);assert.equal(s.operation.automationAllowed,false);
 const forbidden=/handle|bytes|token|password|credential/i;function check(value){if(!value||typeof value!=='object')return;for(const [key,child]of Object.entries(value)){assert(!forbidden.test(key),key);check(child);}}check(s);assert.doesNotThrow(()=>JSON.stringify(s));
});
test('capture requires a current logged-in observation and updates the same identity without losing its alias',()=>{
 const e=create();root(e);const id=capture(e);assert.equal(e.begin(command({kind:'capture'})).status,'logged_in_observation_required');e.renameAccount(command({accountId:id,name:'我的日常账号'}));observe(e);assert.equal(capture(e),id);const s=e.getSnapshot();assert.equal(s.accounts.length,1);assert.equal(s.accounts[0].snapshotRevision,2);assert.equal(s.accounts[0].name,'我的日常账号');assert.equal(s.accounts[0].validation,'unverified');
});
test('captured state is not validated until an explicit restore and identity confirmation',()=>{
 const {e,a}=twoProfiles();e.selectAccount(command({accountId:a}));const op=start(e,a);let s=e.getSnapshot();assert.equal(s.operation.backupMetadata.uid,'demo-b');assert(s.operation.rollbackAvailable);assert.equal(s.accounts.find(x=>x.id===a).validation,'unverified');assert.equal(e.deleteAccount(command({accountId:a})).status,'snapshot_in_use');assert.equal(settle(e,op).status,'identity-verified-demo');s=e.getSnapshot();assert.equal(s.currentAccount.uid,initial.uid);assert.equal(s.accounts.find(x=>x.id===a).validation,'verified-demo');assert.equal(s.operation.rollbackAvailable,false);
});
test('different device, channel or version rejects restore without altering the observed game',()=>{
 for(const changed of [{deviceKey:'other-device'},{channel:'other-channel'},{gameVersion:'other-version'}]){const e=create();root(e);const a=capture(e);observe(e,{uid:'demo-b',...changed});close(e);const before=e.getSnapshot();const answer=e.begin(command({kind:'start',accountId:a}));assert.equal(answer.status,'incompatible_snapshot');assert.equal(e.getSnapshot().currentAccount.uid,'demo-b');assert.equal(e.getSnapshot().gameStatus,before.gameStatus);}
});
test('unknown results block cross-account restore but current launch cannot clear old evidence',()=>{
 const {e,a}=twoProfiles();assert.equal(e.begin(command({kind:'start',accountId:a,hasUnknownResults:true})).status,'unknown_results_block_switch');const op=start(e,'current',{hasUnknownResults:true});assert(settle(e,op,{uid:'demo-b'}).ok);assert.equal(e.getSnapshot().operation.automationAllowed,false);assert.equal(e.getSnapshot().operation.hasUnknownResults,true);
});
test('wrong identity preserves rollback metadata and profile dependencies until old identity is freshly confirmed',()=>{
 const {e,a,b}=twoProfiles(),op=start(e,a);const observation='wrong-'+(++sequence);assert.equal(settle(e,op,{uid:'unexpected',observationId:observation}).status,'identity_mismatch');let s=e.getSnapshot();assert.equal(s.operation.stage,'failed');assert.equal(s.operation.automationAllowed,false);assert.equal(s.operation.backupMetadata.uid,'demo-b');assert(s.operation.rollbackAvailable);assert.equal(e.deleteAccount(command({accountId:a})).status,'snapshot_in_use');assert.equal(e.deleteAccount(command({accountId:b})).status,'snapshot_in_use');assert.equal(e.begin(command({kind:'start'})).status,'operation_busy');assert.equal(e.rollback(command({operationId:op})).status,'awaiting_rollback_identity');assert.equal(settle(e,op,{uid:'demo-b',observationId:observation}).status,'stale_observation');assert.equal(settle(e,op,{uid:'demo-b'}).status,'rolled-back-demo');s=e.getSnapshot();assert.equal(s.currentAccount.uid,'demo-b');assert.equal(s.currentAccount.name,'账号 B');assert.equal(s.accounts.find(x=>x.id===a).validation,'unverified');assert.equal(s.operation.stage,'completed');
});
test('a failed rollback stays gated and cannot report original identity restored',()=>{
 const {e,a}=twoProfiles(),op=start(e,a);settle(e,op,{uid:'unexpected'});e.rollback(command({operationId:op}));assert.equal(settle(e,op,{uid:'still-wrong'}).status,'rollback_identity_mismatch');assert.equal(e.getSnapshot().operation.automationAllowed,false);assert.equal(e.getSnapshot().operation.rollbackAvailable,true);assert.equal(e.rollback(command({operationId:op})).status,'awaiting_rollback_identity');assert.equal(settle(e,op,{uid:'demo-b'}).status,'rolled-back-demo');
});
test('late operation IDs and already-used observations cannot complete a later lifecycle request',()=>{
 const e=create();e.setCapabilities(command({mode:'normal-demo'}));const old=start(e),observation='confirmed-'+(++sequence);assert(settle(e,old,{observationId:observation}).ok);const next=e.begin(command({kind:'start'}));assert.equal(e.confirmQuiescent(command({operationId:old,inputQuiescent:true})).status,'stale_operation');assert.equal(settle(e,old).status,'stale_operation');assert.equal(e.getSnapshot().operation.id,next.operationId);e.confirmQuiescent(command({operationId:next.operationId,inputQuiescent:true}));assert.equal(settle(e,next.operationId,{observationId:observation}).status,'stale_observation');assert.equal(e.getSnapshot().operation.stage,'awaiting_identity');assert(settle(e,next.operationId).ok);
});
test('capability loss at commit prevents stop/capture rather than trusting an old mode',()=>{
 const e=create();root(e);const op=e.begin(command({kind:'capture'}));e.setCapabilities(command({mode:'normal-demo'}));assert.equal(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).status,'root_required');assert.equal(e.getSnapshot().gameStatus,'running');assert.equal(e.getSnapshot().accounts.length,0);root(e);assert(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).ok);
});
test('deleting a local profile never logs out or deletes the observed game account',()=>{
 const e=create();root(e);const a=capture(e);observe(e);assert.equal(e.selectAccount(command({accountId:a})).status,'game_must_be_stopped');assert.equal(e.deleteAccount(command({accountId:a})).status,'deleted-local-snapshot');const s=e.getSnapshot();assert.equal(s.accounts.length,0);assert.equal(s.selectedAccountId,'current');assert.equal(s.currentAccount.uid,initial.uid);assert.equal(s.gameStatus,'running');
});
test('autostart is a preference; a boot review only restores the assistant pending authorization',()=>{
 const e=create();root(e);const op=start(e);settle(e,op);assert(e.getSnapshot().operation.automationAllowed);const before=e.getSnapshot().gameStatus;e.setAutoStart(command({enabled:true}));assert.equal(e.getSnapshot().gameStatus,before);assert.equal(e.getSnapshot().boot.count,0);assert.equal(e.reviewBoot(command({})).status,'assistant-awaiting-authorization');const s=e.getSnapshot();assert(s.boot.assistantReady);assert(s.boot.requiresAuthorization);assert(s.boot.requiresIdentityReview);assert.equal(s.boot.gameLaunchRequested,false);assert.equal(s.boot.automationResumeRequested,false);assert.equal(s.capabilities.mode,'unknown');assert.equal(s.operation.automationAllowed,false);assert.equal(s.identityStatus,'requires_recheck');assert(!('queue'in s));
 const off=create();assert.equal(off.reviewBoot(command({})).status,'autostart-disabled');assert.equal(off.getSnapshot().boot.assistantReady,false);
});
test('boot during a pending restore fails safely and preserves recovery metadata',()=>{
 const {e,a}=twoProfiles(),op=start(e,a);e.setAutoStart(command({enabled:true}));e.reviewBoot(command({}));assert.equal(e.getSnapshot().operation.failure.code,'boot_interrupted');assert(e.getSnapshot().operation.rollbackAvailable);assert.equal(settle(e,op).status,'invalid_stage');assert.equal(e.rollback(command({operationId:op})).status,'root_required');root(e);assert.equal(e.rollback(command({operationId:op})).status,'awaiting_rollback_identity');assert(settle(e,op,{uid:'demo-b'}).ok);
});
test('command replay, immutable public snapshots and separate instances preserve ownership',()=>{
 const e=create();root(e);const request=command({kind:'capture'}),answer=e.begin(request),events=e.getSnapshot().events.length;assert.deepEqual(e.begin(request),answer);assert.equal(e.getSnapshot().events.length,events);assert.equal(e.begin({...request,kind:'close'}).status,'command_conflict');const s=e.getSnapshot();s.currentAccount.uid='mutated';s.capabilities.mode='root-demo';assert.equal(e.getSnapshot().currentAccount.uid,initial.uid);assert.equal(create().getSnapshot().accounts.length,0);assert.equal(create().getSnapshot().capabilities.mode,'unknown');assert.equal(e.begin(command({kind:'start',token:'not-accepted'})).status,'unexpected_field');
});
test('browser UMD loads without Node, I/O or device globals',()=>{
 const context=vm.createContext({});vm.runInContext(fs.readFileSync(require.resolve('../game-session-simulator.js'),'utf8'),context);assert.equal(typeof context.YysGameSessionSimulator.create,'function');assert.equal(context.YysGameSessionSimulator.create().getSnapshot().capabilities.mode,'unknown');
});
test('running game rejects profile selection and cross-account start at module boundary',()=>{
 const {e,a}=twoProfiles();observe(e,{uid:'demo-b',name:'账号 B'});const before=e.getSnapshot();
 assert.equal(e.selectAccount(command({accountId:a})).status,'game_must_be_stopped');
 assert.equal(e.getSnapshot().selectedAccountId,before.selectedAccountId);
 assert.equal(e.begin(command({kind:'start',accountId:a})).status,'game_must_be_stopped');
 assert.equal(e.getSnapshot().operation.id,before.operation.id);assert.equal(e.getSnapshot().gameStatus,'running');assert.equal(e.getSnapshot().events.length,before.events.length);
 assert(e.selectAccount(command({accountId:'current'})).ok);
});
test('only a confirmed close permits selecting another account; selection alone never restores it',()=>{
 const {e,a}=twoProfiles();observe(e,{uid:'demo-b',name:'账号 B'});const op=e.begin(command({kind:'close'}));assert(op.ok);
 assert.equal(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:false})).status,'input_not_quiescent');
 assert.equal(e.selectAccount(command({accountId:a})).status,'game_must_be_stopped');
 assert(e.confirmQuiescent(command({operationId:op.operationId,inputQuiescent:true})).ok);assert(e.selectAccount(command({accountId:a})).ok);
 assert.equal(e.getSnapshot().currentAccount.uid,'demo-b');assert.equal(e.getSnapshot().gameStatus,'stopped');
 const restore=start(e,a);assert.equal(settle(e,restore).status,'identity-verified-demo');
});
test('awaiting identity rejects another profile without changing the pending operation',()=>{
 const {e,a}=twoProfiles();const op=start(e,'current');const before=e.getSnapshot();
 assert.equal(e.getSnapshot().gameStatus,'awaiting_identity');assert.equal(e.selectAccount(command({accountId:a})).status,'game_must_be_stopped');
 assert.equal(e.begin(command({kind:'start',accountId:a})).ok,false);assert.equal(e.getSnapshot().operation.id,op);assert.equal(e.getSnapshot().events.length,before.events.length);
 assert.equal(settle(e,op,{uid:'demo-b'}).status,'identity-verified-demo');
});
test('running current identity keeps its existing fresh confirmation flow without cross-account permission',()=>{
 const {e,b}=twoProfiles();observe(e,{uid:'demo-b',name:'账号 B'});
 assert.equal(e.selectAccount(command({accountId:b})).status,'game_must_be_stopped');
 const same=start(e,b);assert.equal(e.getSnapshot().gameStatus,'awaiting_identity');assert.equal(settle(e,same,{uid:'demo-b'}).status,'identity-verified-demo');
});
test('a previously selected profile cannot bypass the running-game guard through implicit start',()=>{
 const {e,a}=twoProfiles();assert(e.selectAccount(command({accountId:a})).ok);observe(e,{uid:'demo-b',name:'账号 B'});
 const before=e.getSnapshot();assert.equal(e.begin(command({kind:'start'})).status,'game_must_be_stopped');
 assert.equal(e.getSnapshot().selectedAccountId,a);assert.equal(e.getSnapshot().operation.id,before.operation.id);assert.equal(e.getSnapshot().events.length,before.events.length);
});
console.log(count+'/'+count+' game-session simulator scenarios passed; no Android or session restoration was performed.');
