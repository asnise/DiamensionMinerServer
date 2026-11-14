// Loads a model into one shape the preview uses, from either source:
//  - a Blockbench .bbmodel (converted with the same rules as tools/exporter/exea_exporter.js), or
//  - exported files: <id>_bones.json + <id>_anims.json (+ _meta.json) and the item models in a pack folder.
//
// Model = {
//   id, source: 'bbmodel' | 'export', files: [paths to watch], name,
//   bones: [{ name, parent, origin:[px], rotation:[deg], meshScale, visible, elements, children:[] }],
//   roots: [bone], byName: { name: bone },
//   anims: { name: { length, loop, tracks: { bone: { position:[kf], rotation:[kf], scale:[kf] } }, events:[] } },
//   texture: { url, width, height } | null, meta: {} | null, warnings: [{ level, msg, where }]
// }
// Element = { from:[px from pivot], to:[px], rotation: { origin:[px], x, y, z } | null,
//             faces: { north: { uv:[u1,v1,u2,v2] in 0..1, rotation } } }
// Keyframe = { time, pre:[x,y,z], post:[x,y,z], interp: 'linear'|'catmullrom'|'step' }
//   (position in pixels, rotation in degrees, scale as factor; exactly the .bbmodel / _anims.json values)

const S = window.studio;

/** Item models only accept coordinates -16..32 with the pivot at 8, so 24 px either way. */
export const MESH_LIMIT_PX = 24;

export async function loadModel(file) {
    const lower = file.toLowerCase();
    if (lower.endsWith('.bbmodel')) return loadBbmodel(file);
    if (lower.endsWith('_bones.json') || lower.endsWith('_anims.json') || lower.endsWith('_meta.json')) return loadExport(file);
    throw new Error('Open a .bbmodel or an exported <id>_bones.json');
}

// ---------------------------------------------------------------- .bbmodel

