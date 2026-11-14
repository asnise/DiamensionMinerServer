// Pose math, a line-by-line port of the plugin so the preview matches the game:
//  - evaluate()  = core/animation/KeyframeTimeline.evaluate (Blockbench BoneAnimator.interpolate)
//  - computePose() = core/animation/AnimationController.computePose / updateBoneRecursive
// Keep the two in step when either changes.
import * as THREE from 'three';

const EPSILON = 1 / 1200;
const ZERO = [0, 0, 0];
const ONE = [1, 1, 1];
const DEG = Math.PI / 180;

function catmullRom(t, p0, p1, p2, p3) {
    const v0 = (p2 - p0) * 0.5;
    const v1 = (p3 - p1) * 0.5;
    const t2 = t * t;
    const t3 = t * t2;
    return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
}

/** Value of a track at {@code time}: before = last key strictly before, after = first key at or after. */
export function evaluate(track, time, fallback) {
    if (!track || track.length === 0) return fallback;
    const n = track.length;
    let a = 0;
    while (a < n && track[a].time < time) a++;
    const after = a < n ? track[a] : null;
    const before = a > 0 ? track[a - 1] : null;
    if (before && Math.abs(before.time - time) < EPSILON) return before.post;
    if (after && Math.abs(after.time - time) < EPSILON) return after.pre;
    if (!before) return after.pre;
    if (!after) return before.post;
    if (before.interp === 'step') return before.post;
    const dt = after.time - before.time;
    const alpha = dt <= 0 ? 1 : (time - before.time) / dt;
    if (before.interp === 'catmullrom' || after.interp === 'catmullrom') {
        const p0 = a - 2 >= 0 ? track[a - 2].post : before.post;
        const p1 = before.post;
        const p2 = after.pre;
        const p3 = a + 1 < n ? track[a + 1].pre : after.pre;
        return [0, 1, 2].map(i => catmullRom(alpha, p0[i], p1[i], p2[i], p3[i]));
    }
    return [0, 1, 2].map(i => before.post[i] + (after.pre[i] - before.post[i]) * alpha);
}

const FLIP_Y = new THREE.Matrix4().makeRotationY(Math.PI);
const tmpT = new THREE.Matrix4();
const tmpR = new THREE.Matrix4();
const tmpS = new THREE.Matrix4();
const tmpE = new THREE.Euler(0, 0, 0, 'ZYX');

/**
 * World matrix (blocks, relative to the driver's feet) of every bone into {@code out[name]}.
 * Root = Ry(-bodyYaw) · half turn (Blockbench models face -Z, a yaw-0 mob faces +Z), then per bone
 * T(origin - parentOrigin + animPos) · R_ZYX(rest + animRot) · S(animScale).
 * The plugin's extra per-display half turn only cancels the ItemDisplay renderer's own, so the mesh
 * is drawn here directly in that bone space.
 */
export function computePose(model, anim, time, bodyYaw, out) {
    const root = new THREE.Matrix4().makeRotationY(-bodyYaw * DEG).multiply(FLIP_Y);
    for (const b of model.roots) poseBone(b, null, root, anim, time, out);
    return out;
}

function poseBone(bone, parent, parentMatrix, anim, time, out) {
    const m = (out[bone.name] ||= new THREE.Matrix4()).copy(parentMatrix);
    const o = bone.origin;
    const p = parent ? parent.origin : ZERO;
    m.multiply(tmpT.makeTranslation((o[0] - p[0]) / 16, (o[1] - p[1]) / 16, (o[2] - p[2]) / 16));
    const tr = anim ? anim.tracks[bone.name] : null;
    let rot = ZERO, scale = ONE;
    if (tr) {
        const pos = evaluate(tr.position, time, ZERO);
        if (pos !== ZERO) m.multiply(tmpT.makeTranslation(pos[0] / 16, pos[1] / 16, pos[2] / 16));
        rot = evaluate(tr.rotation, time, ZERO);
        scale = evaluate(tr.scale, time, ONE);
    }
    const rx = rot[0] + bone.rotation[0], ry = rot[1] + bone.rotation[1], rz = rot[2] + bone.rotation[2];
    if (rx || ry || rz) m.multiply(tmpR.makeRotationFromEuler(tmpE.set(rx * DEG, ry * DEG, rz * DEG, 'ZYX')));
    m.multiply(tmpS.makeScale(scale[0], scale[1], scale[2]));
    for (const c of bone.children) poseBone(c, bone, m, anim, time, out);
}

/** Playback clock with the plugin's loop rules (keeps the overshoot on loop, like Blockbench). */
export class Clock {
    constructor() { this.time = 0; this.playing = false; this.speed = 1; this.loopOverride = ''; }
    mode(anim) { return this.loopOverride || anim.loop; }
    /** Advances; returns events whose time was crossed, in order. */
    tick(anim, dt) {
        if (!anim || !this.playing) return [];
        const prev = this.time;
        this.time += dt * this.speed;
        const lower = prev === 0 ? -Infinity : prev;
        const fired = anim.events.filter(e => e.time > lower && e.time <= this.time);
        if (this.time >= anim.length) {
            const mode = this.mode(anim);
            if (mode === 'loop') this.time = anim.length > 0 ? this.time % anim.length : 0;
            else { this.time = anim.length; this.playing = false; }
        }
        return fired;
    }
}
