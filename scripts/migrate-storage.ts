#!/usr/bin/env bun
// One-time release migration, not application-server/provider code. Never log values.
import {mkdir,open,link,unlink,readFile} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {homedir} from 'node:os';
const home=homedir();
const env=await readFile(join(home,'.config/htpc-next/runtime.env'),'utf8').catch(()=>'');
const setting=(key:string)=>env.split('\n').find(s=>s.startsWith(key+'='))?.slice(key.length+1).trim().replace(/^['"]|['"]$/g,'');
const data=process.env.HTPC_DATA||setting('HTPC_DATA')||join(home,'.local/share/htpc-next');
const sources=[
  ['youtube','client',process.env.HTPC_GOOGLE_TV_CLIENT_FILE||setting('HTPC_GOOGLE_TV_CLIENT_FILE')||join(home,'.config/htpc-next/google-tv-client.json')],
  ['youtube','grant',join(data,'oauth/google-youtube.json')],
  ['steam','grant',join(data,'oauth/steam.json')],
];
await mkdir(join(data,'storage'),{recursive:true,mode:0o700});
for(const [namespace,key,source] of sources){
  const target=join(data,'storage',namespace,key+'.json');
  if(await Bun.file(target).exists()||!await Bun.file(source).exists())continue;
  const value=JSON.parse(await readFile(source,'utf8'));
  await mkdir(dirname(target),{recursive:true,mode:0o700});
  const temp=target+'.'+crypto.randomUUID();
  const file=await open(temp,'wx',0o600);
  try{await file.writeFile(JSON.stringify({present:true,value,revision:crypto.randomUUID()}));await file.sync();}
  finally{await file.close();}
  try{await link(temp,target);console.log('Imported serverStorage '+namespace+'/'+key);}
  catch(e){if((e as {code?:string}).code!=='EEXIST')throw e;}
  finally{await unlink(temp);}
}
