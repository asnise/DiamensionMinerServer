// Effect preview: runs an effect preset the way core/effect/* does (same 20 Hz ticks, layer timing,
// anchors, shapes, budgets left out) and draws it with three.js. Particles and blocks are drawn with
// flat colours (see looks.js), so it shows timing, size, shape and motion, not exact textures.
import * as THREE from 'three';
import { parseShape, sample, outline, contains, frame, radiusOf, lengthOf } from './shape.js';
import { particleLook, blockColor, hexColor, pickBlock } from './looks.js';
import { COLORED, GRID } from './textures.js';

const TICK = 0.05;
const MAX_PARTICLES = 12000;
const MAX_DEPTH = 4;

// ------------------------------------------------------------------ particle renderer

// tile >= 0: a sprite from the Minecraft atlas (textures.js), tinted by the colour; -1: a soft round dot.
const VERT = `
attribute float alpha; attribute float psize; attribute float tile; varying vec3 vColor; varying float vAlpha; varying float vTile;
uniform float uScale;
void main() { vColor = color; vAlpha = alpha; vTile = tile; vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_PointSize = psize * uScale / max(0.05, -mv.z); gl_Position = projectionMatrix * mv; }`;
const FRAG = `
uniform sampler2D uAtlas; uniform float uGrid;
varying vec3 vColor; varying float vAlpha; varying float vTile;
void main() {
  if (vTile < 0.0) { vec2 c = gl_PointCoord - 0.5; float d = length(c); if (d > 0.5) discard;
    gl_FragColor = vec4(vColor, vAlpha * smoothstep(0.5, 0.25, d)); return; }
  float col = mod(vTile, uGrid), row = floor(vTile / uGrid);
  vec2 uv = vec2((col + gl_PointCoord.x) / uGrid, 1.0 - (row + gl_PointCoord.y) / uGrid);
  vec4 tx = texture2D(uAtlas, uv);
  if (tx.a < 0.1) discard;
  gl_FragColor = vec4(tx.rgb * vColor, tx.a * vAlpha); }`;

class Particles {
    constructor(scene, atlas) {
        this.n = 0;
        this.tile = new Float32Array(MAX_PARTICLES);
        this.sprites = new Array(MAX_PARTICLES);
        this.pos = new Float32Array(MAX_PARTICLES * 3);
        this.vel = new Float32Array(MAX_PARTICLES * 3);
        this.col = new Float32Array(MAX_PARTICLES * 3);
        this.col0 = new Float32Array(MAX_PARTICLES * 3);
        this.col1 = new Float32Array(MAX_PARTICLES * 3);
        this.alpha = new Float32Array(MAX_PARTICLES);
        this.size = new Float32Array(MAX_PARTICLES);
        this.age = new Float32Array(MAX_PARTICLES);
        this.life = new Float32Array(MAX_PARTICLES);
        this.phys = new Float32Array(MAX_PARTICLES * 3); // gravity, rise, drag
        const g = new THREE.BufferGeometry();
        g.setAttribute('position', new THREE.BufferAttribute(this.pos, 3));
        g.setAttribute('color', new THREE.BufferAttribute(this.col, 3));
        g.setAttribute('alpha', new THREE.BufferAttribute(this.alpha, 1));
        g.setAttribute('psize', new THREE.BufferAttribute(this.size, 1));
        g.setAttribute('tile', new THREE.BufferAttribute(this.tile, 1));
        g.setDrawRange(0, 0);
        this.geo = g;
        this.points = new THREE.Points(g, new THREE.ShaderMaterial({
            vertexShader: VERT, fragmentShader: FRAG, vertexColors: true, transparent: true, depthWrite: false,
            uniforms: { uScale: { value: 600 }, uAtlas: { value: atlas || null }, uGrid: { value: GRID } },
        }));
        this.points.frustumCulled = false;
        this.points.renderOrder = 5;
        scene.add(this.points);
    }

