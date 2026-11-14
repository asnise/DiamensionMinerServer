// Bakes Blockbench IK into plain rotation keyframes, so EXea (FK only) plays what Blockbench shows.
// For every animation in the open project that keys an IK null object, samples each IK chain
// every 0.05 s (one server tick), reads the solved bone rotations and replaces that animation's
// rotation keyframes on the chain bones with linear ones. Run it again after changing IK keys.
//   node bbbake.mjs [step=0.05]      (set BBMCP_PROJECT=<name> when other projects are open)
import { connect, call, text, inProject } from './bbmcp.mjs';

const step = Number(process.argv[2] || 0.05);
await connect();
const code = `(() => {
  const step = ${step};
  const chains = NullObject.all.filter(n => n.ik_target).map(n => {
    const target = OutlinerNode.uuids[n.ik_target];
    const source = n.ik_source ? OutlinerNode.uuids[n.ik_source] : n.parent;
    const bones = [];
    for (let b = target; b && b !== source.parent; b = b.parent) { if (b instanceof Group) bones.unshift(b); if (b === source) break; }
    return { nul: n, bones };
  });
  const report = {};
  const wasMode = Modes.selected && Modes.selected.id;
  Modes.options.animate.select();
  Undo.initEdit({ animations: Animation.all, keyframes: [] });
  for (const anim of Animation.all) {
    const active = chains.filter(c => { const a = anim.animators[c.nul.uuid]; return a && a.keyframes.length; });
    if (!active.length) continue;
    anim.select();
    const bones = [...new Set(active.flatMap(c => c.bones))];
    const samples = new Map(bones.map(b => [b, []]));
    const n = Math.max(1, Math.round(anim.length / step));
    for (let i = 0; i <= n; i++) {
      const t = Math.min(anim.length, i * step);
      Timeline.setTime(t);
      Animator.preview();
      for (const b of bones) {
        const r = b.mesh.rotation, rest = b.rotation;
        const v = [r.x, r.y, r.z].map((rad, k) => Math.radToDeg(rad) - rest[k]);
        const prev = samples.get(b).at(-1);
        if (prev) for (let k = 0; k < 3; k++) { while (v[k] - prev.v[k] > 180) v[k] -= 360; while (v[k] - prev.v[k] < -180) v[k] += 360; }
        samples.get(b).push({ t, v });
      }
    }
    for (const b of bones) {
      const ba = anim.getBoneAnimator(b);
      for (const kf of ba.rotation.slice()) kf.remove();
      for (const s of samples.get(b)) ba.addKeyframe({ channel: 'rotation', time: Math.round(s.t * 1000) / 1000, interpolation: 'linear', data_points: [{ x: +s.v[0].toFixed(3), y: +s.v[1].toFixed(3), z: +s.v[2].toFixed(3) }] });
    }
    report[anim.name] = { bones: bones.map(b => b.name), samples: n + 1 };
  }
  Undo.finishEdit('Bake IK into rotation keyframes');
  if (wasMode && Modes.options[wasMode]) Modes.options[wasMode].select();
  return report;
})()`;
console.log(text(await call('risky_eval', { code: inProject(code) })));
