// Toolbox items you drag onto a timeline (or onto the model): action types, ready-made skill
// templates, effect layers and effect presets. Drag data travels as MIME type application/x-exea.
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const MIME = 'application/x-exea';

/** Skill templates: several actions with times relative to where they are dropped. */
export const SKILL_TEMPLATES = [
    { id: 'slash', label: 'Slash', sub: 'cone hit + sweep', actions: [
        { at: 0, type: 'sound', sound: 'entity.player.attack.sweep', pitch: 0.7 },
        { at: 0, type: 'hit', shape: { type: 'cone', radius: 4, angle: 120 }, damage: 10, knockback: 0.5 },
        { at: 0, type: 'fx', id: 'impact_spark' }] },
    { id: 'ground_slam', label: 'Ground slam', sub: 'warning → slam + rocks', actions: [
        { at: 0, type: 'telegraph', shape: { type: 'circle', radius: 5 }, duration: 0.8 },
        { at: 0.8, type: 'hit', shape: { type: 'circle', radius: 5 }, damage: 18, knockup: 0.5 },
        { at: 0.8, type: 'fx', id: 'ground_slam', anchor: { ground: true, offset: [0, 0, 1.5] } }] },
    { id: 'fireball', label: 'Fireball', sub: 'homing projectile', actions: [
        { at: 0, type: 'projectile', speed: 0.7, homing: 0.1, lifetime: 3, radius: 0.6, trail: 'projectile_trail', damage: 6,
          on_hit: [{ type: 'hit', shape: { type: 'sphere', radius: 2 }, damage: 8 }, { type: 'fx', id: 'fire_burst' }] }] },
    { id: 'volley', label: 'Volley', sub: '5 projectiles fanned', actions: [
        { at: 0, type: 'projectile', count: 5, spread: 50, speed: 0.8, lifetime: 2, radius: 0.4, damage: 5, on_hit: [{ type: 'fx', id: 'impact_spark' }] }] },
    { id: 'dash_strike', label: 'Dash strike', sub: 'warning line → dash → hit', actions: [
        { at: 0, type: 'telegraph', shape: { type: 'line', length: 8, width: 2 }, duration: 0.5 },
        { at: 0.5, type: 'move', mode: 'dash', direction: 'target', distance: 7, duration: 0.3 },
        { at: 0.5, type: 'fx', id: 'dash_trail', follow: true },
        { at: 0.8, type: 'hit', shape: { type: 'circle', radius: 2.5 }, damage: 12 }] },
    { id: 'leap_slam', label: 'Leap slam', sub: 'jump onto the target', actions: [
        { at: 0, type: 'move', mode: 'leap', to: 'target', height: 4, duration: 0.7 },
        { at: 0.7, type: 'hit', shape: { type: 'circle', radius: 4 }, damage: 16, knockup: 0.4 },
        { at: 0.7, type: 'fx', id: 'ground_slam' }] },
    { id: 'blink', label: 'Blink behind', sub: 'teleport behind target', actions: [
        { at: 0, type: 'move', mode: 'teleport', to: 'behind_target', distance: 2.5, fx_out: 'smoke_puff', fx_in: 'void_burst' }] },
    { id: 'shockwave', label: 'Shockwave', sub: 'ring + push away', actions: [
        { at: 0, type: 'fx', id: 'shockwave', scale: 1.5 },
        { at: 0, type: 'move', mode: 'push', radius: 6, strength: 1.2 }] },
    { id: 'beam', label: 'Beam', sub: 'warning → 4 ticks of damage', actions: [
        { at: 0, type: 'telegraph', shape: { type: 'line', length: 12, width: 1.5 }, duration: 0.6 },
        { at: 0.6, type: 'fx', id: 'beam' },
        { at: 0.6, type: 'hit', shape: { type: 'line', length: 12, width: 1.5 }, damage: 6, repeat: { count: 4, interval: 0.25 } }] },
    { id: 'summon', label: 'Summon minions', sub: 'circle → 3 minions', actions: [
        { at: 0, type: 'fx', id: 'summon_circle' },
        { at: 1, type: 'summon', model: 'my_minion', count: 3, radius: 3, max_alive: 6 }] },
    { id: 'heal', label: 'Heal + shield', sub: 'aura, heal 10%, 2 s invulnerable', actions: [
        { at: 0, type: 'fx', id: 'aura_loop', follow: true },
        { at: 0, type: 'self', heal: 0.1, invulnerable: 2 }] },
];

