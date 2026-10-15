#!/usr/bin/env bun
import {strict as assert} from 'node:assert';

export async function infrastructureTest(base:string,rpc:(m:string,o:string,a?:any)=>Promise<any>) {
  const browser=await rpc('chromium','instance.new',{name:'CDP bridge test',profile:null,proxy:null,language:'en',width:800,height:600});
  const endpoint=await rpc('chromium','instance.socket',{instance:browser.id});
  async function open(){
    const socket=new WebSocket(base.replace(/^http/,'ws')+endpoint.path);
    let id=0;const events:any[]=[],pending=new Map<number,{resolve:(v:any)=>void,reject:(e:unknown)=>void,timer:Timer}>();
    const disconnected=new Promise<void>(resolve=>socket.onclose=()=>{for(const p of pending.values()){clearTimeout(p.timer);p.reject(Error('disconnected'))}pending.clear();resolve()});
    socket.onmessage=({data})=>{const j=JSON.parse(String(data)),p=pending.get(j.id);if(p){pending.delete(j.id);clearTimeout(p.timer);j.error?p.reject(j.error):p.resolve(j.result)}else events.push(j)};
    await new Promise<void>((resolve,reject)=>{socket.onopen=()=>resolve();socket.onerror=reject});
    return {socket,events,disconnected,call:(method:string,params:any={},sessionId?:string):Promise<any>=>new Promise((resolve,reject)=>{
      const n=++id,timer=setTimeout(()=>{pending.delete(n);reject(Error(method+' timeout'))},15000);pending.set(n,{resolve,reject,timer});socket.send(JSON.stringify({id:n,method,params,sessionId}));
    })};
  }
  const first=await open(),second=await open();
  try {
    const results=await Promise.all(Array.from({length:12},(_,i)=>(i%2?first:second).call('Browser.getVersion')));
    assert(results.every(r=>r.product.includes('Chrome')));
    await first.call('Target.setDiscoverTargets',{discover:true});
    const tab=await first.call('Target.createTarget',{url:'about:blank'});
    const session=(await first.call('Target.attachToTarget',{targetId:tab.targetId,flatten:true})).sessionId;
    const other=(await second.call('Target.attachToTarget',{targetId:tab.targetId,flatten:true})).sessionId;
    await assert.rejects(second.call('Runtime.evaluate',{expression:'1'},session));
    const frame=(await first.call('Page.getFrameTree',{},session)).frameTree.frame.id;
    const world={tab:{instance:browser.id,tab:tab.targetId},name:'storage-test'};
    const context=(await first.call('Page.createIsolatedWorld',{frameId:frame,worldName:world.name},session)).executionContextId;
    const key={namespace:'test-app',key:'secret'};
    await first.call('Runtime.evaluate',{expression:"globalThis.privateValue={token:'PRIVATE_TRANSFER_TOKEN'};void 0",contextId:context},session);
    const stamp=await rpc('storage','writeFrom',{key,world,expression:'privateValue',expected:'0'});
    assert(!JSON.stringify(stamp).includes('PRIVATE'));
    assert.equal((await rpc('storage','readInto',{key,world,binding:'restored'})).revision,stamp.revision);
    const same=await first.call('Runtime.evaluate',{expression:'restored.token===privateValue.token',contextId:context,returnByValue:true},session);
    assert.equal(same.result.value,true);
    const main=await second.call('Runtime.evaluate',{expression:'typeof restored',returnByValue:true},other);
    assert.equal(main.result.value,'undefined');
    await first.call('Runtime.evaluate',{expression:'privateValue=null;restored=null',contextId:context},session);
    await rpc('storage','delete',{key,expected:stamp.revision});
    assert(first.events.some(e=>e.method==='Target.targetCreated'));
    second.socket.close();await second.disconnected;
    const reconnect=await open();assert((await reconnect.call('Browser.getVersion')).product);reconnect.socket.close();await reconnect.disconnected;
    const pending=first.call('Runtime.evaluate',{expression:'new Promise(()=>{})',awaitPromise:true},session);
    const rejected=assert.rejects(pending);
    await rpc('chromium','instance.delete',{instance:browser.id});
    await Promise.race([first.disconnected,new Promise((_,reject)=>setTimeout(()=>reject(Error('socket did not close with browser')),10000))]);
    await rejected;
    assert.equal((await fetch(base+endpoint.path)).status,404);
    console.log('✓ CDP multiplexing/events/session isolation/reconnect/process close, private KV transfers and isolated worlds');
  }finally{first.socket.close();second.socket.close();}
}
