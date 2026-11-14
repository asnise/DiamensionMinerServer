// EXea Studio renderer: wires the panels, the viewport and the timeline around one open model.
import { loadModel, round, MESH_LIMIT_PX } from './core/model.js';
import { computePose, Clock } from './core/pose.js';
import { Viewport } from './viewport.js';
import { Timeline } from './timeline.js';
import { FxEditor, LAYER_TYPES } from './fx/editor.js';
import { EntityEditor, ACTION_TYPES } from './entity/editor.js';
import { Live } from './live.js';
import { initResizers } from './resize.js';
import { MenuBar } from './menu.js';
import { Project } from './project.js';
import { ask, info } from './modal.js';

const S = window.studio;
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const store = {
    get(k, d) { try { const v = localStorage.getItem('exea.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('exea.' + k, JSON.stringify(v)); } catch { /* storage off */ } },
};

const state = {
    model: null,
    anim: null,
    clock: new Clock(),
    yaw: 0,
    selected: null,
    hidden: new Set(),
    collapsed: new Set(),
    pose: {},
    file: null,
};

const viewport = new Viewport($('#viewport'));
const timeline = new Timeline({
    canvas: $('#tlCanvas'), wrap: $('#tlTracks'), names: $('#tlNames'),
    onScrub: (t) => { state.clock.time = t; state.clock.playing = false; updatePlayIcon(); },
    onSelectBone: (b) => select(b),
});
viewport.onPick = (b) => { if (document.body.dataset.mode !== 'effects') select(b); };

// ---------------------------------------------------------------- open / reload

async function open(file, keep = false) {
    let model;
    try {
        model = await loadModel(file);
    } catch (e) {
        toast(`Could not open ${S.path.basename(file)}: ${e.message}`, true);
        return;
    }
    const prev = keep ? { anim: state.anim?.name, time: state.clock.time, playing: state.clock.playing, sel: state.selected } : null;
    state.model = model;
    state.file = file;
    state.hidden.clear();
    await viewport.setModel(model);
    $('#dropHint').classList.add('hidden');
    $('#btnReload').disabled = false;
    if (document.body.dataset.mode !== 'effects') $('#docTitle').innerHTML = `${esc(model.name)} <span class="chip">${model.source === 'bbmodel' ? '.bbmodel' : 'exported'}</span>`;
    renderTitleBar();
    renderProjectPane();

    const names = Object.keys(model.anims);
    const pick = prev?.anim && model.anims[prev.anim] ? prev.anim : (names.includes('idle') ? 'idle' : names[0]);
    setAnim(pick, prev ? prev.time : 0, prev ? prev.playing : true);
    state.selected = prev?.sel && model.byName[prev.sel] ? prev.sel : null;
    renderLayers();
    renderAnims();
    renderChecks();
    select(state.selected);
    updatePose();
    if (!keep) viewport.fit();
    S.watch(model.files);
    if (document.body.dataset.mode === 'entity') ent.syncModel();
    remember(file);
    renderHud();
}

S.onFileChanged(async () => {
    if (!state.file) return;
    await open(state.file, true);
    toast('Reloaded from disk');
});

async function openDialog() {
    const f = await S.openDialog();
    if (f) open(f);
}

function remember(file) {
    const list = [file, ...store.get('recent', []).filter(f => f !== file)].slice(0, 6);
    store.set('recent', list);
    renderRecent();
}

function renderRecent() {
    const list = store.get('recent', []);
    $('#recent').innerHTML = list.length ? '<h4>Recent</h4>' + list.map(f => `<div class="row" data-file="${esc(f)}" title="${esc(f)}"><span class="name">${esc(f)}</span></div>`).join('') : '';
    $('#recent').querySelectorAll('.row').forEach(r => r.addEventListener('click', () => open(r.dataset.file)));
}

// ---------------------------------------------------------------- animation

function setAnim(name, time = 0, play = true) {
    state.anim = name ? state.model.anims[name] : null;
    state.clock.time = Math.min(time, state.anim?.length ?? 0);
    state.clock.playing = !!state.anim && play;
    timeline.setAnim(state.anim, state.model);
    timeline.setSelected(state.selected);
    $('#tlLen').textContent = (state.anim?.length ?? 0).toFixed(2);
    $('#tlAnimName').textContent = state.anim ? `${state.anim.name} · ${state.anim.loop}` : '';
    document.querySelectorAll('#animList .row').forEach(r => r.classList.toggle('sel', r.dataset.anim === name));
    updatePlayIcon();
    if (!state.selected) renderProps();
}

function togglePlay() {
    if (!state.anim) return;
    if (!state.clock.playing && state.clock.time >= state.anim.length - 1e-6) state.clock.time = 0;
    state.clock.playing = !state.clock.playing;
    updatePlayIcon();
}

let shownPlaying = null;
function updatePlayIcon() {
    if (shownPlaying === state.clock.playing) return;
    shownPlaying = state.clock.playing;
    $('#playIcon').innerHTML = state.clock.playing
        ? '<path d="M4 2.5h3v11H4zM9 2.5h3v11H9z" fill="currentColor"/>'
        : '<path d="M4 2.5l9 5.5-9 5.5z" fill="currentColor"/>';
}

function updatePose() {
    if (!state.model) return;
    computePose(state.model, state.anim, state.clock.time, state.yaw, state.pose);
    viewport.applyPose(state.pose, state.yaw);
}

/** Shows an animation event the way the plugin reads it (TimelineEvent). */
function fireEvent(ev) {
    let kind = ev.type, label = '', bone = ev.bone;
    if (ev.type === 'sound') label = ev.sound;
    else if (ev.type === 'particle') label = ev.particle;
    let hit = null;
    if (ev.type === 'script') {
        for (const line of ev.script.split(/[\r\n;]+/).map(s => s.trim()).filter(Boolean)) {
            const words = line.split(/\s+/);
            const args = {};
            for (const w of words.slice(1)) { const i = w.indexOf('='); if (i > 0) args[w.slice(0, i).toLowerCase()] = w.slice(i + 1); }
            const verb = words[0].toLowerCase();
            const b = args.bone ? args.bone.toLowerCase() : null;
            if (verb === 'hit') {
                hit = { kind: 'hit', radius: +args.radius || 3, cone: +args.cone || 360 };
                viewport.flashEvent(hit, b ? state.pose[b] : null, state.yaw);
            } else {
                viewport.flashEvent({ kind: verb === 'sound' ? 'sound' : verb === 'particle' ? 'particle' : 'custom' }, b ? state.pose[b] : null, state.yaw);
            }
            logEvent(verb, line.slice(words[0].length).trim());
        }
        return;
    }
    viewport.flashEvent({ kind }, bone ? state.pose[bone] : null, state.yaw);
    logEvent(kind, label + (bone ? `  @${bone}` : ''));
}

function logEvent(verb, rest) {
    const el = document.createElement('div');
    el.className = 'ev';
    el.innerHTML = `<b>${esc(verb)}</b> ${esc(rest)}`;
    $('#eventLog').appendChild(el);
    setTimeout(() => el.remove(), 1900);
    while ($('#eventLog').children.length > 6) $('#eventLog').firstChild.remove();
}

// ---------------------------------------------------------------- left panel

const ICON_CUBE = '<svg class="ico" viewBox="0 0 16 16"><path d="M8 1.8l5.5 3v6.4L8 14.2l-5.5-3V4.8z M2.5 4.8L8 7.8l5.5-3 M8 7.8v6.4" fill="none" stroke="currentColor"/></svg>';
const ICON_GROUP = '<svg class="ico" viewBox="0 0 16 16"><path d="M3 3h10v10H3z" fill="none" stroke="currentColor" stroke-dasharray="2 1.5"/></svg>';
const ICON_TWIST = '<svg viewBox="0 0 8 8"><path d="M2 1l4 3-4 3z" fill="currentColor"/></svg>';
const ICON_EYE = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z" fill="none" stroke="currentColor"/><circle cx="8" cy="8" r="2" fill="currentColor"/></svg>';
const ICON_EYE_OFF = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M2 14L14 2M3.5 10.5C2.3 9.3 1.5 8 1.5 8S4 3.5 8 3.5c1 0 1.9.3 2.7.7M12.6 5.6C13.8 6.8 14.5 8 14.5 8S12 12.5 8 12.5c-1 0-1.9-.3-2.7-.7" fill="none" stroke="currentColor"/></svg>';
const ICON_LOOP = '<svg class="ico" viewBox="0 0 16 16"><path d="M3 9V7a3 3 0 0 1 3-3h7M11 2l2 2-2 2M13 7v2a3 3 0 0 1-3 3H3M5 14l-2-2 2-2" fill="none" stroke="currentColor"/></svg>';
const ICON_ONCE = '<svg class="ico" viewBox="0 0 16 16"><path d="M3 8h9M9 5l3 3-3 3" fill="none" stroke="currentColor"/></svg>';
const ICON_HOLD = '<svg class="ico" viewBox="0 0 16 16"><path d="M3 8h7M7 5l3 3-3 3M12.5 4v8" fill="none" stroke="currentColor"/></svg>';

function renderLayers() {
    const m = state.model;
    const filter = $('#layerFilter').value.trim().toLowerCase();
    const out = [];
    const walk = (bones, depth) => {
        for (const b of bones) {
            const match = !filter || b.name.includes(filter);
            const kids = filter ? b.children : (state.collapsed.has(b.name) ? [] : b.children);
            if (match) {
                const hidden = state.hidden.has(b.name) || !b.visible;
                out.push(`<div class="row${b.children.length && !state.collapsed.has(b.name) ? ' open' : ''}${b.name === state.selected ? ' sel' : ''}${hidden ? ' dim' : ''}" data-bone="${esc(b.name)}" style="padding-left:${8 + depth * 12}px">
                    <span class="twist">${b.children.length && !filter ? ICON_TWIST : ''}</span>
                    ${b.elements.length ? ICON_CUBE : ICON_GROUP}
                    <span class="name">${esc(b.displayName || b.name)}</span>
                    ${b.meshScale > 1 ? '<span class="chip warn" title="Mesh scaled down">×' + b.meshScale + '</span>' : ''}
                    <span class="tag">${b.elements.length || ''}</span>
                    <span class="eye${hidden ? ' off' : ''}" title="Hide in preview">${hidden ? ICON_EYE_OFF : ICON_EYE}</span>
                </div>`);
            }
            walk(kids, filter ? depth : depth + 1);
        }
    };
    if (m) walk(m.roots, 0);
    $('#layerTree').innerHTML = out.join('') || '<div class="empty">No bones</div>';
    $('#layerTree').querySelectorAll('.row').forEach(row => {
        const name = row.dataset.bone;
        row.addEventListener('click', (e) => {
            if (e.target.closest('.twist')) {
                state.collapsed.has(name) ? state.collapsed.delete(name) : state.collapsed.add(name);
                renderLayers();
                return;
            }
            if (e.target.closest('.eye')) {
                const hide = !state.hidden.has(name);
                // Hiding a bone hides its children too, like Figma frames.
                const all = [];
                (function walk(b) { all.push(b.name); b.children.forEach(walk); })(state.model.byName[name]);
                for (const n of all) { hide ? state.hidden.add(n) : state.hidden.delete(n); viewport.setBoneVisible(n, !hide && state.model.byName[n].visible); }
                renderLayers();
                return;
            }
            select(name === state.selected ? null : name);
        });
    });
}

function renderAnims() {
    const anims = Object.values(state.model?.anims || {});
    $('#animList').innerHTML = anims.length ? anims.map(a => `
        <div class="row${state.anim?.name === a.name ? ' sel' : ''}" data-anim="${esc(a.name)}" title="${a.events.length} event(s)">
            ${a.loop === 'loop' ? ICON_LOOP : a.loop === 'hold' ? ICON_HOLD : ICON_ONCE}
            <span class="name">${esc(a.name)}</span>
            ${a.events.length ? `<span class="chip" style="color:var(--kf-event)">${a.events.length} fx</span>` : ''}
            <span class="len">${a.length.toFixed(2)}s</span>
        </div>`).join('') : '<div class="empty">No animations</div>';
    $('#animList').querySelectorAll('.row').forEach(r => r.addEventListener('click', () => setAnim(r.dataset.anim)));
}

// ---------------------------------------------------------------- right panel

function select(name) {
    state.selected = name && state.model?.byName[name] ? name : null;
    viewport.select(state.selected);
    timeline.setSelected(state.selected);
    document.querySelectorAll('#layerTree .row').forEach(r => r.classList.toggle('sel', r.dataset.bone === state.selected));
    const row = document.querySelector(`#layerTree .row[data-bone="${CSS.escape(state.selected || '')}"]`);
    row?.scrollIntoView({ block: 'nearest' });
    renderProps();
}

const vec = (v, d = 2) => `<div class="grid3">${['X', 'Y', 'Z'].map((a, i) => `<div class="num"><i>${a}</i>${round(v[i], d)}</div>`).join('')}</div>`;

function renderProps() {
    const m = state.model;
    if (!m) { $('#props').innerHTML = '<div class="empty">Open a model to inspect it.</div>'; return; }
    if (state.selected) return renderBoneProps(m.byName[state.selected]);

    const st = m.stats;
    const budget = st.meshBones > 50 ? 'err' : st.meshBones > 30 ? 'warn' : '';
    const meta = m.meta || {};
    const hb = viewport.hitbox?.userData || {};
    const a = state.anim;
    $('#props').innerHTML = `
        <div class="sec"><h3>Model <span class="muted">${esc(m.id)}</span></h3>
            <div class="kv">
                <span>Source</span><span title="${esc(m.file)}">${m.source === 'bbmodel' ? 'Blockbench file' : 'Exported files'}</span>
                <span>Texture</span><span>${m.texture ? `${m.texture.width}×${m.texture.height}` : '<span class="chip err">none</span>'}</span>
                <span>Bones</span><span>${st.bones} <span class="muted">(${st.meshBones} with cubes)</span></span>
                <span>Cubes</span><span>${st.cubes}</span>
                <span>Animations</span><span>${st.anims}</span>
            </div>
            <div style="margin-top:12px" title="Each bone with cubes is one display entity. About 30 for a mob, 50 for a boss.">
                <div class="kv"><span>Entity budget</span><span>${st.meshBones} / ${st.meshBones > 30 ? 50 : 30}</span></div>
                <div class="meter ${budget}"><div style="width:${Math.min(100, st.meshBones / (st.meshBones > 30 ? 50 : 30) * 100)}%"></div></div>
            </div>
        </div>
        <div class="sec"><h3>Driver <span class="muted">${m.meta ? esc(S.path.basename(m.files.find(f => /_meta\.json$/i.test(f)) || '')) : 'no meta file'}</span></h3>
            <div class="kv">
                <span>Name</span><span>${esc(meta.display_name || m.id)}</span>
                <span>Mob</span><span>${esc(hb.driver || 'husk')}</span>
                <span>Hitbox</span><span>${round(hb.w || 0)} × ${round(hb.h || 0)} blocks</span>
                <span>Health</span><span>${meta.health ?? 20}</span>
                <span>Damage</span><span>${meta.attack_damage ?? 3}</span>
                <span>Speed</span><span>${meta.movement_speed ?? 0.23}</span>
                <span>Glow bones</span><span>${(meta.glow_bones || []).length ? esc(meta.glow_bones.join(', ')) : '—'}</span>
            </div>
        </div>
        ${a ? `<div class="sec"><h3>Animation <span class="muted">${esc(a.name)}</span></h3>
            <div class="kv">
                <span>Length</span><span>${a.length.toFixed(2)} s · ${Math.round(a.length * 20)} ticks</span>
                <span>Loop</span><span>${a.loop}</span>
                <span>Bones keyed</span><span>${Object.keys(a.tracks).length}</span>
                <span>Events</span><span>${a.events.length}</span>
            </div>
            ${a.events.length ? `<div style="margin-top:10px;display:flex;flex-direction:column;gap:4px">${a.events.map(e => `<div class="chip" style="height:auto;padding:3px 6px;font-family:var(--mono)"><span style="color:var(--kf-event)">${e.time.toFixed(2)}</span> ${esc(e.type === 'script' ? e.script : e.type + ' ' + (e.sound || e.particle))}${e.bone ? ' @' + esc(e.bone) : ''}</div>`).join('')}</div>` : ''}
        </div>` : ''}`;
}

function renderBoneProps(b) {
    const a = state.anim;
    const tr = a?.tracks[b.name] || {};
    const world = state.pose[b.name];
    const p = world ? [world.elements[12], world.elements[13], world.elements[14]] : [0, 0, 0];
    const extent = round(b.maxExtent, 1);
    $('#props').innerHTML = `
        <div class="sec"><h3>${b.elements.length ? 'Bone' : 'Group'} <span class="muted">${esc(b.displayName || b.name)}</span></h3>
            <div class="kv">
                <span>Parent</span><span>${b.parent ? `<a href="#" data-sel="${esc(b.parent)}" style="color:var(--accent)">${esc(b.parent)}</a>` : '—'}</span>
                <span>Children</span><span>${b.children.length}</span>
                <span>Cubes</span><span>${b.elements.length}${b.elements.length ? '' : ' <span class="muted">(no entity)</span>'}</span>
                <span>Visible</span><span>${b.visible ? 'yes' : '<span class="chip warn">hidden in file</span>'}</span>
            </div>
        </div>
        <div class="sec"><h3>Pivot <span class="muted">px</span></h3>${vec(b.origin)}</div>
        <div class="sec"><h3>Rest rotation <span class="muted">deg</span></h3>${vec(b.rotation)}</div>
        <div class="sec"><h3>Mesh</h3>
            <div class="kv">
                <span>Reach from pivot</span><span>${extent} px ${b.elements.length ? (b.meshScale > 1 ? '<span class="chip warn">over ' + MESH_LIMIT_PX + '</span>' : '<span class="chip ok">ok</span>') : ''}</span>
                <span>Stored at</span><span>${b.meshScale > 1 ? '1/' + b.meshScale + ' (mesh_scale)' : 'full size'}</span>
            </div>
            ${b.elements.length ? `<div class="meter ${b.meshScale > 1 ? 'warn' : ''}"><div style="width:${Math.min(100, b.maxExtent / MESH_LIMIT_PX * 100)}%"></div></div>` : ''}
        </div>
        <div class="sec"><h3>In game now <span class="muted">blocks from feet</span></h3>${vec(p, 3)}</div>
        ${a ? `<div class="sec"><h3>Keys in ${esc(a.name)}</h3>
            <div class="kv">
                <span style="color:var(--kf-pos)">Position</span><span>${(tr.position || []).length}</span>
                <span style="color:var(--kf-rot)">Rotation</span><span>${(tr.rotation || []).length}</span>
                <span style="color:var(--kf-scale)">Scale</span><span>${(tr.scale || []).length}</span>
            </div></div>` : ''}`;
    $('#props').querySelectorAll('[data-sel]').forEach(l => l.addEventListener('click', (e) => { e.preventDefault(); select(l.dataset.sel); }));
}

function renderChecks() {
    const w = state.model?.warnings || [];
    const errs = w.filter(x => x.level === 'err').length;
    const badge = $('#checkBadge');
    badge.hidden = w.length === 0;
    badge.textContent = w.length;
    badge.classList.toggle('err', errs > 0);
    $('#checks').innerHTML = w.length ? w.map(x => `<div class="check ${x.level}"><span class="dot"></span><div><div>${esc(x.msg)}</div><div class="where">${esc(x.where || '')}</div></div></div>`).join('')
        : `<div class="check ok"><span class="dot"></span><div>${state.model ? 'No problems found: the game should match Blockbench.' : 'Open a model to check it.'}</div></div>`;
}

function renderHud() {
    const m = state.model;
    if (!m) { $('#hud').innerHTML = ''; return; }
    const hb = viewport.hitbox?.userData || {};
    $('#hud').innerHTML = `<span class="chip">${m.stats.meshBones} entities</span><span class="chip">${m.stats.cubes} cubes</span><span class="chip">${esc(hb.driver)} ${round(hb.w)}×${round(hb.h)}</span><span class="chip accent">faces +Z at yaw 0</span>`;
}

// ---------------------------------------------------------------- toasts, theme, input

function toast(msg, err = false) {
    const el = document.createElement('div');
    el.className = 'toast' + (err ? ' err' : '');
    el.textContent = msg;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), err ? 5000 : 2200);
}

function setTheme(t) {
    document.documentElement.dataset.theme = t;
    viewport.setTheme(t);
    store.set('theme', t);
    timeline.draw();
}

$('#btnOpen').addEventListener('click', openDialog);
$('#btnOpen2').addEventListener('click', openDialog);
$('#btnReload').addEventListener('click', () => state.file && open(state.file, true));
$('#btnFit').addEventListener('click', () => viewport.fit());
$('#btnTheme').addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'));
$('#btnPlay').addEventListener('click', togglePlay);
$('#btnStart').addEventListener('click', () => { state.clock.time = 0; });
$('#speed').addEventListener('change', (e) => { state.clock.speed = +e.target.value; });
$('#loopMode').addEventListener('change', (e) => { state.clock.loopOverride = e.target.value; });
$('#yaw').addEventListener('input', (e) => { state.yaw = +e.target.value; });
$('#layerFilter').addEventListener('input', renderLayers);
document.querySelectorAll('#viewToggles .toggle').forEach(b => b.addEventListener('click', () => {
    b.classList.toggle('on');
    viewport.setView(b.dataset.view, b.classList.contains('on'));
}));
// Each tab bar switches the panes of its own mode (m-model / m-fx) inside its panel.
document.querySelectorAll('.tabs').forEach(tabs => tabs.addEventListener('click', (e) => {
    const t = e.target.closest('.tab');
    if (!t) return;
    const group = ['m-fx', 'm-ent', 'm-model'].find(c => tabs.classList.contains(c));
    tabs.querySelectorAll('.tab').forEach(x => x.classList.toggle('on', x === t));
    tabs.parentElement.querySelectorAll('.pane.' + group).forEach(p => p.classList.toggle('on', p.dataset.pane === t.dataset.tab));
}));

// ---------------------------------------------------------------- Model / Effects modes

const panels = initResizers(store);
const live = new Live({ store, toast });
const project = new Project(store);
const fx = new FxEditor({
    live, project,
    viewport, toast, store,
    modelState: () => ({ model: state.model, pose: state.pose, yaw: state.yaw }),
});

// Entity mode drives the model's animation clock while it previews a skill.
const ent = new EntityEditor({
    viewport, fx, toast, store, live, project,
    modelState: () => ({ model: state.model, pose: state.pose, yaw: state.yaw }),
    playAnim: (name, speed) => {
        if (!state.model?.anims[name]) return;
        state.anim = state.model.anims[name];
        state.clock.time = 0;
        state.clock.speed = speed * ent.speed;
        state.clock.loopOverride = 'hold';
        state.clock.playing = true;
        timeline.setAnim(state.anim, state.model);
    },
    seekAnim: (t) => { state.clock.time = Math.min(t, state.anim?.length ?? 0); },
    pauseAnim: (paused) => { state.clock.playing = !paused; },
    setAnimSpeed: (s) => { state.clock.speed = s; },
    stopPreview: () => {
        const idle = state.model?.anims.idle ? 'idle' : null;
        state.clock.loopOverride = '';
        if (idle) setAnim(idle);
    },
});

function setMode(mode) {
    const was = document.body.dataset.mode;
    document.body.dataset.mode = mode;
    store.set('mode', mode);
    document.querySelectorAll('#modes .mode').forEach(b => b.classList.toggle('on', b.dataset.mode === mode));
    fx.setActive(mode === 'effects');
    ent.setActive(mode === 'entity');
    if (was === 'entity' && mode !== 'entity') {
        state.clock.loopOverride = $('#loopMode').value;
        state.clock.speed = +$('#speed').value;
        if (state.anim) setAnim(state.anim.name);
    }
    if (mode === 'model') {
        $('#docTitle').innerHTML = state.model
            ? `${esc(state.model.name)} <span class="chip">${state.model.source === 'bbmodel' ? '.bbmodel' : 'exported'}</span>`
            : '<span class="muted">No model open</span>';
        timeline.resize();
    }
}
document.querySelectorAll('#modes .mode').forEach(b => b.addEventListener('click', () => setMode(b.dataset.mode)));

// ---------------------------------------------------------------- project

function renderProjectPane() {
    const el = $('#projectPane');
    if (!el) return;
    if (!project.open) {
        const rec = project.recent();
        el.innerHTML = `<div class="proj-empty">
            <p class="pane-hint">A project is one folder that keeps your models (with their entity / skill files) and your effects together.</p>
            <div class="start-btns"><button class="btn primary" data-act="new">New Project…</button><button class="btn" data-act="open">Open Project…</button></div>
            ${rec.length ? '<h4>Recent projects</h4>' + rec.map(d => `<div class="row" data-dir="${esc(d)}" title="${esc(d)}"><span class="name">${esc(S.path.basename(d))}</span><span class="tag">${esc(d)}</span></div>`).join('') : ''}
        </div>`;
    } else {
        el.innerHTML = `<div class="proj-head">
                <div class="proj-name">${esc(project.name)}</div>
                <div class="folder-line" title="${esc(project.dir)}">${esc(project.dir)}</div>
                <div class="start-btns"><button class="btn primary" data-act="import">Import model…</button><button class="btn" data-act="show">Show folder</button></div>
            </div>
            <h4>Models <span class="muted">models/</span></h4>
            ${project.models.length ? project.models.map(m => `<div class="row${m.file === state.file ? ' sel' : ''}" data-file="${esc(m.file)}" title="${esc(m.file)}">
                <span class="name">${esc(m.name)}</span><span class="tag">${m.kind === 'bbmodel' ? '.bbmodel' : 'exported'}</span></div>`).join('')
                : '<div class="empty">No models yet. Import a .bbmodel (or drop one on the window).</div>'}
            <h4>Effects <span class="muted">effects/</span></h4>
            <div class="pane-hint">Effects you save in Effects mode are written here. <a href="#" data-act="fx">Open Effects</a></div>
            <h4>Server <span class="muted">Export to Server goes here</span></h4>
            <div class="folder-line">${project.serverDir ? esc(project.serverDir) : '<span class="muted">not set</span>'}</div>
            <div class="start-btns"><button class="btn" data-act="server">Set server folder…</button></div>`;
    }
    el.querySelectorAll('[data-act]').forEach(b => b.addEventListener('click', (e) => {
        e.preventDefault();
        ({ new: newProject, open: () => openProject(), import: importModel, show: () => S.showFolder(project.dir),
            fx: () => setMode('effects'), server: setServerFolder })[b.dataset.act]();
    }));
    el.querySelectorAll('.row[data-dir]').forEach(r => r.addEventListener('click', () => openProject(r.dataset.dir)));
    el.querySelectorAll('.row[data-file]').forEach(r => r.addEventListener('click', () => open(r.dataset.file)));
}

function renderTitleBar() {
    document.title = [state.model?.name, project.name, 'EXea Studio'].filter(Boolean).join(' — ');
}

async function newProject() {
    const dir = await S.openDialog({ directory: true, title: 'Choose (or create) an empty folder for the new project' });
    if (!dir) return;
    const name = await ask('New project', 'Project name', S.path.basename(dir), `Creates exea-project.json, models/ and effects/ in ${dir}`);
    if (!name) return;
    try {
        await project.create(dir, name.trim());
        toast(`Project "${name.trim()}" created`);
    } catch (e) { toast('Could not create the project: ' + e.message, true); }
}

async function openProject(dir) {
    dir ||= await S.openDialog({ directory: true, title: 'Open project folder' });
    if (!dir) return;
    if (await project.load(dir)) { toast(`Opened project ${project.name}`); return; }
    const name = await ask('Not a project yet', 'Make it a project named', S.path.basename(dir),
        'This folder has no exea-project.json. Studio can turn it into a project (adds models/ and effects/; nothing else is touched).');
    if (name) await project.create(dir, name.trim());
}

function closeProject() {
    project.close();
    toast('Project closed');
}

async function importAndOpen(file) {
    try {
        const r = await project.importModel(file);
        toast(`Imported ${S.path.basename(r.file)} into models/`);
        open(r.file);
    } catch (e) { toast('Import failed: ' + e.message, true); }
}

async function importModel() {
    if (!project.open) return toast('Open or create a project first', true);
    const f = await S.openDialog({ title: 'Import model into project' });
    if (f) importAndOpen(f);
}

async function setServerFolder() {
    const dir = await S.openDialog({ directory: true, title: 'EXeaEntityCore plugin folder of your server (server/plugins/EXeaEntityCore)', defaultPath: project.serverDir || undefined });
    if (!dir) return;
    await project.setServer(dir);
    toast('Server folder set');
}

project.onChange(() => {
    renderProjectPane();
    renderRecent();
    renderTitleBar();
    fx.ready.then(() => fx.loadLibrary()).catch(() => {});
    if (document.body.dataset.mode === 'entity') ent.renderAll?.();
});

// ---------------------------------------------------------------- menus

const mode = () => document.body.dataset.mode;
const editor = () => mode() === 'effects' ? fx : mode() === 'entity' ? ent : null;

async function saveCurrent(as = false) {
    if (mode() === 'effects') return fx.save(as);
    if (mode() === 'entity') return ent.save();
    toast('Model mode only previews. Edit the model in Blockbench; skills and stats are saved in Entity mode.');
}

async function saveAll() {
    if (fx.doc && (fx.doc.dirty || !fx.doc.file)) await fx.save();
    if (ent.doc) await ent.save();
}

async function exportCurrent() {
    if (mode() === 'effects') {
        if (!fx.doc) return;
        if (!fx.doc.file) await fx.save();
        if (!fx.doc.file) return;
        if (!project.serverDir) await setServerFolder();
        if (!project.serverDir) return;
        try {
            await S.exportModel({ serverDir: project.serverDir, effects: [{ id: fx.doc.id, file: fx.doc.file }] });
            toast(`Effect ${fx.doc.id} copied to the server. Run /exea reload (or use Live).`);
        } catch (e) { toast('Export failed: ' + e.message, true); }
        return;
    }
    if (!state.model) return toast('Open a model first', true);
    if (mode() !== 'entity') setMode('entity');
    await ent.ready;
    ent.exportToServer();
}

function sendLive() {
    if (mode() === 'effects') return fx.playInGame();
    if (mode() === 'entity') return ent.sendLive();
    toast('Switch to Effects or Entity mode to send to a live server');
}

function editDelete() {
    if (mode() === 'effects' && fx.sel >= 0) fx.layerAction(fx.sel, 'del');
    else if (mode() === 'entity') {
        const s = ent.sel;
        if (s?.kind === 'action') ent.actionAction(s.index, 'del');
        else if (s?.kind === 'skill') ent.skillAction(s.skill, 'del');
    }
}

function editDuplicate() {
    if (mode() === 'effects' && fx.sel >= 0) fx.layerAction(fx.sel, 'dup');
    else if (mode() === 'entity') {
        const s = ent.sel;
        if (s?.kind === 'action') ent.actionAction(s.index, 'dup');
        else if (s?.kind === 'skill') ent.skillAction(s.skill, 'dup');
    }
}

const viewToggle = (v, key) => ({
    label: { grid: 'Grid', hitbox: 'Hitbox', pivots: 'Pivots', wire: 'Wireframe' }[v], hint: key,
    checked: () => !!document.querySelector(`#viewToggles [data-view=${v}]`)?.classList.contains('on'),
    run: () => document.querySelector(`#viewToggles [data-view=${v}]`)?.click(),
});

function whereAreMyFiles() {
    const row = (k, v, open) => `<tr><td>${k}</td><td>${v ? `<code>${esc(v)}</code>${open ? ` <a href="#" data-open="${esc(open)}">open</a>` : ''}` : '<span class="muted">—</span>'}</td></tr>`;
    const p = project.open;
    info('Where are my files?', `
        <p>${p ? `Project <b>${esc(project.name)}</b> keeps everything in one folder:` : 'No project is open. Studio is working on loose files. <b>File → New Project…</b> keeps everything in one folder.'}</p>
        <table class="where">
            ${row('Project', project.dir, project.dir)}
            ${row('Models (.bbmodel)', project.modelsDir, project.modelsDir)}
            ${row('Entity + skills', p ? '<model>_meta.json and <model>_moveset.json next to the model' : (state.file ? 'next to the open model' : ''), null)}
            ${row('Your effects', fx.folder ? S.path.join(fx.folder, '<id>.json') : 'pick a folder (Effects → Library → Folder…)', fx.folder)}
            ${row('Built-in effects', 'inside the plugin jar (read-only; save one with the same id to override it)', null)}
            ${row('Server folder', project.serverDir, project.serverDir)}
        </table>
        <p><b>Save</b> (Ctrl+S) writes the file of the mode you are in. <b>Export to Server</b> (Ctrl+E) copies the model, its
        entity / skill files and the effects it uses into the server folder; then run <code>/exea reload</code>. <b>Send to Live</b>
        (Ctrl+Enter) does the same through the Live link, with no reload needed.</p>`, 620);
    document.querySelectorAll('.modal [data-open]').forEach(a => a.addEventListener('click', (e) => { e.preventDefault(); S.showFolder(a.dataset.open); }));
}

function shortcuts() {
    const rows = [
        ['Ctrl+Shift+N / Ctrl+Shift+O', 'New / open project'], ['Ctrl+O', 'Open model'], ['Ctrl+R', 'Reload model'],
        ['Ctrl+S / Ctrl+Shift+S', 'Save / save as'], ['Ctrl+E', 'Export to server'], ['Ctrl+Enter', 'Send to live server'],
        ['Ctrl+Z / Ctrl+Y', 'Undo / redo'], ['Ctrl+D / Del', 'Duplicate / delete selection'], ['Ctrl+1 / 2 / 3', 'Model / Effects / Entity'],
        ['Space', 'Play / pause'], ['Home', 'Back to start'], ['← →', 'Step (Shift: bigger)'], ['F', 'Frame model'],
        ['G H P W', 'Grid, hitbox, pivots, wireframe'], ['Esc', 'Clear selection'], ['Drag from Toolbox', 'Drop on the timeline or on a bone'],
    ];
    info('Keyboard shortcuts', `<table class="where">${rows.map(([k, v]) => `<tr><td><code>${esc(k)}</code></td><td>${esc(v)}</td></tr>`).join('')}</table>`, 520);
}

let appVersion = '';
S.info?.().then(i => { appVersion = i.version; });
const DOCS = 'https://github.com/asnise/EXeaEntityCore/blob/main/';

new MenuBar($('#menubar'), [
    { label: 'File', items: () => [
        { label: 'New Project…', accel: 'Ctrl+Shift+N', run: newProject },
        { label: 'Open Project…', accel: 'Ctrl+Shift+O', run: () => openProject() },
        { label: 'Open Recent', enabled: () => project.recent().length > 0 || store.get('recent', []).length > 0, sub: () => [
            ...(project.recent().length ? [{ header: 'Projects' }, ...project.recent().map(d => ({ label: d, run: () => openProject(d) }))] : []),
            ...(store.get('recent', []).length ? [{ header: 'Models' }, ...store.get('recent', []).map(f => ({ label: f, run: () => open(f) }))] : []),
        ] },
        { label: 'Close Project', enabled: () => project.open, run: closeProject },
        '-',
        { label: 'Import Model into Project…', enabled: () => project.open, run: importModel },
        { label: 'Open Model…', accel: 'Ctrl+O', run: openDialog },
        { label: 'Reload Model', accel: 'Ctrl+R', enabled: () => !!state.file, run: () => open(state.file, true) },
        '-',
        { label: 'New Effect', run: () => { setMode('effects'); fx.newEffect(); } },
        { label: 'Open Effect…', run: () => { setMode('effects'); fx.openFile(); } },
        '-',
        { label: 'Save', accel: 'Ctrl+S', run: () => saveCurrent() },
        { label: 'Save As…', accel: 'Ctrl+Shift+S', enabled: () => mode() === 'effects', run: () => saveCurrent(true) },
        { label: 'Save All', accel: 'Ctrl+Alt+S', run: saveAll },
        '-',
        { label: 'Export to Server', accel: 'Ctrl+E', run: exportCurrent },
        { label: 'Send to Live Server', accel: 'Ctrl+Enter', run: sendLive },
        { label: 'Set Server Folder…', run: setServerFolder },
        { label: 'Show in Explorer', sub: () => [
            { label: 'Project folder', enabled: () => project.open, run: () => S.showFolder(project.dir) },
            { label: 'Effects folder', enabled: () => !!fx.folder, run: () => S.showFolder(fx.folder) },
            { label: 'Model folder', enabled: () => !!state.file, run: () => S.showFolder(S.path.dirname(state.file)) },
            { label: 'Server folder', enabled: () => !!project.serverDir, run: () => S.showFolder(project.serverDir) },
        ] },
        '-',
        { label: 'Exit', run: () => S.quit() },
    ] },
    { label: 'Edit', items: () => [
        { label: 'Undo', accel: 'Ctrl+Z', enabled: () => !!editor()?.hist?.canUndo, run: () => editor()?.undo() },
        { label: 'Redo', accel: 'Ctrl+Y', enabled: () => !!editor()?.hist?.canRedo, run: () => editor()?.redo() },
        { label: 'Redo', accel: 'Ctrl+Shift+Z', hidden: true, run: () => editor()?.redo() },
        '-',
        { label: 'Duplicate', accel: 'Ctrl+D', enabled: () => !!editor(), run: editDuplicate },
        { label: 'Delete', hint: 'Del', enabled: () => !!editor(), run: editDelete },
        '-',
        { label: 'Add Layer', enabled: () => mode() === 'effects' && !!fx.doc, sub: () => LAYER_TYPES.map(t => ({ label: t, run: () => fx.addLayer(t) })) },
        { label: 'Add Action', enabled: () => mode() === 'entity' && !!ent.sel?.skill, sub: () => ACTION_TYPES.map(t => ({ label: t, run: () => ent.addAction(t) })) },
        { label: 'New Skill', enabled: () => mode() === 'entity' && !!ent.doc, run: () => ent.addSkill() },
    ] },
    { label: 'View', items: () => [
        viewToggle('grid', 'G'), viewToggle('hitbox', 'H'), viewToggle('pivots', 'P'), viewToggle('wire', 'W'),
        '-',
        { label: 'Frame Model', hint: 'F', run: () => viewport.fit() },
        { label: 'Light Theme', checked: () => document.documentElement.dataset.theme === 'light', run: () => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light') },
        { label: 'Reset Panel Sizes', run: () => panels.reset() },
    ] },
    { label: 'Window', items: () => [
        { label: 'Model', accel: 'Ctrl+1', checked: () => mode() === 'model', run: () => setMode('model') },
        { label: 'Effects', accel: 'Ctrl+2', checked: () => mode() === 'effects', run: () => setMode('effects') },
        { label: 'Entity', accel: 'Ctrl+3', checked: () => mode() === 'entity', run: () => setMode('entity') },
        '-',
        { label: 'Project Panel', run: () => { setMode('model'); document.querySelector('#left .tabs.m-model [data-tab="project"]')?.click(); } },
        { label: 'Live Link…', run: () => $('#btnLive').click() },
        { label: 'Developer Tools', hint: 'F12', run: () => S.toggleDevTools?.() },
    ] },
    { label: 'Help', items: () => [
        { label: 'Where are my files?', run: whereAreMyFiles },
        { label: 'Keyboard Shortcuts', run: shortcuts },
        '-',
        { label: 'Documentation', run: () => window.open(DOCS + 'README.md') },
        { label: 'File Formats', run: () => window.open(DOCS + 'docs/FORMATS.md') },
        '-',
        { label: 'About EXea Studio', run: () => info('EXea Studio', `<p>Preview and edit EXeaEntityCore models, effects and skills.</p><p class="muted">Version ${esc(appVersion)}</p>`) },
    ] },
]);

$('#btnNewProject')?.addEventListener('click', newProject);
$('#btnOpenProject')?.addEventListener('click', () => openProject());

window.addEventListener('keydown', (e) => {
    if (e.target.matches('input[type=text], input[type=number], input:not([type]), select, textarea')) return;
    if (document.body.dataset.mode === 'entity') {
        ent.key(e);
        return;
    }
    if (document.body.dataset.mode === 'effects') {
        if (fx.key(e)) return;
        const k2 = e.key.toLowerCase();
        if (!e.ctrlKey && ['g', 'h', 'p', 'w'].includes(k2)) document.querySelector(`#viewToggles [data-view=${{ g: 'grid', h: 'hitbox', p: 'pivots', w: 'wire' }[k2]}]`).click();
        if (k2 === 'f') viewport.fit();
        return;
    }
    const k = e.key.toLowerCase();
    if (e.ctrlKey) return; // Ctrl shortcuts belong to the menu bar
    const toggle = { g: 'grid', h: 'hitbox', p: 'pivots', w: 'wire' }[k];
    if (toggle) { document.querySelector(`#viewToggles [data-view=${toggle}]`).click(); return; }
    if (k === ' ') { e.preventDefault(); togglePlay(); }
    else if (k === 'f') viewport.fit();
    else if (k === 'home') state.clock.time = 0;
    else if (k === 'escape') select(null);
    else if (k === 'arrowright' || k === 'arrowleft') {
        if (!state.anim) return;
        state.clock.playing = false;
        updatePlayIcon();
        const step = e.shiftKey ? 0.25 : 0.05;
        state.clock.time = Math.min(state.anim.length, Math.max(0, Math.round((state.clock.time + (k === 'arrowright' ? step : -step)) / 0.05) * 0.05));
    }
});

// Drag and drop of files (models, a project's exea-project.json). Toolbox drags are handled by the editors.
const hint = $('#dropHint');
const isFileDrag = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
window.addEventListener('dragover', (e) => {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    hint.classList.remove('hidden');
    hint.classList.add('drag');
});
window.addEventListener('dragleave', (e) => { if (!e.relatedTarget) { hint.classList.remove('drag'); if (state.model) hint.classList.add('hidden'); } });
window.addEventListener('drop', (e) => {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    hint.classList.remove('drag');
    if (state.model) hint.classList.add('hidden');
    const f = e.dataTransfer.files[0];
    if (!f) return;
    const p = S.pathOf(f);
    if (/exea-project\.json$/i.test(p)) openProject(S.path.dirname(p));
    else if (project.open && !p.startsWith(project.modelsDir)) importAndOpen(p);
    else open(p);
});

// ---------------------------------------------------------------- loop

let last = performance.now();
let propsTimer = 0;
function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (state.anim) {
        const fired = state.clock.tick(state.anim, dt);
        if (!state.clock.playing) updatePlayIcon();
        updatePose();
        for (const ev of fired) fireEvent(ev);
        $('#tlTime').textContent = state.clock.time.toFixed(2);
        timeline.setTime(state.clock.time);
    } else if (state.model) {
        updatePose();
    }
    propsTimer += dt;
    if (state.selected && propsTimer > 0.15) { propsTimer = 0; renderBoneProps(state.model.byName[state.selected]); }
    fx.frame(dt);
    ent.frame(dt);
    viewport.render(dt);
    requestAnimationFrame(frame);
}

setTheme(store.get('theme', 'dark'));
Promise.all([fx.ready, ent.ready]).then(() => setMode(new URLSearchParams(location.search).get('mode') || store.get('mode', 'model')))
    .catch(e => toast('Effects editor failed to start: ' + e.message, true));
renderRecent();
renderProjectPane();
const lastProject = store.get('lastProject', null);
if (lastProject) {
    project.load(lastProject).then((ok) => {
        // reopen the project's last model (unless a file was given on the command line)
        if (!ok || state.file || new URLSearchParams(location.search).get('open')) return;
        const last = store.get('recent', []).find(f => f.startsWith(project.dir));
        if (last) open(last);
        else if (project.models.length === 1) open(project.models[0].file);
    }).catch(() => {});
}
renderProps();
renderChecks();
requestAnimationFrame(frame);

// A file given on the command line (EXeaStudio.exe "<file>") arrives as ?open=<path>.
const startFile = new URLSearchParams(location.search).get('open');
if (startFile) open(startFile);
