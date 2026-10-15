#!/usr/bin/env bun
/** Small diagnostic/integration CDP client. Callers choose what public data to print. */
export async function openCdp(url: string) {
  const socket = new WebSocket(url);
  let sequence = 0;
  const pending = new Map<number, { resolve: (value: any) => void, reject: (error: unknown) => void, timer: Timer }>();
  socket.onmessage = ({ data }) => {
    const message = JSON.parse(String(data)), call = pending.get(message.id);
    if (call) { pending.delete(message.id); clearTimeout(call.timer); message.error ? call.reject(Error(message.error.message)) : call.resolve(message.result); }
  };
  socket.onclose = () => { for (const call of pending.values()) { clearTimeout(call.timer); call.reject(Error('CDP disconnected')); } pending.clear(); };
  await new Promise<void>((resolve, reject) => { socket.onopen = () => resolve(); socket.onerror = reject; });
  const call = (method: string, params: any = {}): Promise<any> => new Promise((resolve, reject) => {
    const id = ++sequence, timer = setTimeout(() => { pending.delete(id); reject(Error(method + ' timed out')); }, 20000);
    pending.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }));
  });
  return { call, close: () => socket.close(), async evaluate(expression: string) {
    const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, timeout: 15000 });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  } };
}
