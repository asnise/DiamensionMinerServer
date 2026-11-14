// Close-up renders (offscreen) around a point. node bbface.mjs <prefix> x y z dist
import fs from 'node:fs';
import { connect, call } from './bbmcp.mjs';
const [prefix, x, y, z, dist = '22'] = process.argv.slice(2);
const c = [Number(x), Number(y), Number(z)], d = Number(dist);
await connect();
// Blockbench is shared by parallel sessions: own view id per process, and select our project first.
const VIEW = 'v' + process.pid;
if (process.env.BBMCP_PROJECT) {
    await call('risky_eval', { code: `(() => { const p = ModelProject.all.find(p => p.name === ${JSON.stringify(process.env.BBMCP_PROJECT)}); if (!p) throw new Error('no project ' + ${JSON.stringify(process.env.BBMCP_PROJECT)}); p.select(); return p.name; })()` });
}
await call('risky_eval', { code: `(() => { Modes.options.edit.select(); return true; })()` });
try { await call('delete_offscreen_view', { view: VIEW }); } catch {}
await call('create_offscreen_view', { id: VIEW, width: 700, height: 700, copy_view: 'none', antialias: false });
const views = { front: [0, 0, -1], q: [-0.7, 0.15, -0.7], side: [1, 0, 0], back: [0, 0.1, 1] };
for (const [n, v] of Object.entries(views)) {
    const r = await call('set_camera_angle', { view: VIEW, position: [c[0] + v[0] * d, c[1] + v[1] * d, c[2] + v[2] * d], target: c, projection: 'perspective', fov: 35 });
    const img = (r.content || []).find(k => k.type === 'image');
    if (img) fs.writeFileSync(`${prefix}_${n}.png`, Buffer.from(img.data, 'base64'));
}
await call('delete_offscreen_view', { view: VIEW });