    /** sprite: { tiles, random } from textures.js, or null for a dot. */
    add(p, v, c0, c1, size, life, look, sprite = null, rnd = Math.random) {
        if (this.n >= MAX_PARTICLES) return;
        const i = this.n++;
        this.sprites[i] = sprite ? { tiles: sprite.tiles, pick: sprite.random ? Math.floor(rnd() * sprite.tiles.length) : -1 } : null;
        this.tile[i] = -1;
        this.pos.set(p, i * 3);
        this.vel.set(v, i * 3);
        this.col0.set(c0, i * 3);
        this.col1.set(c1, i * 3);
        this.col.set(c0, i * 3);
        this.size[i] = size;
        this.age[i] = 0;
        this.life[i] = life;
        this.alpha[i] = 1;
        this.phys[i * 3] = look.gravity;
        this.phys[i * 3 + 1] = look.rise;
        this.phys[i * 3 + 2] = look.drag;
    }

    tick() {
        for (let i = 0; i < this.n; i++) {
            this.age[i] += TICK;
            if (this.age[i] >= this.life[i]) { this.swap(i, --this.n); i--; continue; }
            const k = i * 3;
            this.vel[k + 1] += this.phys[k + 1] - this.phys[k];
            for (let a = 0; a < 3; a++) { this.vel[k + a] *= this.phys[k + 2]; this.pos[k + a] += this.vel[k + a]; }
            if (this.pos[k + 1] < 0.02 && this.phys[k] > 0) { this.pos[k + 1] = 0.02; this.vel[k + 1] = 0; this.vel[k] *= 0.5; this.vel[k + 2] *= 0.5; }
            const t = this.age[i] / this.life[i];
            const sp = this.sprites[i];
            if (sp) this.tile[i] = sp.tiles[sp.pick >= 0 ? sp.pick : Math.min(sp.tiles.length - 1, Math.floor(t * sp.tiles.length))] ?? -1;
            for (let a = 0; a < 3; a++) this.col[k + a] = this.col0[k + a] + (this.col1[k + a] - this.col0[k + a]) * t;
            this.alpha[i] = t < 0.7 ? 1 : 1 - (t - 0.7) / 0.3;
        }
        this.flush();
    }

    swap(i, j) {
        for (const [arr, w] of [[this.pos, 3], [this.vel, 3], [this.col, 3], [this.col0, 3], [this.col1, 3], [this.phys, 3], [this.alpha, 1], [this.size, 1], [this.age, 1], [this.life, 1], [this.tile, 1]]) {
            for (let a = 0; a < w; a++) arr[i * w + a] = arr[j * w + a];
        }
        this.sprites[i] = this.sprites[j];
    }

    flush() {
        this.geo.setDrawRange(0, this.n);
        for (const name of ['position', 'color', 'alpha', 'psize', 'tile']) this.geo.attributes[name].needsUpdate = true;
    }

    clear() { this.n = 0; this.flush(); }
}

/** Vanilla tints grey sprites (smoke, spell) with a light colour; the look colours are darker averages. */
const brighten = (hex) => {
    const c = [(hex >> 16) & 255, (hex >> 8) & 255, hex & 255].map(v => Math.min(255, Math.round(v * 1.35 + 30)));
    return (c[0] << 16) | (c[1] << 8) | c[2];
};
const rgb = (hex) => [((hex >> 16) & 255) / 255, ((hex >> 8) & 255) / 255, (hex & 255) / 255];

// ------------------------------------------------------------------ the simulator

export class FxSim {
    /** textures: McTextures (optional): Minecraft sprites and block faces when a client jar is found. */
    constructor(scene, textures = null) {
        this.scene = scene;
        this.tex = textures;
        this.group = new THREE.Group();
        scene.add(this.group);
        this.particles = new Particles(scene, textures?.atlas);
        this.box = new THREE.BoxGeometry(1, 1, 1);
        this.mats = new Map();
        this.instances = [];
        this.time = 0;
        this.acc = 0;
        this.ctx = null;
        this.def = null;
        this.onLog = null;
        this.seed = 1;
    }

    mat(hex, opts = {}) {
        const key = hex + JSON.stringify(opts);
        if (!this.mats.has(key)) this.mats.set(key, new THREE.MeshLambertMaterial({ color: hex, ...opts }));
        return this.mats.get(key);
    }

