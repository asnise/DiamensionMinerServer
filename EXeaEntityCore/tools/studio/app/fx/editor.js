// Effects mode: library of effect presets (bundled + your folder), layer list, schema form, live
// preview (sim.js) on the ground or on a bone of the open model, layer timeline, validation, save.
import * as THREE from 'three';
import { FxSim, effectDuration } from './sim.js';
import { SchemaForms } from './form.js';
import { PARTICLES } from './looks.js';
import { McTextures } from './textures.js';
import { History } from '../history.js';
import { renderPalette, LAYER_TEMPLATES, dragData, isToolDrag } from '../palette.js';
import { confirmBox, ask } from '../modal.js';

const S = window.studio;
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export const LAYER_TYPES = ['particle', 'debris', 'ground_wave', 'decal', 'telegraph', 'model', 'sound', 'preset'];
const TYPE_COLOR = { particle: '#0d99ff', debris: '#a0703c', ground_wave: '#14ae5c', decal: '#7a7a7a', telegraph: '#f24822', model: '#9747ff', sound: '#ffc021', preset: '#ff6fd0' };
const NEW_LAYER = {
    particle: { type: 'particle', particle: 'flame', count: 10, shape: { type: 'sphere', radius: 0.5 } },
    debris: { type: 'debris', anchor: { ground: true }, count: 10 },
    ground_wave: { type: 'ground_wave', radius: [1, 5], duration: 0.5 },
    decal: { type: 'decal', anchor: { ground: true }, pattern: 'crack', radius: 2 },
    telegraph: { type: 'telegraph', anchor: { ground: true }, shape: { type: 'circle', radius: 3 }, duration: 1 },
    model: { type: 'model', item_model: 'exea:fx/slash_arc', lifetime: 0.5 },
    sound: { type: 'sound', sound: 'entity.generic.explode' },
    preset: { type: 'preset', id: 'impact_spark' },
};
const SOUNDS = ['entity.generic.explode', 'entity.dragon_fireball.explode', 'entity.ender_dragon.flap', 'entity.ender_dragon.shoot', 'entity.evoker.cast_spell',
    'entity.evoker_fangs.attack', 'entity.illusioner.cast_spell', 'entity.ravager.roar', 'entity.ravager.stunned', 'entity.warden.roar', 'entity.warden.attack_impact',
    'entity.warden.emerge', 'entity.wither.spawn', 'entity.wither.break_block', 'entity.zombie.attack_iron_door', 'entity.iron_golem.attack', 'entity.iron_golem.hurt',
    'entity.breeze.wind_burst', 'entity.breeze.shoot', 'entity.player.attack.strong', 'entity.player.attack.knockback', 'entity.arrow.shoot', 'entity.arrow.hit',
    'entity.firework_rocket.blast', 'entity.firework_rocket.launch', 'entity.illusioner.mirror_move', 'entity.shulker.shoot', 'entity.elder_guardian.curse',
    'block.glass.break', 'block.stone.break', 'block.amethyst_block.chime', 'block.end_portal.spawn', 'block.portal.trigger', 'block.fire.extinguish',
    'block.conduit.activate', 'block.sculk_shrieker.shriek', 'item.trident.thunder', 'item.trident.riptide_1', 'item.totem.use', 'item.mace.smash_ground_heavy',
    'ui.toast.challenge_complete', 'entity.experience_orb.pickup', 'entity.player.levelup', 'entity.player.attack.sweep', 'entity.player.attack.crit', 'entity.warden.sonic_boom', 'entity.enderman.teleport',
    'entity.evoker.prepare_summon', 'entity.blaze.shoot', 'entity.wither.shoot', 'item.firecharge.use', 'block.anvil.land', 'block.beacon.activate',
    'block.note_block.bass', 'entity.generic.extinguish_fire', 'entity.lightning_bolt.thunder', 'entity.ender_dragon.growl', 'block.respawn_anchor.deplete'];
const ITEM_SUGGEST = ['stone', 'cobblestone', 'iron_ingot', 'gold_ingot', 'diamond', 'emerald', 'amethyst_shard', 'blaze_rod', 'ender_pearl', 'fire_charge',
    'snowball', 'arrow', 'trident', 'iron_sword', 'diamond_sword', 'netherite_sword', 'shield', 'bone', 'skeleton_skull', 'tnt', 'magma_cream', 'slime_ball'];
const BLOCK_SUGGEST = ['ground', 'stone', 'deepslate', 'dirt', 'grass_block', 'sand', 'gravel', 'netherrack', 'blackstone', 'basalt', 'obsidian', 'magma_block',
    'black_concrete', 'purple_concrete', 'red_concrete', 'red_stained_glass', 'ice', 'snow_block', 'end_stone'];

export class FxEditor {
    constructor({ viewport, modelState, toast, store, live, project }) {
        this.vp = viewport;
        this.live = live;
        this.project = project;
        this.hist = new History();
        this.modelState = modelState; // () => { model, pose, yaw }
        this.toast = toast;
        this.store = store;
        this.textures = new McTextures();
        this.sim = new FxSim(viewport.scene, this.textures);
        this.sim.onLog = (k, t) => this.log(k, t);
        this.library = new Map();     // id -> { def, source, file }
        this.doc = null;              // { id, def, source, file, dirty }
        this.sel = -1;
        this.playing = true;
        this.repeat = true;
        this.speed = 1;
        this.preview = { origin: 'ground', bone: null, scale: 1, target: true, tags: '' };
        this.targetMesh = this.makeTarget();
        // A solid floor just under the grid: blocks of a ground wave sit inside it until they pop up, as in game.
        this.floor = new THREE.Mesh(new THREE.PlaneGeometry(64, 64), new THREE.MeshLambertMaterial({ color: 0x2b2d31 }));
        this.floor.rotation.x = -Math.PI / 2;
        this.floor.position.y = -0.003;
        this.floor.visible = false;
        viewport.scene.add(this.floor);
        this.ready = this.init();
    }

