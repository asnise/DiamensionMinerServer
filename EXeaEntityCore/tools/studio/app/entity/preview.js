// Plays one moveset skill on the open model, the way core/skill does it: actions scheduled in ticks
// (at ÷ speed, repeats expanded), hit shapes flashed, telegraphs / fx through the effect simulator,
// projectiles flown at a target dummy, dashes / leaps / teleports moving the model.
import * as THREE from 'three';
import { parseShape, outline, contains, frame } from '../fx/shape.js';

const TICK = 0.05;

export class SkillPreview {
    /**
     * host: { scene, sim (FxSim), fxDefs(): Map, model(), pose(), yaw(), playAnim(name, speed), animLength(name),
     *         setRoot([x,y,z]), dummy(): [x,y,z], log(kind, text) }
     */
    constructor(host) {
        this.h = host;
        this.group = new THREE.Group();
        host.scene.add(this.group);
        this.gizmos = [];
        this.shots = [];
        this.moves = [];
        this.running = false;
        this.time = 0;
        this.acc = 0;
        this.root = [0, 0, 0];
    }

    /** Schedule like SkillCast: [{ tick, action, skill }], and the length in ticks. */
    static schedule(skill, moveset, speed, animLength) {
        const q = [];
        const ticks = (s) => Math.round(s * 20 / speed);
        const add = (sk, from, base) => {
            let last = base;
            for (const a of sk.actions || []) {
                const at = a.at || 0;
                if (at + 1e-9 < from) continue;
                const rep = a.repeat || {};
                const count = Math.max(1, rep.count || 1), interval = Math.max(0.05, rep.interval ?? 0.25);
                for (let i = 0; i < count; i++) {
                    const t = base + ticks(at - from + i * interval);
                    q.push({ tick: t, action: a, skill: sk });
                    last = Math.max(last, t);
                }
            }
            return last;
        };
        const last = add(skill, 0, 0);
        const dur = typeof skill.duration === 'number' ? ticks(skill.duration) : Math.max(ticks(animLength), last + 1);
        q.sort((a, b) => a.tick - b.tick);
        return { q, dur, add, ticks };
    }

    start(skillId, skill, moveset) {
        this.stop();
        const speed = (skill.speed || 1);
        const animLen = skill.animation ? this.h.animLength(skill.animation) : 0;
        const s = SkillPreview.schedule(skill, moveset, speed, animLen);
        Object.assign(this, { skillId, skill, moveset, speed, q: s.q, dur: s.dur, sched: s, tick: 0, time: 0, acc: 0, running: true, hitOnce: new Map() });
        this.root = [0, 0, 0];
        this.h.setRoot(this.root);
        if (skill.animation) this.h.playAnim(skill.animation, speed);
    }

    stop() {
        this.running = false;
        for (const g of this.gizmos) this.group.remove(g.obj);
        this.gizmos = [];
        for (const s of this.shots) this.group.remove(s.mesh);
        this.shots = [];
        this.moves = [];
    }

    get length() { return this.running || this.dur ? (this.dur || 0) / 20 : 0; }

    advance(dt) {
        // gizmos fade, shots fly and moves run even after the skill's own clock ended
        this.acc += dt;
        while (this.acc >= TICK) {
            this.acc -= TICK;
            this.step();
        }
        for (let i = 0; i < this.gizmos.length; i++) {
            const g = this.gizmos[i];
            g.t += dt;
            const k = Math.max(0, 1 - g.t / g.life);
            g.obj.traverse(o => { if (o.material) o.material.opacity = (o.material.userData.base ??= o.material.opacity) * k; });
            if (g.t >= g.life) { this.group.remove(g.obj); this.gizmos.splice(i--, 1); }
        }
    }

    /** Still something to see (skill clock, projectiles, moves, fading shapes)? */
    get busy() {
        return (this.running && this.tick < this.dur) || this.shots.length > 0 || this.moves.length > 0 || this.gizmos.length > 0;
    }

    step() {
        if (this.running) {
            while (this.q.length && this.q[0].tick <= this.tick) {
                const e = this.q.shift();
                this.run(e.action, e.skill, null);
            }
            this.tick++;
            this.time = this.tick / 20;
            if (this.tick >= this.dur && !this.q.length) this.running = false;
        }
        for (let i = 0; i < this.moves.length; i++) if (!this.moves[i]()) this.moves.splice(i--, 1);
        for (let i = 0; i < this.shots.length; i++) if (!this.flyShot(this.shots[i])) { this.group.remove(this.shots[i].mesh); this.shots.splice(i--, 1); }
    }