    /** A block's material: its real faces from the jar when loaded (grass top tinted), else a flat colour. */
    blockMat(key, glow) {
        const faces = this.tex?.block(key);
        const g = glow ? { emissive: hexColor(glow), emissiveIntensity: 0.5 } : {};
        if (!faces) return this.mat(blockColor(key), g);
        const ck = 'blk:' + key + (glow || '');
        if (!this.mats.has(ck)) {
            const m = (map, color = 0xffffff) => new THREE.MeshLambertMaterial({ map, color, ...g });
            const side = m(faces.side), top = m(faces.top, faces.grass ? 0x7cbd6b : 0xffffff), bottom = m(faces.bottom);
            this.mats.set(ck, [side, side, top, bottom, side, side]); // BoxGeometry: +x, -x, +y, -y, +z, -z
        }
        return this.mats.get(ck);
    }

    rnd() { // deterministic, so scrubbing shows the same thing each time
        this.seed = (this.seed * 1664525 + 1013904223) % 4294967296;
        return this.seed / 4294967296;
    }

    /** ctx: { library: Map id → def, scale, tags:Set, origin(): {pos, yaw}, target(): [x,y,z] | null } */
    load(def, ctx) {
        this.def = def;
        this.ctx = ctx;
        this.restart();
    }

    /** Sets the context without loading an effect (several effects are then added with spawn()). */
    setContext(ctx) {
        this.ctx = ctx;
        this.def = null;
    }

    /** Starts one more effect next to the running ones (skill previews play many at once). */
    spawn(def, origin, scale = 1) {
        if (!this.ctx || !def) return;
        this.instances.push(this.newInstance(def, origin, scale, 0));
    }

    restart() {
        this.clear();
        this.time = 0;
        this.acc = 0;
        this.seed = 1;
        if (this.def) this.instances.push(this.newInstance(this.def, this.ctx.origin, this.ctx.scale, 0));
    }

    clear() {
        for (const inst of this.instances) this.stopInstance(inst);
        this.instances = [];
        this.particles.clear();
        for (const c of [...this.group.children]) this.group.remove(c);
    }

    get duration() {
        return this.def ? effectDuration(this.def, this.ctx.library) : 0;
    }

    get active() { return this.instances.length > 0 || this.particles.n > 0; }

    /** Real-time advance; returns true while anything is still playing. */
    advance(dt) {
        this.acc += dt;
        while (this.acc >= TICK) { this.acc -= TICK; this.tick(); }
        return this.active;
    }

    /** Jumps to a time by replaying from the start. */
    seek(t) {
        this.restart();
        const n = Math.min(20 * 120, Math.round(t / TICK));
        for (let i = 0; i < n; i++) this.tick(true);
    }

    tick(silent = false) {
        this.silent = silent;
        for (let i = 0; i < this.instances.length; i++) {
            if (!this.tickInstance(this.instances[i])) { this.stopInstance(this.instances[i]); this.instances.splice(i--, 1); }
        }
        this.particles.tick();
        this.time += TICK;
    }

    log(kind, text) { if (!this.silent) this.onLog?.(kind, text); }

    // ---------------------------------------------------------------- instances (EffectInstance.java)

    newInstance(def, origin, scale, depth) {
        return { def, origin, scale, depth, ticks: 0, cycle: 0, started: def.layers.map(() => false), runs: [], dur: effectDuration(def, this.ctx.library) * 20 };
    }

    tickInstance(inst) {
        const t = inst.cycle / 20;
        inst.def.layers.forEach((l, i) => {
            if (inst.started[i] || t + 1e-6 < (l.start || 0)) return;
            inst.started[i] = true;
            if ((l.chance ?? 1) < 1 && this.rnd() >= l.chance) return;
            if (l.when && !this.ctx.tags.has(l.when)) return;
            const run = this.startLayer(inst, l);
            if (run) inst.runs.push(run);
        });
        for (let i = 0; i < inst.runs.length; i++) {
            if (!this.tickRun(inst.runs[i])) { inst.runs[i].end?.(); inst.runs.splice(i--, 1); }
        }
        inst.ticks++;
        inst.cycle++;
        if (inst.def.loop && inst.cycle >= inst.dur) { inst.cycle = 0; inst.started.fill(false); }
        const done = !inst.def.loop && inst.runs.length === 0 && inst.cycle >= inst.dur;
        return !done && inst.ticks < inst.dur + 600;
    }

    stopInstance(inst) {
        for (const r of inst.runs) r.end?.();
        inst.runs = [];
    }

