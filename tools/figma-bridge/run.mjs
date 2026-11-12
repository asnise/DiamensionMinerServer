// Send a Figma Plugin API script to the "DM Figma Bridge" plugin.
//   node run.mjs <script.js> [--channel flowux] [--port 3055] [--png out.png]
// The script body runs as an async function with `figma` in scope; `return` a JSON-able value.
// --png: if the script returns { png: <base64> } (e.g. figma.base64Encode(await node.exportAsync())),
// the image is written to that path instead of printed.
import { readFileSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args.splice(i, 2)[1] : d; };
const channel = opt('--channel', 'flowux');
const port = opt('--port', '3055');
const pngOut = opt('--png', null);
const file = args[0];
if (!file) { console.error('usage: node run.mjs <script.js> [--channel c] [--port p] [--png out.png]'); process.exit(2); }
const code = readFileSync(file, 'utf8');

const ws = new WebSocket('ws://localhost:' + port);
const id = 'exec_' + Date.now();
const timer = setTimeout(() => { console.error('timeout: is the DM Figma Bridge plugin running and connected to channel ' + channel + '?'); process.exit(1); }, 120000);
ws.onerror = () => { console.error('cannot reach socket on port ' + port + ' (run: bunx cursor-talk-to-figma-socket)'); process.exit(1); };
ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'join', channel }));
  setTimeout(() => ws.send(JSON.stringify({ id, type: 'message', channel, message: { id, command: 'exec', label: file, params: { code } } })), 300);
};
ws.onmessage = (e) => {
  let d; try { d = JSON.parse(e.data); } catch { return; }
  const m = d.message;
  if (!m || m.id !== id || (m.result === undefined && m.error === undefined)) return;
  clearTimeout(timer);
  if (m.error) { console.error('FIGMA ERROR:', m.error); process.exit(1); }
  let r = m.result;
  if (pngOut && r && typeof r.png === 'string') { writeFileSync(pngOut, Buffer.from(r.png, 'base64')); r = { ...r, png: 'written to ' + pngOut }; }
  console.log(JSON.stringify(r, null, 1));
  ws.close(); process.exit(0);
};