    // ---------------------------------------------------------------- frames (ActionContext.frame)

    yaw(a) { return this.h.yaw() + ((a.anchor || {}).yaw || 0); }

    bonePos(bone) {
        const m = bone ? this.h.pose()[bone] : null;
        if (!m) return null;
        const e = m.elements;
        return [e[12] + this.root[0], e[13] + this.root[1], e[14] + this.root[2]];
    }

    frameOf(a, impact) {
        const an = a.anchor || {};
        let base;
        if (impact) base = impact.slice();
        else if (a.bone) base = this.bonePos(a.bone) || [this.root[0], this.root[1] + 1, this.root[2]];
        else if (an.at === 'target') base = this.h.dummy().slice();
        else base = this.root.slice();
        const y = this.yaw(a);
        let p = frame(base, y).toWorld(an.offset || [0, 0, 0]);
        if (an.ground) p[1] = 0;
        return frame(p, y);
    }

    // ---------------------------------------------------------------- actions

    run(a, skill, impact) {
        const type = a.type;
        const f = this.frameOf(a, impact);
        const lib = this.h.fxDefs();
        switch (type) {
            case 'hit': {
                const shape = parseShape(a.shape);
                const hit = this.dummyInside(f, shape, a.height ?? 3);
                this.flashShape(f, shape, hit ? 0xff3b30 : 0xffa020, 0.45);
                this.h.log('hit', `${a.damage ?? 0} dmg${a.knockback ? ' kb ' + a.knockback : ''}${a.knockup ? ' up ' + a.knockup : ''}${hit ? '  → hits the dummy' : '  (misses the dummy)'}`);
                break;
            }
            case 'telegraph': {
                const layer = { type: 'telegraph', anchor: { ground: true }, shape: a.shape, duration: a.duration ?? 1, color: a.color, style: a.style, fill: a.fill };
                this.h.sim.spawn({ layers: [layer] }, () => ({ pos: f.pos, yaw: f.yaw }));
                break;
            }
            case 'sound':
                this.h.log('sound', `${a.sound}  vol ${a.volume ?? 1}`);
                break;
            case 'fx': {
                const def = lib.get(String(a.id || '').toLowerCase());
                if (!def) { this.h.log('warn', `fx "${a.id}" is not in the effect library`); break; }
                const origin = a.follow && !impact && a.bone ? () => ({ pos: this.bonePos(a.bone) || f.pos, yaw: this.yaw(a) })
                    : a.follow && !impact ? () => ({ pos: this.root.slice(), yaw: this.yaw(a) }) : () => ({ pos: f.pos, yaw: f.yaw });
                this.h.sim.spawn(def, origin, a.scale ?? 1);
                break;
            }
            case 'projectile': this.fire(a, f); break;
            case 'move': this.move(a, f); break;
            case 'summon': this.h.log('summon', `${a.count ?? 1} × ${a.model}`); break;
            case 'self': this.h.log('self', [a.heal ? `heal ${a.heal * 100}%` : '', a.invulnerable ? `invulnerable ${a.invulnerable}s` : '', a.speed && a.speed !== 1 ? `speed ×${a.speed}` : ''].filter(Boolean).join(', ')); break;
            case 'animation': this.h.playAnim(a.name, (a.speed || 1)); this.h.log('animation', a.name); break;
            case 'event': this.h.log('event', a.tag); break;
            case 'preset_skill': {
                const other = this.moveset.skills?.[a.skill];
                if (!other || !this.running) break;
                const last = this.sched.add(other, a.from || 0, this.tick + 1);
                this.q.sort((x, y) => x.tick - y.tick);
                this.dur = Math.max(this.dur, last + 1);
                this.h.log('preset_skill', `${a.skill} from ${a.from || 0}s`);
                break;
            }
            default: this.h.log(type, '(plugin action, not previewed)');
        }
    }

    dummyInside(f, shape, height) {
        const d = this.h.dummy();
        for (const dy of [0, 0.9, 1.6]) {
            const dx = d[0] - f.pos[0], dz = d[2] - f.pos[2];
            const r = f.yaw * Math.PI / 180;
            const lx = dx * -Math.cos(r) + dz * -Math.sin(r), lz = dx * -Math.sin(r) + dz * Math.cos(r);
            if (contains(shape, lx, d[1] + dy - f.pos[1], lz, 1, height)) return true;
        }
        return false;
    }

