#!/usr/bin/env bun
// Only external HTTP responses are mocked. Production Scala.js login state machines,
// the Chromium WebSocket, isolated worlds and server KV all run unchanged.
export async function accountFixture(base: string, instance: string, provider: 'youtube' | 'steam') {
  const socket = new WebSocket(base.replace(/^http/, 'ws') + `/api/v1/chromium/${instance}/cdp`);
  const state = { approved: false, scanned: false, denied: false, expired: false, failLibrary: false, empty: false,
    starts: 0, polls: 0, libraryCalls: 0, refreshes: 0, slowDown: false, wrongTransfer: false };
  const errors: unknown[] = [];
  const requests: {url: string, fields: Record<string,string>}[] = [];
  let next = 0;
  const pending = new Map<number, { resolve: (v:any)=>void, reject:(e:unknown)=>void, timer:Timer }>();
  const send = (method: string, params:unknown={}, sessionId?:string):Promise<any> => new Promise((resolve,reject)=>{
    const id=++next,timer=setTimeout(()=>{pending.delete(id);reject(Error('Fixture CDP timeout: '+method))},15000);
    pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params,sessionId}));
  });
  const steamId='76561198000000001';
  const token=(seconds:number)=>'e30.'+Buffer.from(JSON.stringify({sub:steamId,exp:Math.floor(Date.now()/1000)+seconds})).toString('base64url')+'.PRIVATE_FIXTURE_TOKEN';
  async function event(message:any) {
    if(message.method==='Target.attachedToTarget') {
      const patterns=provider==='youtube'?['https://oauth2.googleapis.com/device/code','https://oauth2.googleapis.com/token','https://www.googleapis.com/youtube/v3/*']:
        ['https://api.steampowered.com/IAuthenticationService/*','https://api.steampowered.com/IPlayerService/*','https://login.steampowered.com/jwt/*','https://steamcommunity.com/login/settoken'];
      await send('Fetch.enable',{patterns:patterns.map(urlPattern=>({urlPattern}))},message.params.sessionId);
      await send('Runtime.runIfWaitingForDebugger',{},message.params.sessionId);
    }
    if(message.method!=='Fetch.requestPaused')return;
    const {requestId,request}=message.params,url=new URL(request.url);
    const fields=Object.fromEntries(new URLSearchParams(request.postData||''));requests.push({url:request.url,fields});
    let json:any={},status=200,result=1,cookie:string|undefined;
    if(provider==='youtube') {
      if(url.pathname==='/device/code') {state.starts++;json={device_code:'PRIVATE_DEVICE_CODE',user_code:'AbCD-WXYZ',verification_url:'https://www.google.com/device',expires_in:60,interval:5};}
      else if(url.pathname==='/token') {
        if(fields.grant_type==='refresh_token') {state.refreshes++;json={access_token:'PRIVATE_GOOGLE_ACCESS_REFRESHED',expires_in:3600};}
        else {state.polls++;
          if(state.approved)json={access_token:'PRIVATE_GOOGLE_ACCESS',refresh_token:'PRIVATE_GOOGLE_REFRESH',expires_in:1};
          else {status=400;json={error:state.expired?'expired_token':state.denied?'access_denied':state.slowDown?'slow_down':'authorization_pending'};state.slowDown=false;}
        }
      } else if(url.pathname.endsWith('/channels'))json={items:[{id:'fixture-channel',snippet:{title:'Fixture account'},contentDetails:{relatedPlaylists:{uploads:'fixture-uploads'}}}]};
      else if(url.pathname.endsWith('/subscriptions'))json={items:[{snippet:{resourceId:{channelId:'fixture-channel'}}}]};
      else json={items:[{snippet:{title:'Subscribed fixture',resourceId:{videoId:'testvid9999'},thumbnails:{high:{url:'/youtube.svg'}},videoOwnerChannelTitle:'Fixture channel'}}]};
    } else {
      if(url.pathname.includes('BeginAuthSessionViaQR')) {state.starts++;json={response:{client_id:'PRIVATE_CLIENT_1',request_id:'PRIVATE_REQUEST',challenge_url:'https://s.team/q/1/initial',interval:5}};}
      else if(url.pathname.includes('PollAuthSessionStatus')) {state.polls++;
        if(state.expired)result=27;else if(state.denied)result=9;
        else if(state.approved)json={response:{refresh_token:token(86400),access_token:token(1),account_name:'Fixture Steam'}};
        else json={response:{new_client_id:'PRIVATE_CLIENT_2',new_challenge_url:'https://s.team/q/1/rotated',had_remote_interaction:state.scanned}};
      } else if(url.pathname==='/jwt/finalizelogin') {state.refreshes++;json={transfer_info:[{url:state.wrongTransfer?'https://example.com/login/settoken':'https://steamcommunity.com/login/settoken',params:{nonce:'PRIVATE_NONCE',auth:'PRIVATE_AUTH'}}]};}
      else if(url.pathname==='/login/settoken') {json={result:1};cookie=`steamLoginSecure=${steamId}%7C%7C${token(3600)}; Path=/; Secure; HttpOnly; SameSite=None`;}
      else {state.libraryCalls++;
        if(state.failLibrary){status=500;result=2;}
        else {const games=state.empty?[]:Array.from({length:37},(_,n)=>{const i=n*17%37;return{appid:1000+i,name:'Game '+String(i).padStart(2,'0'),rtime_last_played:i<2?0:1700000000+i*1000,playtime_forever:37-i,playtime_2weeks:i}});json={response:{game_count:games.length,games}};}
      }
    }
    await send('Fetch.fulfillRequest',{requestId,responseCode:status,responseHeaders:[{name:'Content-Type',value:'application/json'},
      {name:'x-eresult',value:String(result)},...(cookie?[{name:'Set-Cookie',value:cookie}]:[])],body:Buffer.from(JSON.stringify(json)).toString('base64')},message.sessionId);
  }
  socket.onmessage=({data})=>{const message=JSON.parse(String(data)),call=pending.get(message.id);if(call){pending.delete(message.id);clearTimeout(call.timer);message.error?call.reject(message.error):call.resolve(message.result);}else void event(message).catch(e=>errors.push(e));};
  await new Promise<void>((resolve,reject)=>{socket.onopen=()=>resolve();socket.onerror=reject;});
  await send('Target.setAutoAttach',{autoAttach:true,waitForDebuggerOnStart:true,flatten:true});
  return {state,errors,requests,async close(){await send('Target.setAutoAttach',{autoAttach:false,waitForDebuggerOnStart:false,flatten:true});socket.close();}};
}

export const auditCredentials = `(()=>{
  window.__credentialLeaks=[];
  const inspect=s=>{if(/PRIVATE_(?:FIXTURE_TOKEN|GOOGLE_ACCESS|GOOGLE_REFRESH|DEVICE_CODE|CLIENT_|REQUEST|NONCE|AUTH)/.test(s))window.__credentialLeaks.push('credential crossed the UI boundary')};
  const Socket=window.WebSocket;
  window.WebSocket=class extends Socket{constructor(...a){super(...a);this.addEventListener('message',e=>inspect(String(e.data)));}};
  const original=window.fetch.bind(window);window.fetch=async(...a)=>{const r=await original(...a);if(String(a[0]).startsWith('/api/'))inspect(await r.clone().text());return r;};return true;
})()`;
