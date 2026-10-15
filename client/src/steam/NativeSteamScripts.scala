package htpc.client.steam

/** Native-client adaptation remains inside the frontend App, executed over generic CDP. */
object NativeSteamScripts:
  val snapshot = """async ids => {
    if(!window.SteamClient||!window.loginStore)throw Error('Steam 客户端正在初始化。');
    const loggedIn=!!window.App?.BHasCurrentUser()&&!!window.App?.GetServicesInitialized(),user=window.App?.m_CurrentUser||{};
    if(!window.__qijianNative){
      const q=window.__qijianNative={errors:{},launch:null};
      q.registration=SteamClient.Apps.RegisterForGameActionShowError((action,id,name,error,detail)=>{
        q.errors[Number(id)]=[error,detail].filter(Boolean).join(' · ');
      });
    }
    const q=window.__qijianNative;
    q.downloads||={};
    if(q.launch&&!ids.includes(q.launch.appId))ids.push(q.launch.appId);
    const actions=loggedIn?await SteamClient.Apps.GetActiveGameActions():[];
    const folders=loggedIn?await SteamClient.InstallFolder.GetInstallFolders():[];
    const overview=window.downloadsStore?.LocalDownloadOverview||{};
    const games=ids.map(id=>{
      const app=window.appStore?.GetAppOverviewByAppID(id),local=app?.local_per_client_data||{};
      const item=app&&loggedIn?window.downloadsStore.GetDownloadItemForOverview({appid:id,selected_clientid:local.clientid})||{}:{},active=overview.update_appid===id;
      // Current CEF uses EAppUpdateProgress_Download=2; sum game/workshop/shader content.
      const parts=(item.update_type_info||[]).map(t=>t.progress?.[2]).filter(Boolean);
      const progress=active?overview.progress?.[2]:undefined;
      const sumTotal=parts.reduce((sum,p)=>sum+Number(p.bytes_total||0),0),last=q.downloads[id]||{};
      const done=Number(progress?.bytes_in_progress??(active?overview.update_bytes_downloaded:undefined)??item.downloaded_bytes??(sumTotal?parts.reduce((sum,p)=>sum+Number(p.bytes_in_progress||0),0):undefined)??last.done??0);
      const total=Number(progress?.bytes_total??(active?overview.update_bytes_to_download:undefined)??item.total_bytes??(sumTotal||undefined)??last.total??0);
      if(total>0)q.downloads[id]={done,total};
      const task=actions.find(a=>Number(a.gameid)===id);
      return {appId:id,installed:!!local.installed,running:local.display_status===4||local.display_status===36,
        downloading:!!item.active,paused:!item.completed&&(!!item.paused||!!overview.paused&&item.appid===id),queued:!item.completed&&item.queue_index>=0,
        bytesDone:done,bytesTotal:total,
        rate:Number(active?overview.update_network_bytes_per_second||0:0),
        percent:Number(item.completed?100:(active?overview.overall_percent_complete:undefined)??item.overall_percent_complete??(total>0?100*done/total:0)),
        error:String(q.errors[id]||item.update_error||''),
        task:String(task?.strTaskName||''),taskDone:String(task?.strNumDone||''),taskTotal:String(task?.strNumTotal||'')};
    });
    let install=null;
    if(q.launch&&games.some(g=>g.appId===q.launch.appId&&g.running))q.launch.seenRunning=true;
    if(loggedIn){const i=await SteamClient.Installs.GetInstallManagerInfo();if(i&&![0,14,16].includes(i.eInstallState))install={
      stage:i.eInstallState,appIds:(i.rgApps||i.rgAppIDs||[]).map(a=>a.nAppID),required:Number(i.nDiskSpaceRequired||0),
      available:Number(i.nDiskSpaceAvailable||0),folder:i.iInstallFolder??-1,error:String(i.errorDetail||'')};}
    return {loggedIn,accountName:String(loginStore.accountName||''),steamId:loggedIn?String(user.strSteamID||''):'',games,launch:q.launch,
      folders:folders.map(f=>({index:f.nFolderIndex,path:f.strFolderPath,free:String(f.nFreeSpace??f.strFreeSpace??'0'),mounted:!!f.bIsMounted})),install};
  }"""

  val qr = """async refresh => {
    const image=[...document.images].find(i=>i.src.startsWith('blob:')&&i.width>=100&&Math.abs(i.width-i.height)<2);
    if(!image)return {image:null,state:'loading'};
    const wrapper=image.parentElement,container=wrapper?.parentElement;
    const retry=container?.querySelector('button');
    const obscured=[image,wrapper,container].filter(Boolean).some(e=>{
      const style=getComputedStyle(e);
      return style.filter!=='none'||style.visibility!=='visible'||style.display==='none';
    });
    // Steam replaces an expired challenge with a blurred store-homepage QR.
    // Never expose that underlying placeholder as a valid login challenge.
    if(retry||obscured){
      if(refresh&&retry&&!retry.disabled){retry.click();return {image:null,state:'loading'}}
      return {image:null,state:retry?'expired':'loading'};
    }
    const src=image.src,blob=await(await fetch(src)).blob();
    if(blob.size>200000||!['image/png','image/svg+xml','image/jpeg','image/gif'].includes(blob.type))return {image:null,state:'unavailable'};
    const data=await new Promise(resolve=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.readAsDataURL(blob)});
    if(image.src!==src||getComputedStyle(wrapper).filter!=='none'||container.querySelector('button'))return {image:null,state:'loading'};
    return {image:data,state:'ready'};
  }"""

  val action = """async (op,id,folder,expectedAccount) => {
    try {
      if(!window.App?.BHasCurrentUser())throw Error('请先扫码登录本机 Steam。');
      if(expectedAccount&&loginStore.accountName.toLowerCase()!==expectedAccount.toLowerCase())throw Error('本机 Steam 与当前游戏库账号不同，请先切换到同一账号。');
      if(!Number.isInteger(id)||id<=0)throw Error('无效的游戏编号。');
      const app=appStore.GetAppOverviewByAppID(id);if(!app)throw Error('本机 Steam 尚未加载此游戏。');
      if(op==='prepare'){
        const i=await SteamClient.Installs.GetInstallManagerInfo();
        if(i.eInstallState>0&&i.eInstallState<14&&!i.rgApps.some(a=>a.nAppID===id))throw Error('请先完成或取消另一个游戏的安装设置。');
        SteamClient.Installs.OpenInstallWizard([id]);
      }
      else if(op==='install'){
        const i=await SteamClient.Installs.GetInstallManagerInfo();
        if(i.eInstallState!==7||!(i.rgApps||i.rgAppIDs||[]).some(a=>a.nAppID===id))throw Error('安装任务已变化，请重新选择游戏。');
        const folders=await SteamClient.InstallFolder.GetInstallFolders();
        if(!folders.some(f=>f.nFolderIndex===folder&&f.bIsMounted))throw Error('安装磁盘已不可用。');
        await SteamClient.Installs.SetInstallFolder(folder);await SteamClient.Installs.SetCreateShortcuts(false,false);
        const fresh=await SteamClient.Installs.GetInstallManagerInfo();
        if(fresh.nDiskSpaceAvailable<fresh.nDiskSpaceRequired)throw Error('所选磁盘空间不足。');
        await SteamClient.Installs.ContinueInstall();
      }else if(op==='cancel'){
        const i=await SteamClient.Installs.GetInstallManagerInfo();
        if(!i.rgApps.some(a=>a.nAppID===id))throw Error('安装任务已变化。');
        await SteamClient.Installs.CancelInstall();
      }
      else if(op==='pause')SteamClient.Downloads.PauseAppUpdate(id,'0');
      else if(op==='resume')SteamClient.Downloads.ResumeAppUpdate(id,'0');
      else if(op==='cancel-launch'){
        const action=(await SteamClient.Apps.GetActiveGameActions()).find(a=>Number(a.gameid)===id&&a.strActionName==='LaunchApp');
        if(!action)throw Error('启动任务已结束。');
        SteamClient.Apps.CancelGameAction(action.nGameActionID);
        if(window.__qijianNative.launch?.appId===id)window.__qijianNative.launch=null;
      }
      else if(op==='play'){
        if(!app.local_per_client_data?.installed||app.local_per_client_data.display_status!==11)throw Error('请先完成游戏安装和更新。');
        window.__qijianNative.errors[id]='';
        window.__qijianNative.launch={appId:id,startedAt:Date.now(),seenRunning:false};
        SteamClient.Apps.RunGame(app.GetGameID(),'', -1,100);
      }else if(op==='uninstall'){
        if([1,4,36].includes(app.local_per_client_data?.display_status))throw Error('请先结束游戏。');
        SteamClient.Installs.OpenUninstallWizard([id],'0',true);
      }
      else if(op==='stop')SteamClient.Apps.TerminateApp(String(id),false);
      else throw Error('未知的游戏操作。');
      return {ok:true,error:''};
    }catch(e){return {ok:false,error:String(e.message||'本机 Steam 操作失败。')}}
  }"""

  val eulas = """async id => {
    const i=await SteamClient.Installs.GetInstallManagerInfo();
    if(i.eInstallState!==8||!i.rgApps.some(a=>a.nAppID===id))return [];
    const agreements=await SteamClient.Apps.LoadEula(id);
    return await Promise.all(agreements.map(async e=>{
      const url=new URL(e.url.replace('http://','https://'));
      url.searchParams.set('eulaLang','schinese');url.searchParams.set('json','1');
      const r=await fetch(url,{credentials:'include',signal:AbortSignal.timeout(10000)});
      if(!r.ok)throw Error('无法读取游戏许可协议，请重试。');
      const data=await r.json();if(typeof data.content!=='string')throw Error('游戏许可协议格式不受支持。');
      const doc=new DOMParser().parseFromString(data.content,'text/html');
      doc.querySelectorAll('script,style').forEach(n=>n.remove());
      return {id:e.id,version:e.version,title:String(data.title||'游戏许可协议'),text:doc.body.textContent.slice(0,1000000)};
    }));
  }"""

  val acceptEulas = """async (id,accepted,expectedAccount)=>{
    try{
      if(!window.App?.BHasCurrentUser()||loginStore.accountName.toLowerCase()!==expectedAccount.toLowerCase())throw Error('本机账号已变化。');
      const i=await SteamClient.Installs.GetInstallManagerInfo();
      if(i.eInstallState!==8||!i.rgApps.some(a=>a.nAppID===id))throw Error('安装任务已变化，请重新选择游戏。');
      const current=await SteamClient.Apps.LoadEula(id);
      if(!current.every(e=>accepted.some(a=>a.id===e.id&&a.version===e.version)))throw Error('协议已更新，请重新阅读。');
      for(const e of current)await SteamClient.Apps.MarkEulaAccepted(id,e.id,e.version);
      await SteamClient.Installs.ContinueInstall();
      return {ok:true,error:''};
    }catch(e){return {ok:false,error:String(e.message)}}
  }"""
