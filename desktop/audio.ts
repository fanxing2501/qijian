#!/usr/bin/env bun
/** Private HTPC audio bus. Never changes the system default device or its volume. */
import { mkdir, rename } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const sinkName = 'qijian.audio';
const directory = join(homedir(), '.config/htpc-next');
const configPath = join(directory, 'audio.json');
type Settings = { output: string; volume: number };
async function command(args: string[]) {
  const child = Bun.spawn(args, { stdout: 'pipe', stderr: 'pipe' });
  const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  if (code !== 0) throw Error(`${args[0]} failed: ${stderr.trim()}`);
  return stdout;
}
export async function graph(): Promise<any[]> { return JSON.parse(await command(['/usr/bin/pw-dump'])); }
export function node(data: any[], name = sinkName) {
  return data.find(o => o.type === 'PipeWire:Interface:Node' && o.info?.props?.['node.name'] === name);
}
async function save(value: Settings) {
  await mkdir(directory, { recursive: true });
  const temp = `${configPath}.${process.pid}.tmp`;
  await Bun.write(temp, JSON.stringify(value) + '\n', { mode: 0o600 });
  await rename(temp, configPath);
}
async function settings(): Promise<Settings> {
  if (await Bun.file(configPath).exists()) {
    const value = await Bun.file(configPath).json();
    if (typeof value.output !== 'string' || !value.output || value.output === sinkName || !Number.isFinite(value.volume) || value.volume < 0 || value.volume > 1)
      throw Error('Invalid HTPC audio configuration');
    return value;
  }
  const data = await graph();
  const metadata = data.find(o => o.type === 'PipeWire:Interface:Metadata' && o.props?.['metadata.name'] === 'default');
  const output = metadata?.metadata?.find((m: any) => m.key === 'default.audio.sink')?.value?.name;
  if (!output || output === sinkName || node(data, output)?.info?.props?.['media.class'] !== 'Audio/Sink') throw Error('No physical output selected');
  const value = { output, volume: 0.3 };
  await save(value); return value;
}
async function volume(id: number, value: number) { await command(['/usr/bin/wpctl', 'set-volume', String(id), `${Math.round(value * 100)}%`]); }

async function serve() {
  const config = await settings();
  if (node(await graph())) throw Error('HTPC audio bus already exists');
  const loop = Bun.spawn(['/usr/bin/pw-loopback', '--name=qijian-audio', '--channels=2', '--channel-map=[ FL, FR ]', '--latency=20',
    '--capture-props=' + JSON.stringify({ 'media.class': 'Audio/Sink', 'node.name': sinkName, 'node.description': '栖间 HTPC',
      'node.virtual': true, 'priority.session': -1000, 'audio.position': ['FL', 'FR'] }),
    '--playback-props=' + JSON.stringify({ 'node.name': 'qijian.audio.output', 'node.description': '栖间 HTPC → 扬声器',
      'target.object': config.output, 'node.passive': true, 'node.dont-fallback': true, 'audio.position': ['FL', 'FR'] }),
  ], { stdout: 'inherit', stderr: 'inherit' });
  let stopping = false;
  const stop = () => { stopping = true; loop.kill('SIGTERM'); };
  process.on('SIGTERM', stop); process.on('SIGINT', stop);
  try {
    let found: any;
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline && loop.exitCode === null && !stopping) {
      found = node(await graph()); if (found) break;
      await Bun.sleep(100);
    }
    if (!found) throw Error('HTPC audio bus startup failed');
    await volume(found.id, config.volume);
    await command(['/usr/bin/systemd-notify', '--ready']);
    const code = await loop.exited;
    if (!stopping) throw Error(`HTPC audio bus exited (${code})`);
  } finally {
    process.off('SIGTERM', stop); process.off('SIGINT', stop);
    if (loop.exitCode === null) loop.kill('SIGTERM');
    await loop.exited;
  }
}

if (import.meta.main) {
  switch (process.argv[2] || 'status') {
    case 'prepare': await settings(); break;
    case 'serve': await serve(); break;
    case 'set-volume': {
      const percent = Number(process.argv[3]);
      if (!Number.isFinite(percent) || percent < 0 || percent > 100) throw Error('Volume must be 0–100');
      const config = await settings(), current = node(await graph());
      if (!current) throw Error('HTPC audio bus is not running');
      await volume(current.id, percent / 100);
      await save({ ...config, volume: percent / 100 });
      console.log(`HTPC volume: ${percent}%`); break;
    }
    case 'status': {
      const config = await settings(), current = node(await graph());
      console.log(JSON.stringify({ ...config, sink: sinkName, running: !!current,
        actual: current ? (await command(['/usr/bin/wpctl', 'get-volume', String(current.id)])).trim() : null })); break;
    }
    default: throw Error('Usage: audio.ts prepare|serve|status|set-volume <0–100>');
  }
}