    async init() {
        await this.textures.init();
        const paths = await S.paths();
        this.paths = paths;
        const docs = {};
        for (const n of ['common', 'effect']) docs[n + '.schema.json'] = await S.schema(n);
        this.forms = new SchemaForms(docs);
        // Full lists of blocks / items / particles from the player's own Minecraft jar (names only), else short built-in lists.
        const fromJar = async (dir) => {
            if (!this.textures.info) return [];
            const files = await S.mc.list(`assets/minecraft/${dir}/`);
            return files.map(f => f.slice(`assets/minecraft/${dir}/`.length)).filter(f => /^[a-z0-9_]+\.json$/.test(f)).map(f => f.slice(0, -5)).sort();
        };
        const [jarBlocks, jarItems, jarParticles] = await Promise.all([fromJar('blockstates'), fromJar('items'), fromJar('particles')]).catch(() => [[], [], []]);
        const particles = [...new Set([...Object.keys(PARTICLES), ...jarParticles])].sort();
        const blocks = jarBlocks.length ? ['ground', ...jarBlocks] : BLOCK_SUGGEST;
        const items = jarItems.length ? jarItems : ITEM_SUGGEST;
        this.forms.suggest = {
            particle: particles, sound: [...new Set(SOUNDS)].sort(), block: blocks, item: items, item_model: items,
            id: () => [...this.libraryDefs().keys()].filter(id => id !== this.doc?.id).sort(),
            when: () => (this.preview.tags || '').split(',').map(t => t.trim()).filter(Boolean),
        };
        this.suggestLists = { particles, sounds: [...new Set(SOUNDS)].sort(), blocks, items };
        $('#fxAddType').insertAdjacentHTML('beforeend', LAYER_TYPES.map(t => `<option value="${t}">${t}</option>`).join(''));
        this.bind();
        await this.loadLibrary();
        // Start options (also used for screenshots): --fx=<id> --layer=<n>
        const q = new URLSearchParams(location.search);
        const want = q.get('fx');
        const first = want && this.library.has(want) ? want : this.library.get('ground_slam') ? 'ground_slam' : [...this.library.keys()][0];
        if (first) await this.openFromLibrary(first);
        if (q.get('layer')) this.selectLayer(+q.get('layer'));
    }

