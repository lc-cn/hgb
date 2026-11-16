/* Memory-only game-session review fixture. No device, files, session bytes, tokens,
 * network, timers, Android calls or scheduler ownership. Root modes are simulated. */
(function(root,factory){
  'use strict';
  var api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  root.YysGameSessionSimulator=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  var clone=function(v){return JSON.parse(JSON.stringify(v));};
  var nonempty=function(v){return typeof v==='string'&&v.trim()!=='';};
  var initialAccount={uid:'demo-account-a',name:'账号 A',server:'demo-server',channel:'demo-channel',gameVersion:'demo-game-1',deviceKey:'demo-device-1',observationId:'initial-demo-observation'};
  function canonical(v){
    if(Array.isArray(v))return '['+v.map(canonical).join(',')+']';
    if(v&&typeof v==='object')return '{'+Object.keys(v).sort().map(function(k){return JSON.stringify(k)+':'+canonical(v[k]);}).join(',')+'}';
    return JSON.stringify(v);
  }
  function identity(value){return {uid:value.uid,server:value.server,channel:value.channel};}
  function sameIdentity(a,b){return !!a&&!!b&&a.uid===b.uid&&a.server===b.server&&a.channel===b.channel;}
  function create(){
    var mode='unknown',gameStatus='running',current=clone(initialAccount),identityStatus='observed-demo';
    var accounts=[],selected='current',operation=null,autoStart=false;
    var boot={count:0,assistantReady:false,requiresAuthorization:false,requiresIdentityReview:false,gameLaunchRequested:false,automationResumeRequested:false,simulated:true};
    var commands=new Map(),observations=new Set([initialAccount.observationId]),handles=new Map(),operationHandles=new Map();
    var events=[],seq=0,profileSeq=0,operationSeq=0,revision=0;
    function rejected(status,message){return {ok:false,status:status,message:message};}
    function event(type,data){var item=Object.assign({id:'game-event-'+(++seq),seq:seq,type:type,simulated:true},data||{});events.push(item);revision++;return item;}
    function capabilities(){return {mode:mode,simulated:true,source:'review-fixture',canLaunch:mode!=='unknown',canStop:mode==='root-demo',canCapture:mode==='root-demo',canRestore:mode==='root-demo'};}
    function account(id){return accounts.find(function(p){return p.id===id;})||null;}
    function metadata(value){return value?clone(value):null;}
    function activeOperation(){return !!operation&&(['waiting_quiescence','awaiting_identity','awaiting_rollback_identity'].includes(operation.stage)||(operation.stage==='failed'&&operation.rollbackAvailable));}
    function fresh(observationId){return nonempty(observationId)&&!observations.has(observationId);}
    function markObservation(observationId){observations.add(observationId);}
    function currentEnvironment(){return current?{deviceKey:current.deviceKey,channel:current.channel,gameVersion:current.gameVersion}:null;}
    function snapshot(){return clone({schemaVersion:1,simulated:true,revision:revision,capabilities:capabilities(),gameStatus:gameStatus,currentAccount:current,identityStatus:identityStatus,accounts:accounts,selectedAccountId:selected,operation:operation,autoStart:autoStart,boot:boot,events:events});}
    function transact(type,input,fields,fn){
      input=input||{};
      if(!nonempty(input.commandId))return rejected('command_id_required','需要明确的命令身份');
      if(Object.keys(input).some(function(k){return k!=='commandId'&&!fields.includes(k);}))return rejected('unexpected_field','命令包含未定义字段');
      var fingerprint;
      try{fingerprint=type+':'+canonical(input);}catch(_){return rejected('invalid_command','命令必须为可序列化的演示元数据');}
      var prior=commands.get(input.commandId);
      if(prior)return prior.fingerprint===fingerprint?clone(prior.result):rejected('command_conflict','同一命令身份不能用于不同操作');
      var result=fn(input);
      commands.set(input.commandId,{fingerprint:fingerprint,result:clone(result)});
      return clone(result);
    }
    function checkOperation(p,stage){
      if(!operation||operation.id!==p.operationId)return rejected('stale_operation','生命周期操作已经变化');
      if(stage&&!stage.includes(operation.stage))return rejected('invalid_stage','当前阶段不接受该事件');
      return null;
    }
    function setCapabilities(input){return transact('setCapabilities',input,['mode'],function(p){
      if(!['unknown','root-demo','normal-demo'].includes(p.mode))return rejected('invalid_mode','请选择明确的演示能力模式');
      mode=p.mode;event('capabilities_reviewed',{mode:mode});return {ok:true,status:'configured'};
    });}
    function reviewObservedAccount(input){return transact('reviewObservedAccount',input,['uid','name','server','channel','gameVersion','deviceKey','observationId'],function(p){
      if(activeOperation())return rejected('operation_busy','请通过当前操作核对身份');
      if(![p.uid,p.server,p.channel,p.gameVersion,p.deviceKey].every(nonempty))return rejected('identity_required','需要账号、区服、渠道和环境元数据');
      if(!fresh(p.observationId))return rejected('stale_observation','需要新的身份观察');
      current={uid:p.uid,name:nonempty(p.name)?p.name:p.uid,server:p.server,channel:p.channel,gameVersion:p.gameVersion,deviceKey:p.deviceKey,observationId:p.observationId};
      markObservation(p.observationId);gameStatus='running';identityStatus='observed-demo';
      event('identity_review_observed',{identity:identity(current),observationId:p.observationId});return {ok:true,status:'observed'};
    });}
    function selectAccount(input){return transact('selectAccount',input,['accountId'],function(p){
      if(p.accountId!=='current'&&!account(p.accountId))return rejected('account_not_found','没有该本地账号快照');
      if(gameStatus!=='stopped'&&p.accountId!=='current')return rejected('game_must_be_stopped','请先停止游戏，再选择账号快照');
      selected=p.accountId;event('account_selected',{accountId:selected});return {ok:true,status:'selected'};
    });}
    function begin(input){return transact('begin',input,['kind','accountId','hasUnknownResults'],function(p){
      if(!['start','close','capture'].includes(p.kind))return rejected('invalid_kind','未知的游戏生命周期操作');
      if(p.hasUnknownResults!==undefined&&typeof p.hasUnknownResults!=='boolean')return rejected('invalid_unknown_flag','需要明确的未决结果状态');
      if(activeOperation())return rejected('operation_busy','当前生命周期操作尚未结束');
      var targetId=p.kind==='start'?(p.accountId||selected):'current';
      var profile=targetId==='current'?null:account(targetId);
      if(targetId!=='current'&&!profile)return rejected('account_not_found','没有该本地账号快照');
      if(p.kind==='start'&&gameStatus!=='stopped'&&profile&&!sameIdentity(profile,current))return rejected('game_must_be_stopped','请先停止游戏，再切换登录账号');
      if(mode==='unknown')return rejected('capabilities_unknown','能力尚未核对');
      if((p.kind==='close'||p.kind==='capture'||profile)&&mode!=='root-demo')return rejected('root_required','该操作需要专用 Root 演示能力');
      if(p.kind==='capture'&&(!current||gameStatus!=='running'||!['observed-demo','verified-demo'].includes(identityStatus)))return rejected('logged_in_observation_required','先核对已登录的游戏账号');
      var environment=currentEnvironment();
      if(profile&&(!environment||profile.deviceKey!==environment.deviceKey||profile.channel!==environment.channel||profile.gameVersion!==environment.gameVersion))return rejected('incompatible_snapshot','快照与当前设备、渠道或游戏版本不兼容');
      if(profile&&p.hasUnknownResults===true&&!sameIdentity(profile,current))return rejected('unknown_results_block_switch','存在未核对结果，不能跨账号切换');
      var expected=profile?metadata(profile):metadata(current);
      if(p.kind==='start'&&!expected)return rejected('identity_required','需要可核对的预期账号');
      var dependencies=[];
      if(profile)dependencies.push(profile.id);
      var priorProfile=accounts.find(function(a){return sameIdentity(a,current);});
      if(profile&&priorProfile&&!dependencies.includes(priorProfile.id))dependencies.push(priorProfile.id);
      if(p.kind==='capture'&&priorProfile)dependencies.push(priorProfile.id);
      operation={id:'game-operation-'+(++operationSeq),kind:p.kind,stage:'waiting_quiescence',accountId:targetId,expectedIdentity:expected?identity(expected):null,targetIdentity:expected?identity(expected):null,backupMetadata:profile?metadata(current):null,rollbackAvailable:false,identityVerified:false,automationAllowed:false,hasUnknownResults:p.hasUnknownResults===true,failure:null,result:null,profileId:null,dependencies:dependencies};
      operationHandles.set(operation.id,{target:profile?handles.get(profile.id):null,backup:profile&&current?{placeholder:Symbol('review-backup')}:null,environment:metadata(environment),expectedMetadata:expected,quiescent:false});
      event('lifecycle_requested',{operationId:operation.id,kind:p.kind,accountId:targetId});
      return {ok:true,status:'waiting_quiescence',operationId:operation.id,requiresHostPause:true};
    });}
    function confirmQuiescent(input){return transact('confirmQuiescent',input,['operationId','inputQuiescent'],function(p){
      var error=checkOperation(p,['waiting_quiescence']);if(error)return error;
      if(p.inputQuiescent!==true)return rejected('input_not_quiescent','需要独立确认旧输入已静默');
      var data=operationHandles.get(operation.id);
      if(mode==='unknown')return rejected('capabilities_unknown','能力需要重新核对');
      if((operation.kind!=='start'||operation.accountId!=='current')&&mode!=='root-demo')return rejected('root_required','当前没有所需的 Root 演示能力');
      data.quiescent=true;
      if(operation.kind==='close'){
        gameStatus='stopped';identityStatus='stopped';operation.stage='completed';operation.result='closed-demo';
      }else if(operation.kind==='capture'){
        // Stopping and producing an opaque placeholder are simulated, never device I/O.
        gameStatus='stopped';identityStatus='stopped';
        var profile=accounts.find(function(a){return sameIdentity(a,data.expectedMetadata);});
        if(!profile){profile={id:'account-'+(++profileSeq),name:data.expectedMetadata.name,snapshotRevision:0};accounts.push(profile);}
        Object.assign(profile,{uid:data.expectedMetadata.uid,observedName:data.expectedMetadata.name,server:data.expectedMetadata.server,channel:data.expectedMetadata.channel,deviceKey:data.expectedMetadata.deviceKey,gameVersion:data.expectedMetadata.gameVersion,snapshotRevision:profile.snapshotRevision+1,validation:'unverified',verifiedObservationId:null,capturedByOperation:operation.id});
        handles.set(profile.id,{placeholder:Symbol('review-session')});
        operation.profileId=profile.id;operation.stage='completed';operation.result='captured-unverified';
      }else{
        if(operation.accountId!=='current'){
          // Compatibility is checked again at commit; no profile/credential is copied out.
          var target=account(operation.accountId),environment=data.environment;
          if(!target||!data.target||target.deviceKey!==environment.deviceKey||target.channel!==environment.channel||target.gameVersion!==environment.gameVersion)return rejected('incompatible_snapshot','目标快照已不可用或不兼容');
          gameStatus='stopped';operation.rollbackAvailable=!!data.backup;
        }
        gameStatus='awaiting_identity';identityStatus='awaiting_identity';operation.stage='awaiting_identity';
      }
      event('lifecycle_quiescent',{operationId:operation.id,stage:operation.stage,result:operation.result});
      return {ok:true,status:operation.stage,operationId:operation.id,profileId:operation.profileId};
    });}
    function confirmIdentity(input){return transact('confirmIdentity',input,['operationId','uid','server','channel','observationId','name'],function(p){
      var error=checkOperation(p,['awaiting_identity','awaiting_rollback_identity']);if(error)return error;
      if(![p.uid,p.server,p.channel].every(nonempty))return rejected('identity_required','需要账号、区服和渠道');
      if(!fresh(p.observationId))return rejected('stale_observation','需要新的身份观察，不能复用旧结果');
      var data=operationHandles.get(operation.id),rollback=operation.stage==='awaiting_rollback_identity',expected=operation.expectedIdentity;
      var observed={uid:p.uid,server:p.server,channel:p.channel},nameSource=rollback?operation.backupMetadata:data.expectedMetadata;markObservation(p.observationId);
      current={uid:p.uid,name:nonempty(p.name)?p.name:(sameIdentity(observed,nameSource)?nameSource.name:p.uid),server:p.server,channel:p.channel,deviceKey:data.environment.deviceKey,gameVersion:data.environment.gameVersion,observationId:p.observationId};
      gameStatus='running';
      if(!sameIdentity(observed,expected)){
        operation.stage='failed';operation.identityVerified=false;operation.automationAllowed=false;identityStatus='mismatch';
        operation.failure={code:rollback?'rollback_identity_mismatch':'identity_mismatch',expected:metadata(expected),observed:observed};
        event('identity_mismatch',{operationId:operation.id,code:operation.failure.code,observationId:p.observationId});
        return rejected(operation.failure.code,'实际账号与预期不符，保持自动化关闭');
      }
      operation.stage='completed';operation.identityVerified=true;operation.automationAllowed=!operation.hasUnknownResults;operation.result=rollback?'rolled-back-demo':'identity-verified-demo';operation.failure=null;operation.rollbackAvailable=false;identityStatus='verified-demo';
      if(!rollback&&operation.accountId!=='current'){
        var profile=account(operation.accountId);profile.validation='verified-demo';profile.verifiedObservationId=p.observationId;
      }
      data.backup=null;
      event('identity_confirmed',{operationId:operation.id,rollback:rollback,observationId:p.observationId});
      return {ok:true,status:operation.result,operationId:operation.id,identityVerified:true,automationAllowed:operation.automationAllowed,requiresHostResume:true};
    });}
    function rollback(input){return transact('rollback',input,['operationId'],function(p){
      var error=checkOperation(p,['failed','awaiting_identity']);if(error)return error;
      var data=operationHandles.get(operation.id);
      if(!operation.rollbackAvailable||!operation.backupMetadata||!data.backup||!data.quiescent)return rejected('rollback_unavailable','没有可核对的切换前回退状态');
      if(mode!=='root-demo')return rejected('root_required','回退需要专用 Root 演示能力');
      operation.stage='awaiting_rollback_identity';operation.expectedIdentity=identity(operation.backupMetadata);operation.identityVerified=false;operation.automationAllowed=false;operation.failure=null;
      gameStatus='awaiting_identity';identityStatus='awaiting_rollback_identity';
      event('rollback_requested',{operationId:operation.id});return {ok:true,status:'awaiting_rollback_identity',operationId:operation.id};
    });}
    function renameAccount(input){return transact('renameAccount',input,['accountId','name'],function(p){
      var profile=account(p.accountId);if(!profile)return rejected('account_not_found','没有该本地账号快照');
      if(!nonempty(p.name))return rejected('name_required','请填写快照名称');
      profile.name=p.name.trim();event('account_renamed',{accountId:profile.id});return {ok:true,status:'renamed'};
    });}
    function deleteAccount(input){return transact('deleteAccount',input,['accountId'],function(p){
      var profile=account(p.accountId);if(!profile)return rejected('account_not_found','没有该本地账号快照');
      if(activeOperation()&&operation.dependencies.includes(profile.id))return rejected('snapshot_in_use','当前生命周期操作依赖该快照');
      accounts=accounts.filter(function(a){return a.id!==profile.id;});handles.delete(profile.id);if(selected===profile.id)selected='current';
      event('local_snapshot_deleted',{accountId:profile.id});return {ok:true,status:'deleted-local-snapshot'};
    });}
    function setAutoStart(input){return transact('setAutoStart',input,['enabled'],function(p){
      if(typeof p.enabled!=='boolean')return rejected('enabled_required','需要明确开启或关闭偏好');
      autoStart=p.enabled;event('autostart_preference_saved',{enabled:autoStart});return {ok:true,status:'saved-preference'};
    });}
    function reviewBoot(input){return transact('reviewBoot',input,[],function(){
      boot={count:boot.count+1,assistantReady:autoStart,requiresAuthorization:autoStart,requiresIdentityReview:autoStart,gameLaunchRequested:false,automationResumeRequested:false,simulated:true};
      if(autoStart){
        mode='unknown';identityStatus='requires_recheck';
        if(operation){operation.identityVerified=false;operation.automationAllowed=false;}
        if(activeOperation()){
          operation.stage='failed';operation.identityVerified=false;operation.automationAllowed=false;operation.failure={code:'boot_interrupted'};
        }
      }
      event('boot_reviewed',{enabled:autoStart,assistantReady:boot.assistantReady});return {ok:true,status:autoStart?'assistant-awaiting-authorization':'autostart-disabled'};
    });}
    return Object.freeze({getSnapshot:snapshot,setCapabilities:setCapabilities,reviewObservedAccount:reviewObservedAccount,selectAccount:selectAccount,begin:begin,confirmQuiescent:confirmQuiescent,confirmIdentity:confirmIdentity,rollback:rollback,renameAccount:renameAccount,deleteAccount:deleteAccount,setAutoStart:setAutoStart,reviewBoot:reviewBoot});
  }
  return Object.freeze({version:'1.0.0-prototype',create:create});
});