    /** Anchor → frame (EffectInstance.frame). */
    anchorFrame(inst, a = {}) {
        let base, yaw;
        const o = inst.origin();
        yaw = o.yaw + (a.yaw || 0);
        const tgt = this.ctx.target?.();
        base = a.at === 'target' && tgt ? tgt.slice() : o.pos.slice();
        const off = a.offset || [0, 0, 0];
        let p = frame(base, yaw).toWorld(off.map(v => v * inst.scale));
        if (a.ground) p[1] = 0;
        return frame(p, yaw);
    }

    // ---------------------------------------------------------------- layer runs (LayerRun.java)

    startLayer(inst, l) {
        const make = {
            particle: () => this.particleRun(inst, l), debris: () => this.debrisRun(inst, l), ground_wave: () => this.waveRun(inst, l),
            decal: () => this.decalRun(inst, l), telegraph: () => this.telegraphRun(inst, l), model: () => this.modelRun(inst, l),
            sound: () => this.soundRun(inst, l), preset: () => this.presetRun(inst, l),
        }[l.type];
        if (!make) { this.log('custom', `${l.type} (plugin layer type, not previewed)`); return null; }
        const run = make();
        const durT = Math.round((l.duration || 0) * 20);
        Object.assign(run, { layer: l, age: 0, next: 0, durT, intT: Math.max(1, Math.round((l.interval || 0.05) * 20)), emitting: true, inst });
        return run;
    }

    tickRun(run) {
        if (run.emitting && run.age >= run.next) {
            const progress = run.durT <= 0 ? 1 : Math.min(1, run.age / run.durT);
            run.emit(progress);
            run.next += run.intT;
        }
        if (run.age >= run.durT) run.emitting = false;
        const alive = run.update ? run.update() : false;
        run.age++;
        return run.emitting || alive;
    }

    frameOf(run) {
        const a = run.layer.anchor || {};
        if (!a.follow && run.fixed) return run.fixed;
        run.fixed = this.anchorFrame(run.inst, a);
        return run.fixed;
    }

    particleRun(inst, l) {
        const shape = parseShape(l.shape);
        const look = particleLook(l.particle);
        const key = String(l.particle || '').replace(/^minecraft:/, '');
        const run = {};
        run.emit = (progress) => {
            const f = this.frameOf(run), s = inst.scale;
            let beam = null, beamLen = -1;
            const tgt = this.ctx.target?.();
            if (shape.type === 'LINE' && shape.toTarget && tgt) {
                const d = [tgt[0] - f.pos[0], tgt[1] + 0.9 - f.pos[1], tgt[2] - f.pos[2]];
                beamLen = Math.hypot(...d);
                if (beamLen > 1e-6) beam = d.map(v => v / beamLen);
            }
            const count = Math.max(1, l.count ?? 10);
            for (let i = 0; i < count; i++) {
                const p = sample(shape, () => this.rnd(), progress, s, beam ? beamLen : -1);
                let w = beam ? [f.pos[0] + beam[0] * p[2], f.pos[1] + beam[1] * p[2], f.pos[2] + beam[2] * p[2]] : f.toWorld(p);
                const sp = (l.spread || 0) * s;
                if (sp > 0) w = w.map(v => v + (this.rnd() - 0.5) * 2 * sp);
                let dir = null;
                const m = l.motion || 'none';
                if (m === 'up') dir = [0, 1, 0];
                else if (m === 'forward') dir = f.forward;
                else if (m === 'outward') dir = f.dir([p[0], shape.type === 'SPHERE' ? p[1] : 0, p[2]]);
                else if (m === 'inward') dir = f.dir([-p[0], shape.type === 'SPHERE' ? -p[1] : 0, -p[2]]);
                else if (m === 'random') dir = [this.rnd() - 0.5, this.rnd() - 0.5, this.rnd() - 0.5];
                if (beam && dir) dir = beam;
                let v;
                if (dir && l.speed) {
                    const len = Math.hypot(...dir) || 1;
                    v = dir.map(c => c / len * l.speed * s);
                } else {
                    v = [(this.rnd() - 0.5) * look.jitter, (this.rnd() - 0.5) * look.jitter, (this.rnd() - 0.5) * look.jitter];
                }
                let c0 = look.color, c1;
                if (key === 'dust' || key === 'entity_effect') c0 = hexColor(l.color, key === 'dust' ? 0xff0000 : 0xffffff);
                if (key === 'dust_color_transition') { c0 = hexColor(l.color, 0xff0000); c1 = hexColor(l.to_color, c0); }
                let sprite = this.tex?.ready ? this.tex.particle(key) : null;
                if (['block', 'falling_dust', 'dust_pillar', 'block_marker', 'block_crumble'].includes(key)) {
                    const blk = pickBlock(l.block, () => this.rnd());
                    c0 = blockColor(blk);
                    if (this.tex?.ready) { sprite = { tiles: [this.tex.blockTile(blk)], random: false }; c0 = 0xffffff; }
                } else if (sprite && COLORED.has(key)) c0 = 0xffffff;
                else if (sprite && !key.startsWith('dust') && key !== 'entity_effect') c0 = brighten(c0);
                const tint = 0.9 + this.rnd() * 0.2;
                const a = rgb(c0).map(x => Math.min(1, x * tint));
                const b = c1 !== undefined ? rgb(c1) : a;
                const size = look.size * (key.startsWith('dust') ? (l.size ?? 1) : 1) * (0.8 + this.rnd() * 0.4);
                this.particles.add(w, v, a, b, size * (sprite ? 1.6 : 1), look.life * (0.7 + this.rnd() * 0.6), look, sprite, () => this.rnd());
            }
        };
        return run;
    }