    makeTarget() {
        const g = new THREE.Group();
        const m = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), new THREE.MeshBasicMaterial({ color: 0xf24822, wireframe: true, transparent: true, opacity: 0.6 }));
        m.position.y = 0.9;
        g.add(m);
        g.visible = false;
        this.vp.scene.add(g);
        return g;
    }

    // ---------------------------------------------------------------- library

    async loadLibrary() {
        this.library.clear();
        for (const e of await S.readJsonTree(this.paths.presets)) this.addLib(e, 'built-in');
        const folder = this.folder;
        if (folder) for (const e of await S.readJsonTree(folder)) this.addLib(e, 'mine');
        this.renderLibrary();
        this.renderPaletteView();
    }

    /** Where "my effects" live: the open project's effects/ folder, else the folder picked by hand. */
    get folder() {
        return this.project?.effectsDir || this.store.get('fxFolder', null);
    }

    addLib(e, source) {
        try {
            this.library.set(e.id, { def: JSON.parse(e.text), source, file: e.file });
        } catch {
            this.toast(`${e.file}: not valid JSON`, true);
        }
    }

    libraryDefs() {
        const m = new Map();
        for (const [id, v] of this.library) m.set(id, v.def);
        if (this.doc) m.set(this.doc.id, this.doc.def);
        return m;
    }

    renderLibrary() {
        const q = $('#fxFilter').value.trim().toLowerCase();
        const groups = { mine: [], 'built-in': [] };
        for (const [id, v] of this.library) {
            const tags = (v.def.tags || []).join(' ');
            if (q && !id.includes(q) && !tags.includes(q) && !String(v.def.name || '').toLowerCase().includes(q)) continue;
            groups[v.source].push([id, v]);
        }
        const folder = this.folder;
        const row = ([id, v]) => `<div class="row${this.doc?.id === id ? ' sel' : ''}" data-id="${esc(id)}" title="${esc(v.def.description || '')}">
            <span class="type-dot" style="background:${TYPE_COLOR[(v.def.layers || [])[0]?.type] || '#888'}"></span>
            <span class="name">${esc(v.def.name || id)}<span class="sub">${esc(id)}</span></span>
            ${v.def.loop ? '<span class="chip">loop</span>' : ''}</div>`;
        $('#fxLibrary').innerHTML =
            `<div class="group-title"><span>${this.project?.open ? 'Project effects' : 'My effects'}</span><span class="muted">${groups.mine.length}</span></div>` +
            (folder ? `<div class="folder-line" title="${esc(folder)}"><a href="#" data-open-folder>${esc(folder)}</a></div>` + (groups.mine.map(row).join('') || '<div class="empty">Nothing saved here yet. <b>File → Save</b> puts the effect in this folder.</div>')
                : '<div class="empty">Open a project (<b>File → Open Project</b>) or pick a folder (Folder…) to keep your effects.</div>') +
            `<div class="group-title"><span>Built in</span><span class="muted">${groups['built-in'].length}</span></div>` +
            groups['built-in'].map(row).join('');
        $('#fxLibrary').querySelectorAll('.row[data-id]').forEach(r => r.addEventListener('click', () => this.openFromLibrary(r.dataset.id)));
        $('#fxLibrary').querySelector('[data-open-folder]')?.addEventListener('click', (e) => { e.preventDefault(); S.showFolder(folder).catch(err => this.toast(err.message, true)); });
    }

    async confirmDiscard() {
        if (!this.doc?.dirty) return true;
        return confirmBox('Unsaved changes', `Discard the unsaved changes to "${this.doc.id}"?`, 'Discard', true);
    }

    async openFromLibrary(id) {
        if (this.doc?.id === id) return;
        if (!(await this.confirmDiscard())) return;
        const v = this.library.get(id);
        this.setDoc({ id, def: structuredClone(v.def), source: v.source, file: v.source === 'mine' ? v.file : null, dirty: false });
    }

    async openFile() {
        const f = await S.openDialog({ title: 'Open effect', filters: [{ name: 'Effect preset', extensions: ['json'] }] });
        if (!f || !(await this.confirmDiscard())) return;
        try {
            const def = JSON.parse(await S.readText(f));
            if (!Array.isArray(def.layers)) throw new Error('no "layers" array');
            this.setDoc({ id: def.id || S.path.basename(f, '.json').toLowerCase(), def, source: 'file', file: f, dirty: false });
        } catch (e) {
            this.toast(`Could not open: ${e.message}`, true);
        }
    }

    async newEffect() {
        if (!(await this.confirmDiscard())) return;
        let n = 1;
        while (this.library.has('my_effect_' + n)) n++;
        this.setDoc({ id: 'my_effect_' + n, def: { format: 1, name: 'My effect ' + n, layers: [structuredClone(NEW_LAYER.particle)] }, source: 'new', file: null, dirty: true });
    }

    async chooseFolder() {
        const d = await S.openDialog({ title: 'Folder of your effects (plugins/EXeaEntityCore/effects)', directory: true });
        if (!d) return;
        this.store.set('fxFolder', d);
        if (this.project?.open) this.toast('A project is open: its effects/ folder is used. Close the project to use this folder.', true);
        await this.loadLibrary();
        this.toast('Effects folder: ' + d);
    }

    setDoc(doc) {
        this.doc = doc;
        this.sel = -1;
        this.hist.reset(JSON.stringify(doc.def));
        this.renderAll();
        this.restart();
    }

    // ---------------------------------------------------------------- editing

    undo() { this.restoreDef(this.hist.undo()); }
    redo() { this.restoreDef(this.hist.redo()); }

    restoreDef(s) {
        if (!s || !this.doc) return;
        this.doc.def = JSON.parse(s);
        this.doc.dirty = true;
        if (this.sel >= this.doc.def.layers.length) this.sel = -1;
        this.renderLayers();
        this.renderProps();
        this.renderTitle();
        this.restart();
        this.validateSoon();
    }

    changed(rerenderLayers = false) {
        this.doc.dirty = true;
        this.hist.record(JSON.stringify(this.doc.def));
        if (rerenderLayers) this.renderLayers();
        this.renderTitle();
        this.restart();
        this.validateSoon();
    }

    addLayer(type) {
        if (!type || !this.doc) return;
        this.doc.def.layers.push(structuredClone(NEW_LAYER[type]));
        this.sel = this.doc.def.layers.length - 1;
        this.changed(true);
        this.renderProps();
        document.querySelector('#left .tabs.m-fx [data-tab="fxlayers"]').click();
    }

    layerAction(i, act) {
        const L = this.doc.def.layers;
        if (act === 'del') { L.splice(i, 1); if (this.sel >= L.length) this.sel = L.length - 1; }
        else if (act === 'dup') { L.splice(i + 1, 0, structuredClone(L[i])); this.sel = i + 1; }
        else if (act === 'up' && i > 0) { [L[i - 1], L[i]] = [L[i], L[i - 1]]; this.sel = i - 1; }
        else if (act === 'down' && i < L.length - 1) { [L[i + 1], L[i]] = [L[i], L[i + 1]]; this.sel = i + 1; }
        this.changed(true);
        this.renderProps();
    }

    selectLayer(i) {
        this.sel = i;
        this.renderLayers();
        this.renderProps();
        this.drawTimeline();
    }

    async save(as = false) {
        if (!this.doc) return;
        const def = this.cleaned();
        let file = this.doc.file;
        const folder = this.folder;
        if (!file && !as && folder) {
            // first save: straight into the project / effects folder, after naming it
            const name = await ask('Save effect', 'Name (id)', this.doc.id,
                `Saved as <id>.json in ${folder}. A built-in preset's id replaces that preset in game.`);
            if (!name) return;
            file = S.path.join(folder, name.trim().toLowerCase() + '.json');
        } else if (as || !file) {
            file = await S.saveDialog({ title: 'Save effect', defaultPath: folder ? S.path.join(folder, this.doc.id + '.json') : this.doc.id + '.json' });
            if (!file) return;
        }
        const id = S.path.basename(file, '.json').toLowerCase();
        if (!/^[a-z0-9_\-/]+$/.test(id)) { this.toast('File name must be lower case a-z 0-9 _ -', true); return; }
        def.id = id;
        await S.writeJson(file, JSON.stringify(def, null, 2) + '\n');
        this.doc.file = file;
        this.doc.id = id;
        this.doc.def.id = id;
        this.doc.source = 'mine';
        this.doc.dirty = false;
        await this.loadLibrary();
        this.renderTitle();
        this.toast(`Saved ${S.path.basename(file)} — run /exea reload in game`);
    }

    /** Live link: send this effect as it is now (saved or not), reload, play it at the player. */
    async playInGame() {
        if (!this.doc) return;
        const def = this.cleaned();
        // built-in presets that were not changed are already on the server
        const needsUpload = this.doc.dirty || this.doc.source !== 'built-in';
        if (needsUpload && !(await this.live.send({ id: this.doc.id, inlineEffects: [{ id: this.doc.id, def }] }))) return;
        await this.live.cmd('fx', { id: this.doc.id, scale: this.preview.scale });
    }

    /** The definition as written to disk: format first, empty optional fields dropped. */
    cleaned() {
        const d = this.doc.def;
        const out = { format: 1, id: this.doc.id };
        for (const k of ['name', 'description', 'tags', 'duration', 'loop']) if (d[k] !== undefined && d[k] !== '' && !(Array.isArray(d[k]) && !d[k].length)) out[k] = d[k];
        out.layers = d.layers;
        return out;
    }

    // ---------------------------------------------------------------- checks

    validateSoon() {
        clearTimeout(this.vt);
        this.vt = setTimeout(() => this.validate(), 250);
    }

    async validate() {
        if (!this.doc) return;
        const errs = await S.validate('effect', this.cleaned());
        const lib = this.libraryDefs();
        this.doc.def.layers.forEach((l, i) => {
            if (l.type === 'preset' && l.id && !lib.has(String(l.id).toLowerCase())) errs.push({ path: `/layers/${i}/id`, message: `effect "${l.id}" is not in the library (the plugin logs a warning)` });
            if (!LAYER_TYPES.includes(l.type)) errs.push({ path: `/layers/${i}/type`, message: `"${l.type}" is not built in: it needs a plugin that registers it`, level: 'warn' });
        });
        this.errors = errs;
        const badge = $('#fxCheckBadge');
        badge.hidden = errs.length === 0;
        badge.textContent = errs.length;
        badge.classList.toggle('err', errs.some(e => e.level !== 'warn'));
        $('#fxChecks').innerHTML = errs.length ? errs.map(e => `<div class="check ${e.level === 'warn' ? '' : 'err'}"><span class="dot"></span><div><div>${esc(e.message)}</div><div class="where">${esc(e.path || '/')}</div></div></div>`).join('')
            : '<div class="check ok"><span class="dot"></span><div>Valid: the plugin will load this effect.</div></div>';
        this.renderLayers();
    }

    // ---------------------------------------------------------------- rendering

    renderAll() {
        this.renderLibrary();
        this.renderLayers();
        this.renderProps();
        this.renderTitle();
        this.validate();
    }

    renderTitle() {
        if (!this.doc) return;
        const src = { 'built-in': 'built in · read only (Save as… to copy)', mine: 'my effects', file: 'file', new: 'not saved' }[this.doc.source];
        $('#fxDocName').textContent = `${this.doc.id}${this.doc.dirty ? ' •' : ''}  —  ${src}`;
        if (document.body.dataset.mode === 'effects') {
            $('#docTitle').innerHTML = `${esc(this.doc.def.name || this.doc.id)}${this.doc.dirty ? ' <span class="dirty" style="color:var(--accent)">•</span>' : ''} <span class="chip">effect</span>`;
        }
    }

    renderLayers() {
        if (!this.doc) { $('#fxLayers').innerHTML = ''; return; }
        const bad = new Set((this.errors || []).map(e => (e.path.match(/^\/layers\/(\d+)/) || [])[1]).filter(Boolean).map(Number));
        $('#fxLayers').innerHTML = this.doc.def.layers.map((l, i) => {
            const label = l.particle || l.sound || l.id || l.item_model || l.pattern || l.shape?.type || '';
            return `<div class="row${i === this.sel ? ' sel' : ''}" data-i="${i}">
                <span class="type-dot" style="background:${TYPE_COLOR[l.type] || '#888'}"></span>
                <span class="name">${esc(l.type)}<span class="sub">${esc(label)}</span>${bad.has(i) ? ' <span class="chip err">!</span>' : ''}</span>
                <span class="sub">${(l.start || 0).toFixed(2)}s</span>
                <span class="acts"><button data-a="up" title="Move up">↑</button><button data-a="down" title="Move down">↓</button><button data-a="dup" title="Duplicate">⧉</button><button data-a="del" title="Delete">✕</button></span>
            </div>`;
        }).join('') || '<div class="empty">No layers. Add one above.</div>';
        $('#fxLayers').querySelectorAll('.row[data-i]').forEach(r => r.addEventListener('click', (e) => {
            const i = +r.dataset.i;
            const a = e.target.closest('button[data-a]');
            if (a) this.layerAction(i, a.dataset.a);
            else this.selectLayer(i === this.sel ? -1 : i);
        }));
        this.drawTimeline();
    }

    renderProps() {
        const el = $('#fxProps');
        if (!this.doc) { el.innerHTML = '<div class="empty">Pick an effect in the library.</div>'; return; }
        const st = this.modelState();
        const bones = st.model ? st.model.bones.map(b => b.name) : [];
        const p = this.preview;
        el.innerHTML = `
            <div class="sec"><h3>Preview <span class="muted">not saved in the file</span></h3>
                <div class="form">
                    <div class="frow"><label>Play at</label><div class="ctl"><div class="seg-ctl" id="pvOrigin">
                        <button data-o="ground" class="${p.origin === 'ground' ? 'on' : ''}">Ground</button>
                        <button data-o="bone" class="${p.origin === 'bone' ? 'on' : ''}" ${bones.length ? '' : 'disabled title="Open a model in Model mode first"'}>Bone</button></div></div></div>
                    ${p.origin === 'bone' && bones.length ? `<div class="frow"><label>Bone</label><div class="ctl"><select id="pvBone">${bones.map(b => `<option${b === p.bone ? ' selected' : ''}>${esc(b)}</option>`).join('')}</select></div></div>` : ''}
                    <div class="frow"><label>Scale</label><div class="ctl"><input type="number" id="pvScale" step="0.1" min="0.1" value="${p.scale}"></div></div>
                    <div class="frow"><label title="A target 6 blocks in front, for beams and anchors at the target">Target</label><div class="ctl"><input type="checkbox" id="pvTarget" ${p.target ? 'checked' : ''}><span class="unit">6 blocks ahead</span></div></div>
                    <div class="frow"><label title="Particle and block textures come from your own Minecraft client jar (never copied into Studio)">Textures</label><div class="ctl">
                        <span class="unit" style="flex:1;align-self:center">${this.textures.info ? 'Minecraft ' + esc(this.textures.info.version) : 'colours only (no jar found)'}</span>
                        <button class="btn sm" id="pvJar">Jar…</button></div></div>
                    <div class="frow"><label title="Tags for layers with &quot;when&quot;">Tags</label><div class="ctl"><input type="text" id="pvTags" value="${esc(p.tags)}" placeholder="phase2, …"></div></div>
                </div>
            </div>
            <div class="sec" id="fxEditSec"></div>
            ${this.doc.source === 'built-in' ? '<div class="sec"><p class="hint">Built-in presets are part of the plugin jar. Edit freely, then <b>Save as…</b> into your folder: a file with the same id replaces the built-in one.</p></div>' : ''}
            <div class="sec"><div class="pane-tools" style="padding:0">
                <button class="btn sm primary" id="fxSave">${this.doc.file ? 'Save' : 'Save as…'}</button>
                ${this.doc.file ? '<button class="btn sm" id="fxSaveAs">Save as…</button>' : ''}
                <button class="btn sm" id="fxCopyJson" title="Copy the JSON">Copy JSON</button>
                <button class="btn sm" id="fxPlayLive" title="Upload this effect (saved or not) to the live server, reload, and play it in front of the player">▶ Play in game</button>
            </div></div>`;
        el.querySelectorAll('#pvOrigin button').forEach(b => b.addEventListener('click', () => {
            p.origin = b.dataset.o;
            if (p.origin === 'bone' && !p.bone) p.bone = bones.find(x => /hand|core|head/.test(x)) || bones[0];
            this.renderProps();
            this.restart();
        }));
        el.querySelector('#pvBone')?.addEventListener('change', (e) => { p.bone = e.target.value; this.restart(); });
        el.querySelector('#pvScale').addEventListener('change', (e) => { p.scale = Math.max(0.1, +e.target.value || 1); this.restart(); });
        el.querySelector('#pvTarget').addEventListener('change', (e) => { p.target = e.target.checked; this.restart(); });
        el.querySelector('#pvJar').addEventListener('click', async () => {
            const info = await this.textures.pickJar();
            if (info) { this.toast('Textures from Minecraft ' + info.version); this.renderProps(); this.restart(); }
        });
        el.querySelector('#pvTags').addEventListener('change', (e) => { p.tags = e.target.value; this.restart(); });
        el.querySelector('#fxSave').addEventListener('click', () => this.save(false));
        el.querySelector('#fxSaveAs')?.addEventListener('click', () => this.save(true));
        el.querySelector('#fxPlayLive').addEventListener('click', () => this.playInGame());
        el.querySelector('#fxCopyJson').addEventListener('click', async () => { await navigator.clipboard.writeText(JSON.stringify(this.cleaned(), null, 2)); this.toast('JSON copied'); });

        const sec = el.querySelector('#fxEditSec');
        const effSchema = this.forms.docs['effect.schema.json'];
        if (this.sel < 0) {
            sec.innerHTML = `<h3>Effect <span class="muted">${esc(this.doc.id)}</span></h3><div></div>`;
            const top = { ...effSchema, properties: Object.fromEntries(Object.entries(effSchema.properties).filter(([k]) => !['layers', 'format', '$schema', 'id', 'duration', 'loop'].includes(k))) };
            const def = this.doc.def;
            this.effectTiming(sec);
            if (Array.isArray(def.tags)) def.tags = def.tags.join(', ');
            this.forms.render(sec.lastElementChild, top, 'effect.schema.json', def, (k) => {
                if (k === 'tags' && typeof def.tags === 'string') { /* kept as text while editing */ }
                this.changed(false);
                this.renderLibrary();
            });
            // tags: the schema says array; edit as comma text and convert
            const tagIn = [...sec.querySelectorAll('.frow')].find(r => r.querySelector('label')?.textContent === 'tags')?.querySelector('input');
            if (tagIn) {
                tagIn.value = typeof def.tags === 'string' ? def.tags : '';
                tagIn.onchange = () => { const v = tagIn.value.split(',').map(s => s.trim()).filter(Boolean); if (v.length) def.tags = v; else delete def.tags; this.changed(false); };
            }
            if (typeof def.tags === 'string') def.tags = def.tags.split(',').map(s => s.trim()).filter(Boolean);
            sec.insertAdjacentHTML('beforeend', '<p class="hint" style="margin-top:10px">Pick a layer to edit it. On the timeline: drag a bar to move it, its right edge to change its duration, and the end line to set the effect length.</p>');
            return;
        }
        const layer = this.doc.def.layers[this.sel];
        sec.innerHTML = `<h3><span class="type-dot" style="background:${TYPE_COLOR[layer.type] || '#888'}"></span>${esc(layer.type)} <span class="muted">layer ${this.sel + 1}</span></h3><div></div>`;
        if (!LAYER_TYPES.includes(layer.type)) {
            sec.lastElementChild.innerHTML = `<p class="hint">A plugin-registered layer type: edit its JSON.</p><textarea id="rawLayer" style="width:100%;height:160px;background:var(--panel-2);border:0;border-radius:5px;font-family:var(--mono);color:inherit">${esc(JSON.stringify(layer, null, 2))}</textarea>`;
            sec.querySelector('#rawLayer').onchange = (e) => { try { this.doc.def.layers[this.sel] = JSON.parse(e.target.value); this.changed(true); } catch { this.toast('Not valid JSON', true); } };
            return;
        }
        this.layerTiming(sec, layer);
        this.forms.render(sec.lastElementChild, effSchema.$defs.layer, 'effect.schema.json', layer, (k) => {
            this.changed(k === 'type' || k === 'start' || k === 'particle' || k === 'sound' || k === 'id');
            if (k === 'type') this.renderProps();
        }, ['type', 'start', 'duration', 'interval']);
    }

    /** Effect length: Auto (end of the last layer) or a fixed number of seconds, and Loop. */
    effectTiming(sec) {
        const def = this.doc.def, auto = typeof def.duration !== 'number';
        const len = effectDuration(def, this.libraryDefs());
        const box = document.createElement('div');
        box.innerHTML = `<div class="timing">
                <div><label>Length</label><div class="seg-ctl" id="fxLenMode"><button data-m="auto" class="${auto ? 'on' : ''}" title="Ends when the last layer ends">Auto</button><button data-m="fixed" class="${auto ? '' : 'on'}" title="Cut (or hold) the effect at a set time">Fixed</button></div></div>
                <div><label>Seconds</label><input type="number" id="fxLen" step="0.05" min="0.05" value="${len.toFixed(2)}" ${auto ? 'disabled' : ''}></div>
                <div><label>Loop</label><label class="timing-check"><input type="checkbox" id="fxLoop" ${def.loop ? 'checked' : ''}> repeat</label></div>
            </div>`;
        sec.insertBefore(box, sec.lastElementChild);
        box.querySelectorAll('#fxLenMode button').forEach(b => b.onclick = () => {
            if (b.dataset.m === 'auto') delete def.duration; else def.duration = +len.toFixed(2);
            this.changed(true); this.renderProps(); this.restart();
        });
        box.querySelector('#fxLen').onchange = (e) => { const v = +e.target.value; if (v > 0) { def.duration = +v.toFixed(2); this.changed(true); this.restart(); } };
        box.querySelector('#fxLoop').onchange = (e) => { if (e.target.checked) def.loop = true; else delete def.loop; this.changed(false); this.restart(); };
    }

    /** Start / Duration / Interval of one layer. Duration 0 = one burst at the start. */
    layerTiming(sec, layer) {
        const box = document.createElement('div');
        const dur = layer.duration || 0;
        const note = dur > 0
            ? `Runs ${dur.toFixed(2)} s: about ${Math.max(1, Math.round(dur / (layer.interval || 0.05)))} spawns.`
            : 'Instant: one burst. Set a duration (or drag the right side of its mark on the timeline) to keep it going.';
        box.innerHTML = `<div class="timing">
                <div><label title="Seconds after the effect starts">Start (s)</label><input type="number" id="lyStart" step="0.05" min="0" value="${layer.start || 0}"></div>
                <div><label title="0 = one burst at the start. Above 0 = keeps spawning for this long">Duration (s)</label><input type="number" id="lyDur" step="0.05" min="0" value="${dur}"></div>
                <div><label title="While it runs: one spawn every … seconds (0.05 = every tick)">Every (s)</label><input type="number" id="lyInt" step="0.05" min="0.05" value="${layer.interval ?? 0.05}" ${dur > 0 ? '' : 'disabled'}></div>
            </div>
            <p class="hint" style="margin:-2px 0 8px">${note}</p>`;
        sec.insertBefore(box, sec.lastElementChild);
        const done = () => { this.changed(true); this.renderProps(); this.restart(); };
        const set = (k, v) => { if (v > 0) layer[k] = +v.toFixed(2); else delete layer[k]; done(); };
        box.querySelector('#lyStart').onchange = (e) => set('start', Math.max(0, +e.target.value || 0));
        box.querySelector('#lyDur').onchange = (e) => set('duration', Math.max(0, +e.target.value || 0));
        box.querySelector('#lyInt').onchange = (e) => {
            const v = Math.max(0.05, +e.target.value || 0.05);
            if (v === 0.05) delete layer.interval; else layer.interval = +v.toFixed(2);
            done();
        };
    }

    // ---------------------------------------------------------------- preview

    origin() {
        const p = this.preview;
        const st = this.modelState();
        if (p.origin === 'bone' && st.model && p.bone && st.pose[p.bone]) {
            const e = st.pose[p.bone].elements;
            return { pos: [e[12], e[13], e[14]], yaw: st.yaw };
        }
        return { pos: [0, 0, 0], yaw: st.yaw };
    }

    targetPos() {
        if (!this.preview.target) return null;
        const yaw = this.modelState().yaw * Math.PI / 180;
        return [-Math.sin(yaw) * 6, 0, Math.cos(yaw) * 6];
    }

    restart() {
        if (!this.doc) return;
        this.sim.load(structuredClone(this.cleaned()), {
            library: this.libraryDefs(), scale: this.preview.scale, tags: new Set(this.preview.tags.split(',').map(s => s.trim()).filter(Boolean)),
            origin: () => this.origin(), target: () => this.targetPos(),
        });
        $('#fxLen').textContent = this.sim.duration.toFixed(2);
        this.playing = true;
        this.updatePlayIcon();
        this.drawTimeline();
    }

    setActive(on) {
        this.active = on;
        this.sim.group.visible = on;
        this.sim.particles.points.visible = on;
        this.targetMesh.visible = on && this.preview.target;
        this.floor.visible = on;
        this.floor.material.color.set(document.documentElement.dataset.theme === 'light' ? 0xe4e4e4 : 0x2b2d31);
        if (on) {
            if (!this.framed && !this.modelState().model) {
                // Effects face +Z: look from the front-right, high enough to see a 6-block area and the target.
                this.framed = true;
                this.vp.controls.target.set(0, 0.5, 2.5);
                this.vp.camera.position.set(7, 7.5, 12);
                this.vp.controls.update();
            }
            this.renderProps();
            this.renderTitle();
            this.restart();
        }
    }

    frame(dt) {
        if (!this.active || !this.doc) return;
        const t = this.targetPos();
        this.targetMesh.visible = !!t;
        if (t) this.targetMesh.position.set(...t);
        if (this.playing) {
            // Replay once everything is gone (particles outlive the effect's own duration), or after 10 s more.
            const alive = this.sim.advance(dt * this.speed);
            if ((!alive && this.sim.time >= this.sim.duration) || this.sim.time > this.sim.duration + 10) {
                if (this.repeat) this.sim.restart();
                else { this.playing = false; this.updatePlayIcon(); }
            }
        }
        $('#fxTime').textContent = this.sim.time.toFixed(2);
        this.drawTimeline();
    }

    togglePlay() {
        if (!this.playing && this.sim.time >= this.sim.duration) this.sim.restart();
        this.playing = !this.playing;
        this.updatePlayIcon();
    }

    updatePlayIcon() {
        $('#fxPlayIcon').innerHTML = this.playing ? '<path d="M4 2.5h3v11H4zM9 2.5h3v11H9z" fill="currentColor"/>' : '<path d="M4 2.5l9 5.5-9 5.5z" fill="currentColor"/>';
    }

    log(kind, text) {
        if (!this.active) return;
        const el = document.createElement('div');
        el.className = 'ev';
        el.innerHTML = `<b>${esc(kind)}</b> ${esc(text)}`;
        $('#eventLog').appendChild(el);
        setTimeout(() => el.remove(), 1900);
        while ($('#eventLog').children.length > 6) $('#eventLog').firstChild.remove();
    }

    // ---------------------------------------------------------------- layer timeline

    bindTimeline() {
        const canvas = $('#fxTlCanvas'), wrap = $('#fxTlTracks');
        this.tl = { canvas, wrap, ctx: canvas.getContext('2d'), w: 0, h: 0 };
        new ResizeObserver(() => this.resizeTimeline()).observe(wrap);
        let drag = null;
        const ROW = 22, RULER = 24;
        canvas.addEventListener('pointerdown', (e) => {
            if (!this.doc) return;
            const r = canvas.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
            canvas.setPointerCapture(e.pointerId);
            // the effect's end line (any row): dragging it sets a fixed length
            const xe = this.tx(this.sim.duration || 0);
            if (Math.abs(x - xe) < 5 && y > RULER) { drag = { mode: 'end', x, len: this.sim.duration || 0 }; return; }
            const i = Math.floor((y - RULER) / ROW);
            const L = this.doc.def.layers;
            if (y > RULER && i >= 0 && i < L.length) {
                const l = L[i], x0 = this.tx(l.start || 0), x1 = this.tx((l.start || 0) + (l.duration || 0));
                if (x >= x0 - 6 && x <= Math.max(x1, x0 + 10) + 4) {
                    // right edge of a bar (or right side of an instant diamond) stretches the duration
                    const dur = l.duration || 0;
                    const edge = dur > 0 ? Math.abs(x - x1) < 6 : x > x0 + 2;
                    drag = { i, mode: edge ? 'dur' : 'move', x, start: l.start || 0, dur };
                    if (i !== this.sel) this.selectLayer(i);
                    return;
                }
                this.selectLayer(i);
            }
            drag = { mode: 'scrub' };
            this.scrub(x);
        });
        canvas.addEventListener('pointermove', (e) => {
            if (!drag) { canvas.style.cursor = this.cursorAt(e); return; }
            const r = canvas.getBoundingClientRect(), x = e.clientX - r.left;
            if (drag.mode === 'scrub') return this.scrub(x);
            const dt = Math.round(((x - drag.x) / this.pps()) * 20) / 20;
            if (drag.mode === 'end') {
                this.doc.def.duration = Math.max(0.05, +(drag.len + dt).toFixed(2));
                this.drawTimeline();
                drag.moved = true;
                return;
            }
            const l = this.doc.def.layers[drag.i];
            if (drag.mode === 'move') { const s = Math.max(0, +(drag.start + dt).toFixed(2)); if (s) l.start = s; else delete l.start; }
            else { const d = +(drag.dur + dt).toFixed(2); if (d >= 0.05) l.duration = d; else delete l.duration; }
            this.drawTimeline();
            drag.moved = true;
        });
        canvas.addEventListener('pointerup', () => {
            if (drag?.moved) { this.changed(true); this.renderProps(); this.restart(); }
            drag = null;
        });
    }

    cursorAt(e) {
        if (!this.doc) return '';
        const r = this.tl.canvas.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
        if (y <= 24) return '';
        if (Math.abs(x - this.tx(this.sim.duration || 0)) < 5) return 'ew-resize';
        const l = this.doc.def.layers[Math.floor((y - 24) / 22)];
        if (!l) return '';
        const x0 = this.tx(l.start || 0), dur = l.duration || 0, x1 = this.tx((l.start || 0) + dur);
        if (dur > 0 ? Math.abs(x - x1) < 6 : (x > x0 + 2 && x < x0 + 10)) return 'ew-resize';
        if (x >= x0 - 6 && x <= Math.max(x1, x0 + 6)) return 'grab';
        return '';
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

    span() { return Math.max(0.5, this.sim.duration || 1); }
    pps() { return (this.tl.w - 44) / this.span(); }
    tx(t) { return 12 + t * this.pps(); }

    scrub(x) {
        const t = Math.max(0, Math.min(this.span(), (x - 12) / this.pps()));
        this.playing = false;
        this.updatePlayIcon();
        this.sim.seek(t);
    }

    drawTimeline() {
        const t = this.tl;
        if (!t || !t.w || !this.active) return;
        const c = t.ctx, css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
        const ROW = 22, RULER = 24;
        c.clearRect(0, 0, t.w, t.h);
        const names = $('#fxTlNames');
        const L = this.doc?.def.layers || [];
        names.innerHTML = '<div class="tl-name head">Layer</div>' + L.map((l, i) => `<div class="tl-name${i === this.sel ? ' sel' : ''}" style="height:${ROW}px" data-i="${i}">${esc(l.type)} <span class="muted">&nbsp;${esc(l.particle || l.sound || l.id || l.pattern || '')}</span></div>`).join('');
        names.querySelectorAll('[data-i]').forEach(n => n.onclick = () => this.selectLayer(+n.dataset.i));
        c.font = '10px ' + css('--font');
        c.textBaseline = 'middle';
        L.forEach((l, i) => {
            const y = RULER + i * ROW;
            if (i === this.sel) { c.fillStyle = css('--accent-soft'); c.fillRect(0, y, t.w, ROW); }
            c.fillStyle = css('--line');
            c.fillRect(0, y + ROW - 1, t.w, 1);
            const x0 = this.tx(l.start || 0), dur = l.duration || 0;
            const color = TYPE_COLOR[l.type] || '#888';
            // lifetime tail of pieces that outlive the layer
            const extra = l.type === 'debris' ? (Array.isArray(l.lifetime) ? l.lifetime[1] : l.lifetime ?? 1.5) : l.type === 'decal' ? (l.lifetime ?? 4) : l.type === 'model' ? (l.lifetime ?? 0.5) : 0;
            if (extra) { c.fillStyle = color + '40'; c.fillRect(x0, y + 7, extra * this.pps() + dur * this.pps(), ROW - 14); }
            c.fillStyle = color;
            if (dur > 0) { roundRect(c, x0, y + 4, Math.max(4, dur * this.pps()), ROW - 8, 4); c.fill(); }
            else { c.beginPath(); c.moveTo(x0, y + 4); c.lineTo(x0 + 6, y + ROW / 2); c.lineTo(x0, y + ROW - 4); c.lineTo(x0 - 6, y + ROW / 2); c.fill(); }
        });
        // ruler
        c.fillStyle = css('--panel');
        c.fillRect(0, 0, t.w, RULER);
        c.fillStyle = css('--line');
        c.fillRect(0, RULER - 1, t.w, 1);
        const pps = this.pps(), steps = [0.05, 0.1, 0.25, 0.5, 1, 2, 5], step = steps.find(s => s * pps >= 48) || 5;
        for (let s = 0; s <= this.span() + 1e-6; s += step) {
            const x = this.tx(s);
            c.fillStyle = css('--line');
            c.fillRect(Math.round(x), RULER - 7, 1, 6);
            c.fillStyle = css('--text-3');
            c.fillText(s.toFixed(step < 1 ? 2 : 0) + 's', x + 3, 10);
        }
        const end = this.tx(this.sim.duration);
        c.fillStyle = 'rgba(0,0,0,0.18)';
        if (end < t.w) c.fillRect(end, RULER, t.w - end, t.h - RULER);
        // effect end: dashed when Auto, solid accent when a fixed length is set (drag it)
        const fixed = typeof this.doc?.def.duration === 'number';
        c.strokeStyle = fixed ? css('--accent') : css('--text-3');
        c.setLineDash(fixed ? [] : [3, 3]);
        c.beginPath(); c.moveTo(Math.round(end) + 0.5, RULER); c.lineTo(Math.round(end) + 0.5, t.h); c.stroke();
        c.setLineDash([]);
        c.fillStyle = c.strokeStyle;
        c.fillText(`end ${this.sim.duration.toFixed(2)}s`, Math.min(end + 4, t.w - 60), t.h - 8);
        if (this.dropX != null) {
            // where a dragged toolbox item would start
            c.fillStyle = css('--accent');
            c.fillRect(Math.round(this.dropX) - 1, RULER, 2, t.h - RULER);
            c.fillText((Math.round(Math.max(0, (this.dropX - 12) / this.pps()) * 20) / 20).toFixed(2) + 's', this.dropX + 5, RULER + 10);
        }
        const px = Math.round(this.tx(Math.min(this.sim.time, this.span()))) + 0.5;
        c.strokeStyle = css('--accent');
        c.beginPath(); c.moveTo(px, RULER - 2); c.lineTo(px, t.h); c.stroke();
        c.fillStyle = css('--accent');
        c.beginPath(); c.moveTo(px - 5, 3); c.lineTo(px + 5, 3); c.lineTo(px + 5, 13); c.lineTo(px, 19); c.lineTo(px - 5, 13); c.closePath(); c.fill();
    }

    // ---------------------------------------------------------------- wiring

    // ---------------------------------------------------------------- toolbox (drag onto the timeline)

    renderPaletteView() {
        const el = $('#fxToolbox');
        if (!el) return;
        const fxIds = [...this.library.keys()].filter(id => id !== this.doc?.id).sort();
        const sections = [
            { title: 'Ready-made layers', items: LAYER_TEMPLATES.map(t => ({ label: t.label, sub: t.layer.type, color: TYPE_COLOR[t.layer.type], data: { kind: 'layer', layer: t.layer } })) },
            { title: 'Layer types', items: LAYER_TYPES.map(t => ({ label: t, color: TYPE_COLOR[t], data: { kind: 'layer', layer: NEW_LAYER[t] } })) },
            { title: 'Effects (as a nested preset)', items: fxIds.map(id => ({ label: id, sub: this.library.get(id)?.def?.description || '', color: TYPE_COLOR.preset, data: { kind: 'effect', id } })) },
        ];
        renderPalette(el, sections, (d) => this.dropLayer(d, this.sim.time || 0), $('#fxToolFilter')?.value || '');
    }

    /** Adds a toolbox item as a layer starting at {@code start} seconds. */
    dropLayer(d, start) {
        if (!d || !this.doc) return;
        const layer = d.kind === 'effect' ? { type: 'preset', id: d.id } : structuredClone(d.layer);
        const s = Math.max(0, Math.round(start * 20) / 20);
        if (s > 0) layer.start = +((layer.start || 0) + s).toFixed(2);
        this.doc.def.layers.push(layer);
        this.sel = this.doc.def.layers.length - 1;
        this.changed(true);
        this.renderProps();
        this.toast(`Layer ${layer.type} added at ${s.toFixed(2)} s`);
    }

    bindDrops() {
        const wrap = $('#fxTlTracks');
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
            const x = e.clientX - this.tl.canvas.getBoundingClientRect().left;
            this.dropLayer(d, Math.max(0, (x - 12) / this.pps()));
        });
        const vp = $('#viewport');
        vp.addEventListener('dragover', (e) => { if (this.active && isToolDrag(e)) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } });
        vp.addEventListener('drop', (e) => {
            if (!this.active) return;
            const d = dragData(e);
            if (!d) return;
            e.preventDefault();
            e.stopPropagation();
            this.dropLayer(d, this.sim.time || 0);
        });
    }

    bind() {
        $('#fxToolFilter').oninput = () => this.renderPaletteView();
        this.bindDrops();
        $('#fxNew').onclick = () => this.newEffect();
        $('#fxOpenFile').onclick = () => this.openFile();
        $('#fxFolder').onclick = () => this.chooseFolder();
        $('#fxFilter').oninput = () => this.renderLibrary();
        $('#fxAddType').onchange = (e) => { this.addLayer(e.target.value); e.target.value = ''; };
        $('#fxPlay').onclick = () => this.togglePlay();
        $('#fxRestart').onclick = () => { this.sim.restart(); this.playing = true; this.updatePlayIcon(); };
        $('#fxRepeat').onclick = (e) => { this.repeat = !this.repeat; e.currentTarget.classList.toggle('on', this.repeat); };
        $('#fxSpeed').onchange = (e) => { this.speed = +e.target.value; };
        this.bindTimeline();
    }

    /** Keys while in Effects mode; true when handled. */
    key(e) {
        const k = e.key.toLowerCase();

        if (e.ctrlKey) return false;
        if (k === ' ') { e.preventDefault(); this.togglePlay(); return true; }
        if (k === 'home') { this.sim.restart(); this.playing = true; this.updatePlayIcon(); return true; }
        if ((k === 'delete') && this.sel >= 0) { this.layerAction(this.sel, 'del'); return true; }
        if (k === 'escape') { this.selectLayer(-1); return true; }
        return false;
    }
}

function roundRect(c, x, y, w, h, r) {
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
}
