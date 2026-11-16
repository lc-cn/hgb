import fs from 'node:fs/promises';
export async function connect(){
 const tabs=await(await fetch('http://127.0.0.1:9444/json/list')).json();const target=tabs.find(t=>t.type==='page');if(!target)throw Error('Missing dedicated review page');
 const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((ok,no)=>{ws.onopen=ok;ws.onerror=no});let id=0;const pending=new Map(),errors=[];
 ws.onmessage=e=>{const r=JSON.parse(e.data);if(r.id){const p=pending.get(r.id);pending.delete(r.id);if(p)r.error?p.reject(Error(JSON.stringify(r.error))):p.resolve(r.result)}else if(r.method==='Runtime.exceptionThrown')errors.push(r.params.exceptionDetails)};
 const call=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}))});
 const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value};
 const delay=n=>new Promise(r=>setTimeout(r,n));await call('Page.enable');await call('Runtime.enable');
 const open=async(file='app.html',suffix='')=>{const u=new URL(file+suffix,new URL('../../',import.meta.url));u.searchParams.set('_check',String(Date.now()));await call('Page.navigate',{url:u.href});for(let k=0;k<70;k++){await delay(70);try{if(await evaluate(`document.readyState==='complete'&&location.href===${JSON.stringify(u.href)}`)){await delay(180);return}}catch{}}throw Error('Navigation failed '+u.href)};
 const viewport=(width=1600,height=1200)=>call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
 const screenshot=async(path,selector='.stage>.device')=>{await evaluate('scrollTo(0,0)');await delay(100);const clip=await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e)throw Error('No screenshot target');const r=e.getBoundingClientRect();return{x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,scale:1}})()`);const r=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true,fromSurface:true,clip});await fs.writeFile(path,Buffer.from(r.data,'base64'))};
 return {call,evaluate,open,viewport,delay,screenshot,errors,close:()=>ws.close()};
}
