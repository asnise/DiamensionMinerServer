// Renders a Blockbench project from several angles through the MCP plugin (offscreen view, the
// user's camera is not moved) and stitches nothing: one PNG per angle.
//   node bbshots.mjs <outPrefix> [anim] [time]
import fs from 'node:fs';
import { connect, call } from './bbmcp.mjs';

const [prefix = 'shot', anim, time] = process.argv.slice(2);
await connect();
// Blockbench is shared by parallel sessions: own view id per process, and select our project first.
const VIEW = 'v' + process.pid;
if (process.env.BBMCP_PROJECT) {
    await call('risky_eval', { code: `(() => { const p = ModelProject.all.find(p => p.name === ${JSON.stringify(process.env.BBMCP_PROJECT)}); if (!p) throw new Error('no project ' + ${JSON.stringify(process.env.BBMCP_PROJECT)}); p.select(); return p.name; })()` });
}
if (anim) {
    const code = `(() => { Modes.options.animate.select(); const a = Animation.all.find(a => a.name.endsWith('.${anim}')); a.select(); Timeline.setTime(${Number(time || 0)}); Animator.preview(); return a.name; })()`;
    await call('risky_eval', { code });
} else {
    await call('risky_eval', { code: `(() => { Modes.options.edit.select(); return true; })()` });
}
try { await call('delete_offscreen_view', { view: VIEW }); } catch {}
await call('create_offscreen_view', { id: VIEW, width: 900, height: 900, copy_view: 'none' });
const center = [0, 26, 0];
const views = {
    front: [0, 30, -110],
    side: [110, 30, 0],
    back: [0, 30, 110],
    three_q: [-75, 55, -80],
};
for (const [name, pos] of Object.entries(views)) {
    const r = await call('set_camera_angle', { view: VIEW, position: pos, target: center, projection: 'perspective', fov: 40 });
    const img = (r.content || []).find(c => c.type === 'image');
    if (!img) { console.log(name, 'no image', JSON.stringify(r).slice(0, 300)); continue; }
    fs.writeFileSync(`${prefix}_${name}.png`, Buffer.from(img.data, 'base64'));
    console.log('saved', `${prefix}_${name}.png`);
}
await call('delete_offscreen_view', { view: VIEW });
