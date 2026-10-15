#!/usr/bin/env bun
import { strict as assert } from 'node:assert';
import { openCdp } from './cdp-client';

export async function hostTest(base: string, config: string, rpc: (m: string, op: string, args: any) => Promise<any>, raw: typeof rpc) {
  let connections = 0;
  const upstream = Bun.serve<{ connection: number }>({
    hostname: '127.0.0.1', port: 0,
    fetch(request, server) {
      if (new URL(request.url).pathname === '/json/list') return Response.json([
        { id: 'public', title: 'Fixture app', url: 'about:blank', type: 'page', webSocketDebuggerUrl: `ws://127.0.0.1:${server.port}/socket` },
        { id: 'foreign', title: 'Invalid authority', url: 'about:blank', type: 'page', webSocketDebuggerUrl: 'ws://example.com:8080/socket' },
      ]);
      if (server.upgrade(request, { data: { connection: ++connections } })) return;
      return new Response('not found', { status: 404 });
    },
    websocket: { message(socket, data) {
      const request = JSON.parse(String(data));
      socket.send(JSON.stringify({ id: request.id, result: { result: { value: socket.data.connection } } }));
    } },
  });
  try {
    await Bun.write(config, JSON.stringify({ fixture: { unit: `groot-htpc-test-absent-${crypto.randomUUID()}.service`, cdp: `http://127.0.0.1:${upstream.port}` } }));
    assert.equal((await rpc('host', 'service.status', { service: 'fixture' })).available, false);
    assert.equal((await raw('host', 'service.start', { service: 'fixture' })).payload.code, 'Execution');
    assert.equal((await rpc('host', 'cdp.targets', { service: 'fixture' })).length, 2);
    assert.equal((await raw('host', 'cdp.socket', { service: 'fixture', target: 'missing' })).payload.code, 'NotFound');
    assert.equal((await raw('host', 'cdp.socket', { service: 'fixture', target: 'foreign' })).payload.code, 'InvalidArgument');
    const endpoint = await rpc('host', 'cdp.socket', { service: 'fixture', target: 'public' });
    const a = await openCdp(base.replace(/^http/, 'ws') + endpoint.path);
    const b = await openCdp(base.replace(/^http/, 'ws') + endpoint.path);
    try { assert.notEqual(await a.evaluate('1'), await b.evaluate('1')); }
    finally { a.close(); b.close(); }
    console.log('✓ host service failure, target discovery, stale/foreign target rejection and independent CDP bridge connections');
  } finally { await upstream.stop(true); await Bun.write(config, '{}'); }
}