    debrisRun(inst, l) {
        const run = { pieces: [] };
        const size = rangeOf(l.size, 0.2, 0.45), speed = rangeOf(l.speed, 0.3, 0.6), life = rangeOf(l.lifetime, 1, 1.5);
        const gravity = l.gravity ?? 0.08, bounce = Math.max(0, Math.min(1, l.bounce ?? 0.3)), spin = (l.spin ?? 20) * Math.PI / 180;
        run.emit = () => {
            const f = this.frameOf(run), s = inst.scale;
            for (let i = 0; i < (l.count ?? 8); i++) {
                const a = this.rnd() * Math.PI * 2, d = (l.radius ?? 0.5) * s * Math.sqrt(this.rnd());
                const pos = f.toWorld([d * Math.cos(a), 0.05, d * Math.sin(a)]);
                const elev = Math.max(0, Math.min(90, (l.angle ?? 60) + this.rnd() * 30 - 15)) * Math.PI / 180;
                let flat;
                if (l.direction === 'up') flat = [(this.rnd() - 0.5) * 0.2, 0, (this.rnd() - 0.5) * 0.2];
                else if (l.direction === 'forward') { const c = (this.rnd() - 0.5) * (l.cone ?? 60) * Math.PI / 180; flat = f.dir([Math.sin(c), 0, Math.cos(c)]); }
                else { flat = f.dir([d * Math.cos(a), 0, d * Math.sin(a)]); if (Math.hypot(...flat) < 1e-6) flat = [Math.cos(a), 0, Math.sin(a)]; }
                const fl = Math.hypot(flat[0], flat[2]) || 1;
                const v = l.direction === 'up' ? [flat[0], 1, flat[2]] : [flat[0] / fl * Math.cos(elev), Math.sin(elev), flat[2] / fl * Math.cos(elev)];
                const vl = Math.hypot(...v), sp = pick(speed, this) * s;
                const sz = pick(size, this) * s;
                const mesh = new THREE.Mesh(this.box, this.blockMat(pickBlock(l.block, () => this.rnd()), l.glow));
                mesh.scale.setScalar(sz);
                mesh.position.set(...pos);
                const axis = new THREE.Vector3(this.rnd() - 0.5, this.rnd() - 0.5, this.rnd() - 0.5).normalize();
                this.group.add(mesh);
                run.pieces.push({ mesh, v: v.map(c => c / vl * sp), axis, spin: (this.rnd() * 2 - 1) * spin, life: Math.round(pick(life, this) * 20), shrink: -1, sz, rest: false });
            }
        };
        run.update = () => {
            for (let i = 0; i < run.pieces.length; i++) {
                const p = run.pieces[i];
                if (--p.life <= 0 && p.shrink < 0) p.shrink = 5;
                if (p.shrink >= 0) {
                    p.mesh.scale.setScalar(p.sz * Math.max(0, p.shrink / 5));
                    if (p.shrink-- <= 0) { this.group.remove(p.mesh); run.pieces.splice(i--, 1); continue; }
                }
                if (p.rest) continue;
                p.v[1] -= gravity;
                const m = p.mesh.position;
                m.x += p.v[0]; m.y += p.v[1]; m.z += p.v[2];
                if (p.v[1] < 0 && m.y - p.sz / 2 <= 0) {
                    m.y = p.sz / 2;
                    p.v[1] = -p.v[1] * bounce; p.v[0] *= 0.6; p.v[2] *= 0.6; p.spin *= 0.6;
                    if (Math.abs(p.v[1]) < 0.05 && Math.hypot(p.v[0], p.v[2]) < 0.05) p.rest = true;
                }
                p.mesh.rotateOnAxis(p.axis, p.spin);
            }
            return run.pieces.length > 0;
        };
        run.end = () => { for (const p of run.pieces) this.group.remove(p.mesh); run.pieces = []; };
        return run;
    }