/** Effect layer templates (besides the bare layer types). */
export const LAYER_TEMPLATES = [
    { id: 'smoke_burst', label: 'Smoke burst', layer: { type: 'particle', particle: 'large_smoke', count: 20, shape: { type: 'sphere', radius: 0.8 }, motion: 'outward', speed: 0.05 } },
    { id: 'fire_burst', label: 'Fire burst', layer: { type: 'particle', particle: 'flame', count: 40, shape: { type: 'sphere', radius: 0.5 }, motion: 'outward', speed: 0.18 } },
    { id: 'fog_cloud', label: 'Fog cloud', layer: { type: 'particle', particle: 'campfire_cosy_smoke', anchor: { ground: true }, count: 3, duration: 2, interval: 0.15, shape: { type: 'circle', radius: 4 }, motion: 'up', speed: 0.004 } },
    { id: 'rocks', label: 'Flying rocks', layer: { type: 'debris', anchor: { ground: true }, count: 12, block: 'ground', speed: [0.35, 0.7], angle: 70 } },
    { id: 'wave', label: 'Ground wave', layer: { type: 'ground_wave', radius: [1, 5], duration: 0.5, height: 0.6 } },
    { id: 'cracks', label: 'Cracks', layer: { type: 'decal', anchor: { ground: true }, pattern: 'crack', radius: 2.5, pieces: 14, lifetime: 3 } },
    { id: 'warn', label: 'Warning circle', layer: { type: 'telegraph', anchor: { ground: true }, shape: { type: 'circle', radius: 3 }, duration: 1 } },
    { id: 'ring', label: 'Expanding ring', layer: { type: 'particle', particle: 'cloud', anchor: { ground: true, offset: [0, 0.2, 0] }, count: 30, duration: 0.5, shape: { type: 'ring', radius: 0.5, radius_end: 6 } } },
    { id: 'spiral', label: 'Spiral aura', layer: { type: 'particle', particle: 'dust', color: '#ffd34d', anchor: { follow: true }, count: 6, duration: 2, shape: { type: 'spiral', radius: 0.9, height: 2.2, turns: 2, arms: 2 } } },
    { id: 'boom', label: 'Explosion sound', layer: { type: 'sound', sound: 'entity.generic.explode', volume: 1.5, pitch: [0.7, 0.9] } },
];

/**
 * Renders draggable chips. sections: [{ title, items: [{ label, sub?, color?, data }] }].
 * onPick(data) runs on click (adds at the playhead) — dragging drops at the pointer.
 */
export function renderPalette(el, sections, onPick, filter = '') {
    const q = filter.trim().toLowerCase();
    el.innerHTML = sections.map((s, si) => {
        const items = s.items.filter(it => !q || (it.label + ' ' + (it.sub || '')).toLowerCase().includes(q));
        if (!items.length) return '';
        return `<div class="group-title"><span>${esc(s.title)}</span><span class="muted">${items.length}</span></div>
            <div class="chips">${items.map((it, ii) => `<div class="chip-item" draggable="true" data-s="${si}" data-i="${s.items.indexOf(it)}" title="${esc((it.sub ? it.sub + ' — ' : '') + 'drag onto the timeline, or click to add at the playhead')}">
                <span class="type-dot" style="background:${it.color || 'var(--text-3)'}"></span><span>${esc(it.label)}</span></div>`).join('')}</div>`;
    }).join('') || '<div class="empty">Nothing matches.</div>';
    el.querySelectorAll('.chip-item').forEach(c => {
        const it = sections[+c.dataset.s].items[+c.dataset.i];
        c.addEventListener('dragstart', (e) => {
            e.dataTransfer.setData(MIME, JSON.stringify(it.data));
            e.dataTransfer.effectAllowed = 'copy';
            document.body.classList.add('dragging-tool');
        });
        c.addEventListener('dragend', () => document.body.classList.remove('dragging-tool'));
        c.addEventListener('click', () => onPick(it.data));
    });
}

/** The x-exea payload of a drag, or null (files and other drags). */
export function dragData(e) {
    if (!e.dataTransfer || ![...e.dataTransfer.types].includes(MIME)) return null;
    try { return JSON.parse(e.dataTransfer.getData(MIME) || 'null'); } catch { return null; }
}

export function isToolDrag(e) {
    return !!e.dataTransfer && [...e.dataTransfer.types].includes(MIME);
}