    flashShape(f, shape, color, life) {
        const obj = shapeGizmo(f, shape, color, 0.35);
        this.group.add(obj);
        this.gizmos.push({ obj, t: 0, life });
    }

    fire(a, f) {
        const count = Math.max(1, a.count || 1), spread = a.spread || 0, speed = a.speed ?? 0.8, gravity = a.gravity || 0;
        const d = this.h.dummy();
        for (let i = 0; i < count; i++) {
            const turn = count === 1 ? 0 : (-spread / 2 + spread * i / (count - 1)) * Math.PI / 180;
            let v;
            if (a.aim === 'forward') v = f.forward.slice();
            else if (a.aim === 'target_ground') {
                const to = [d[0] - f.pos[0], d[1] - f.pos[1], d[2] - f.pos[2]];
                const flat = Math.hypot(to[0], to[2]), time = Math.max(4, flat / speed);
                v = [to[0] / time, (to[1] + 0.5 * gravity * time * time) / time, to[2] / time];
            } else v = [d[0] - f.pos[0], d[1] + 0.9 - f.pos[1], d[2] - f.pos[2]];
            if (a.aim !== 'target_ground') { const l = Math.hypot(...v) || 1; v = v.map(c => c / l * speed); }
            const c = Math.cos(turn), s = Math.sin(turn);
            v = [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c];
            const mesh = new THREE.Mesh(new THREE.SphereGeometry(Math.max(0.12, (a.radius ?? 0.5) * 0.5), 12, 8), new THREE.MeshBasicMaterial({ color: 0xc58bff }));
            mesh.position.set(...f.pos);
            this.group.add(mesh);
            const shot = { a, pos: f.pos.slice(), v, life: Math.round((a.lifetime ?? 3) * 20), mesh };
            const trail = this.h.fxDefs().get(String(a.trail ?? 'projectile_trail'));
            if (trail) this.h.sim.spawn(trail, () => ({ pos: shot.pos.slice(), yaw: 0 }), 1);
            this.shots.push(shot);
        }
    }

    flyShot(s) {
        if (--s.life < 0) return false;
        const a = s.a, d = this.h.dummy(), homing = a.homing || 0;
        if (homing > 0) {
            const want = [d[0] - s.pos[0], d[1] + 0.9 - s.pos[1], d[2] - s.pos[2]], wl = Math.hypot(...want) || 1, sp = Math.hypot(...s.v) || 1;
            const n = s.v.map((c, i) => (c / sp) * (1 - homing) + (want[i] / wl) * homing), nl = Math.hypot(...n) || 1;
            s.v = n.map(c => c / nl * sp);
        }
        s.v[1] -= a.gravity || 0;
        for (let i = 0; i < 3; i++) s.pos[i] += s.v[i];
        s.mesh.position.set(...s.pos);
        const r = a.radius ?? 0.5;
        const inDummy = Math.abs(s.pos[0] - d[0]) <= 0.3 + r && Math.abs(s.pos[2] - d[2]) <= 0.3 + r && s.pos[1] >= d[1] - r && s.pos[1] <= d[1] + 1.8 + r;
        if (inDummy || s.pos[1] <= 0) {
            this.h.log('projectile', inDummy ? `hits the dummy${a.damage ? ', ' + a.damage + ' dmg' : ''}` : 'hits the ground');
            for (const h of a.on_hit || []) this.run(h, this.skill, s.pos.slice());
            return false;
        }
        return true;
    }

