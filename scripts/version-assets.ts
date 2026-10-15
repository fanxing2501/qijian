#!/usr/bin/env bun
// Reproducible tar archives normalize mtimes, so asset URLs must carry content identity.
const versions = new Map<string, string>();
for (const name of ['app.js', 'tv.css']) {
  const bytes = await Bun.file(`dist/web/${name}`).arrayBuffer();
  versions.set(name, new Bun.CryptoHasher('sha256').update(bytes).digest('hex').slice(0, 16));
}
for (const name of ['index.html', 'console.html']) {
  const path = `dist/web/${name}`;
  let html = await Bun.file(path).text();
  for (const [asset, hash] of versions) html = html.replaceAll(`/${asset}"`, `/${asset}?v=${hash}"`);
  await Bun.write(path, html);
}
