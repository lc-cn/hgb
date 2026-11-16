(() => {
  'use strict';
  const key='yys-ui-v5-theme';
  const valid=v=>['system','light','dark'].includes(v);
  const params=new URLSearchParams(location.search);
  let saved;try{saved=localStorage.getItem(key)}catch{}
  let preference=valid(params.get('theme'))?params.get('theme'):valid(saved)?saved:'system';
  const dark=matchMedia('(prefers-color-scheme: dark)');
  const reducedTransparency=matchMedia('(prefers-reduced-transparency: reduce)');
  const reducedMotion=matchMedia('(prefers-reduced-motion: reduce)');
  let transparencyOverride=params.get('transparency')==='reduced'?true:null;
  let motionOverride=params.get('motion')==='reduced'?true:null;
  function getState(){return{preference,effective:preference==='system'?(dark.matches?'dark':'light'):preference,transparency:(transparencyOverride??reducedTransparency.matches)?'reduced':'normal',motion:(motionOverride??reducedMotion.matches)?'reduced':'normal'}}
  function apply(){const s=getState(),r=document.documentElement;r.dataset.theme=s.effective;r.dataset.themePreference=s.preference;r.dataset.transparency=s.transparency;r.dataset.motion=s.motion;window.dispatchEvent(new CustomEvent('appearancechange',{detail:s}));updateReview();return s}
  function setPreference(value){if(!valid(value))throw Error('Unknown theme preference');preference=value;try{localStorage.setItem(key,value)}catch{}const url=new URL(location.href);url.searchParams.set('theme',value);history.replaceState(history.state,'',url.href);return apply()}
  function setReviewTransparency(value){transparencyOverride=value===null?null:!!value;return apply()}
  function setReviewMotion(value){motionOverride=value===null?null:!!value;return apply()}
  function controlMarkup(){return '<div class="appearance-switch" role="radiogroup" aria-label="主题">'+[['system','系统','跟随系统'],['light','浅色','浅色主题'],['dark','深色','深色主题']].map(([value,label,name])=>'<button type="button" class="glass-button theme-choice" role="radio" aria-label="'+name+'" aria-checked="'+(preference===value)+'" data-appearance-choice="'+value+'">'+label+'</button>').join('')+'</div>'}
  function updateReview(){document.querySelectorAll('[data-appearance-preference]').forEach(e=>e.value=preference);document.querySelectorAll('[data-appearance-choice]').forEach(e=>e.setAttribute('aria-checked',e.dataset.appearanceChoice===preference));document.querySelectorAll('[data-appearance-transparency]').forEach(e=>e.setAttribute('aria-pressed',getState().transparency==='reduced'));document.querySelectorAll('[data-appearance-motion]').forEach(e=>e.setAttribute('aria-pressed',getState().motion==='reduced'))}
  function chooseTheme(value){setPreference(value);queueMicrotask(()=>document.querySelector('[data-appearance-choice="'+value+'"]')?.focus({preventScroll:true}))}
  window.uiAppearance=Object.freeze({getState,setPreference,setReviewTransparency,setReviewMotion,controlMarkup});
  [dark,reducedTransparency,reducedMotion].forEach(m=>m.addEventListener('change',apply));
  apply();
  document.addEventListener('click',e=>{const a=e.target.closest('a[href]');if(!a)return;let u;try{u=new URL(a.getAttribute('href'),location.href)}catch{return}if(u.protocol!==location.protocol||!u.pathname.includes('/ui-v5/')||!u.pathname.endsWith('.html'))return;u.searchParams.set('theme',preference);if(transparencyOverride!==null)u.searchParams.set('transparency',getState().transparency);if(motionOverride!==null)u.searchParams.set('motion',getState().motion);a.href=u.href},true);
  document.addEventListener('change',e=>{if(e.target.matches('[data-appearance-preference]'))setPreference(e.target.value)});
  document.addEventListener('click',e=>{const choice=e.target.closest('[data-appearance-choice]');if(choice)chooseTheme(choice.dataset.appearanceChoice);if(e.target.closest('[data-appearance-transparency]'))setReviewTransparency(getState().transparency!=='reduced');if(e.target.closest('[data-appearance-motion]'))setReviewMotion(getState().motion!=='reduced')});
  document.addEventListener('keydown',e=>{const choice=e.target.closest('[data-appearance-choice]');if(!choice||!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(e.key))return;e.preventDefault();const buttons=[...choice.closest('.appearance-switch').querySelectorAll('[data-appearance-choice]')],index=buttons.indexOf(choice),next=e.key==='Home'?0:e.key==='End'?buttons.length-1:(index+(['ArrowRight','ArrowDown'].includes(e.key)?1:-1)+buttons.length)%buttons.length;chooseTheme(buttons[next].dataset.appearanceChoice)});
  function ensureReview(host){if(!host.querySelector('.appearance-review')){const view=document.createElement('div');view.className='appearance-review';view.setAttribute('aria-label','设备外外观评审');view.innerHTML='<label>主题<select data-appearance-preference><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></label><button type="button" data-appearance-transparency aria-pressed="false">降低透明度</button><button type="button" data-appearance-motion aria-pressed="false">减少动效</button>';host.append(view)}updateReview()}
  document.addEventListener('DOMContentLoaded',()=>{document.querySelectorAll('.review-tools,#review-controls').forEach(host=>{ensureReview(host);new MutationObserver(()=>ensureReview(host)).observe(host,{childList:true})})});
})();
