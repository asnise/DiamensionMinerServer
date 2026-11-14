// Entity mode: the open model's settings (_meta.json), moveset (_moveset.json: AI, phases, skills,
// actions), a skill preview with a target dummy, checks, save, and export to a server folder.
import * as THREE from 'three';
import { SchemaForms } from '../fx/form.js';
import { FxSim } from '../fx/sim.js';
import { parseShape, frame } from '../fx/shape.js';
import { SkillPreview, shapeGizmo } from './preview.js';
import { PARTICLES } from '../fx/looks.js';
import { History } from '../history.js';
import { renderPalette, SKILL_TEMPLATES, dragData, isToolDrag } from '../palette.js';
import { ask } from '../modal.js';

const S = window.studio;
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export const ACTION_TYPES = ['hit', 'telegraph', 'fx', 'sound', 'projectile', 'move', 'summon', 'self', 'animation', 'preset_skill', 'event'];
const ACT_COLOR = { hit: '#f24822', telegraph: '#ff8a65', fx: '#0d99ff', sound: '#ffc021', projectile: '#9747ff', move: '#14ae5c', summon: '#ff6fd0', self: '#4dd0e1', animation: '#b3b3b3', preset_skill: '#e0e0e0', event: '#ffd54f' };
const ACTION_HELP = {
    hit: 'damage in a shape', telegraph: 'warning area on the ground', fx: 'play an effect preset', sound: 'play a sound',
    projectile: 'fire a projectile', move: 'dash / leap / teleport / pull / push', summon: 'spawn other models',
    self: 'heal, invulnerable, speed', animation: 'play another animation', preset_skill: "run another skill's actions",
    event: 'signal for your plugin',
};
const NEW_ACTION = {
    hit: { type: 'hit', shape: { type: 'cone', radius: 4, angle: 120 }, damage: 10 },
    telegraph: { type: 'telegraph', shape: { type: 'circle', radius: 4 }, duration: 0.8 },
    fx: { type: 'fx', id: 'impact_spark' },
    sound: { type: 'sound', sound: 'entity.player.attack.sweep' },
    projectile: { type: 'projectile', speed: 0.6, lifetime: 3, on_hit: [{ type: 'fx', id: 'impact_spark' }] },
    move: { type: 'move', mode: 'dash', distance: 5, duration: 0.3 },
    summon: { type: 'summon', model: 'my_minion', count: 2, radius: 3 },
    self: { type: 'self', heal: 0.1 },
    animation: { type: 'animation', name: 'idle' },
    preset_skill: { type: 'preset_skill', skill: '', from: 0 },
    event: { type: 'event', tag: 'my_tag' },
};