async function loadBbmodel(file) {
    const bb = JSON.parse(await S.readText(file));
    const warnings = [];
    const warn = (msg, where, level = 'warn') => warnings.push({ level, msg, where });
    const dir = S.path.dirname(file);
    const baseId = S.path.basename(file, '.bbmodel').toLowerCase().replace(/\s+/g, '_');

    const meta = await findMeta(dir, baseId);
    const id = meta ? meta.id : baseId;

    const texW = bb.resolution?.width || 16;
    const texH = bb.resolution?.height || 16;
    let texture = null;
    const tex0 = (bb.textures || [])[0];
    if (tex0 && typeof tex0.source === 'string' && tex0.source.startsWith('data:image')) {
        texture = { url: tex0.source, width: texW, height: texH };
    } else if (tex0) {
        warn('The first texture is not embedded in the .bbmodel; the preview shows it untextured.', 'textures[0]');
    }
    if ((bb.textures || []).length > 1) warn(`${bb.textures.length} textures: EXea uses only the first one.`, 'textures');

    const elementsByUuid = Object.fromEntries((bb.elements || []).map(e => [e.uuid, e]));
    const groups = bb.groups || [];
    const groupByUuid = Object.fromEntries(groups.map(g => [g.uuid, g]));

    // Walk the outliner once: parent of every group and the direct child elements of each group.
    // (Older .bbmodel files keep the group data inside the outliner itself; both shapes work.)
    const parentOf = {};
    const directEls = {};
    const outlinerGroups = [];
    (function walk(nodes, parent) {
        for (const n of nodes || []) {
            if (typeof n === 'string') { if (parent) (directEls[parent] ||= []).push(n); continue; }
            if (!groupByUuid[n.uuid]) outlinerGroups.push(n);
            parentOf[n.uuid] = parent;
            walk(n.children, n.uuid);
        }
    })(bb.outliner, null);
    const allGroups = groups.length ? groups : outlinerGroups;

    const bones = [];
    for (const g of allGroups) {
        const name = g.name.toLowerCase();
        const origin = g.origin || [0, 0, 0];
        const els = (directEls[g.uuid] || []).map(u => elementsByUuid[u])
            .filter(el => el && el.export !== false && (el.type || 'cube') === 'cube');
        let maxExtent = 0;
        for (const el of els) {
            const inf = el.inflate || 0;
            for (let i = 0; i < 3; i++) {
                maxExtent = Math.max(maxExtent, Math.abs(el.from[i] - inf - origin[i]), Math.abs(el.to[i] + inf - origin[i]));
            }
        }
        const meshScale = maxExtent > MESH_LIMIT_PX ? Math.ceil(maxExtent / MESH_LIMIT_PX * 1000) / 1000 : 1;
        const elements = els.map(el => {
            const inf = el.inflate || 0;
            const rel = (v, i) => v - origin[i];
            const faces = {};
            for (const [face, f] of Object.entries(el.faces || {})) {
                if (f.texture === null || f.texture === undefined || !f.uv) continue;
                faces[face] = { uv: [f.uv[0] / texW, f.uv[1] / texH, f.uv[2] / texW, f.uv[3] / texH], rotation: f.rotation || 0 };
            }
            const r = el.rotation || [0, 0, 0];
            return {
                name: el.name,
                from: el.from.map((v, i) => rel(v - inf, i)),
                to: el.to.map((v, i) => rel(v + inf, i)),
                rotation: (r[0] || r[1] || r[2]) ? { origin: (el.origin || [0, 0, 0]).map(rel), x: r[0] || 0, y: r[1] || 0, z: r[2] || 0 } : null,
                faces,
            };
        });
        bones.push({
            name, displayName: g.name, parent: parentOf[g.uuid] ? groupByUuid[parentOf[g.uuid]]?.name.toLowerCase()
                ?? outlinerGroups.find(o => o.uuid === parentOf[g.uuid])?.name.toLowerCase() ?? null : null,
            origin, rotation: g.rotation || [0, 0, 0], meshScale, maxExtent,
            visible: g.visibility !== false, elements,
        });
    }

    // Locators: effect keyframes can name a locator, which plays at the bone it sits in.
    const locatorBone = {};
    for (const [guid, uuids] of Object.entries(directEls)) {
        const g = groupByUuid[guid] || outlinerGroups.find(o => o.uuid === guid);
        for (const u of uuids) {
            const el = elementsByUuid[u];
            if (el && el.type === 'locator' && el.name && g) locatorBone[el.name.toLowerCase()] = g.name.toLowerCase();
        }
    }

    const anims = {};
    const boneNames = new Set(bones.map(b => b.name));
    for (const a of bb.animations || []) {
        let name = a.name;
        if (name.startsWith(`animation.${id}.`)) name = name.substring(`animation.${id}.`.length);
        if (name.startsWith('animation.')) name = name.split('.').pop();
        name = name.toLowerCase();
        const loop = a.loop === 'loop' || a.loop === true ? 'loop' : a.loop === 'hold' ? 'hold' : 'once';
        const tracks = {};
        for (const [uuid, animator] of Object.entries(a.animators || {})) {
            if (uuid === 'effects') continue;
            const g = groupByUuid[uuid] || outlinerGroups.find(o => o.uuid === uuid);
            if (!g) continue;
            const bone = g.name.toLowerCase();
            if (animator.rotation_global) warn('Global rotation is played as local.', `${name}/${bone}`);
            for (const kf of animator.keyframes || []) {
                if (!['position', 'rotation', 'scale'].includes(kf.channel)) continue;
                const where = `${name}/${bone}/${kf.channel}@${kf.time}`;
                if (kf.interpolation === 'bezier') warn('Bezier is played as linear.', where);
                const pre = dataPoint(kf.data_points?.[0], kf.channel, where, warn);
                const post = kf.data_points?.length > 1 ? dataPoint(kf.data_points[1], kf.channel, where, warn) : pre;
                ((tracks[bone] ||= {})[kf.channel] ||= []).push({ time: +Number(kf.time).toFixed(5), pre, post, interp: interpOf(kf.interpolation) });
            }
        }
        sortTracks(tracks);
        const events = [];
        for (const kf of a.animators?.effects?.keyframes || []) {
            const time = +Number(kf.time).toFixed(5);
            for (const dp of kf.data_points || []) {
                const where = `${name}/effects/${kf.channel}@${kf.time}`;
                const at = (loc) => {
                    const n = (loc || '').trim().toLowerCase();
                    if (!n) return undefined;
                    const b = locatorBone[n] || (boneNames.has(n) ? n : null);
                    if (!b) warn(`Locator "${loc}" is not a locator or bone; it plays at the entity.`, where);
                    return b || undefined;
                };
                if (kf.channel === 'sound' && dp.effect) events.push({ time, type: 'sound', sound: String(dp.effect).trim(), bone: at(dp.locator) });
                else if (kf.channel === 'particle' && dp.effect) events.push({ time, type: 'particle', particle: String(dp.effect).trim(), bone: at(dp.locator) });
                else if (kf.channel === 'timeline' && dp.script && String(dp.script).trim()) events.push({ time, type: 'script', script: String(dp.script).trim() });
            }
        }
        events.sort((x, y) => x.time - y.time);
        anims[name] = { name, length: a.length || 0, loop, tracks, events };
    }

    const files = [file];
    if (meta) files.push(meta.file);
    return finish({ id, name: bb.name || id, source: 'bbmodel', file, files, bones, anims, texture, meta: meta?.data || null, warnings });
}

