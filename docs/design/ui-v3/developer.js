(() => {
  'use strict';
  // Local UI fixture only. No OAuth, network, filesystem, or game input adapter exists here.
  const TARGET_REPO = 'onmyoji-assist/tasks';
  const el = document.getElementById('app-root');
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const clone = v => JSON.parse(JSON.stringify(v));
  const paths = {
    back:'m15 18-6-6 6-6', plus:'M12 5v14M5 12h14', close:'m6 6 12 12M18 6 6 18',
    chevron:'m9 5 7 7-7 7', more:'M12 5h.01M12 12h.01M12 19h.01',
    copy:'M9 9h11v12H9zM5 16H3V3h12v2', code:'m8 6-6 6 6 6M16 6l6 6-6 6m-6 3 4-18',
    play:'m8 5 11 7-11 7z', pause:'M8 5v14M16 5v14', check:'m5 12 4 4L19 6',
    upload:'M12 15V3m-5 5 5-5 5 5M5 15v5h14v-5', logout:'M9 4H4v16h5m5-13 5 5-5 5m-6-5h11',
    steps:'M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01',
    form:'M4 3h16v18H4zM8 8h8M8 12h8M8 16h4', image:'M3 3h18v18H3zM3 17l5-5 4 4 4-6 5 7M7 7h.01',
    locate:'M12 2v4M12 18v4M2 12h4M18 12h4M7 7h10v10H7z', tap:'M10 13V5a2 2 0 0 1 4 0v7m0-3 3 1 3 3-2 8h-8l-5-7a2 2 0 0 1 3-2l2 1',
    wait:'M12 8v5l3 2M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18',
    text:'M4 4h16M12 4v16M8 20h8', error:'m12 3 10 18H2zM12 9v5M12 17h.01',
    up:'m6 14 6-6 6 6', down:'m6 10 6 6 6-6', trash:'M4 7h16M9 7V3h6v4M6 7l1 14h10l1-14M10 11v6m4-6v6',
    save:'M4 3h13l4 4v14H3V3zM7 3v6h10V3M7 21v-8h10v8',
    github:'M9 19c-4 1-4-2-6-2m12 5v-4c0-1-.4-2-1-2 4-.5 7-2 7-6 0-2-1-3-2-4 0-1 0-3-1-4-2 0-3 1-4 2-2-.5-4-.5-6 0-1-1-2-2-4-2-1 1-1 3-1 4-1 1-2 2-2 4 0 4 3 5.5 7 6-.6 0-1 1-1 2v4',
    shield:'m12 2 8 3v6c0 5-8 11-8 11S4 16 4 11V5zM8 12l3 3 5-6',
    swipe:'M3 12h18m-5-5 5 5-5 5M8 7l-5 5 5 5', reset:'M4 9a8 8 0 1 1 0 6M4 3v6h6', network:'M3 8c5-5 13-5 18 0M6 12c3-3 9-3 12 0m-8 4c1-1 3-1 4 0m-2 4h.01'
  };
  const ico = name => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name] || paths.form}"/></svg>`;
  const command = (action, value = '') => `data-command="${action}" data-value="${esc(value)}"`;
  const ib = (name, label, action, value = '', disabled = false) => `<button class="iconbtn" title="${esc(label)}" aria-label="${esc(label)}" ${command(action,value)} ${disabled?'disabled':''}>${ico(name)}</button>`;
  const btn = (label, action, value = '', cls = 'tonal', disabled = false) => `<button class="${cls}" ${command(action,value)} ${disabled?'disabled':''}>${esc(label)}</button>`;
  const row = (title, sub, action, value = '', icon = 'form', trail = '') => `<div class="row dev-list-row"><button class="row-main" ${command(action,value)}><span class="row-icon">${ico(icon)}</span><span class="row-text"><span class="row-title">${esc(title)}</span>${sub?`<span class="row-sub">${esc(sub)}</span>`:''}</span></button>${trail || `<button class="iconbtn small" aria-label="打开${esc(title)}" ${command(action,value)}>${ico('chevron')}</button>`}</div>`;
  const section = (title, action = '', value = '') => `<div class="dev-section-heading"><span>${esc(title)}</span>${action?ib('plus',`添加${title}`,action,value):''}</div>`;
  const field = (label, value, binding, type = 'text', extra = '') => `<label class="field"><span>${esc(label)}</span><input type="${type}" value="${esc(value)}" data-bind="${binding}" ${extra}></label>`;
  const area = (label, value, binding, extra = '') => `<label class="field"><span>${esc(label)}</span><textarea data-bind="${binding}" ${extra}>${esc(value)}</textarea></label>`;
  const choices = (label, options, value, action, multi = false) => `<div class="choice-field"><div class="choice-label">${esc(label)}</div><div class="choice-group" role="group" aria-label="${esc(label)}">${options.map(o=>{const [key,text] = Array.isArray(o)?o:[o,o];return `<button class="choice-option" aria-pressed="${multi?value.includes(key):value===key}" ${command(action,key)}>${esc(text)}</button>`;}).join('')}</div></div>`;
  const catalog = {
    locate:{name:'定位目标',icon:'locate',description:'按图像资源或文字定位，输出目标区域',params:[['resource','图像资源','resource'],['threshold','匹配阈值','number']],output:'目标区域',default:{resource:'claim-entry',threshold:0.85}},
    tap:{name:'点击目标',icon:'tap',description:'点击已定位的目标区域',params:[['target','点击目标','target'],['repeat','点击次数','number']],output:'点击结果',default:{target:[658,288,90,40],repeat:1}},
    read:{name:'识别文字',icon:'text',description:'读取指定区域中的文字',params:[['region','识别区域','text'],['language','语言','text']],output:'识别文本',default:{region:'全屏',language:'简体中文'}},
    verify:{name:'核对画面',icon:'check',description:'确认预期图像或文字出现',params:[['resource','预期资源','resource'],['timeout','最长等待（秒）','number']],output:'核对结果',default:{resource:'claim-result',timeout:8}},
    wait:{name:'等待',icon:'wait',description:'等待指定时长后进入下一动作',params:[['duration','等待时长（秒）','number']],output:'等待结果',default:{duration:1}},
    swipe:{name:'滑动',icon:'swipe',description:'在指定区域按方向滑动',params:[['region','滑动区域','text'],['direction','方向','direction']],output:'滑动结果',default:{region:'画面中央',direction:'向上'}},
    observe:{name:'获取画面',icon:'image',description:'获取当前画面，供后续动作引用',params:[],output:'画面',default:{}}
  };
  let sequence = 100;
  const uid = prefix => `${prefix}-${++sequence}`;
  function action(type,id,output,params){return {id,type,output,params:Object.fromEntries(Object.entries({...catalog[type].default,...params}).map(([k,v])=>[k,{source:type==='tap'&&k==='target'&&typeof v==='string'?'output':'constant',value:v}]))};}
  const sample = {
    id:'jade-claim',name:'永久勾玉卡产出',description:'进入领取页，领取后核对画面。',priority:20,
    steps:[
      {id:'open-entry',name:'进入领取页',actions:[action('locate','locate-entry','entry'),action('tap','tap-entry','opened',{target:'entry'})]},
      {id:'claim-output',name:'领取产出',actions:[action('locate','locate-claim','claim',{resource:'claim-button'}),action('tap','tap-claim','claimed',{target:'claim'})]},
      {id:'check-result',name:'核对领取结果',actions:[action('verify','verify-result','verified')]}
    ],
    schema:[{key:'retryLimit',label:'识别重试次数',type:'数值',required:true,default:2,min:0,max:5,step:1,control:'滑块',options:[]},{key:'claimScope',label:'领取范围',type:'单选',required:true,default:'当前账号',options:['当前账号','指定权益'],control:'按钮组'}],
    resources:[{id:'claim-entry',name:'权益入口',available:true},{id:'claim-button',name:'领取按钮',available:true},{id:'claim-result',name:'领取完成标记',available:true}]
  };
  const secondary = clone(sample);secondary.id='shop-exchange';secondary.name='商店物资兑换';secondary.description='按已选物资列表逐项兑换。';
  const state = {
    route:'auth',orientation:new URLSearchParams(location.search).get('orientation')==='landscape'?'landscape':'portrait',
    auth:'signedout',authorizedRepo:null,repo:TARGET_REPO,currentTaskId:sample.id,tasks:[sample,secondary],dirty:false,
    stepId:'open-entry',actionId:'locate-entry',fieldKey:'retryLimit',fieldDraft:null,fieldDrafts:{},modal:null,back:[],
    executor:{status:'idle',scope:'step',boundTask:null,boundConfig:null,outputs:{},stepIndex:0,actionIndex:0,completedSteps:[],automaticQueuePaused:true,unknown:false,logs:[],input:null,output:null,generation:0},
    publish:{status:'idle',version:'2026.11.15.2',changelog:'优化领取页识别与领取结果核对。',outcome:'success',generation:0},
    debugOutcome:'success',previewValues:{},previewErrors:[],errors:[],lastTask:null
  };
  const authNames={signedout:'未登录',pending:'等待授权',authorized:'有写权限',denied:'无权限',unknown:'网络未知',expired:'授权失效'};
  function authorized(){return state.auth==='authorized'&&state.authorizedRepo===TARGET_REPO;}
  function requireAuth(){if(authorized())return true;state.route='auth';state.modal=null;render();return false;}
  function previewValue(key){return state.previewValues[task().id]?.[key];}
  function setPreviewValue(key,value){state.previewValues[task().id]??={};state.previewValues[task().id][key]=value;}
  function task(){return state.tasks.find(t=>t.id===state.currentTaskId)||state.tasks[0];}
  function step(){return task().steps.find(s=>s.id===state.stepId)||task().steps[0];}
  function currentAction(){return task().steps.flatMap(s=>s.actions).find(a=>a.id===state.actionId);}
  function dirty(){state.dirty=true;const d=document.getElementById('dev-dirty');if(d)d.textContent='未保存';}
  function toast(message){const t=document.getElementById('toast');t.textContent=message;t.classList.remove('hidden');clearTimeout(toast.timer);toast.timer=setTimeout(()=>t.classList.add('hidden'),2200);}
  function navigate(route, options={}){
    if(route!=='auth'&&!requireAuth())return false;
    if(!['auth','tasks','task','step','action','actions','schema','field','preview','resources','json','debug','publish','diagnostics'].includes(route.split('/')[0]))route=authorized()?'tasks':'auth';
    if(!options.replace&&route!==state.route)state.back.push(state.route);
    const [base,id]=route.split('/');
    if(base==='task'&&id){if(!state.tasks.some(t=>t.id===id))return false;state.currentTaskId=id;state.fieldDraft=null;}
    if(base==='step'&&id){if(!task().steps.some(s=>s.id===id)){route=`task/${task().id}`;}else state.stepId=id;}
    if(base==='actions'&&!task().steps.length){route=`task/${task().id}`;toast('先添加一个步骤');}
    if(base==='action'&&id){state.actionId=id;const owner=task().steps.find(s=>s.actions.some(a=>a.id===id));if(owner)state.stepId=owner.id;}
    if(base==='field'&&id){const f=task().schema.find(f=>f.key===id);state.fieldKey=id;const draftKey=`${task().id}:${id}`;state.fieldDraft=state.fieldDrafts[draftKey]||(state.fieldDrafts[draftKey]=clone(f||{key:uid('field'),label:'',type:'文本',required:false,default:'',min:0,max:10,step:1,control:'输入框',options:[]}));}
    state.route=route;state.modal=null;history.replaceState(null,'',`${location.pathname}${location.search}#${route}`);render();return true;
  }
  function back(){const route=state.back.pop()||(state.route.startsWith('task/')?'tasks':`task/${task().id}`);navigate(route,{replace:true});}
  function issue(message,route){return {message,route};}
  function validate(t=task()){
    const out=[];if(!t.name.trim())out.push(issue('填写任务名称',`task/${t.id}`));if(!/^[a-z][a-z0-9-]*$/.test(t.id))out.push(issue('任务标识需为小写英文、数字和连字符',`task/${t.id}`));
    if(state.tasks.some(other=>other!==t&&other.id===t.id))out.push(issue('任务标识已存在',`task/${t.id}`));
    if(!t.steps.length)out.push(issue('至少添加一个步骤',`task/${t.id}`));
    const outputs=new Set();
    t.steps.forEach(s=>{if(!s.name.trim())out.push(issue('填写步骤名称',`step/${s.id}`));if(!s.actions.length)out.push(issue(`${s.name}：添加一个动作`,`step/${s.id}`));s.actions.forEach(a=>{Object.entries(a.params).forEach(([key,b])=>{
      if(b.source==='constant'&&catalog[a.type].params.find(p=>p[0]===key)?.[2]==='resource'&&!t.resources.find(r=>r.id===b.value&&r.available))out.push(issue(`${s.name} · ${catalog[a.type].name}：资源缺失`,`action/${a.id}`));
      if(b.source==='config'&&!t.schema.some(f=>f.key===b.value))out.push(issue(`${s.name}：选择有效的任务配置`,`action/${a.id}`));
      if(b.source==='output'&&!outputs.has(b.value))out.push(issue(`${s.name}：前序输出不可用`,`action/${a.id}`));
      const paramType=catalog[a.type].params.find(p=>p[0]===key)?.[2];
      if(b.source==='constant'&&paramType==='number'&&(typeof b.value!=='number'||!Number.isFinite(b.value)||b.value<0||(key==='threshold'&&b.value>1)))out.push(issue(`${s.name}：${key} 数值无效`,`action/${a.id}`));
      if(b.source==='constant'&&paramType==='target'&&(!Array.isArray(b.value)||b.value.length!==4||b.value.some(v=>typeof v!=='number'||v<0)))out.push(issue(`${s.name}：点击区域不完整`,`action/${a.id}`));
      if(b.source==='config'){const f=t.schema.find(f=>f.key===b.value);if(f&&((paramType==='number'&&f.type!=='数值')||(['resource','text','direction'].includes(paramType)&&!['文本','单选'].includes(f.type))||paramType==='target'))out.push(issue(`${s.name}：${key} 与配置字段类型不匹配`,`action/${a.id}`));}
      if(b.source==='output'&&outputs.has(b.value)){const source=t.steps.flatMap(s=>s.actions).find(v=>v.output===b.value);if((paramType==='target'&&source?.type!=='locate')||(paramType==='number')||(paramType==='resource'))out.push(issue(`${s.name}：${key} 与前序输出类型不匹配`,`action/${a.id}`));}
      if(b.value===''||b.value===undefined)out.push(issue(`${s.name}：${key} 不能为空`,`action/${a.id}`));
    });if(a.output){if(outputs.has(a.output))out.push(issue(`${s.name}：输出名重复`,`action/${a.id}`));outputs.add(a.output);}});});
    const fields=new Set();t.schema.forEach(f=>{if(!f.label.trim()||!f.key.trim()||fields.has(f.key))out.push(issue('配置字段名称或标识不完整／重复',`field/${f.key}`));fields.add(f.key);if(f.type==='数值'&&(+f.min>+f.max||+f.step<=0))out.push(issue(`${f.label}：数值约束无效`,`field/${f.key}`));if(['单选','多选'].includes(f.type)&&!f.options.length)out.push(issue(`${f.label}：添加可选项`,`field/${f.key}`));});return out;
  }
  function validateTestConfig(t=task()){
    const errors=[];
    const validDate=v=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&!Number.isNaN(Date.parse(v))&&new Date(v).toISOString().slice(0,10)===v;
    t.schema.forEach(f=>{const v=state.previewValues[t.id]?.[f.key]??f.default;let problem='';const empty=v===null||v===undefined||v===''||(Array.isArray(v)&&!v.length)||(f.type==='日期范围'&&!v?.start&&!v?.end);
      if(f.required&&empty)problem='请填写此项';else if(empty)return;
      else if(f.type==='数值'){if(typeof v!=='number'||!Number.isFinite(v))problem='请输入有效数值';else if(v<Number(f.min)||v>Number(f.max))problem=`范围 ${f.min}–${f.max}`;else{const steps=(v-Number(f.min))/Number(f.step);if(!Number.isFinite(steps)||Math.abs(steps-Math.round(steps))>1e-7)problem=`请按步长 ${f.step} 取值`;}}
      else if(f.type==='单选'&&!f.options.includes(v))problem='请选择有效选项';
      else if(f.type==='多选'&&(!Array.isArray(v)||v.some(x=>!f.options.includes(x))))problem='请选择有效选项';
      else if(f.type==='时间'&&!/^([01]\d|2[0-3]):[0-5]\d$/.test(v))problem='请选择有效时间';
      else if(f.type==='日期'&&!validDate(v))problem='请选择有效日期';
      else if(f.type==='日期范围'&&(!validDate(v?.start)||!validDate(v?.end)||v.start>v.end))problem='请选择完整且顺序正确的日期范围';
      if(problem)errors.push({message:`${f.label}：${problem}`,taskId:t.id,field:f.key,route:'preview'});
    });return errors;
  }
  function definition(t){return {id:t.id,name:t.name,description:t.description,priority:Number(t.priority),configSchema:clone(t.schema),resources:clone(t.resources),steps:t.steps.map(s=>({id:s.id,name:s.name,actions:s.actions.map(a=>({id:a.id,action:a.type,inputs:clone(a.params),output:a.output}))}))};}
  const manifest = [
    ['auth','GitHub 授权','保护'],['tasks','任务制作列表','制作'],['task/jade-claim','任务信息与步骤','制作'],['step/open-entry','步骤与动作','制作'],['actions','添加基础动作','制作'],['action/locate-entry','动作参数与引用','制作'],['schema','配置 schema','配置'],['field/retryLimit','配置字段编辑','配置'],['preview','用户配置预览','配置'],['resources','资源与缺失状态','验证'],['json','生成 JSON / 定位校验','验证'],['debug','动作 / 步骤 / 任务调试','调试'],['publish','上传 GitHub Release','发布'],['diagnostics','开发诊断','诊断']
  ].map(([id,title,group])=>({id,title,group,protected:id!=='auth'}));
  function reviewControls(){
    document.getElementById('review-controls').innerHTML=`<span>本地评审</span>${[['portrait','竖屏'],['landscape','横屏']].map(([v,t])=>`<button data-review="orientation" data-value="${v}" aria-pressed="${state.orientation===v}">${t}</button>`).join('')}<span>身份夹具</span>${Object.entries(authNames).map(([v,t])=>`<button data-review="auth" data-value="${v}" aria-pressed="${state.auth===v}">${t}</button>`).join('')}<span>调试</span>${[['success','通过'],['failed','定位失败'],['unknown','结果未知']].map(([v,t])=>`<button data-review="debug" data-value="${v}" aria-pressed="${state.debugOutcome===v}">${t}</button>`).join('')}<button data-review="resource">切换资源缺失</button><span>上传结果</span>${[['success','成功'],['failed','确定失败'],['unknown','响应未知'],['expired','权限失效']].map(([v,t])=>`<button data-review="publish" data-value="${v}" aria-pressed="${state.publish.outcome===v}">${t}</button>`).join('')}`;
    document.getElementById('review-page-list').innerHTML=`<h3>页面索引</h3>${manifest.map(m=>`<button data-review="route" data-value="${m.id}">${m.title}</button>`).join('')}`;
  }
  function authPage(){
    const a=state.auth;let detail='',actions='';
    if(a==='pending'){detail=`<div class="dev-auth-state"><div class="dev-state-title">${ico('wait')}等待 GitHub 授权</div><p>在浏览器输入验证码，返回后检查仓库权限。</p><div class="dev-device-code">KJHF-MQSP</div></div>`;actions=btn('打开 GitHub 授权页','open-auth','','filled')+btn('取消','cancel-auth','','textbtn');}
    else if(a==='denied'){detail=`<div class="dev-auth-state"><div class="dev-state-title">${ico('shield')}当前账号没有写权限</div><p>已登录 github-reader。请联系仓库管理员授权，或更换 GitHub 账号。</p></div>`;actions=btn('更换 GitHub 账号','login','','filled');}
    else if(a==='unknown'){detail=`<div class="dev-auth-state"><div class="dev-state-title">${ico('network')}暂时无法检查权限</div><p>网络连接未完成，尚未确认是否具有仓库写权限。</p></div>`;actions=btn('重新检查','retry-auth','','filled');}
    else if(a==='expired'){detail=`<div class="dev-auth-state"><div class="dev-state-title">${ico('shield')}授权已失效</div><p>本地制作内容已保留，重新授权后可继续。</p></div>`;actions=btn('重新授权','login','','filled');}
    else if(a==='authorized'){detail=`<div class="dev-auth-state"><div class="dev-state-title">${ico('check')}已确认仓库写权限</div><p>github-author · 可制作、调试和上传任务。</p></div>`;actions=btn('进入任务制作','navigate','tasks','filled')+btn('退出登录','logout','','textbtn');}
    else actions=btn('使用 GitHub 授权','login','','filled');
    return `<div class="dev-auth"><div>${icoWrap('github','dev-auth-mark')}<h3>开发者工作区</h3><p>使用 GitHub 登录，并验证指定任务仓库的写权限。</p><div class="dev-repo">${ico('shield')}<div><strong>${TARGET_REPO}</strong><small>指定任务仓库</small></div></div></div><div class="dev-auth-details">${detail}${actions}<p class="dev-secondary-note">普通用户无需登录 GitHub 即可使用已发布任务。</p></div></div>`;
  }
  function icoWrap(name,cls){return `<div class="${cls}">${ico(name)}</div>`;}
  function tasksPage(){return `<div class="dev-tasks-layout"><div class="dev-tasks-summary"><div class="dev-author-summary"><span class="dev-avatar">GH</span><span class="row-text"><strong>github-author</strong><small>已验证指定仓库写权限</small></span>${ib('logout','退出登录','logout')}</div><div class="dev-content-note">${TARGET_REPO}</div>${row('上传任务版本','包含当前全部任务','navigate','publish','upload')}${row('只读 JSON','查看生成文件与校验','navigate','json','code')}${row('开发诊断','观察、版本与输入归属','navigate','diagnostics','locate')}</div><div class="dev-tasks-list">${section('本地制作','new-task')}<div class="list">${state.tasks.map(t=>row(t.name,`${t.steps.length} 个步骤 · ${t.schema.length} 项配置`,'navigate',`task/${t.id}`,'steps',`<div class="dev-list-actions">${ib('copy',`复制${t.name}`,'copy-task',t.id)}${ib('chevron',`编辑${t.name}`,'navigate',`task/${t.id}`)}</div>`)).join('')}</div><p class="dev-content-note">${state.tasks.length} 个任务保存在此设备。</p></div></div>`;}
  function taskPage(){const t=task();return `<div class="dev-editor-layout"><div class="dev-editor-main"><div class="form">${field('任务名称',t.name,'task.name')}${field('任务标识',t.id,'task.id','text')}${area('说明',t.description,'task.description')}${field('优先级',t.priority,'task.priority','number','min="0" step="1"')}</div><div class="dev-task-subnav">${row('配置字段',`${t.schema.length} 项配置`,'navigate','schema','form')}${row('资源',`${t.resources.length} 个图像资源`,'navigate','resources','image')}${row('生成 JSON','只读与校验定位','navigate','json','code')}</div></div><div class="dev-editor-side">${section('步骤','add-step')}<div class="list">${t.steps.length?t.steps.map((s,i)=>`<div class="row dev-list-row"><button class="row-main" ${command('navigate',`step/${s.id}`)}><span class="dev-order-number">${i+1}</span><span class="row-text"><span class="row-title">${esc(s.name)}</span><span class="row-sub">${s.actions.length} 个动作</span></span></button>${ib('more',`管理步骤${i+1}`,'step-menu',s.id)}</div>`).join(''):'<p class="dev-empty-inline">先添加一个步骤。</p>'}</div>${row('调试任务','从当前任务开始验证','navigate','debug','play')}</div></div>`;}
  function stepPage(){const s=step();if(!s)return '<p class="empty">此任务还没有步骤。</p>';return `<div class="dev-step-layout"><div class="dev-step-main"><div class="form">${field('步骤名称',s.name,'step.name')}</div><div class="dev-inline-facts"><span>步骤顺序</span><strong>${task().steps.indexOf(s)+1} / ${task().steps.length}</strong></div><div class="dev-content-note">暂停后继续，会重新执行当前未完成步骤。</div>${row('调试此步骤','查看动作输入、输出与画面','debug-step',s.id,'play')}</div><div class="dev-step-side">${section('动作','navigate','actions')}<div class="list">${s.actions.length?s.actions.map((a,i)=>`<div class="row dev-list-row"><button class="row-main" ${command('navigate',`action/${a.id}`)}><span class="dev-type-icon">${ico(catalog[a.type].icon)}</span><span class="row-text"><span class="row-title">${i+1}. ${catalog[a.type].name}</span><span class="row-sub">${esc(a.output?`输出 ${a.output}`:'无命名输出')}</span></span></button>${ib('more','管理动作','action-menu',a.id)}</div>`).join(''):'<p class="dev-empty-inline">选择 App 提供的基础动作。</p>'}</div></div></div>`;}
  function actionsPage(){return `${section('选择动作')}<div class="list">${Object.entries(catalog).map(([k,c])=>row(c.name,c.description,'add-action',k,c.icon)).join('')}</div>`;}
  function availableOutputs(a){const all=task().steps.flatMap(s=>s.actions);return all.slice(0,all.findIndex(v=>v.id===a.id)).filter(a=>a.output).map(a=>a.output);}
  function paramEditor(a,[key,label,type]){const b=a.params[key]||{source:'constant',value:''};let editor='';
    if(b.source==='config'){editor=task().schema.length?choices('选择配置字段',task().schema.map(f=>[f.key,f.label]),b.value,`bind-param:${key}`):'<p class="help">尚无配置字段。</p>';}
    else if(b.source==='output'){const outputs=availableOutputs(a);editor=outputs.length?choices('选择前序输出',outputs,b.value,`bind-param:${key}`):'<p class="help">尚无可用的前序输出。</p>';}
    else if(type==='resource')editor=choices('选择图像资源',task().resources.map(r=>[r.id,`${r.name}${r.available?'':' · 缺失'}`]),b.value,`bind-param:${key}`);
    else if(type==='target')editor=`<div class="dev-field-grid">${['横坐标','纵坐标','宽度','高度'].map((label,i)=>field(label,Array.isArray(b.value)?b.value[i]:'',`target.${key}.${i}`,'number','min="0" step="1"')).join('')}</div>`;
    else if(type==='direction')editor=choices('方向',['向上','向下','向左','向右'],b.value,`bind-param:${key}`);
    else editor=field(label,b.value,`param.${key}`,type==='number'?'number':'text',type==='number'?`step="${key==='threshold'?'0.01':'1'}" min="0" ${key==='threshold'?'max="1"':''}`:'');
    return `<div class="dev-param-block"><div class="choice-label">${esc(label)}</div>${choices('取值来源',[['constant','固定值'],['config','任务配置'],['output','前序输出']],b.source,`source:${key}`)}${editor}</div>`;
  }
  function actionPage(){const a=currentAction();if(!a)return '<p class="empty">此动作已移除。</p>';const c=catalog[a.type];const errs=validate().filter(e=>e.route===`action/${a.id}`);return `<div class="dev-action-layout"><div class="dev-action-main"><div class="form">${c.params.length?c.params.map(p=>paramEditor(a,p)).join(''):'<p class="help">此动作无需输入参数。</p>'}</div>${errs.map(e=>`<div class="dev-error">${ico('error')}<span>${esc(e.message)}</span></div>`).join('')}</div><div class="dev-action-side">${section('输出')}<div class="form">${field('输出名称',a.output,'action.output')}</div><div class="dev-output"><strong>${c.output}</strong><code>${esc(a.output||'未命名')}</code></div>${row('调试此动作','查看解析后的输入与输出','debug-action',a.id,'play')}${row('动作类型',c.name,'change-action',a.id,c.icon)}</div></div>`;}
  function schemaPage(){return `${section('配置字段','new-field')}<div class="list">${task().schema.map(f=>row(f.label,`${f.type} · ${f.key}`,'navigate',`field/${f.key}`,'form')).join('')||'<p class="dev-empty-inline">暂无配置字段。</p>'}</div>${row('预览用户配置','检查实际输入控件','navigate','preview','play')}`;}
  function fieldPage(){const f=state.fieldDraft;if(!f)return '<p class="empty">选择一个配置字段。</p>';const numeric=f.type==='数值';const options=['单选','多选'].includes(f.type);return `<div class="dev-schema-fields"><div class="dev-schema-guide"><div class="form">${field('显示名称',f.label,'field.label')}${field('字段标识',f.key,'field.key')}${choices('字段类型',['文本','数值','单选','多选','时间','日期','日期范围'],f.type,'field-type')}</div></div><div class="dev-schema-form"><div class="form"><div class="dev-switch-row"><span>必填</span><button class="switch ${f.required?'on':''}" role="switch" aria-checked="${f.required}" aria-label="必填" ${command('field-required')}></button></div>${numeric?`<div class="dev-field-grid">${field('最小值',f.min,'field.min','number')}${field('最大值',f.max,'field.max','number')}${field('步长',f.step,'field.step','number','min="0.001" step="any"')}${field('默认值',f.default,'field.default','number')}</div>${choices('输入控件',['精确值','滑块'],f.control==='滑块'?'滑块':'精确值','field-control')}`:options?area('可选项，每行一个',f.options.join('\n'),'field.options')+field('默认值',Array.isArray(f.default)?f.default.join(', '):f.default,'field.default'):f.type==='日期范围'?field('默认开始日期',f.default?.start||'','field.rangeStart','date')+field('默认结束日期',f.default?.end||'','field.rangeEnd','date'):field('默认值',f.default,'field.default',f.type==='时间'?'time':f.type==='日期'?'date':'text')}<div id="field-error" class="field-error" role="status"></div></div>${state.fieldKey!=='new'?`<div class="form">${btn('删除字段','delete-field',state.fieldKey,'textbtn danger')}</div>`:''}</div></div>`;}
  function previewPage(){return `<div class="dev-schema-preview">${section('任务配置')}<div class="form">${task().schema.map(f=>{const v=previewValue(f.key)??f.default;let content='';if(['单选','多选'].includes(f.type))content=choices(f.label,f.options,f.type==='多选'?(Array.isArray(v)?v:[]):v,`preview:${f.key}`,f.type==='多选');else if(f.type==='数值'&&f.control==='滑块')content=`<div class="choice-field"><div class="choice-label">${esc(f.label)} · <output id="preview-${esc(f.key)}">${esc(v)}</output></div><input type="range" min="${esc(f.min)}" max="${esc(f.max)}" step="${esc(f.step)}" value="${esc(v)}" data-bind="preview.${esc(f.key)}" aria-label="${esc(f.label)}">${field('精确值',v,`preview.${f.key}`,'number',`min="${f.min}" max="${f.max}" step="${f.step}"`)}</div>`;else if(f.type==='日期范围')content=`<div class="dev-field-grid">${field(`${f.label} · 开始`,v?.start||'',`preview-range:${f.key}:start`,'date')}${field(`${f.label} · 结束`,v?.end||'',`preview-range:${f.key}:end`,'date')}</div>`;else content=field(f.label,v,`preview.${f.key}`,f.type==='数值'?'number':f.type==='时间'?'time':f.type==='日期'?'date':'text');const error=state.previewErrors.find(e=>e.taskId===task().id&&e.field===f.key);return `<div id="preview-field-${esc(f.key)}">${content}${error?`<p class="field-error" role="alert">${esc(error.message)}</p>`:''}</div>`;}).join('')||'<p class="dev-empty-inline">此任务无需额外配置。</p>'}</div></div>`;}
  function resourcesPage(){return `<div class="dev-resources-layout"><div class="dev-resources-overview">${section('资源引用','add-resource')}<div class="dev-content-note">${task().resources.length} 个资源 · ${task().resources.filter(r=>!r.available).length} 个缺失</div>${row('校验任务','检查资源和参数引用','navigate','json','check')}</div><div class="dev-resources-list">${task().resources.map(r=>`<div class="row"><div class="dev-resource-thumb ${r.available?'':'missing'}">${ico(r.available?'image':'error')}</div><span class="row-text"><span class="row-title">${esc(r.name)}</span><span class="row-sub">${esc(r.id)}${r.available?'':' · 资源缺失'}</span></span>${ib('chevron','查看引用位置','resource-reference',r.id)}</div>`).join('')}${task().resources.some(r=>!r.available)?'<div class="dev-error">'+ico('error')+'<span>缺失的资源会阻止调试和上传。点击资源定位使用它的动作。</span></div>':''}</div></div>`;}
  function jsonPage(){const errs=validate();return `<div class="dev-json-layout"><div class="dev-json-overview"><div class="dev-json-status">${ico(errs.length?'error':'check')}${errs.length?`${errs.length} 项待修正`:'校验通过'}</div>${errs.length?errs.map(e=>row(e.message,'点击定位','navigate',e.route,'error')).join(''):'<p class="dev-content-note">动作、配置与资源引用完整。</p>'}<div class="dev-content-note">JSON 自动生成，只读。</div>${row('返回任务',task().name,'navigate',`task/${task().id}`,'steps')}</div><div class="dev-json-wrap"><pre class="dev-json" tabindex="0" aria-label="只读任务 JSON">${esc(JSON.stringify(definition(task()),null,2))}</pre></div></div>`;}
  function debugPage(){const e=state.executor;const status={idle:'未开始',running:'执行中',paused:'已暂停',success:'已完成',failed:'执行失败',unknown:'结果未知'}[e.status];const bound=e.boundTask||task();const s=bound.steps[e.stepIndex]||bound.steps[0];const a=s?.actions[e.actionIndex]||s?.actions[0];const isPaused=['paused','unknown'].includes(e.status);const logs=e.logs.length?e.logs:[{label:'等待调试',detail:'选择单动作、单步骤或整个任务',time:'—',error:false}];return `<div class="dev-debug-layout"><div class="dev-debug-view"><div class="dev-debug-heading"><span>${esc(bound.name)}</span><span class="dev-debug-state ${['failed','unknown'].includes(e.status)?'error':''}">${status}</span></div><div class="dev-observation" role="img" aria-label="领取页画面与目标定位结果"><span class="dev-world-label">权益领取</span><div class="dev-world-orb"></div><div class="dev-world-gate"></div><div class="dev-world-button">领取</div>${e.status!=='failed'?'<div class="dev-target-box"><span>claim-button · 0.96</span></div>':''}</div><div class="dev-debug-current"><span class="dot"></span><span>${esc(s?.name||'无步骤')} · ${esc(a?catalog[a.type].name:'无动作')}</span></div><div class="dev-debug-controls">${e.status==='running'?btn('暂停','debug-pause','','tonal'):isPaused?btn('继续','debug-resume','','tonal',e.unknown):btn('单动作','debug-run','action','tonal')}${e.status!=='running'&&!isPaused?btn('单步骤','debug-run','step','tonal')+btn('整个任务','debug-run','task','tonal'):''}${isPaused?btn('重新开始','debug-restart','','textbtn',e.unknown):''}${e.status==='running'?ib('reset','重置调试','debug-reset'):''}</div>${e.unknown?`<div class="dev-error">${ico('error')}<span>点击结果尚未确认。请人工核对画面，仅确认当前步骤可安全重做后继续。${btn('确认可重做','debug-check','','textbtn')}</span></div>`:''}</div><div class="dev-debug-details">${section('执行结果')}${logs.slice(-5).reverse().map(l=>`<div class="dev-debug-event ${l.error?'error':''}">${ico(l.error?'error':'check')}<span><strong>${esc(l.label)}</strong><small>${esc(l.detail)}</small></span><time>${l.time}</time></div>`).join('')}<dl class="dev-debug-io"><dt>动作输入</dt><dd>${esc(e.input?JSON.stringify(e.input):'—')}</dd><dt>动作输出</dt><dd>${esc(e.output?JSON.stringify(e.output):'—')}</dd></dl>${['failed','unknown'].includes(e.status)&&a?row('定位到动作',`${s.name} · ${catalog[a.type].name}`,'navigate',`action/${a.id}`,'locate'):''}<div class="dev-content-note">托管队列已暂停。退出调试后仍保持暂停。</div></div></div>`;}
  function publishPage(){const p=state.publish;const errs=state.tasks.flatMap(t=>validate(t));const statuses={uploading:['upload','正在上传','正在生成任务包并上传到 GitHub Release。'],success:['check','版本已发布','用户可拉取此版本，启用由用户自行决定。'],failed:['error','上传失败','GitHub 拒绝了附件上传。本地内容已保留，可以修正后重新上传。'],unknown:['network','上传结果未知','尚未确认远端是否已发布。检查版本记录前，请勿重复上传。'],expired:['shield','仓库权限已失效','本地内容已保留，重新授权后再上传。']};const info=statuses[p.status];return `<div class="dev-publish-layout"><div class="dev-publish-main"><div class="dev-publish-summary">${ico('github')}<span><strong>任务仓库 Release</strong><small>${TARGET_REPO}</small></span></div>${info?`<div class="dev-upload-state"><div class="dev-upload-symbol">${ico(info[0])}</div><h3>${info[1]}</h3><p>${info[2]}</p>${p.status==='uploading'?'<div class="dev-upload-progress"><span></span></div>':''}${p.status==='success'?`<div class="dev-content-note">${esc(p.snapshot?.version||p.version)} · ${esc(p.publishedAt||'2026-11-15 09:41')}</div>`:''}${p.status==='expired'?btn('重新授权','login','','tonal'):p.status==='unknown'?btn('检查远端版本','check-release','','tonal'):p.status==='failed'?btn('重新上传','retry-upload','','tonal'):p.status==='success'?btn('返回制作','navigate','tasks','tonal'):''}</div>`:`<div class="dev-content-note">上传即发布此版本，包含当前全部 ${state.tasks.length} 个任务。</div><div class="dev-upload-facts"><div><span>任务</span><strong>${state.tasks.length} 个</strong></div><div><span>校验</span><strong>${errs.length?`${errs.length} 项待修正`:'通过'}</strong></div></div>${errs.length?`<div class="dev-error">${ico('error')}<span>先修正任务后再上传。${btn('查看问题','publish-issues','','textbtn')}</span></div>`:''}`}</div><div class="dev-publish-form"><div class="form">${field('版本号',p.version,'publish.version','text',['uploading','success','unknown'].includes(p.status)?'disabled':'')}${area('更新说明',p.changelog,'publish.changelog',['uploading','success','unknown'].includes(p.status)?'disabled':'')}</div><div class="dev-upload-facts">${(p.snapshot&&p.status!=='idle'?p.snapshot.tasks:state.tasks).map(t=>`<div><span>${esc(t.name)}</span><strong>${t.steps.length} 步骤</strong></div>`).join('')}</div></div></div>`;}
  function diagnosticsPage(){const e=state.executor;const statuses={idle:'空闲',running:'执行中',paused:'暂停',success:'完成',failed:'失败',unknown:'结果未知'};return `<div class="dev-debug-layout"><div class="dev-debug-view">${section('最近观察')}<div class="dev-observation" role="img" aria-label="最近观察到的画面"><span class="dev-world-label">权益领取 · 09:41</span><div class="dev-world-orb"></div><div class="dev-world-gate"></div><div class="dev-world-button">领取</div></div><div class="facts"><div class="fact"><span>前台应用</span><strong>阴阳师</strong></div><div class="fact"><span>画面</span><strong>1280 × 720 · 横屏</strong></div></div></div><div class="dev-debug-details">${section('版本与输入归属')}<div class="facts"><div class="fact"><span>App 版本</span><strong>0.1.0</strong></div><div class="fact"><span>已启用任务版本</span><strong>v2026.11.15.1</strong></div><div class="fact"><span>输入归属</span><strong>${e.status==='running'?'任务执行器':'无输入'}</strong></div><div class="fact"><span>执行器</span><strong>${statuses[e.status]}</strong></div><div class="fact"><span>托管队列</span><strong>${e.automaticQueuePaused?'已暂停':'等待调度'}</strong></div><div class="fact"><span>当前任务</span><strong>${esc(e.boundTask?.name||'—')}</strong></div><div class="fact"><span>已完成步骤</span><strong>${e.completedSteps.length}</strong></div><div class="fact"><span>未决结果</span><strong>${e.unknown?'需要人工核对':'无'}</strong></div></div></div></div>`;}
  function modalPage(){const m=state.modal;if(!m)return '';let content='';
    if(m.type==='step-menu'||m.type==='action-menu'){const kind=m.type==='step-menu'?'step':'action';content=`<h3>${kind==='step'?'管理步骤':'管理动作'}</h3>${row('复制','','duplicate',kind,'copy')}${row('上移','','move',`${kind}:-1`,'up')}${row('下移','','move',`${kind}:1`,'down')}${row('删除','','delete',kind,'trash')}`;}
    else if(m.type==='resources')content=`<h3>选择图像资源</h3>${sample.resources.filter(r=>!task().resources.some(v=>v.id===r.id)).map(r=>row(r.name,r.id,'select-resource',r.id,'image')).join('')||'<p class="dev-content-note">可用资源已全部加入。</p>'}`;
    else if(m.type==='resume')content=`<h3>恢复当前任务</h3>${row('继续','保留已完成步骤，重做未完成步骤','resume-mode','continue','play')}${row('重新开始','从当前任务第一个步骤开始','resume-mode','restart','reset')}`;
    else if(m.type==='action-types')content=`<h3>更换动作</h3>${Object.entries(catalog).map(([key,c])=>row(c.name,c.description,'replace-action',key,c.icon)).join('')}`;
    else if(m.type==='issues')content=`<h3>修正后再上传</h3>${state.tasks.map(t=>validate(t).map(e=>row(`${t.name} · ${e.message}`,'点击定位','publish-issue-route',JSON.stringify({taskId:t.id,route:e.route}),'error')).join('')).join('')}`;
    else if(m.type==='release-check')content=`<h3>远端版本检查</h3><p class="dev-content-note">暂时无法确认该版本是否已发布。请恢复网络后重新检查，当前制作内容已保留。</p>`;
    return `<div class="dev-modal-shade" data-dismiss="true"><section class="dev-sheet" role="dialog" aria-modal="true" aria-label="${esc(m.type)}"><div class="dev-sheet-handle"></div>${content}${btn('取消','close-modal','','textbtn')}</section></div>`;
  }
  function header(){const route=state.route.split('/')[0];let title='开发者模式',actions='';const protectedPage=route!=='auth';if(route==='tasks'){title='任务制作';actions=ib('upload','上传版本','navigate','publish')+ib('plus','新建任务','new-task');}
    else if(route==='task'){title='编辑任务';actions=`<span class="dev-dirty" id="dev-dirty">${state.dirty?'未保存':'已保存'}</span>`+ib('save','保存任务','save-task')+ib('play','调试任务','navigate','debug');}
    else if(route==='step'){title='编辑步骤';actions=ib('save','保存步骤','save-task')+ib('plus','添加动作','navigate','actions');}
    else if(route==='action'){title=currentAction()?catalog[currentAction().type].name:'动作参数';actions=ib('save','保存动作','save-task')+ib('play','调试动作','debug-action',state.actionId);}
    else if(route==='actions')title='添加动作';else if(route==='schema'){title='配置字段';actions=ib('play','预览用户配置','navigate','preview')+ib('plus','新建字段','new-field');}
    else if(route==='field'){title='配置字段';actions=btn('保存','save-field','','dev-head-text');}
    else if(route==='preview')title='配置预览';else if(route==='resources')title='资源管理';else if(route==='json')title='任务 JSON';
    else if(route==='diagnostics'){title='开发诊断';}
    else if(route==='debug'){title='任务调试';actions=ib('reset','重置调试','debug-reset','',state.executor.unknown);}
    else if(route==='publish'){title='上传版本';actions=btn('上传','upload','','dev-head-text',state.publish.status==='uploading'||state.publish.status==='success'||state.publish.status==='unknown'||state.publish.status==='expired'||state.tasks.some(t=>validate(t).length));}
    return `<header class="appbar">${protectedPage?ib('back','返回','back'):`<a class="iconbtn" href="app.html#settings" aria-label="返回设置">${ico('back')}</a>`}<h2>${title}</h2>${actions}</header>`;
  }
  function render(){if(state.route!=='auth'&&!authorized()){state.route='auth';state.modal=null;}reviewControls();const base=state.route.split('/')[0];const pages={auth:authPage,tasks:tasksPage,task:taskPage,step:stepPage,actions:actionsPage,action:actionPage,schema:schemaPage,field:fieldPage,preview:previewPage,resources:resourcesPage,json:jsonPage,debug:debugPage,publish:publishPage,diagnostics:diagnosticsPage};el.innerHTML=`<section class="device dev-app ${state.orientation==='landscape'?'land':''}" aria-label="开发者模式 ${state.orientation==='landscape'?'横屏':'竖屏'}预览"><div class="statusbar"><span>9:41</span><span>▴ ▰ 86%</span></div>${header()}<div class="appbody">${(pages[base]||authPage)()}</div><div class="gesture"></div>${authorized()?modalPage():''}</section>`;}
  function setAuth(value){let requested=typeof value==='string'?value:value?.state;const repo=typeof value==='object'?(value.repo||TARGET_REPO):TARGET_REPO;if(!Object.hasOwn(authNames,requested))throw new Error('Unknown auth fixture');if(requested==='authorized'&&repo!==TARGET_REPO)requested='denied';state.auth=requested;state.authorizedRepo=requested==='authorized'?repo:null;state.executor.generation++;if(state.executor.status==='running')state.executor.status='paused';state.publish.generation++;if(state.publish.status==='uploading'){state.publish.status='unknown';state.publish.disposition='unknown';}if(requested==='authorized'&&state.publish.status==='expired'&&state.publish.disposition==='not-published')state.publish.status='idle';state.modal=null;if(requested!=='authorized')state.route='auth';else if(state.route==='auth')state.route='tasks';render();return clone({auth:state.auth,authorized:authorized()});}
  function log(label,detail,error=false){state.executor.logs.push({label,detail,error,time:'09:41'});}
  function resolveInputs(a,e){return Object.fromEntries(Object.entries(a.params).map(([k,b])=>[k,b.source==='constant'?clone(b.value):b.source==='config'?clone(e.boundConfig[b.value]):clone(e.outputs[b.value]??{reference:b.value,region:[658,288,90,40]})]));}
  function pumpDebug(generation){
    const e=state.executor;if(!authorized()||generation!==e.generation||e.status!=='running')return;
    const t=e.boundTask,s=t.steps[e.stepIndex],a=s.actions[e.actionIndex];
    e.input=resolveInputs(a,e);e.output=null;log('开始动作',`${s.name} · ${catalog[a.type].name}`);render();
    setTimeout(()=>{if(!authorized()||generation!==e.generation||e.status!=='running')return;
      if(e.attemptOutcome==='unknown'){e.status='unknown';e.unknown=true;e.output=null;log('结果未知','点击后未获得确认画面',true);render();return;}
      if(e.attemptOutcome==='failed'){e.status='failed';e.output={code:'TARGET_NOT_FOUND',actionId:a.id};log('定位失败',`${s.name} · ${catalog[a.type].name}`,true);render();return;}
      const value=a.type==='locate'?{region:[658,288,90,40],confidence:0.96}:a.type==='read'?'已领取':true;
      e.output={name:a.output,value};if(a.output)e.outputs[a.output]=value;log('动作完成',catalog[a.type].name);
      if(e.scope==='action'){e.status='success';render();return;}
      if(e.actionIndex+1<s.actions.length){e.actionIndex++;pumpDebug(generation);return;}
      if(!e.completedSteps.includes(s.id))e.completedSteps.push(s.id);log('步骤完成',s.name);
      if(e.scope==='task'&&e.stepIndex+1<t.steps.length){e.stepIndex++;e.actionIndex=0;pumpDebug(generation);return;}
      e.status='success';log(e.scope==='task'?'任务完成':'调试完成',t.name);render();
    },850);
  }
  function startDebug(scope,mode){
    if(!requireAuth())return;const e=state.executor;
    if(e.status==='running'){toast('先暂停当前执行器');navigate('debug');return;}
    if(e.unknown){toast('先核对结果未知的动作');return;}
    if(e.status==='paused'&&!mode){toast('选择继续或重新开始当前任务');navigate('debug');return;}
    if(!mode){const errs=validate();if(errs.length){state.errors=errs;toast(errs[0].message);navigate('json');return;}const configErrors=validateTestConfig();state.previewErrors=state.previewErrors.filter(e=>e.taskId!==task().id).concat(configErrors);if(configErrors.length){navigate('preview');toast(configErrors[0].message);document.getElementById(`preview-field-${configErrors[0].field}`)?.scrollIntoView?.({block:'center'});return;}
      e.boundTask=clone(task());e.boundConfig=Object.fromEntries(task().schema.map(f=>[f.key,clone(previewValue(f.key)??f.default)]));e.outputs={};e.completedSteps=[];
      if(scope==='action'){e.stepIndex=Math.max(0,e.boundTask.steps.findIndex(s=>s.actions.some(a=>a.id===state.actionId)));e.actionIndex=Math.max(0,e.boundTask.steps[e.stepIndex].actions.findIndex(a=>a.id===state.actionId));}
      else if(scope==='step'){e.stepIndex=Math.max(0,e.boundTask.steps.findIndex(s=>s.id===state.stepId));e.actionIndex=0;}
      else{e.stepIndex=0;e.actionIndex=0;}
      e.scope=scope;
    }else if(mode==='restart'){e.stepIndex=0;e.actionIndex=0;e.completedSteps=[];e.outputs={};e.scope='task';log('重新开始','从当前任务的第一个步骤开始');}
    else{e.actionIndex=0;if(e.scope==='action')e.scope='step';log('继续执行','保留已完成步骤，重做未完成步骤');}
    // A review-injected paused state receives a snapshot only when first resumed.
    if(!e.boundTask){e.boundTask=clone(task());e.boundConfig=Object.fromEntries(task().schema.map(f=>[f.key,clone(previewValue(f.key)??f.default)]));}
    e.status='running';e.automaticQueuePaused=true;e.attemptOutcome=state.debugOutcome;const generation=++e.generation;state.route='debug';state.modal=null;pumpDebug(generation);
  }
  function upload(){if(!requireAuth())return;const p=state.publish;if(['uploading','success','unknown','expired'].includes(p.status))return;const errors=state.tasks.flatMap(t=>validate(t));if(errors.length){state.modal={type:'issues'};render();return;}if(!p.version.trim()||!p.changelog.trim()){toast('填写版本号和更新说明');return;}p.snapshot={version:p.version.trim(),changelog:p.changelog,tasks:state.tasks.map(t=>definition(t))};p.status='uploading';const generation=++p.generation;const outcome=p.outcome;render();setTimeout(()=>{if(generation!==p.generation)return;if(!authorized()){p.status='expired';render();return;}p.status=outcome;if(p.status==='success'){p.publishedAt='2026-11-15 09:41';p.disposition='published';}if(p.status==='failed')p.disposition='not-published';if(p.status==='unknown')p.disposition='unknown';if(p.status==='expired'){p.disposition='not-published';state.auth='expired';state.authorizedRepo=null;state.route='auth';}render();},1100);}
  function mutateList(kind,operation,value){const list=kind==='step'?task().steps:step().actions;const id=kind==='step'?state.stepId:state.actionId;const index=list.findIndex(v=>v.id===id);if(index<0)return;if(operation==='duplicate'){const item=clone(list[index]);item.id=uid(kind);if(kind==='step'){item.name+=' 副本';const refs=new Map(item.actions.filter(a=>a.output).map(a=>[a.output,`${a.output}_${sequence}`]));item.actions.forEach(a=>{a.id=uid('action');if(a.output)a.output=refs.get(a.output);Object.values(a.params).forEach(b=>{if(b.source==='output'&&refs.has(b.value))b.value=refs.get(b.value);});});}else item.output=item.output?`${item.output}_copy`:'';list.splice(index+1,0,item);}else if(operation==='move'){const target=index+Number(value);if(target>=0&&target<list.length)[list[index],list[target]]=[list[target],list[index]];}else if(operation==='delete')list.splice(index,1);state.modal=null;dirty();render();}
  function saveField(){const f=state.fieldDraft;const error=document.getElementById('field-error');const duplicate=task().schema.some(v=>v.key===f.key&&v.key!==state.fieldKey);let message='';if(!f.label.trim()||!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(f.key))message='填写显示名称和有效英文标识';else if(duplicate)message='字段标识已存在';else if(f.type==='数值'&&(!Number.isFinite(+f.min)||!Number.isFinite(+f.max)||+f.min>+f.max||+f.step<=0||+f.default<+f.min||+f.default>+f.max))message='检查默认值、最小值、最大值和步长';else if(['单选','多选'].includes(f.type)&&!f.options.length)message='至少添加一个可选项';else if(f.type==='单选'&&f.default&&!f.options.includes(f.default))message='默认值需要属于可选项';else if(f.type==='多选'&&f.default&&!(Array.isArray(f.default)?f.default:String(f.default).split(',').map(v=>v.trim())).every(v=>f.options.includes(v)))message='默认值需要属于可选项';if(message){error.textContent=message;return;}if(f.type==='多选'&&!Array.isArray(f.default))f.default=f.default?String(f.default).split(',').map(v=>v.trim()):[];const index=task().schema.findIndex(v=>v.key===state.fieldKey);if(index>=0){const old=task().schema[index].key;task().schema[index]=clone(f);task().steps.flatMap(s=>s.actions).forEach(a=>Object.values(a.params).forEach(b=>{if(b.source==='config'&&b.value===old)b.value=f.key;}));}else task().schema.push(clone(f));delete state.fieldDrafts[`${task().id}:${state.fieldKey}`];dirty();navigate('schema');toast('字段已保存到本地');}
  function onCommand(action,value){
    if(['login','retry-auth'].includes(action)){state.auth='pending';state.authorizedRepo=null;state.route='auth';state.modal=null;render();return;}
    if(action==='open-auth'){toast('授权页设计态：请通过画面外身份夹具完成评审');return;}
    if(action==='cancel-auth'||action==='logout'){setAuth('signedout');return;}
    if(action==='navigate'){if(value!=='auth'&&!requireAuth())return;if(value==='publish'&&state.publish.status==='success'){state.publish.status='idle';state.publish.version='';state.publish.changelog='';state.publish.snapshot=null;}navigate(value);return;}
    if(action==='back'){back();return;}
    if(!requireAuth())return;
    if(action==='save-task'){state.dirty=false;render();toast('已保存到本地');}
    else if(action==='new-task'){const t={id:uid('task'),name:'新任务',description:'',priority:0,steps:[],schema:[],resources:[]};state.tasks.push(t);state.currentTaskId=t.id;dirty();navigate(`task/${t.id}`);}
    else if(action==='copy-task'){const source=state.tasks.find(t=>t.id===value);const t=clone(source);t.id=uid('task');t.name+=' 副本';t.steps.forEach(s=>{s.id=uid('step');s.actions.forEach(a=>a.id=uid('action'));});state.tasks.push(t);dirty();navigate(`task/${t.id}`);}
    else if(action==='add-step'){const s={id:uid('step'),name:`步骤 ${task().steps.length+1}`,actions:[]};task().steps.push(s);dirty();navigate(`step/${s.id}`);}
    else if(action==='step-menu'){state.stepId=value;state.modal={type:'step-menu'};render();}
    else if(action==='action-menu'){state.actionId=value;state.modal={type:'action-menu'};render();}
    else if(action==='duplicate'||action==='delete'){mutateList(value,action);}
    else if(action==='move'){const [kind,delta]=value.split(':');mutateList(kind,'move',delta);}
    else if(action==='add-action'){const a=actionFactory(value);step().actions.push(a);dirty();navigate(`action/${a.id}`);}
    else if(action==='change-action'){state.modal={type:'action-types'};render();}
    else if(action==='replace-action'){const a=currentAction();const replacement=actionFactory(value);a.type=value;a.params=replacement.params;state.modal=null;dirty();render();}
    else if(action.startsWith('source:')){const key=action.split(':')[1];const a=currentAction();a.params[key]={source:value,value:value==='constant'?(catalog[a.type].default[key]??''):value==='config'?(task().schema[0]?.key||''):(availableOutputs(a)[0]||'')};dirty();render();}
    else if(action.startsWith('bind-param:')){currentAction().params[action.split(':')[1]].value=value;dirty();render();}
    else if(action==='new-field'){navigate('field/new');}
    else if(action==='field-type'){const f=state.fieldDraft;f.type=value;f.control=value==='数值'?'精确值':['单选','多选'].includes(value)?'按钮组':'输入框';f.default=value==='多选'?[]:value==='日期范围'?{start:'',end:''}:value==='数值'?0:'';render();}
    else if(action==='field-control'){state.fieldDraft.control=value;render();}
    else if(action==='field-required'){state.fieldDraft.required=!state.fieldDraft.required;render();}
    else if(action==='save-field'){saveField();}
    else if(action==='delete-field'){const references=task().steps.flatMap(s=>s.actions).some(a=>Object.values(a.params).some(p=>p.source==='config'&&p.value===value));if(references){toast('此字段仍被动作引用，先修改引用');return;}task().schema=task().schema.filter(f=>f.key!==value);dirty();navigate('schema');}
    else if(action.startsWith('preview:')){const key=action.split(':')[1];const f=task().schema.find(f=>f.key===key);if(f.type==='多选'){const v=previewValue(key)??f.default??[];setPreviewValue(key,v.includes(value)?v.filter(v=>v!==value):[...v,value]);}else setPreviewValue(key,value);render();}
    else if(action==='add-resource'){state.modal={type:'resources'};render();}
    else if(action==='select-resource'){const resource=sample.resources.find(r=>r.id===value);if(resource&&!task().resources.some(r=>r.id===value)){task().resources.push(clone(resource));dirty();}state.modal=null;render();}
    else if(action==='resource-reference'){const s=task().steps.find(s=>s.actions.some(a=>Object.values(a.params).some(b=>b.source==='constant'&&b.value===value)));const a=s?.actions.find(a=>Object.values(a.params).some(b=>b.source==='constant'&&b.value===value));if(a)navigate(`action/${a.id}`);else toast('此资源暂无动作引用');}
    else if(action==='debug-action'){state.actionId=value;startDebug('action');}
    else if(action==='debug-step'){state.stepId=value;startDebug('step');}
    else if(action==='debug-run'){startDebug(value);}
    else if(action==='debug-pause'){if(state.executor.status!=='running')return;state.executor.generation++;state.executor.status='paused';log('已暂停','自动队列保持暂停');render();}
    else if(action==='debug-resume'){if(!state.executor.unknown){state.modal={type:'resume'};render();}}
    else if(action==='debug-restart'){startDebug(state.executor.scope,'restart');}
    else if(action==='resume-mode'){state.modal=null;startDebug(state.executor.scope,value);}
    else if(action==='debug-check'){if(!state.executor.unknown)return;state.executor.unknown=false;state.executor.status='paused';log('画面已核对','选择继续或重新开始');render();}
    else if(action==='debug-reset'){if(state.executor.unknown){toast('先人工核对并确认当前步骤可重做');return;}const generation=state.executor.generation+1;state.executor={status:'idle',scope:'step',boundTask:null,boundConfig:null,outputs:{},stepIndex:0,actionIndex:0,completedSteps:[],automaticQueuePaused:true,unknown:false,logs:[],input:null,output:null,generation};render();}
    else if(action==='upload'||action==='retry-upload'){upload();}
    else if(action==='publish-issues'){state.modal={type:'issues'};render();}
    else if(action==='publish-issue-route'){const parsed=JSON.parse(value);state.currentTaskId=parsed.taskId;navigate(parsed.route);}
    else if(action==='check-release'){state.modal={type:'release-check'};render();}
    else if(action==='close-modal'){state.modal=null;render();}
  }
  function actionFactory(type){return action(type,uid('action'),uid(type.replace('-','_')));}
  function onInput(input){if(!requireAuth())return;const binding=input.dataset.bind;const value=input.type==='number'||input.type==='range'?(input.value===''?'':Number(input.value)):input.value;const [kind,key]=binding.split('.');
    if(kind==='task'){if(key==='id'){const old=task().id;task().id=String(value);state.currentTaskId=String(value);if(state.route===`task/${old}`){state.route=`task/${value}`;history.replaceState(null,'',`${location.pathname}${location.search}#${state.route}`);}}else task()[key]=value;dirty();}
    else if(kind==='step'){step()[key]=value;dirty();}
    else if(kind==='action'){currentAction()[key]=value;dirty();}
    else if(kind==='param'){currentAction().params[key].value=value;dirty();}
    else if(kind==='target'){const index=Number(binding.split('.')[2]);const param=currentAction().params[key];if(!Array.isArray(param.value))param.value=[0,0,1,1];param.value[index]=value;dirty();}
    else if(kind==='field'){if(key==='options')state.fieldDraft.options=String(value).split('\n').map(v=>v.trim()).filter(Boolean);else if(key==='rangeStart'||key==='rangeEnd'){if(typeof state.fieldDraft.default!=='object')state.fieldDraft.default={start:'',end:''};state.fieldDraft.default[key==='rangeStart'?'start':'end']=value;}else state.fieldDraft[key]=value;}
    else if(kind==='publish'){state.publish[key]=value;}
    else if(kind==='preview'){setPreviewValue(key,value);const output=document.getElementById(`preview-${key}`);if(output)output.textContent=value;document.querySelectorAll(`[data-bind="preview.${key}"]`).forEach(node=>{if(node!==input)node.value=value;});}
    else if(binding.startsWith('preview-range:')){const [,k,edge]=binding.split(':');const f=task().schema.find(f=>f.key===k);const range=clone(previewValue(k)??f.default??{start:'',end:''});range[edge]=value;setPreviewValue(k,range);}
  }
  document.addEventListener('click',event=>{const review=event.target.closest('[data-review]');if(review){const kind=review.dataset.review;const value=review.dataset.value;if(kind==='orientation'){state.orientation=value;render();}else if(kind==='auth')setAuth(value);else if(kind==='route')navigate(value);else if(kind==='debug'){state.debugOutcome=value;render();}else if(kind==='publish'){state.publish.outcome=value;render();}else if(kind==='resource'){if(!requireAuth())return;const resource=task().resources[0];if(resource)resource.available=!resource.available;dirty();render();}return;}const target=event.target.closest('[data-command]');if(target&&!target.disabled){onCommand(target.dataset.command,target.dataset.value);return;}if(event.target.dataset.dismiss){state.modal=null;render();}});
  document.addEventListener('input',event=>{if(event.target.dataset.bind)onInput(event.target);});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&state.modal){state.modal=null;render();}});
  window.addEventListener('hashchange',()=>navigate(location.hash.slice(1)||'auth',{replace:true}));
  window.uiV3Developer=Object.freeze({
    getState:()=>clone({...state,authorized:authorized(),fixture:true}),getManifest:()=>clone(manifest),navigate:route=>navigate(String(route)),
    setOrientation:value=>{if(!['portrait','landscape'].includes(value))throw new Error('Unknown orientation');state.orientation=value;render();return state.orientation;},
    reviewAuthState:setAuth,
    reviewDebugState:value=>{if(!requireAuth())return false;if(!['idle','running','paused','success','failed','unknown'].includes(value))throw new Error('Unknown debug state');state.executor.generation++;state.executor.status=value;state.executor.unknown=value==='unknown';state.executor.input={resource:'claim-button',threshold:0.85};state.executor.output=value==='success'?{region:[658,288,90,40],confidence:0.96}:null;log(value==='failed'?'定位失败':value==='unknown'?'结果未知':'执行器状态已更新','本地评审状态',value==='failed'||value==='unknown');navigate('debug');return true;},
    reviewPublishState:value=>{if(!requireAuth())return false;if(!['idle','uploading','success','failed','unknown','expired'].includes(value))throw new Error('Unknown publish state');state.publish.generation++;state.publish.status=value;state.publish.disposition=value==='expired'||value==='failed'?'not-published':value==='success'?'published':'unknown';navigate('publish');return true;}
  });
  state.route=location.hash.slice(1)||'auth';if(state.route!=='auth')state.route='auth';render();
})();
