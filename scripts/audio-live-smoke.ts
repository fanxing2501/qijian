#!/usr/bin/env bun
import { strict as assert } from 'node:assert';
import { graph, node, sinkName } from '../desktop/audio';
import { openCdp } from './cdp-client';

const config = await Bun.file(`${process.env.HOME}/.config/htpc-next/audio.json`).json();
const before = await graph(), sink = node(before), physical = node(before, config.output);
assert(sink && physical, 'private and physical sinks must be present');
const defaultSink = (data: any[]) => data.find(o => o.type === 'PipeWire:Interface:Metadata' && o.props?.['metadata.name'] === 'default')?.metadata?.find((m: any) => m.key === 'default.audio.sink')?.value?.name;
assert.notEqual(defaultSink(before), sinkName, 'private bus must not become the system default');
const baseline = before.filter(o => o.type === 'PipeWire:Interface:Node' && (o.id === physical.id || /^groot-usb-/.test(o.info?.props?.['node.name'] || '')))
  .map(o => ({ id: o.id, name: o.info.props['node.name'], volume: o.info.params?.Props?.[0]?.channelVolumes, mute: o.info.params?.Props?.[0]?.mute }));
function unrelatedUnchanged(data: any[]) {
  assert.equal(defaultSink(data), defaultSink(before));
  for (const old of baseline) {
    const current = node(data, old.name); assert(current);
    assert.deepEqual(current.info.params?.Props?.[0]?.channelVolumes, old.volume, `${old.name} volume must not change`);
    assert.equal(current.info.params?.Props?.[0]?.mute, old.mute);
  }
}
const links = (data: any[], from: number, to: number) => data.filter(o => o.type === 'PipeWire:Interface:Link' && o.info?.['output-node-id'] === from && o.info?.['input-node-id'] === to);
assert(links(before, node(before, 'qijian.audio.output').id, physical.id).length > 0, 'bus output connects to the configured speaker');
async function until(label: string, read: () => Promise<boolean>) {
  const end = Date.now() + 10000;
  while (Date.now() < end) { if (await read()) return; await Bun.sleep(150); }
  throw Error('Timed out: ' + label);
}
const page = (await (await fetch('http://127.0.0.1:9228/json/list')).json()).find((t: any) => t.type === 'page' && t.url.startsWith('http://127.0.0.1:8787/'));
assert(page);
const cdp = await openCdp(page.webSocketDebuggerUrl);
let sample: ReturnType<typeof Bun.spawn> | undefined;
try {
  const changed = Bun.spawn(['bun', 'desktop/audio.ts', 'set-volume', '25'], { stdout: 'inherit', stderr: 'inherit' });
  assert.equal(await changed.exited, 0);
  unrelatedUnchanged(await graph());
  // A real PulseAudio client with silent samples exercises the route used by Proton.
  sample = Bun.spawn(['ffplay', '-hide_banner', '-loglevel', 'error', '-nodisp', '-autoexit', '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo', '-t', '8'], {
    env: { ...process.env, SDL_AUDIODRIVER: 'pulseaudio', PULSE_SINK: sinkName }, stdout: 'ignore', stderr: 'inherit',
  });
  await until('PulseAudio stream routed to HTPC only', async () => {
    const data = await graph(); unrelatedUnchanged(data);
    const stream = data.find(o => o.type === 'PipeWire:Interface:Node' && o.info?.props?.['media.class'] === 'Stream/Output/Audio' && String(o.info.props['application.process.id']) === String(sample!.pid));
    return !!stream && links(data, stream.id, sink.id).length > 0;
  });
  sample.kill('SIGTERM'); await sample.exited; sample = undefined;
  await cdp.evaluate(`(async()=>{const context=new AudioContext();const oscillator=context.createOscillator();const gain=context.createGain();gain.gain.value=0;oscillator.connect(gain).connect(context.destination);oscillator.start();window.__qijianAudioSmoke={context,oscillator};await context.resume();return true})()`);
  await until('kiosk Chromium stream routed to HTPC only', async () => {
    const data = await graph(); unrelatedUnchanged(data);
    return data.some(o => o.type === 'PipeWire:Interface:Node' && o.info?.props?.['media.class'] === 'Stream/Output/Audio' && /chrom/i.test(o.info.props['application.name'] || '') && links(data, o.id, sink.id).length > 0);
  });
  console.log('✓ private HTPC bus: kiosk and PulseAudio playback routed independently; master/default/Windows USB audio unchanged');
} finally {
  if (sample) { sample.kill('SIGTERM'); await sample.exited; }
  await cdp.evaluate(`(async()=>{const s=window.__qijianAudioSmoke;if(s){s.oscillator.stop();await s.context.close();delete window.__qijianAudioSmoke}return true})()`).catch(() => {});
  cdp.close();
  const restore = Bun.spawn(['bun', 'desktop/audio.ts', 'set-volume', String(Math.round(config.volume * 100))], { stdout: 'inherit', stderr: 'inherit' });
  assert.equal(await restore.exited, 0);
  unrelatedUnchanged(await graph());
}