    move(a, f) {
        const mode = a.mode, ticks = Math.max(1, Math.round((a.duration ?? (mode === 'leap' ? 0.7 : 0.3)) * 20 / this.speed));
        const fwd = frame([0, 0, 0], this.h.yaw()).forward;
        const d = this.h.dummy();
        const toDummy = () => { const v = [d[0] - this.root[0], 0, d[2] - this.root[2]], l = Math.hypot(v[0], v[2]) || 1; return [v[0] / l, 0, v[2] / l]; };
        if (mode === 'dash') {
            const dir = a.direction === 'back' ? fwd.map(c => -c) : a.direction === 'target' ? toDummy() : fwd;
            const step = (a.distance ?? 6) / ticks;
            let left = ticks;
            this.moves.push(() => { for (const i of [0, 2]) this.root[i] += dir[i] * step; this.h.setRoot(this.root); return --left > 0; });
            this.h.log('move', `dash ${a.distance ?? 6} blocks`);
        } else if (mode === 'leap') {
            const start = this.root.slice();
            let dest;
            if ((a.to ?? 'target') === 'target') {
                const back = [start[0] - d[0], 0, start[2] - d[2]], bl = Math.hypot(back[0], back[2]);
                dest = bl > 1 ? [d[0] + back[0] / bl * Math.min(1.5, bl - 0.5), 0, d[2] + back[2] / bl * Math.min(1.5, bl - 0.5)] : d.slice();
            } else dest = [start[0] + fwd[0] * (a.distance ?? 6), 0, start[2] + fwd[2] * (a.distance ?? 6)];
            let t = 0;
            const h = a.height ?? 3;
            this.moves.push(() => {
                const k = ++t / ticks;
                this.root = [start[0] + (dest[0] - start[0]) * k, 4 * h * k * (1 - k), start[2] + (dest[2] - start[2]) * k];
                this.h.setRoot(this.root);
                return t < ticks;
            });
            this.h.log('move', 'leap');
        } else if (mode === 'teleport') {
            const dist = a.distance ?? 2;
            // the dummy looks back at the entity, so "behind" it is further along the entity's forward
            this.root = (a.to ?? 'behind_target') === 'behind_target' ? [d[0] + fwd[0] * dist, 0, d[2] + fwd[2] * dist]
                : (a.to === 'target' ? [d[0] - fwd[0] * Math.min(dist, 1.5), 0, d[2] - fwd[2] * Math.min(dist, 1.5)] : [this.root[0] + dist, 0, this.root[2]]);
            this.h.setRoot(this.root);
            for (const id of [a.fx_out, a.fx_in]) { const def = id && this.h.fxDefs().get(id); if (def) this.h.sim.spawn(def, () => ({ pos: this.root.slice(), yaw: 0 })); }
            this.h.log('move', `teleport ${a.to ?? 'behind_target'}`);
        } else {
            const shape = a.shape ? parseShape(a.shape) : parseShape({ type: 'circle', radius: a.radius ?? 5 });
            this.flashShape(f, shape, 0x4aa8ff, 0.5);
            this.h.log('move', `${mode} strength ${a.strength ?? 1}${this.dummyInside(f, shape, 3) ? '  → moves the dummy' : ''}`);
        }
    }
}

/** A translucent shape on the ground (flat shapes) or a wire volume (sphere, box, column). */
export function shapeGizmo(f, shape, color, opacity = 0.3) {
    const g = new THREE.Group();
    const fill = new THREE.MeshBasicMaterial({ color, transparent: true, opacity, side: THREE.DoubleSide, depthWrite: false });
    const line = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.95, depthTest: false });
    if (['SPHERE', 'BOX', 'COLUMN', 'SPIRAL', 'POINT'].includes(shape.type)) {
        let geo;
        if (shape.type === 'BOX') geo = new THREE.BoxGeometry(...shape.size);
        else if (shape.type === 'COLUMN' || shape.type === 'SPIRAL') { geo = new THREE.CylinderGeometry(shape.radius, shape.radius, Math.max(0.1, shape.height), 24, 1, true); geo.translate(0, shape.height / 2, 0); }
        else geo = new THREE.SphereGeometry(Math.max(0.25, shape.radius), 20, 12);
        const m = new THREE.Mesh(geo, fill);
        m.add(new THREE.LineSegments(new THREE.EdgesGeometry(geo, 30), line));
        m.position.set(...f.pos);
        m.rotation.y = -f.yaw * Math.PI / 180;
        g.add(m);
        return g;
    }
    const pts = outline(shape, 48, 1);
    const w = pts.map(p => new THREE.Vector3(...frame(f.pos, f.yaw).toWorld([p[0], 0.04, p[2]])));
    g.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(w), line));
    const verts = [];
    const center = shape.type === 'LINE' || shape.type === 'BOX' ? w[0] : new THREE.Vector3(...frame(f.pos, f.yaw).toWorld([0, 0.04, 0]));
    for (let i = 0; i < w.length - 1; i++) verts.push(center.x, center.y, center.z, w[i].x, w[i].y, w[i].z, w[i + 1].x, w[i + 1].y, w[i + 1].z);
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3));
    g.add(new THREE.Mesh(geo, fill));
    return g;
}
