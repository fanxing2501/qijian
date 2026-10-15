#!/usr/bin/env bun
import { strict as assert } from 'node:assert';
import { resolve } from 'node:path';

export async function playerTest(rpc: (module: string, op: string, args?: any) => Promise<any>, uiTab: any) {
  const iframeApi = `window.__yt=[];window.YT={Player:class{constructor(node,options){this.options=options;this.time=0;this.dead=false;window.__yt.push(this);node.innerHTML='<iframe title="YouTube fixture"></iframe>';setTimeout(()=>options.events.onReady({}),20)}playVideo(){this.options.events.onStateChange({data:1})}pauseVideo(){this.options.events.onStateChange({data:2})}getCurrentTime(){return this.time}getDuration(){return 90}seekTo(t){this.time=t}setVolume(v){this.volume=v}setPlaybackRate(v){this.rate=v}destroy(){this.dead=true}}};window.onYouTubeIframeAPIReady();`;
  const fixture = Bun.serve({ hostname: '127.0.0.1', port: 0, async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === '/favicon.ico') return new Response(null,{status:204});
    if (path === '/youtube-api.js') return new Response(iframeApi,{headers:{'Content-Type':'text/javascript'}});
    if (path === '/') return new Response(`<!doctype html><html><head><link rel="stylesheet" href="/tv.css"></head><body><div id="app"></div>
      <script>Object.defineProperty(navigator,'getGamepads',{value:()=>[],configurable:true});const play=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(...args){this.muted=true;return play.apply(this,args)};const append=HTMLHeadElement.prototype.appendChild;HTMLHeadElement.prototype.appendChild=function(child){if(child.tagName==='SCRIPT'&&child.src==='https://www.youtube.com/iframe_api')child.src='/youtube-api.js';return append.call(this,child)};window.__errors=[];addEventListener('error',e=>__errors.push(e.message));addEventListener('unhandledrejection',e=>__errors.push(String(e.reason)));</script>
      <script type="module">import * as fixture from '/app.js';Object.assign(window,fixture);</script></body></html>`, { headers: { 'Content-Type': 'text/html' } });
    const file = path === '/tv.css' || path === '/shaka-player.js' ? resolve('dist/web', path.slice(1)) : resolve('.runtime/player-fixture', path.slice(1));
    if (!file.startsWith(resolve('.runtime/player-fixture') + '/') && !['/tv.css','/shaka-player.js'].includes(path)) return new Response('', {status:404});
    const type = path.endsWith('.m3u8') ? 'application/x-mpegurl' : path.endsWith('.mpd') ? 'application/dash+xml' : undefined;
    const asset=Bun.file(file);
    if(!await asset.exists())return new Response('Not found',{status:404});
    return new Response(asset, { headers: type ? {'Content-Type':type} : {} });
  }});
  const evaluate = async (script: string) => (await rpc('chromium', 'tab.eval', { tab: uiTab, script, options: {awaitPromise:true, timeoutMs:15000} })).Value?.value;
  const wait = async (label: string, script: string, timeout=20000) => {
    const end=Date.now()+timeout;
    while(Date.now()<end) { if(await evaluate(script))return;await Bun.sleep(80); }
    throw Error(`Player: ${label}: ${JSON.stringify(await evaluate(`({text:document.body.innerText,errors:window.__errors,media:[...document.querySelectorAll('video')].map(v=>({time:v.currentTime,ready:v.readyState,error:v.error?.message,paused:v.paused}))})`))}`);
  };
  const click = (id:string) => evaluate(`document.querySelector('[data-tv-focus="${id}"]').click();true`);
  const key = (key:string) => rpc('chromium','tab.pressKey',{tab:uiTab,key});
  const index = (i:number) => `document.querySelector('.video-player-page')?.dataset.playerIndex==='${i}'`;
  const phase = (value:string) => `document.querySelector('.video-player-page')?.dataset.playerPhase==='${value}'`;
  const back = async () => { await key('Escape');await wait('exit removes media', `!document.querySelector('.video-player-page')&&!document.querySelector('video')`); };
  try {
    await rpc('chromium','tab.goto',{tab:uiTab,navigation:{url:`http://127.0.0.1:${fixture.port}`,waitUntil:'DOMContentLoaded',timeoutMs:15000}});
    await wait('fixture mounts', `!!document.querySelector('[data-tv-focus="fixture:files"]')`);
    await evaluate(`document.querySelector('[data-tv-focus="fixture:files"]').focus();true`);
    await key('Enter');
    await wait('real video and audio decode', `document.querySelector('video')?.currentTime>.3&&document.querySelector('video')?.videoWidth===320`);
    assert(await evaluate(`(()=>{const r=document.querySelector('.video-player-page').getBoundingClientRect();return r.x===0&&r.y===0&&r.width===innerWidth&&r.height===innerHeight})()`));
    await click('player:toggle');
    await wait('pause reports actual state', phase('Paused'));
    const paused=await evaluate(`document.querySelector('video').currentTime`);
    await Bun.sleep(200);
    assert(Math.abs(await evaluate(`document.querySelector('video').currentTime`)-paused)<.1);
    await click('player:forward');
    await wait('seek forward while paused', `document.querySelector('video').currentTime>=10`);
    await click('player:toggle');
    await wait('play after seek', phase('Playing'));
    await evaluate(`document.querySelector('video').currentTime=11.8;true`);
    await wait('EOF automatically advances once', `${index(1)}&&${phase('Playing')}`);
    await click('player:auto-next');
    await evaluate(`document.querySelector('video').currentTime=11.8;true`);
    await wait('disabled auto-next stays at EOF', `${index(1)}&&${phase('Ended')}`);
    await Bun.sleep(200);assert(await evaluate(index(1)));
    await click('player:next');
    await wait('manual next', `${index(2)}&&${phase('Playing')}`);
    await click('player:auto-next');
    await evaluate(`document.querySelector('video').currentTime=11.8;true`);
    await wait('final part stops without loop', `${index(2)}&&${phase('Ended')}`);
    assert(await evaluate(`document.querySelector('[data-tv-focus="player:next"]').disabled`));
    await click('player:rewind');await wait('rewind after EOF', `${index(2)}&&${phase('Paused')}&&document.querySelector('video').currentTime<3`);
    await click('player:toggle');await wait('play again after EOF seek', phase('Playing'));
    await back();
    await wait('original focus restored', `document.activeElement?.getAttribute('data-tv-focus')==='fixture:files'`);
    await click('fixture:resume');
    await wait('requested part and offset', `${index(1)}&&document.querySelector('video')?.currentTime>=3&&${phase('Playing')}`);
    await click('player:toggle');
    await click('player:volume-down');await click('player:speed');
    await click('player:parts');await click('player:part:0');
    await wait('part selection preserves settings', `${index(0)}&&${phase('Playing')}`);
    assert(await evaluate(`Math.abs(document.querySelector('video').volume-.9)<.001&&document.querySelector('video').playbackRate===1.25`));
    await evaluate(`document.querySelector('[data-tv-focus="player:toggle"]').focus();true`);
    await wait('controls hide during playback', `document.querySelector('.video-player-page').dataset.controls==='false'`,6000);
    await evaluate(`(()=>{window.__playerPad={connected:true,mapping:'standard',axes:[0,0],buttons:Array.from({length:17},()=>({pressed:false}))};Object.defineProperty(navigator,'getGamepads',{value:()=>[window.__playerPad],configurable:true});return true})()`);
    await evaluate(`new Promise(resolve=>{window.__playerPad.buttons[0].pressed=true;setTimeout(()=>{window.__playerPad.buttons[0].pressed=false;setTimeout(()=>resolve(true),100)},100)})`);
    await wait('gamepad A reveals controls and pauses', `${phase('Paused')}&&document.querySelector('.video-player-page').dataset.controls==='true'`);
    await evaluate(`window.__playerPad.connected=false;true`);
    const screenshot=await rpc('chromium','tab.screenshot',{tab:uiTab});
    await Bun.write('artifacts/test-video-player.png',Buffer.from(screenshot.base64,'base64'));
    await evaluate(`document.querySelector('[data-tv-focus="player:back"]').focus();true`);
    await key('ArrowUp');assert(await evaluate(`document.activeElement.closest('[data-tv-scope]')!==null`));
    for(let n=0;n<14;n++){await key('Tab');assert(await evaluate(`document.activeElement.closest('[data-tv-scope]')!==null`));}
    await back();
    console.log('✓ fullscreen unified player: real video decode, pause/seek, ordered EOF, auto-next toggle, last P, resume, selection and focus restoration');

    for(const format of ['hls','dash']) {
      await click(`fixture:${format}`);
      await wait(`${format} real audio/video decode`, `document.querySelector('video')?.currentTime>.2&&document.querySelector('video')?.videoWidth===320`,30000);
      await click('player:toggle');await wait(`${format} paused`, phase('Paused'));
      await evaluate(`document.querySelector('video').currentTime=3;true`);
      await wait(`${format} seek`, `document.querySelector('video').currentTime>=3`);
      await click('player:toggle');await wait(`${format} resumes`, `document.querySelector('video').currentTime>3.2`);
      await back();
    }
    console.log('✓ packaged Shaka engine: real HLS and DASH decoding, seek/pause/resume and teardown');

    await click('fixture:race');await wait('deferred source', index(0));
    await click('player:next');await wait('next loads while previous source pending', `${index(1)}&&${phase('Playing')}`);
    await evaluate(`settleFirstPart();true`);await Bun.sleep(150);
    assert.deepEqual(await evaluate(`playerFixtureStats().created`),['second']);
    assert.equal(await evaluate(`playerFixtureStats().aborted`),true);
    await evaluate(`oldPlayerEvent(0,false);oldPlayerEvent(0,false);true`);
    await wait('one EOF fails on next source', `${index(2)}&&${phase('Failed')}`);
    assert.equal(await evaluate(`playerFixtureStats().attempts`),1);
    await click('player:retry');await wait('retry re-resolves current source', `${index(2)}&&${phase('Playing')}`);
    await evaluate(`oldPlayerEvent(0,true);oldPlayerEvent(0,false);true`);
    assert(await evaluate(`${index(2)}&&${phase('Playing')}`));
    await back();
    await evaluate(`oldPlayerEvent(1,false);true`);
    assert.equal(await evaluate(`playerFixtureStats().closed`),2);
    assert(await evaluate(`!document.querySelector('.video-player-page')`));
    await click('fixture:race');await wait('deferred source before exit',index(0));await back();
    await evaluate(`settleFirstPart();true`);await Bun.sleep(150);assert.deepEqual(await evaluate(`playerFixtureStats().created`),[]);
    console.log('✓ stale source resolution, duplicate EOF, late engine events, fresh-source retry and unmount cancellation');

    // Official IFrame API contract, deterministic provider events (no test API in the deployed bundle).
    assert.equal(await evaluate(`typeof window.YT`),'undefined');
    await click('fixture:youtube');
    await wait('YouTube adapter uses shared state', phase('Playing'));
    await evaluate(`window.__yt[0].options.events.onStateChange({data:0});window.__yt[0].options.events.onStateChange({data:0});true`);
    await wait('YouTube EOF advances exactly once', `${index(1)}&&${phase('Playing')}`);
    assert.equal(await evaluate(`__yt.length`),2);assert.equal(await evaluate(`__yt[0].dead`),true);
    await click('player:toggle');await wait('YouTube pause', phase('Paused'));
    await evaluate(`__yt[0].options.events.onError({data:100});true`);assert(await evaluate(phase('Paused')));
    await click('player:toggle');await wait('YouTube resumes', phase('Playing'));
    await evaluate(`__yt[1].options.events.onStateChange({data:2});true`);
    await click('player:toggle');await wait('toggle follows actual provider pause state', phase('Playing'));
    await back();assert.equal(await evaluate(`__yt[1].dead`),true);
    assert.deepEqual(await evaluate(`window.__errors`),[]);
    console.log('✓ YouTube adapter: shared controls, provider EOF → next P, stale callback isolation and disposal');
  } finally { await fixture.stop(true); }
}

if (import.meta.main) {
  const base = process.env.HTPC_PLAYER_TEST_BASE || 'http://127.0.0.1:8787';
  const rpc = async (module:string,op:string,args:any={}) => {
    const response=await fetch(`${base}/api/v1/${module}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:crypto.randomUUID(),op,args})});
    const value=await response.json() as any;
    if(value.outcome!=='result')throw Error(`${module}/${op}: ${JSON.stringify(value)}`);
    return value.payload;
  };
  const browser=await rpc('chromium','instance.new',{name:'Video player verification',profile:null,proxy:null,language:'zh-CN',width:1920,height:1080});
  try {
    const tab=(await rpc('chromium','tab.new',{instance:browser.id})).ref;
    await playerTest(rpc,tab);
  } finally { await rpc('chromium','instance.delete',{instance:browser.id}); }
}