function interpOf(s) {
    return s === 'catmullrom' ? 'catmullrom' : s === 'step' ? 'step' : 'linear';
}

/** One keyframe axis as a number. Molang can't run in the plugin, so like the exporter it becomes the default. */
function dataPoint(dp, channel, where, warn) {
    const fallback = channel === 'scale' ? 1 : 0;
    return ['x', 'y', 'z'].map(axis => {
        const raw = dp ? dp[axis] : undefined;
        if (raw === undefined || raw === null || raw === '') return fallback;
        const n = Number(raw);
        if (Number.isFinite(n)) return n;
        warn(`Molang "${raw}" can't run in game; it plays as ${fallback}.`, `${where} ${axis}`, 'err');
        return fallback;
    });
}

// ---------------------------------------------------------------- exported files

async function loadExport(file) {
    const dir = S.path.dirname(file);
    const id = S.path.basename(file).replace(/_(bones|anims|meta)\.json$/i, '');
    const bonesFile = S.path.join(dir, `${id}_bones.json`);
    const animsFile = S.path.join(dir, `${id}_anims.json`);
    if (!(await S.exists(bonesFile))) throw new Error(`${id}_bones.json is missing next to ${S.path.basename(file)}`);
    const warnings = [];
    const warn = (msg, where, level = 'warn') => warnings.push({ level, msg, where });

    const rawBones = JSON.parse(await S.readText(bonesFile));
    const rawAnims = (await S.exists(animsFile)) ? JSON.parse(await S.readText(animsFile)) : {};
    const meta = await findMeta(dir, id);

    // The pack: <data folder>/pack (server), EXeaEntityCore/pack (repo: src/main/resources/models), or
    // exea_export/pack next to an exported model.
    const candidates = [S.path.join(dir, '..', 'pack'), S.path.join(dir, '..', '..', '..', '..', 'pack'), S.path.join(dir, 'pack')];
    let pack = null;
    for (const c of candidates) {
        if (await S.exists(S.path.join(c, 'assets', 'exea', 'models', 'item', id))) { pack = S.path.resolve(c); break; }
    }
    if (!pack) warn('Item models not found (looked for pack/assets/exea/models/item/' + id + '); bones show as empty.', 'pack', 'err');

    let texture = null;
    if (pack) {
        const texFile = S.path.join(pack, 'assets', 'exea', 'textures', 'item', id, `${id}.png`);
        if (await S.exists(texFile)) {
            const url = 'data:image/png;base64,' + (await S.readBase64(texFile));
            const img = await loadImage(url);
            texture = { url, width: img.naturalWidth, height: img.naturalHeight };
        }
    }

    const bones = [];
    const files = [bonesFile];
    if (await S.exists(animsFile)) files.push(animsFile);
    if (meta) files.push(meta.file);
    for (const b of rawBones) {
        const name = b.name.toLowerCase();
        const k = b.mesh_scale || 1;
        let elements = [];
        if (pack && b.element_count > 0) {
            const mf = S.path.join(pack, 'assets', 'exea', 'models', 'item', id, `${name}.json`);
            if (await S.exists(mf)) {
                files.push(mf);
                const m = JSON.parse(await S.readText(mf));
                const rel = v => (v - 8) * k;
                elements = (m.elements || []).map(el => {
                    const faces = {};
                    for (const [face, f] of Object.entries(el.faces || {})) {
                        const uv = f.uv || [0, 0, 16, 16];
                        faces[face] = { uv: uv.map(v => v / 16), rotation: f.rotation || 0 };
                    }
                    let rotation = null;
                    if (el.rotation) {
                        const r = el.rotation;
                        const o = (r.origin || [8, 8, 8]).map(rel);
                        if (r.axis) rotation = { origin: o, x: r.axis === 'x' ? r.angle : 0, y: r.axis === 'y' ? r.angle : 0, z: r.axis === 'z' ? r.angle : 0 };
                        else rotation = { origin: o, x: r.x || 0, y: r.y || 0, z: r.z || 0 };
                    }
                    return { from: el.from.map(rel), to: el.to.map(rel), rotation, faces };
                });
            } else {
                warn(`Item model ${name}.json is missing from the pack.`, `pack/.../${id}/${name}.json`, 'err');
            }
        }
        let maxExtent = 0;
        for (const el of elements) for (let i = 0; i < 3; i++) maxExtent = Math.max(maxExtent, Math.abs(el.from[i]), Math.abs(el.to[i]));
        bones.push({
            name, displayName: b.original_name || b.name, parent: b.parent ? b.parent.toLowerCase() : null,
            origin: b.origin, rotation: b.rotation || [0, 0, 0], meshScale: k, maxExtent,
            visible: b.visible !== false, elements,
        });
    }

    const anims = {};
    for (const [rawName, a] of Object.entries(rawAnims)) {
        const name = rawName.toLowerCase();
        const tracks = {};
        for (const [bone, kfs] of Object.entries(a.bones || {})) {
            for (const kf of kfs) {
                const ch = String(kf.channel).toLowerCase();
                const pre = kf.vector || [0, 0, 0];
                ((tracks[bone.toLowerCase()] ||= {})[ch] ||= []).push({ time: kf.time, pre, post: kf.post || pre, interp: interpOf(kf.interpolation) });
            }
        }
        sortTracks(tracks);
        const loop = String(a.loop || 'once').toLowerCase();
        anims[name] = { name, length: a.length || 0, loop: ['loop', 'hold'].includes(loop) ? loop : 'once', tracks, events: (a.events || []).slice().sort((x, y) => x.time - y.time) };
    }

    return finish({ id, name: meta?.data?.display_name || id, source: 'export', file: bonesFile, files, bones, anims, texture, meta: meta?.data || null, warnings, pack });
}