export class EntityEditor {
    /** host: { viewport, modelState(), playAnim(name, speed), stopPreview(), fx (FxEditor), toast, store } */
    constructor(host) {
        this.h = host;
        this.vp = host.viewport;
        this.sim = new FxSim(this.vp.scene, host.fx.textures);
        this.sel = { kind: 'entity' };
        this.doc = null;
        this.playing = false;
        this.repeat = true;
        this.speed = 1;
        this.dist = 5;
        this.preview = new SkillPreview({
            scene: this.vp.scene, sim: this.sim,
            fxDefs: () => this.h.fx.libraryDefs(),
            pose: () => this.h.modelState().pose,
            yaw: () => this.h.modelState().yaw,
            playAnim: (n, sp) => this.h.playAnim(n, sp * this.speedForPhase()),
            animLength: (n) => this.h.modelState().model?.anims[n]?.length || 0,
            setRoot: (p) => {
                this.vp.modelRoot.position.set(...p);
                if (this.vp.hitbox) { this.vp.hitbox.position.x = p[0]; this.vp.hitbox.position.z = p[2]; }
            },
            dummy: () => this.dummyPos(),
            log: (k, t) => this.h.fx.log.call({ active: true }, k, t),
        });
        this.dummy = new THREE.Group();
        const dm = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), new THREE.MeshBasicMaterial({ color: 0x14ae5c, wireframe: true, transparent: true, opacity: 0.7 }));
        dm.position.y = 0.9;
        this.dummy.add(dm);
        this.dummy.visible = false;
        this.vp.scene.add(this.dummy);
        this.selGizmo = null;
        this.hist = new History();
        this.ready = this.init();
    }

    async init() {
        const docs = {};
        for (const n of ['common', 'effect', 'moveset', 'meta']) docs[n + '.schema.json'] = await S.schema(n);
        this.forms = new SchemaForms(docs);
        this.bind();
    }

    speedForPhase() { return 1; }

    dummyPos() {
        const y = this.h.modelState().yaw * Math.PI / 180;
        return [-Math.sin(y) * this.dist, 0, Math.cos(y) * this.dist];
    }

    // ---------------------------------------------------------------- documents

    /** Loads meta + moveset of the model open in Model mode (once per model file). */
    async syncModel() {
        const m = this.h.modelState().model;
        if (!m) { this.doc = null; this.renderAll(); return; }
        if (this.doc && this.doc.modelFile === m.file) { this.renderAll(); return; }
        const dir = S.path.dirname(m.file);
        const metaFile = S.path.join(dir, `${m.id}_meta.json`);
        const movesetFile = S.path.join(dir, `${m.id}_moveset.json`);
        let moveset = null;
        if (await S.exists(movesetFile)) {
            try { moveset = JSON.parse(await S.readText(movesetFile)); } catch (e) { this.h.toast(`${m.id}_moveset.json is not valid JSON: ${e.message}`, true); }
        }
        let meta = {};
        if (await S.exists(metaFile)) {
            try { meta = JSON.parse(await S.readText(metaFile)); } catch { /* keep {} */ }
        }
        this.doc = { modelFile: m.file, id: m.id, dir, metaFile, movesetFile, meta, moveset, dirty: false };
        this.hist.reset(this.snap());
        this.sel = { kind: 'entity' };
        this.preview.stop();
        this.renderAll();
    }

    get skills() { return this.doc?.moveset?.skills || {}; }

    /** The editable state as JSON (undo snapshots). */
    snap() {
        return JSON.stringify({ meta: this.doc.meta, moveset: this.doc.moveset });
    }

    undo() { this.restore(this.hist.undo()); }
    redo() { this.restore(this.hist.redo()); }

    restore(s) {
        if (!s || !this.doc) return;
        const v = JSON.parse(s);
        this.doc.meta = v.meta || {};
        this.doc.moveset = v.moveset;
        this.doc.dirty = true;
        // keep the selection when it still exists
        const sel = this.sel;
        if (sel.skill && !this.skills[sel.skill]) this.sel = { kind: 'entity' };
        else if (sel.kind === 'action' && !this.skills[sel.skill]?.actions?.[sel.index]) this.sel = { kind: 'skill', skill: sel.skill };
        else if (sel.kind === 'phase' && !this.doc.moveset?.phases?.[sel.index]) this.sel = { kind: 'ai' };
        this.renderAll();
    }

    changed(relist = false) {
        this.doc.dirty = true;
        this.hist.record(this.snap());
        if (relist) { this.renderSkills(); this.renderPhases(); }
        this.renderTitle();
        this.validateSoon();
        this.drawTimeline();
    }

    createMoveset() {
        const m = this.h.modelState().model;
        const skills = {};
        // One starter skill per attack_* animation: a cone hit at the middle of the swing.
        for (const [name, a] of Object.entries(m.anims)) {
            if (!name.startsWith('attack_')) continue;
            skills[name.replace(/^attack_/, '')] = { animation: name, cooldown: 4, conditions: { range: [0, 4] },
                actions: [{ at: +(a.length * 0.45).toFixed(2), type: 'hit', shape: { type: 'cone', radius: 4, angle: 120 }, damage: 10 }] };
        }
        this.doc.moveset = { format: 1, ai: { mode: 'auto' }, skills };
        this.changed(true);
        this.renderProps();
        this.h.toast(`Moveset created with ${Object.keys(skills).length} starter skill(s) from the attack_* animations`);
    }

    addSkill() {
        if (!this.doc) return;
        if (!this.doc.moveset) return this.createMoveset();
        let n = 1;
        while (this.skills['skill_' + n]) n++;
        const anims = Object.keys(this.h.modelState().model.anims);
        this.doc.moveset.skills['skill_' + n] = { animation: anims.find(a => a.startsWith('attack_')) || anims[0], cooldown: 4, actions: [] };
        this.select({ kind: 'skill', skill: 'skill_' + n });
        this.changed(true);
    }

    skillAction(id, act) {
        const sk = this.doc.moveset.skills;
        if (act === 'del') { delete sk[id]; this.select({ kind: 'entity' }); }
        else if (act === 'dup') { let n = id + '_copy'; while (sk[n]) n += '_'; sk[n] = structuredClone(sk[id]); this.select({ kind: 'skill', skill: n }); }
        else if (act === 'rename') {
            ask('Rename skill', 'New id', id, 'a-z 0-9 _ - · phases, followups and preset_skill follow the rename').then(v => {
                const nn = (v || '').trim().toLowerCase();
                if (!nn || nn === id) return;
                this.renameSkill(id, nn);
            });
            return;
        }
        this.changed(true);
    }

    renameSkill(id, nn) {
        const sk = this.doc.moveset.skills;
        {
            if (!/^[a-z0-9_\-/]+$/.test(nn) || sk[nn]) { this.h.toast('Invalid or existing id', true); return; }
            const rebuilt = {};
            for (const [k, v] of Object.entries(sk)) rebuilt[k === id ? nn : k] = v;
            this.doc.moveset.skills = rebuilt;
            // references follow the rename
            for (const p of this.doc.moveset.phases || []) { if (p.skills) p.skills = p.skills.map(s => (s === id ? nn : s)); if (p.on_enter === id) p.on_enter = nn; }
            for (const s of Object.values(rebuilt)) {
                if (s.followup?.skill === id) s.followup.skill = nn;
                for (const a of s.actions || []) if (a.type === 'preset_skill' && a.skill === id) a.skill = nn;
            }
            this.select({ kind: 'skill', skill: nn });
        }
        this.changed(true);
    }

    // ---------------------------------------------------------------- toolbox (drag & drop)

    renderToolbox() {
        const el = $('#entToolbox');
        if (!el) return;
        const fxIds = [...this.h.fx.libraryDefs().keys()].sort();
        const sections = [
            { title: 'Skill templates', items: SKILL_TEMPLATES.map(t => ({ label: t.label, sub: t.sub, color: 'var(--accent)', data: { kind: 'template', id: t.id, actions: t.actions } })) },
            { title: 'Actions', items: ACTION_TYPES.map(t => ({ label: t, sub: ACTION_HELP[t], color: ACT_COLOR[t], data: { kind: 'action', action: NEW_ACTION[t] } })) },
            { title: 'Effects', items: fxIds.map(id => ({ label: id, sub: this.h.fx.library.get(id)?.def?.description || 'effect preset', color: '#0d99ff', data: { kind: 'effect', id } })) },
        ];
        renderPalette(el, sections, (d) => this.dropTool(d, this.preview.time || 0, null), $('#entToolFilter')?.value || '');
    }

    /** Puts a toolbox item into the selected skill at time {@code at} (seconds), on {@code bone} if given. */
    dropTool(d, at, bone) {
        if (!d || !this.doc) return;
        if (!this.doc.moveset) this.doc.moveset = { format: 1, ai: { mode: 'auto' }, skills: {} };
        let sid = this.sel.skill && this.skills[this.sel.skill] ? this.sel.skill : null;
        if (!sid) {
            // nothing picked: a dropped template becomes a new skill
            let base = d.kind === 'template' ? d.id : 'skill', n = '', k = 1;
            while (this.skills[base + n]) n = '_' + (++k);
            sid = base + n;
            const anims = Object.keys(this.h.modelState().model.anims);
            this.doc.moveset.skills[sid] = { animation: anims.find(a => a.startsWith('attack_')) || anims[0], cooldown: 6, conditions: { range: [0, 6] }, actions: [] };
        }
        const s = this.skills[sid];
        const t0 = Math.max(0, Math.round(at * 20) / 20);
        const list = d.kind === 'template' ? structuredClone(d.actions)
            : d.kind === 'effect' ? [{ type: 'fx', id: d.id, at: 0 }]
                : [structuredClone(d.action)];
        const added = [];
        for (const a of list) {
            a.at = +(t0 + (a.at || 0)).toFixed(2);
            if (bone && ['fx', 'hit', 'projectile', 'telegraph', 'sound', 'event', 'summon'].includes(a.type)) a.bone = bone;
            (s.actions ||= []).push(a);
            added.push(a);
        }
        s.actions.sort((x, y) => (x.at || 0) - (y.at || 0));
        this.sel = { kind: 'action', skill: sid, index: s.actions.indexOf(added[0]) };
        this.changed(true);
        this.select(this.sel);
        this.h.toast(`${added.length} action(s) added to ${sid} at ${t0.toFixed(2)} s${bone ? ' on ' + bone : ''}`);
    }

    /** Drop targets: the skill timeline (time from x) and the model in the viewport (bone under the pointer). */
    bindDrops() {
        const wrap = $('#entTlTracks');
        wrap.addEventListener('dragover', (e) => {
            if (!isToolDrag(e) || !this.doc) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
            this.dropX = e.clientX - this.tl.canvas.getBoundingClientRect().left;
            this.drawTimeline();
        });
        wrap.addEventListener('dragleave', () => { this.dropX = null; this.drawTimeline(); });
        wrap.addEventListener('drop', (e) => {
            const d = dragData(e);
            this.dropX = null;
            if (!d) return;
            e.preventDefault();
            const s = this.tlRows();
            const x = e.clientX - this.tl.canvas.getBoundingClientRect().left;
            const t = Math.max(0, (x - 12) / this.pps()) * (s?.speed || 1);
            this.dropTool(d, t, null);
        });
        const vp = $('#viewport');
        vp.addEventListener('dragover', (e) => {
            if (!isToolDrag(e) || !this.active) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
        });
        vp.addEventListener('drop', (e) => {
            if (!this.active) return;
            const d = dragData(e);
            if (!d) return;
            e.preventDefault();
            e.stopPropagation();
            const bone = this.vp.boneAt(e.clientX, e.clientY);
            this.dropTool(d, this.preview.time || 0, bone);
        });
    }

    addAction(type) {
        const s = this.skills[this.sel.skill];
        if (!s || !type) return;
        const a = structuredClone(NEW_ACTION[type]);
        a.at = +(this.preview.time || 0).toFixed(2);
        if (type === 'preset_skill') a.skill = Object.keys(this.skills).find(k => k !== this.sel.skill) || '';
        (s.actions ||= []).push(a);
        s.actions.sort((x, y) => (x.at || 0) - (y.at || 0));
        this.select({ kind: 'action', skill: this.sel.skill, index: s.actions.indexOf(a) });
        this.changed();
    }

    actionAction(i, act) {
        const list = this.skills[this.sel.skill].actions;
        if (act === 'del') { list.splice(i, 1); this.select({ kind: 'skill', skill: this.sel.skill }); }
        else if (act === 'dup') { list.splice(i + 1, 0, structuredClone(list[i])); this.select({ kind: 'action', skill: this.sel.skill, index: i + 1 }); }
        this.changed();
    }

    addPhase() {
        if (!this.doc?.moveset) return this.createMoveset();
        const ph = (this.doc.moveset.phases ||= []);
        if (!ph.length) ph.push({ id: 'one', hp_above: 0.5 });
        ph.push({ id: ['one', 'two', 'three', 'four', 'five'][ph.length] || 'phase_' + (ph.length + 1), hp_above: 0 });
        this.select({ kind: 'phase', index: ph.length - 1 });
        this.changed(true);
    }

    select(sel) {
        this.sel = sel;
        if (sel.skill && sel.skill !== this.preview.skillId) this.preview.stop();
        this.renderSkills();
        this.renderPhases();
        this.renderProps();
        this.drawTimeline();
    }

    // ---------------------------------------------------------------- checks

    validateSoon() { clearTimeout(this.vt); this.vt = setTimeout(() => this.validate(), 250); }

    async validate() {
        if (!this.doc) { $('#entChecks').innerHTML = ''; return; }
        const errs = [];
        for (const e of await S.validate('meta', this.doc.meta)) errs.push({ ...e, path: 'meta' + e.path });
        const ms = this.doc.moveset;
        if (ms) {
            for (const e of await S.validate('moveset', ms)) errs.push({ ...e, path: 'moveset' + e.path });
            const model = this.h.modelState().model, fx = this.h.fx.libraryDefs();
            const ids = new Set(Object.keys(ms.skills || {}));
            (ms.phases || []).forEach((p, i) => {
                for (const s of p.skills || []) if (!ids.has(s)) errs.push({ path: `moveset/phases/${i}/skills`, message: `unknown skill "${s}"` });
                if (p.on_enter && !ids.has(p.on_enter)) errs.push({ path: `moveset/phases/${i}/on_enter`, message: `unknown skill "${p.on_enter}"` });
            });
            const checkActions = (sid, list, where) => (list || []).forEach((a, i) => {
                const w = `${where}/${i}`;
                if (a.type === 'fx' && a.id && !fx.has(String(a.id).toLowerCase())) errs.push({ path: w, message: `effect "${a.id}" is not in your library or the built-in presets`, level: 'warn' });
                if (a.type === 'projectile') {
                    if (a.trail && !fx.has(a.trail)) errs.push({ path: w + '/trail', message: `effect "${a.trail}" not found`, level: 'warn' });
                    checkActions(sid, a.on_hit, w + '/on_hit');
                }
                if (a.type === 'preset_skill' && !ids.has(a.skill)) errs.push({ path: w, message: `unknown skill "${a.skill}"` });
                if (a.type === 'animation' && model && !model.anims[a.name]) errs.push({ path: w, message: `the model has no animation "${a.name}"` });
                if (a.bone && model && !model.byName[a.bone]) errs.push({ path: w + '/bone', message: `the model has no bone "${a.bone}" (plays at the driver)`, level: 'warn' });
            });
            for (const [sid, s] of Object.entries(ms.skills || {})) {
                if (s.animation && model && !model.anims[s.animation]) errs.push({ path: `moveset/skills/${sid}/animation`, message: `the model has no animation "${s.animation}"` });
                if (s.followup?.skill && !ids.has(s.followup.skill)) errs.push({ path: `moveset/skills/${sid}/followup`, message: `unknown skill "${s.followup.skill}"` });
                if (!s.internal && !s.conditions?.range) errs.push({ path: `moveset/skills/${sid}/conditions`, message: 'no range condition: the AI may cast it at any distance', level: 'warn' });
                checkActions(sid, s.actions, `moveset/skills/${sid}/actions`);
            }
        }
        this.errors = errs;
        const badge = $('#entCheckBadge');
        badge.hidden = !errs.length;
        badge.textContent = errs.length;
        badge.classList.toggle('err', errs.some(e => e.level !== 'warn'));
        $('#entChecks').innerHTML = errs.length ? errs.map(e => `<div class="check ${e.level === 'warn' ? '' : 'err'}"><span class="dot"></span><div><div>${esc(e.message)}</div><div class="where">${esc(e.path)}</div></div></div>`).join('')
            : '<div class="check ok"><span class="dot"></span><div>Valid: the plugin will load these files.</div></div>';
    }

    // ---------------------------------------------------------------- save / export

    cleanMoveset() {
        const m = structuredClone(this.doc.moveset);
        if (!m) return null;
        const out = { format: 1 };
        if (m.ai && Object.keys(m.ai).length) out.ai = m.ai;
        if (m.phases?.length) out.phases = m.phases;
        out.skills = m.skills || {};
        for (const s of Object.values(out.skills)) if (s.actions) s.actions.sort((a, b) => (a.at || 0) - (b.at || 0));
        return out;
    }

    async save() {
        if (!this.doc) return;
        const written = [];
        if (Object.keys(this.doc.meta).length) { await S.writeJson(this.doc.metaFile, JSON.stringify(this.doc.meta, null, 2) + '\n'); written.push(S.path.basename(this.doc.metaFile)); }
        if (this.doc.moveset) { await S.writeJson(this.doc.movesetFile, JSON.stringify(this.cleanMoveset(), null, 2) + '\n'); written.push(S.path.basename(this.doc.movesetFile)); }
        this.doc.dirty = false;
        this.renderTitle();
        this.h.toast('Saved ' + (written.join(', ') || 'nothing'));
    }

    async chooseServer() {
        const d = await S.openDialog({ title: 'Your server\'s plugins/EXeaEntityCore folder', directory: true });
        if (!d) return null;
        await this.h.project.setServer(d);
        this.renderProps();
        return d;
    }

    /** The export job (model, pack, meta, moveset, your effects it uses); folder export and live upload share it. */
    exportJob() {
        const m = this.h.modelState().model;
        const used = new Set();
        const walk = (list) => (list || []).forEach(a => { if (a.type === 'fx' && a.id) used.add(String(a.id).toLowerCase()); if (a.type === 'projectile') { if (a.trail) used.add(a.trail); walk(a.on_hit); } if (a.fx_in) used.add(a.fx_in); if (a.fx_out) used.add(a.fx_out); });
        for (const s of Object.values(this.skills)) walk(s.actions);
        const effects = [...used].map(id => ({ id, e: this.h.fx.library.get(id) })).filter(x => x.e?.source === 'mine').map(x => ({ id: x.id, file: x.e.file }));
        return {
            id: this.doc.id,
            bbmodel: m.source === 'bbmodel' ? m.file : null,
            exportedDir: m.source === 'export' ? this.doc.dir : null, packDir: m.pack || null,
            meta: Object.keys(this.doc.meta).length ? this.doc.meta : null,
            moveset: this.doc.moveset ? this.cleanMoveset() : null,
            effects,
        };
    }

    async sendLive() {
        if (!this.doc) return;
        const r = await this.h.live.send(this.exportJob());
        if (r) { this.lastExport = r.log; this.renderProps(); }
    }

    async exportToServer(dirOverride = null) {
        if (!this.doc) return;
        const serverDir = dirOverride || this.h.project.serverDir || await this.chooseServer();
        if (!serverDir) return;
        const m = this.h.modelState().model;
        // effects the moveset uses that live in your effects folder (built-in ones are in the jar already)
        const used = new Set();
        const walk = (list) => (list || []).forEach(a => { if (a.type === 'fx' && a.id) used.add(String(a.id).toLowerCase()); if (a.type === 'projectile') { if (a.trail) used.add(a.trail); walk(a.on_hit); } if (a.fx_in) used.add(a.fx_in); if (a.fx_out) used.add(a.fx_out); });
        for (const s of Object.values(this.skills)) walk(s.actions);
        const effects = [...used].map(id => ({ id, e: this.h.fx.library.get(id) })).filter(x => x.e?.source === 'mine').map(x => ({ id: x.id, file: x.e.file }));
        try {
            const out = await S.exportModel({
                id: this.doc.id, serverDir,
                bbmodel: m.source === 'bbmodel' ? m.file : null,
                exportedDir: m.source === 'export' ? this.doc.dir : null, packDir: m.pack || null,
                meta: Object.keys(this.doc.meta).length ? this.doc.meta : null,
                moveset: this.doc.moveset ? this.cleanMoveset() : null,
                effects,
            });
            this.h.toast(`Exported ${this.doc.id}: ${out.length} item(s). In game: /exea reload, then /exea spawn ${this.doc.id}`);
            this.lastExport = out;
            this.renderProps();
        } catch (e) {
            this.h.toast('Export failed: ' + e.message, true);
        }
    }

    // ---------------------------------------------------------------- rendering

    renderAll() {
        this.renderToolbox();
        this.renderSkills();
        this.renderPhases();
        this.renderProps();
        this.renderTitle();
        this.validate();
        this.drawTimeline();
    }

    renderTitle() {
        if (document.body.dataset.mode !== 'entity') return;
        const m = this.h.modelState().model;
        $('#docTitle').innerHTML = m ? `${esc(this.doc?.meta.display_name || m.name)}${this.doc?.dirty ? ' <span style="color:var(--accent)">•</span>' : ''} <span class="chip">entity</span>` : '<span class="muted">No model open</span>';
        $('#entDocName').textContent = this.doc ? `${this.doc.id}${this.doc.dirty ? ' •' : ''}` : '';
    }

    renderSkills() {
        const el = $('#entSkills');
        if (!this.doc) { el.innerHTML = '<div class="empty">Open a model in <b>Model</b> mode first.</div>'; return; }
        const rows = [`<div class="row${this.sel.kind === 'entity' ? ' sel' : ''}" data-k="entity"><span class="type-dot" style="background:var(--text-3)"></span><span class="name">Entity settings<span class="sub">${esc(this.doc.id)}_meta.json</span></span></div>`];
        if (!this.doc.moveset) {
            rows.push('<div class="empty">No moveset yet. <b>+ Skill</b> creates one with a starter skill per <b>attack_*</b> animation.</div>');
        } else {
            rows.push('<div class="group-title"><span>Skills</span><span class="muted">' + Object.keys(this.skills).length + '</span></div>');
            for (const [id, s] of Object.entries(this.skills)) {
                const bad = (this.errors || []).some(e => e.path.startsWith(`moveset/skills/${id}/`) && e.level !== 'warn');
                rows.push(`<div class="row${this.sel.skill === id && this.sel.kind !== 'entity' ? ' sel' : ''}" data-k="skill" data-id="${esc(id)}">
                    <span class="type-dot" style="background:${s.internal ? 'var(--text-3)' : 'var(--accent)'}"></span>
                    <span class="name">${esc(s.name || id)}<span class="sub">${esc(s.animation || 'no animation')}</span>${bad ? ' <span class="chip err">!</span>' : ''}</span>
                    ${s.internal ? '<span class="chip">internal</span>' : `<span class="sub">${s.cooldown ?? 0}s</span>`}
                    <span class="acts"><button data-a="rename" title="Rename">✎</button><button data-a="dup" title="Duplicate">⧉</button><button data-a="del" title="Delete">✕</button></span></div>`);
            }
        }
        el.innerHTML = rows.join('');
        el.querySelectorAll('.row[data-k]').forEach(r => r.addEventListener('click', (e) => {
            const b = e.target.closest('button[data-a]');
            if (b) return this.skillAction(r.dataset.id, b.dataset.a);
            if (r.dataset.k === 'entity') this.select({ kind: 'entity' });
            else this.select({ kind: 'skill', skill: r.dataset.id });
        }));
    }

    renderPhases() {
        const el = $('#entPhases');
        if (!this.doc?.moveset) { el.innerHTML = '<div class="empty">No moveset yet.</div>'; return; }
        const ph = this.doc.moveset.phases || [];
        el.innerHTML = `<div class="row${this.sel.kind === 'ai' ? ' sel' : ''}" data-k="ai"><span class="type-dot" style="background:var(--purple)"></span><span class="name">AI<span class="sub">${esc(this.doc.moveset.ai?.mode || 'auto')}</span></span></div>
            <div class="group-title"><span>Phases (high HP → low)</span><span class="muted">${ph.length}</span></div>` +
            (ph.map((p, i) => `<div class="row${this.sel.kind === 'phase' && this.sel.index === i ? ' sel' : ''}" data-k="phase" data-i="${i}">
                <span class="type-dot" style="background:var(--warn)"></span><span class="name">${esc(p.id)}<span class="sub">HP &gt; ${Math.round((p.hp_above || 0) * 100)}%</span></span>
                ${p.on_enter ? `<span class="chip">${esc(p.on_enter)}</span>` : ''}
                <span class="acts"><button data-a="del" title="Delete">✕</button></span></div>`).join('') || '<div class="empty">No phases: every skill is usable at any HP.</div>');
        el.querySelectorAll('.row[data-k]').forEach(r => r.addEventListener('click', (e) => {
            if (e.target.closest('button[data-a="del"]')) { ph.splice(+r.dataset.i, 1); this.select({ kind: 'ai' }); this.changed(true); return; }
            if (r.dataset.k === 'ai') this.select({ kind: 'ai' });
            else this.select({ kind: 'phase', index: +r.dataset.i });
        }));
    }

    renderProps() {
        const el = $('#entProps');
        if (!this.doc) { el.innerHTML = '<div class="empty">Open a model in <b>Model</b> mode, then come back here to give it settings and skills.</div>'; return; }
        const m = this.h.modelState().model;
        const msDocs = 'moveset.schema.json', msSchema = this.forms.docs[msDocs];
        const bones = m.bones.map(b => b.name), anims = Object.keys(m.anims), skillIds = Object.keys(this.skills);
        const fxIds = () => [...this.h.fx.libraryDefs().keys()].sort();
        const particles = this.h.fx.suggestLists?.particles || Object.keys(PARTICLES).sort(), sounds = this.h.fx.suggestLists?.sounds || [];
        this.forms.suggest = { particle: particles, bone: bones, animation: anims, name: anims, idle_animation: anims, move_animation: anims,
            glow_bones: bones, skills: skillIds, on_enter: skillIds, skill: skillIds,
            id: fxIds, trail: fxIds, fx_in: fxIds, fx_out: fxIds, sound: sounds, hurt_sound: sounds, death_sound: sounds,
            hurt_particle: particles, death_particle: particles, item: this.h.fx.suggestLists?.items || [], item_model: this.h.fx.suggestLists?.items || [], block: this.h.fx.suggestLists?.blocks || [], tag: ['hit', 'phase', 'enrage', 'combo'],
            driver: ['husk', 'zombie', 'skeleton', 'wither_skeleton', 'iron_golem', 'ravager', 'warden', 'armor_stand'] };
        const sec = (title, sub = '') => `<div class="sec"><h3>${title} <span class="muted">${sub}</span></h3><div class="body"></div></div>`;
        const sel = this.sel;
        let html = '';
        if (sel.kind === 'entity') html = sec('Entity settings', esc(this.doc.id) + '_meta.json');
        else if (sel.kind === 'ai') html = sec('AI', 'how skills are picked');
        else if (sel.kind === 'phase') html = sec('Phase', (this.doc.moveset.phases[sel.index] || {}).id);
        else if (sel.kind === 'skill') html = sec('Skill', esc(sel.skill)) + `<div class="sec"><h3>Actions <span class="muted">${(this.skills[sel.skill]?.actions || []).length}</span></h3>
            <div class="pane-tools" style="padding:0 0 8px"><select id="entAddAction"><option value="">+ Add action at the playhead…</option>${ACTION_TYPES.map(t => `<option>${t}</option>`).join('')}</select></div><div id="entActList" class="list"></div></div>`;
        else if (sel.kind === 'action') html = sec(`<span class="type-dot" style="background:${ACT_COLOR[this.curAction()?.type] || '#888'}"></span>Action`, `${esc(sel.skill)} · ${esc(this.curAction()?.type)}`);
        html += `<div class="sec"><h3>Files <span class="muted">${esc(this.doc.dir)}</span></h3>
            <div class="pane-tools" style="padding:0">
              <button class="btn sm primary" id="entSave" title="Ctrl+S">Save</button>
              <button class="btn sm" id="entExport" title="Model, pack, meta, moveset and your effects into plugins/EXeaEntityCore">Export to server</button>
              <button class="btn sm" id="entServer">Server…</button>
            </div>
            <p class="hint" style="margin-top:8px">Saved next to the model. Server folder: ${this.h.project.serverDir ? '<a href="#" data-open-server style="color:var(--accent)">' + esc(this.h.project.serverDir) + '</a>' : 'not set'}${this.lastExport ? '<br>Last export: ' + esc(this.lastExport.join(' · ')) : ''}</p></div>
            <div class="sec"><h3>Live server <span class="muted">${this.h.live.ok ? this.h.live.status.players.length + ' player(s) online' : 'not connected (Live, top right)'}</span></h3>
              <div class="pane-tools" style="padding:0">
                <button class="btn sm primary" id="lvSend" title="Upload model, pack, meta, moveset and effects, then reload">Send &amp; reload</button>
                <button class="btn sm" id="lvSpawn" title="Spawn it 3 blocks in front of the player">Spawn</button>
                ${sel.skill ? '<button class="btn sm" id="lvCast" title="The nearest one casts the selected skill at the player">Cast ' + esc(sel.skill) + '</button>' : ''}
                <button class="btn sm" id="lvKill" title="Remove every live entity of this model">Remove all</button>
              </div></div>`;
        el.innerHTML = html;
        const body = el.querySelector('.sec .body');
        if (sel.kind === 'entity') {
            this.forms.render(body, this.forms.docs['meta.schema.json'], 'meta.schema.json', this.doc.meta, (k) => {
                this.changed(false);
                if (['driver', 'hitbox_scale', 'glow_bones'].includes(k)) this.vp.setHitbox(this.doc.meta);
            });
        } else if (sel.kind === 'ai') {
            const ai = (this.doc.moveset.ai ||= {});
            this.forms.render(body, msSchema.$defs.ai, msDocs, ai, () => this.changed(true));
        } else if (sel.kind === 'phase') {
            this.forms.render(body, msSchema.$defs.phase, msDocs, this.doc.moveset.phases[sel.index], () => this.changed(true));
        } else if (sel.kind === 'skill') {
            this.forms.render(body, msSchema.$defs.skill, msDocs, this.skills[sel.skill], (k) => { this.changed(k === 'name' || k === 'animation' || k === 'internal' || k === 'cooldown'); }, ['actions']);
            this.renderActionList();
            el.querySelector('#entAddAction').onchange = (e) => { this.addAction(e.target.value); e.target.value = ''; };
        } else if (sel.kind === 'action') {
            const a = this.curAction();
            if (a) this.forms.render(body, msSchema.$defs.action, msDocs, a, (k) => { if (k === 'type') this.renderProps(); this.changed(); }, ['type']);
            body.insertAdjacentHTML('afterbegin', `<div class="pane-tools" style="padding:0 0 8px"><button class="btn sm" id="entBackSkill">← ${esc(sel.skill)}</button></div>`);
            body.querySelector('#entBackSkill').onclick = () => this.select({ kind: 'skill', skill: sel.skill });
        }
        el.querySelector('#entSave').onclick = () => this.save();
        el.querySelectorAll('[data-open-server]').forEach(a => a.onclick = (e) => { e.preventDefault(); S.showFolder(this.h.project.serverDir).catch(err => this.h.toast(err.message, true)); });
        el.querySelector('#lvSend').onclick = () => this.sendLive();
        el.querySelector('#lvSpawn').onclick = () => this.h.live.cmd('spawn', { model: this.doc.id });
        el.querySelector('#lvKill').onclick = () => this.h.live.cmd('kill', { model: this.doc.id });
        const cast = el.querySelector('#lvCast');
        if (cast) cast.onclick = () => this.h.live.cmd('skill', { skill: sel.skill, model: this.doc.id });
        el.querySelector('#entExport').onclick = () => this.exportToServer();
        el.querySelector('#entServer').onclick = () => this.chooseServer();
    }

    curAction() {
        return this.sel.kind === 'action' ? this.skills[this.sel.skill]?.actions?.[this.sel.index] : null;
    }

    renderActionList() {
        const list = this.skills[this.sel.skill]?.actions || [];
        const el = $('#entActList');
        if (!el) return;
        el.innerHTML = list.map((a, i) => `<div class="row" data-i="${i}"><span class="type-dot" style="background:${ACT_COLOR[a.type] || '#888'}"></span>
            <span class="name">${esc(a.type)}<span class="sub">${esc(actionLabel(a))}</span></span><span class="sub">${(a.at || 0).toFixed(2)}s</span>
            <span class="acts"><button data-a="dup" title="Duplicate">⧉</button><button data-a="del" title="Delete">✕</button></span></div>`).join('') || '<div class="empty">No actions: the skill only plays its animation.</div>';
        el.querySelectorAll('.row[data-i]').forEach(r => r.addEventListener('click', (e) => {
            const b = e.target.closest('button[data-a]');
            if (b) return this.actionAction(+r.dataset.i, b.dataset.a);
            this.select({ kind: 'action', skill: this.sel.skill, index: +r.dataset.i });
        }));
    }

    // ---------------------------------------------------------------- skill timeline

    tlRows() {
        const s = this.skills[this.sel.skill];
        if (!s || (this.sel.kind !== 'skill' && this.sel.kind !== 'action')) return null;
        return s;
    }

    skillLength(s) {
        const m = this.h.modelState().model;
        const speed = s.speed || 1;
        const animLen = s.animation && m?.anims[s.animation] ? m.anims[s.animation].length : 0;
        return SkillPreview.schedule(s, this.doc.moveset, speed, animLen).dur / 20;
    }

    bindTimeline() {
        const canvas = $('#entTlCanvas'), wrap = $('#entTlTracks');
        this.tl = { canvas, wrap, ctx: canvas.getContext('2d') };
        new ResizeObserver(() => this.resizeTimeline()).observe(wrap);
        const ROW = 22, RULER = 24;
        let drag = null;
        canvas.addEventListener('pointerdown', (e) => {
            const s = this.tlRows();
            if (!s) return;
            const r = canvas.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
            canvas.setPointerCapture(e.pointerId);
            const i = Math.floor((y - RULER) / ROW) - 1; // row 0 = animation
            const acts = s.actions || [];
            if (y > RULER + ROW && i >= 0 && i < acts.length) {
                const ax = this.tx(acts[i].at || 0);
                if (Math.abs(x - ax) < 8) { drag = { i, x, at: acts[i].at || 0 }; this.select({ kind: 'action', skill: this.sel.skill, index: i }); return; }
                this.select({ kind: 'action', skill: this.sel.skill, index: i });
            }
            drag = { scrub: true };
            this.scrub(x);
        });
        canvas.addEventListener('pointermove', (e) => {
            if (!drag) return;
            const r = canvas.getBoundingClientRect(), x = e.clientX - r.left;
            if (drag.scrub) return this.scrub(x);
            const a = this.skills[this.sel.skill].actions[drag.i];
            a.at = Math.max(0, Math.round((drag.at + (x - drag.x) / this.pps()) * 20) / 20);
            drag.moved = true;
            this.drawTimeline();
        });
        canvas.addEventListener('pointerup', () => { if (drag?.moved) { this.changed(); this.renderProps(); } drag = null; });
    }

    resizeTimeline() {
        const t = this.tl, dpr = window.devicePixelRatio || 1;
        t.w = t.wrap.clientWidth;
        t.h = t.wrap.clientHeight;
        t.canvas.width = t.w * dpr;
        t.canvas.height = t.h * dpr;
        t.canvas.style.width = t.w + 'px';
        t.canvas.style.height = t.h + 'px';
        t.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.drawTimeline();
    }

    span() { const s = this.tlRows(); return Math.max(0.5, s ? this.skillLength(s) : 1); }
    pps() { return (this.tl.w - 44) / this.span(); }
    tx(t) { return 12 + t * this.pps(); }

    scrub(x) {
        // a skill can't be paused mid-way in game; scrubbing replays it up to that time
        const s = this.tlRows();
        if (!s) return;
        const t = Math.max(0, Math.min(this.span(), (x - 12) / this.pps()));
        this.playSkill(false);
        this.preview.advance(t);
        this.h.seekAnim(t * (s.speed || 1));
        this.playing = false;
        this.updatePlayIcon();
    }

    drawTimeline() {
        const t = this.tl;
        if (!t?.w || document.body.dataset.mode !== 'entity') return;
        const c = t.ctx, css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
        const ROW = 22, RULER = 24;
        c.clearRect(0, 0, t.w, t.h);
        const s = this.tlRows();
        const names = $('#entTlNames');
        if (!s) {
            names.innerHTML = '<div class="empty">Pick a skill</div>';
            $('#entLen').textContent = '0.00';
            return;
        }
        const acts = s.actions || [];
        names.innerHTML = `<div class="tl-name head">Skill</div><div class="tl-name" style="height:${ROW}px">animation <span class="muted">&nbsp;${esc(s.animation || '—')}</span></div>` +
            acts.map((a, i) => `<div class="tl-name${this.sel.kind === 'action' && this.sel.index === i ? ' sel' : ''}" style="height:${ROW}px">${esc(a.type)} <span class="muted">&nbsp;${esc(actionLabel(a))}</span></div>`).join('');
        const len = this.skillLength(s);
        $('#entLen').textContent = len.toFixed(2);
        c.font = '10px ' + css('--font');
        c.textBaseline = 'middle';
        // animation bar
        const animLen = (this.h.modelState().model?.anims[s.animation]?.length || 0) / (s.speed || 1);
        c.fillStyle = css('--line-2');
        if (animLen) c.fillRect(this.tx(0), RULER + 6, animLen * this.pps(), ROW - 12);
        acts.forEach((a, i) => {
            const y = RULER + ROW * (i + 1);
            if (this.sel.kind === 'action' && this.sel.index === i) { c.fillStyle = css('--accent-soft'); c.fillRect(0, y, t.w, ROW); }
            c.fillStyle = css('--line');
            c.fillRect(0, y + ROW - 1, t.w, 1);
            const color = ACT_COLOR[a.type] || '#888';
            const rep = a.repeat || {};
            for (let k = 0; k < Math.max(1, rep.count || 1); k++) {
                const x = this.tx(((a.at || 0) + k * Math.max(0.05, rep.interval ?? 0.25)) / (s.speed || 1));
                const r = k === 0 ? 6 : 4;
                c.fillStyle = color;
                c.beginPath(); c.moveTo(x, y + ROW / 2 - r); c.lineTo(x + r, y + ROW / 2); c.lineTo(x, y + ROW / 2 + r); c.lineTo(x - r, y + ROW / 2); c.fill();
            }
            if (a.type === 'telegraph' || (a.type === 'move' && a.duration)) {
                c.fillStyle = color + '55';
                c.fillRect(this.tx((a.at || 0) / (s.speed || 1)), y + 8, (a.duration ?? 0.3) / (s.speed || 1) * this.pps(), ROW - 16);
            }
        });
        // ruler + playhead
        c.fillStyle = css('--panel');
        c.fillRect(0, 0, t.w, RULER);
        c.fillStyle = css('--line');
        c.fillRect(0, RULER - 1, t.w, 1);
        const pps = this.pps(), steps = [0.05, 0.1, 0.25, 0.5, 1, 2, 5], step = steps.find(v => v * pps >= 48) || 5;
        for (let v = 0; v <= this.span() + 1e-6; v += step) {
            const x = this.tx(v);
            c.fillStyle = css('--line');
            c.fillRect(Math.round(x), RULER - 7, 1, 6);
            c.fillStyle = css('--text-3');
            c.fillText(v.toFixed(step < 1 ? 2 : 0) + 's', x + 3, 10);
        }
        if (this.dropX != null) {
            // where a dragged toolbox item would land
            c.fillStyle = css('--accent');
            c.fillRect(Math.round(this.dropX) - 1, RULER, 2, t.h - RULER);
            const tt = Math.max(0, (this.dropX - 12) / this.pps()) * (s.speed || 1);
            c.fillText((Math.round(tt * 20) / 20).toFixed(2) + 's', this.dropX + 5, RULER + 10);
        }
        const px = Math.round(this.tx(Math.min(this.preview.time, this.span()))) + 0.5;
        c.strokeStyle = css('--accent');
        c.beginPath(); c.moveTo(px, RULER - 2); c.lineTo(px, t.h); c.stroke();
        c.fillStyle = css('--accent');
        c.beginPath(); c.moveTo(px - 5, 3); c.lineTo(px + 5, 3); c.lineTo(px + 5, 13); c.lineTo(px, 19); c.lineTo(px - 5, 13); c.closePath(); c.fill();
    }

    // ---------------------------------------------------------------- playback

    playSkill(withAnim = true) {
        const s = this.tlRows();
        if (!s) return;
        this.sim.setContext({ library: this.h.fx.libraryDefs(), scale: 1, tags: new Set(), origin: () => ({ pos: [0, 0, 0], yaw: 0 }), target: () => this.dummyPos() });
        this.sim.clear();
        this.preview.start(this.sel.skill, s, this.doc.moveset);
        if (!withAnim) this.h.seekAnim(0);
        this.playing = true;
        this.updatePlayIcon();
    }

    togglePlay() {
        if (!this.tlRows()) return;
        if (!this.preview.running && !this.preview.busy) return this.playSkill();
        this.playing = !this.playing;
        this.h.pauseAnim(!this.playing);
        this.updatePlayIcon();
    }

    updatePlayIcon() {
        $('#entPlayIcon').innerHTML = this.playing ? '<path d="M4 2.5h3v11H4zM9 2.5h3v11H9z" fill="currentColor"/>' : '<path d="M4 2.5l9 5.5-9 5.5z" fill="currentColor"/>';
    }

    setActive(on) {
        this.active = on;
        this.dummy.visible = on;
        this.sim.group.visible = on;
        this.sim.particles.points.visible = on;
        this.preview.group.visible = on;
        if (!on) {
            this.preview.stop();
            this.sim.clear();
            this.vp.modelRoot.position.set(0, 0, 0);
            if (this.vp.hitbox) { this.vp.hitbox.position.x = 0; this.vp.hitbox.position.z = 0; }
            if (this.selGizmo) { this.vp.scene.remove(this.selGizmo); this.selGizmo = null; }
            return;
        }
        if (!this.framed) {
            // see the model and the target dummy in front of it
            this.framed = true;
            const y = this.h.modelState().yaw * Math.PI / 180, f = [-Math.sin(y), Math.cos(y)];
            this.vp.controls.target.set(f[0] * 2.5, 1, f[1] * 2.5);
            this.vp.camera.position.set(f[0] * 9 + f[1] * 9, 7, f[1] * 9 - f[0] * 9);
            this.vp.controls.update();
        }
        this.syncModel().then(() => {
            this.renderTitle();
            // start option (screenshots): --skill=<id> selects and plays it
            const want = new URLSearchParams(location.search).get('skill');
            if (want && this.skills[want] && !this.autoPicked) { this.autoPicked = true; this.select({ kind: 'skill', skill: want }); this.playSkill(); }
            // --export-to=<plugins/EXeaEntityCore>: export the open model once, without dialogs
            const exportTo = new URLSearchParams(location.search).get('exportTo');
            if (exportTo && !this.autoExported) { this.autoExported = true; this.exportToServer(exportTo); }
            // --send-live: send to the live server, then spawn it (scripts)
            if (new URLSearchParams(location.search).get('sendLive') && !this.autoSent) {
                this.autoSent = true;
                this.sendLive().then(() => this.h.live.cmd('spawn', { model: this.doc.id }));
            }
        });
    }

    frame(dt) {
        if (!this.active) return;
        this.dummy.position.set(...this.dummyPos());
        if (this.playing) {
            this.preview.advance(dt * this.speed);
            this.sim.advance(dt * this.speed);
            if (!this.preview.busy && !this.sim.active) {
                this.vp.modelRoot.position.set(0, 0, 0);
                if (this.repeat && this.tlRows()) this.playSkill();
                else { this.playing = false; this.updatePlayIcon(); this.h.stopPreview(); }
            }
        }
        $('#entTime').textContent = this.preview.time.toFixed(2);
        this.updateSelGizmo();
        this.drawTimeline();
    }

    /** The selected hit / telegraph / pull shape, drawn where it would land right now. */
    updateSelGizmo() {
        if (this.selGizmo) { this.vp.scene.remove(this.selGizmo); this.selGizmo = null; }
        const a = this.curAction();
        if (!a || !(a.shape || a.type === 'hit')) return;
        const f = this.preview.frameOf(a, null);
        this.selGizmo = shapeGizmo(f, parseShape(a.shape), 0x0d99ff, 0.16);
        this.vp.scene.add(this.selGizmo);
    }

    bind() {
        $('#entAddSkill').onclick = () => this.addSkill();
        $('#entAddPhase').onclick = () => this.addPhase();
        $('#entOpenMoveset').onclick = async () => {
            const f = await S.openDialog({ title: 'Open a moveset', filters: [{ name: 'Moveset', extensions: ['json'] }] });
            if (!f || !this.doc) return;
            try { this.doc.moveset = JSON.parse(await S.readText(f)); this.changed(true); this.select({ kind: 'ai' }); this.h.toast('Moveset loaded; Save writes it as ' + S.path.basename(this.doc.movesetFile)); }
            catch (e) { this.h.toast('Not valid JSON: ' + e.message, true); }
        };
        $('#entPlay').onclick = () => this.togglePlay();
        $('#entRepeat').onclick = (e) => { this.repeat = !this.repeat; e.currentTarget.classList.toggle('on', this.repeat); };
        $('#entSpeed').onchange = (e) => { this.speed = +e.target.value; this.h.setAnimSpeed(this.speed); };
        $('#entDist').oninput = (e) => { this.dist = +e.target.value; $('#entDistVal').textContent = this.dist; };
        $('#entToolFilter').oninput = () => this.renderToolbox();
        this.bindTimeline();
        this.bindDrops();
    }

    key(e) {
        const k = e.key.toLowerCase();
        if (e.ctrlKey) return false;
        if (k === ' ') { e.preventDefault(); this.togglePlay(); return true; }
        if (k === 'delete' && this.sel.kind === 'action') { this.actionAction(this.sel.index, 'del'); return true; }
        if (k === 'escape' && this.sel.kind === 'action') { this.select({ kind: 'skill', skill: this.sel.skill }); return true; }
        return false;
    }
}

function actionLabel(a) {
    switch (a.type) {
        case 'hit': return `${a.shape?.type || ''} ${a.damage ?? 0} dmg`;
        case 'telegraph': return `${a.shape?.type || ''} ${a.duration ?? ''}s`;
        case 'fx': return a.id || '';
        case 'sound': return a.sound || '';
        case 'projectile': return `${a.speed ?? 0.8} b/t${a.homing ? ' homing' : ''}`;
        case 'move': return a.mode || '';
        case 'summon': return `${a.count ?? 1}× ${a.model || ''}`;
        case 'animation': return a.name || '';
        case 'preset_skill': return a.skill || '';
        case 'event': return a.tag || '';
        default: return '';
    }
}
