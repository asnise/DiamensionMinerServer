// Minimal client for the Blockbench MCP plugin (HTTP, streamable).
// Usage:
//   node bbmcp.mjs list                         -> tool names + descriptions
//   node bbmcp.mjs schema <tool>                -> input schema of one tool
//   node bbmcp.mjs call <tool> '<json args>'    -> call a tool, print result
//   node bbmcp.mjs call <tool> @file.json       -> args from a file
// Env: BBMCP_URL (default http://localhost:3010/bb-mcp)
import fs from 'node:fs';

const URL_ = process.env.BBMCP_URL || 'http://localhost:3010/bb-mcp';
let sessionId = null;
let nextId = 1;

async function rpc(method, params, notify = false) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
    if (sessionId) headers['Mcp-Session-Id'] = sessionId;
    const body = { jsonrpc: '2.0', method, params };
    if (!notify) body.id = nextId++;
    const res = await fetch(URL_, { method: 'POST', headers, body: JSON.stringify(body) });
    const sid = res.headers.get('mcp-session-id');
    if (sid) sessionId = sid;
    if (notify) return null;
    const text = await res.text();
    // Either plain JSON or SSE ("data: {...}")
    let msg = null;
    if (text.trim().startsWith('{')) msg = JSON.parse(text);
    else for (const line of text.split('\n')) if (line.startsWith('data:')) {
        const m = JSON.parse(line.slice(5));
        if (m.id === body.id) msg = m;
    }
    if (!msg) throw new Error(`no response (${res.status}): ${text.slice(0, 300)}`);
    if (msg.error) throw new Error(JSON.stringify(msg.error));
    return msg.result;
}

export async function connect() {
    await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'exea-bbmcp', version: '1' } });
    await rpc('notifications/initialized', {}, true);
}

export async function call(name, args = {}) {
    const r = await rpc('tools/call', { name, arguments: args });
    return r;
}

export async function listTools() {
    const all = [];
    let cursor;
    do {
        const r = await rpc('tools/list', cursor ? { cursor } : {});
        all.push(...r.tools);
        cursor = r.nextCursor;
    } while (cursor);
    return all;
}

// Several sessions may drive the same Blockbench: every MCP call acts on the *active* project.
// With BBMCP_PROJECT=<project name> set, inProject() wraps eval code so it selects that project, runs,
// and re-selects whatever was active before (one eval runs atomically inside Blockbench).
export function inProject(code, name = process.env.BBMCP_PROJECT) {
    if (!name) return code;
    return `(() => { const __prev = Project, __p = ModelProject.all.find(p => p.name === ${JSON.stringify(name)});
  if (!__p) throw new Error('project not open: ' + ${JSON.stringify(name)});
  if (__p !== Project) __p.select();
  const __restore = () => { if (__prev && __prev !== __p && ModelProject.all.includes(__prev)) __prev.select(); };
  let __r; try { __r = (${code}); } catch (e) { __restore(); throw e; }
  if (__r && typeof __r.then === 'function') return __r.finally(__restore);
  __restore(); return __r; })()`;
}

export function text(result) {
    return (result.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
}

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, '/')}` || process.argv[1].endsWith('bbmcp.mjs')) {
    const [cmd, a, b] = process.argv.slice(2);
    await connect();
    if (cmd === 'list') {
        for (const t of await listTools()) console.log(`${t.name}: ${(t.description || '').split('\n')[0].slice(0, 140)}`);
    } else if (cmd === 'schema') {
        const t = (await listTools()).find(t => t.name === a);
        console.log(JSON.stringify(t, null, 2));
    } else if (cmd === 'call') {
        const args = b ? (b.startsWith('@') ? JSON.parse(fs.readFileSync(b.slice(1), 'utf8')) : JSON.parse(b)) : {};
        const r = await call(a, args);
        for (const c of r.content || []) {
            if (c.type === 'text') console.log(c.text);
            else if (c.type === 'image') {
                const out = process.env.BBMCP_IMG || 'bbmcp_shot.png';
                fs.writeFileSync(out, Buffer.from(c.data, 'base64'));
                console.log(`[image saved to ${out}]`);
            } else console.log(JSON.stringify(c).slice(0, 500));
        }
        if (r.isError) process.exitCode = 1;
    }
}