// ---------------------------------------------------------------- shared

async function findMeta(dir, id) {
    const exact = S.path.join(dir, `${id}_meta.json`);
    let file = (await S.exists(exact)) ? exact : null;
    if (!file) {
        const metas = (await S.list(dir)).filter(e => !e.dir && /_meta\.json$/i.test(e.name));
        if (metas.length === 1) file = S.path.join(dir, metas[0].name);
    }
    if (!file) return null;
    try {
        return { file, id: S.path.basename(file).replace(/_meta\.json$/i, ''), data: JSON.parse(await S.readText(file)) };
    } catch (e) {
        return { file, id, data: null, error: e.message };
    }
}

function sortTracks(tracks) {
    for (const t of Object.values(tracks)) for (const list of Object.values(t)) list.sort((a, b) => a.time - b.time);
}

function loadImage(url) {
    return new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = url; });
}

/** Builds the tree, then the checks every model gets whatever its source. */
function finish(m) {
    m.byName = {};
    for (const b of m.bones) { b.children = []; m.byName[b.name] = b; }
    m.roots = [];
    for (const b of m.bones) {
        if (b.parent && m.byName[b.parent]) m.byName[b.parent].children.push(b);
        else m.roots.push(b);
    }
    const seen = new Set();
    for (const b of m.bones) {
        if (seen.has(b.name)) m.warnings.push({ level: 'err', msg: `Two bones are named "${b.name}". Bone names must be unique (lower case).`, where: b.name });
        seen.add(b.name);
    }
    for (const b of m.bones) {
        if (b.meshScale > 1) m.warnings.push({ level: 'warn', msg: `Mesh reaches ${round(b.maxExtent)} px from its pivot (limit ${MESH_LIMIT_PX}); it is stored at 1/${b.meshScale} and loses texture sharpness. Split it into more bones.`, where: b.name });
    }
    for (const [an, a] of Object.entries(m.anims)) {
        for (const bone of Object.keys(a.tracks)) {
            if (!m.byName[bone]) m.warnings.push({ level: 'warn', msg: 'Keys for a bone the model does not have.', where: `${an}/${bone}` });
            const rot = a.tracks[bone].rotation || [];
            for (let i = 1; i < rot.length; i++) {
                const d = Math.max(...[0, 1, 2].map(k => Math.abs(rot[i].pre[k] - rot[i - 1].post[k])));
                if (d > 180) { m.warnings.push({ level: 'warn', msg: `${round(d)}° between two rotation keys; keep it under 180°.`, where: `${an}/${bone}@${rot[i].time}` }); break; }
            }
        }
    }
    const meshBones = m.bones.filter(b => b.visible && b.elements.length > 0).length;
    m.stats = {
        bones: m.bones.length, meshBones,
        cubes: m.bones.reduce((s, b) => s + b.elements.length, 0),
        anims: Object.keys(m.anims).length,
    };
    if (meshBones > 50) m.warnings.push({ level: 'err', msg: `${meshBones} bones with cubes: over the boss budget of about 50. Each one is an entity and its packets.`, where: 'bones' });
    else if (meshBones > 30) m.warnings.push({ level: 'warn', msg: `${meshBones} bones with cubes: fine for a boss (≤ 50), heavy for a common mob (≤ 30).`, where: 'bones' });
    return m;
}

export function round(v, d = 2) {
    const k = 10 ** d;
    return Math.round(v * k) / k;
}