    waveRun(inst, l) {
        const run = { pops: [], done: new Set() };
        const r = rangeOf(l.radius, 1, 5), height = l.height ?? 0.5, sector = Math.max(1, Math.min(360, l.angle ?? 360)) * Math.PI / 180;
        run.emit = (progress) => {
            const f = this.frameOf(run), s = inst.scale;
            const rad = (r[0] + (r[1] - r[0]) * progress) * s;
            const n = Math.max(4, Math.ceil(rad * sector * (l.density ?? 1)));
            for (let i = 0; i < n; i++) {
                const a = -sector / 2 + sector * (i + 0.5) / n;
                const w = f.toWorld([rad * Math.sin(a), 0, rad * Math.cos(a)]);
                const bx = Math.floor(w[0]), bz = Math.floor(w[2]), key = bx + ',' + bz;
                if (run.done.has(key)) continue;
                run.done.add(key);
                const mesh = new THREE.Mesh(this.box, this.blockMat(pickBlock(l.block, () => this.rnd())));
                mesh.position.set(bx + 0.5, -0.5, bz + 0.5);
                mesh.scale.setScalar(1.01);
                this.group.add(mesh);
                run.pops.push({ mesh, age: 0 });
            }
        };
        run.update = () => {
            const h = height * inst.scale;
            for (let i = 0; i < run.pops.length; i++) {
                const p = run.pops[i];
                p.age++;
                const y = p.age <= 4 ? h * p.age / 4 : p.age <= 8 ? h * (1 - (p.age - 4) / 4) : 0;
                p.mesh.position.y = -0.5 + y;
                if (p.age > 9) { this.group.remove(p.mesh); run.pops.splice(i--, 1); }
            }
            return run.pops.length > 0;
        };
        run.end = () => { for (const p of run.pops) this.group.remove(p.mesh); run.pops = []; };
        return run;
    }

    decalRun(inst, l) {
        const run = { plates: [], left: -1, shrink: -1 };
        run.emit = () => {
            const f = this.frameOf(run), s = inst.scale, rad = (l.radius ?? 2) * s, pieces = Math.max(1, l.pieces ?? 12);
            const spots = [];
            const pattern = l.pattern || 'crack';
            if (pattern === 'circle') for (let i = 0; i < pieces; i++) { const a = Math.PI * 2 * i / pieces; spots.push([rad * Math.cos(a), rad * Math.sin(a), Math.max(0.25, Math.PI * 2 * rad / pieces * 1.1), -a]); }
            else if (pattern === 'scorch') for (let i = 0; i < pieces; i++) { const a = this.rnd() * Math.PI * 2, d = rad * Math.sqrt(this.rnd()); spots.push([d * Math.cos(a), d * Math.sin(a), (0.4 + this.rnd() * 0.5) * s, this.rnd() * Math.PI]); }
            else {
                const branches = 3 + Math.floor(this.rnd() * 3), per = Math.max(1, Math.floor(pieces / branches));
                for (let b = 0; b < branches; b++) {
                    let a = Math.PI * 2 * b / branches + this.rnd() * 0.6, x = 0, z = 0;
                    const step = rad / per;
                    for (let i = 0; i < per; i++) { a += (this.rnd() - 0.5) * 0.8; x += Math.cos(a) * step; z += Math.sin(a) * step; spots.push([x, z, step * 1.15, -a]); }
                }
            }
            const glow = l.glow ? { emissive: hexColor(l.glow), emissiveIntensity: 0.9 } : {};
            for (const sp of spots) {
                const w = f.toWorld([sp[0], 0, sp[1]]);
                const mesh = new THREE.Mesh(this.box, this.blockMat(pickBlock(l.block ?? 'black_concrete', () => this.rnd()), l.glow));
                mesh.scale.set(sp[2], 0.02, sp[2]);
                mesh.position.set(w[0], 0.012 + this.rnd() * 0.004, w[2]);
                mesh.rotation.y = sp[3] - f.yaw * Math.PI / 180;
                this.group.add(mesh);
                run.plates.push(mesh);
            }
            run.left = Math.round((l.lifetime ?? 4) * 20);
        };
        run.update = () => {
            if (!run.plates.length) return false;
            if (run.left > 0) run.left--;
            else if (run.shrink < 0) run.shrink = 6;
            else {
                for (const m of run.plates) m.scale.multiplyScalar(0.7);
                if (run.shrink-- <= 0) { run.end(); return false; }
            }
            return true;
        };
        run.end = () => { for (const m of run.plates) this.group.remove(m); run.plates = []; };
        return run;
    }

    telegraphRun(inst, l) {
        const shape = parseShape(l.shape);
        const color = hexColor(l.color, 0xff3030);
        const run = { objs: [] };
        const lineMat = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.95, depthTest: false });
        const fillMat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.22, side: THREE.DoubleSide, depthWrite: false });
        const drawOutline = (f, scale, y) => {
            const pts = outline(shape, 64, scale).map(p => new THREE.Vector3(...f.toWorld([p[0], y, p[2]])));
            return new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), lineMat);
        };
        const drawFill = (f, scale) => {
            const pts = outline(shape, 48, scale);
            const verts = [];
            const c = shape.type === 'LINE' || shape.type === 'BOX' ? null : f.toWorld([0, 0.03, 0]);
            for (let i = 0; i < pts.length - 1; i++) {
                const a = f.toWorld([pts[i][0], 0.03, pts[i][2]]), b = f.toWorld([pts[i + 1][0], 0.03, pts[i + 1][2]]);
                const o = c || f.toWorld([pts[0][0], 0.03, pts[0][2]]);
                verts.push(...o, ...a, ...b);
            }
            const g = new THREE.BufferGeometry();
            g.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3));
            return new THREE.Mesh(g, fillMat);
        };
        run.emit = (progress) => {
            const f = this.frameOf(run), s = inst.scale;
            for (const o of run.objs) { this.group.remove(o); o.geometry.dispose(); }
            run.objs = [];
            if (l.style === 'plates') {
                const reach = Math.max(1, (radiusOf(shape, 0, s) + lengthOf(shape, 0, s)));
                const cells = [];
                for (let x = -reach; x <= reach; x++) for (let z = -reach; z <= reach; z++) if (contains(shape, x, 0, z, s, 1)) cells.push([x, z, Math.hypot(x, z)]);
                cells.sort((a, b) => a[2] - b[2]);
                const want = l.fill === false ? cells.length : Math.ceil(cells.length * Math.max(0.05, progress));
                for (const c of cells.slice(0, want)) {
                    const m = new THREE.Mesh(new THREE.BoxGeometry(0.92, 0.02, 0.92), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.55 }));
                    const w = f.toWorld([c[0], 0.01, c[1]]);
                    m.position.set(...w);
                    m.rotation.y = -f.yaw * Math.PI / 180;
                    this.group.add(m);
                    run.objs.push(m);
                }
                return;
            }
            run.objs.push(drawOutline(f, s, 0.06));
            if (l.fill !== false) {
                const k = Math.max(0.02, progress);
                run.objs.push(drawOutline(f, s * k, 0.05));
                run.objs.push(drawFill(f, s * k));
            }
            for (const o of run.objs) { o.renderOrder = 6; this.group.add(o); }
        };
        run.end = () => { for (const o of run.objs) { this.group.remove(o); o.geometry.dispose(); } run.objs = []; };
        return run;
    }

    modelRun(inst, l) {
        const run = { shown: [] };
        const life = Math.max(1, Math.round((l.lifetime ?? 0.5) * 20));
        const ft = (v, d) => (v == null ? [d, d, d] : typeof v === 'number' ? [v, v, v] : Array.isArray(v) ? v : null);
        const scaleFrom = ft(l.scale?.from ?? l.scale, 1), scaleTo = ft(l.scale?.to ?? l.scale, 1);
        const rotFrom = ft(l.rotation?.from ?? l.rotation, 0), rotTo = ft(l.rotation?.to ?? l.rotation, 0);
        run.emit = () => {
            const f = this.frameOf(run);
            const g = new THREE.Group();
            const m = new THREE.Mesh(this.box, new THREE.MeshBasicMaterial({ color: 0x9747ff, wireframe: true }));
            g.add(m);
            g.position.set(...f.pos);
            g.rotation.y = -f.yaw * Math.PI / 180;
            this.group.add(g);
            run.shown.push({ g, m, age: 0 });
            this.log('model', `${l.item_model} (item model, shown as a box)`);
        };
        run.update = () => {
            for (let i = 0; i < run.shown.length; i++) {
                const s = run.shown[i], t = Math.min(1, s.age / life), k = inst.scale;
                s.m.scale.set(...scaleFrom.map((v, a) => (v + (scaleTo[a] - v) * t) * k));
                s.m.rotation.set(...rotFrom.map((v, a) => (v + (rotTo[a] - v) * t) * Math.PI / 180), 'YXZ');
                if ((l.anchor || {}).follow) s.g.position.set(...this.frameOf(run).pos);
                if (++s.age > life) { this.group.remove(s.g); run.shown.splice(i--, 1); }
            }
            return run.shown.length > 0;
        };
        run.end = () => { for (const s of run.shown) this.group.remove(s.g); run.shown = []; };
        return run;
    }

    soundRun(inst, l) {
        const run = {};
        run.emit = () => {
            const p = Array.isArray(l.pitch) ? `${l.pitch[0]}–${l.pitch[1]}` : (l.pitch ?? 1);
            this.log('sound', `${l.sound}  vol ${l.volume ?? 1}  pitch ${p}`);
        };
        return run;
    }

    presetRun(inst, l) {
        const run = { kids: [] };
        run.emit = () => {
            const def = this.ctx.library.get(String(l.id || '').toLowerCase());
            if (!def) { this.log('warn', `preset "${l.id}" is not in the library`); return; }
            if (inst.depth >= MAX_DEPTH) return;
            const f = this.frameOf(run);
            const origin = (l.anchor || {}).follow ? inst.origin : (() => ({ pos: f.pos, yaw: f.yaw }));
            run.kids.push(this.newInstance(def, origin, inst.scale * (l.scale ?? 1), inst.depth + 1));
        };
        run.update = () => {
            for (let i = 0; i < run.kids.length; i++) if (!this.tickInstance(run.kids[i])) { this.stopInstance(run.kids[i]); run.kids.splice(i--, 1); }
            return run.kids.length > 0;
        };
        run.end = () => { for (const k of run.kids) this.stopInstance(k); run.kids = []; };
        return run;
    }
}

function rangeOf(v, a, b) {
    if (typeof v === 'number') return [v, v];
    if (Array.isArray(v) && v.length === 2) return [Math.min(...v), Math.max(...v)];
    return [a, b];
}
function pick(r, sim) { return r[0] + (r[1] - r[0]) * sim.rnd(); }

/** Effect duration like EffectDefinition: explicit, else the end of its last layer (incl. lifetimes). */
export function effectDuration(def, library, depth = 0) {
    if (typeof def.duration === 'number') return Math.max(0.05, def.duration);
    let end = 0;
    for (const l of def.layers || []) {
        let extra = 0;
        if (l.type === 'debris') extra = rangeOf(l.lifetime, 1, 1.5)[1] + 0.25;
        else if (l.type === 'decal') extra = (l.lifetime ?? 4) + 0.3;
        else if (l.type === 'model') extra = l.lifetime ?? 0.5;
        else if (l.type === 'ground_wave') extra = 0.5;
        else if (l.type === 'preset' && depth < MAX_DEPTH) { const d = library?.get(String(l.id || '').toLowerCase()); if (d) extra = effectDuration(d, library, depth + 1); }
        end = Math.max(end, (l.start || 0) + (l.duration || 0) + extra);
    }
    return Math.max(0.05, end);
}
