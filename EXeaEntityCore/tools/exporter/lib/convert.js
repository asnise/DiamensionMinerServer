// Blockbench .bbmodel → EXea model files. Shared by the CLI (../exea_exporter.js) and EXea Studio.
// Pure conversion: convert() reads nothing from disk; writePack() / writeModels() write the results.
const fs = require('fs');
const path = require('path');

/**
 * @param bbmodel  parsed .bbmodel JSON
 * @param entityId model id
 * @param log      receives progress lines ("  Bone: ...", "  [SCALE] ...", "  [WARN] ...")
 * @returns {{ bones, anims, warnings, texture: Buffer|null, models: Object, items: Object }}
 */
function convert(bbmodel, entityId, log = () => {}) {
    const groupsByUuid = {};
    bbmodel.groups.forEach(g => { groupsByUuid[g.uuid] = g; });
    const elementsByUuid = {};
    bbmodel.elements.forEach(e => { elementsByUuid[e.uuid] = e; });

    function findGroupParent(targetUuid, nodes, parentUuid) {
        for (const node of nodes) {
            if (typeof node === 'string') continue;
            if (node.uuid === targetUuid) return parentUuid;
            const found = findGroupParent(targetUuid, node.children || [], node.uuid);
            if (found !== undefined) return found;
        }
        return undefined;
    }

    function getDirectElementUuids(groupUuid) {
        const found = [];
        (function walk(nodes) {
            for (const node of nodes) {
                if (typeof node === 'string') continue;
                if (node.uuid === groupUuid) {
                    for (const child of (node.children || [])) if (typeof child === 'string') found.push(child);
                    return true;
                }
                if (walk(node.children || [])) return true;
            }
            return false;
        })(bbmodel.outliner);
        return found;
    }

    const parentMap = {};
    for (const group of bbmodel.groups) {
        const parent = findGroupParent(group.uuid, bbmodel.outliner, null);
        if (parent) parentMap[group.uuid] = parent;
    }

    // Locator name -> bone it sits in, so effect keyframes can name a locator (or a bone) as their position.
    const locatorBone = {};
    for (const group of bbmodel.groups) {
        for (const uuid of getDirectElementUuids(group.uuid)) {
            const el = elementsByUuid[uuid];
            if (el && el.type === 'locator' && el.name) locatorBone[el.name.toLowerCase()] = group.name.toLowerCase();
        }
    }

    let cmdCounter = 1001;
    const bones = [];
    const boneNameToGroup = {};
    // Filled up front: bbmodel.groups is in creation order, so a group added later (e.g. a forearm the hand was
    // moved into) comes after its children and its name must already be known when a child looks it up.
    const groupUuidToName = {};
    for (const g of bbmodel.groups) groupUuidToName[g.uuid] = g.name.toLowerCase();

    for (const group of bbmodel.groups) {
        const boneName = group.name.toLowerCase();
        boneNameToGroup[boneName] = group;
        const visibleElements = getDirectElementUuids(group.uuid).filter(uuid => {
            const el = elementsByUuid[uuid];
            return el && el.export !== false && (el.type || 'cube') === 'cube'; // skip null objects / locators (IK controllers)
        });
        const parentUuid = parentMap[group.uuid];
        const parentName = parentUuid ? (groupUuidToName[parentUuid] || null) : null;
        const isVisible = group.visibility !== false;
        const origin = group.origin || [0, 0, 0];

        // Item-model elements must stay inside -16..32 (pivot sits at 8). Bigger meshes are shrunk
        // around the pivot here and scaled back up by the plugin (mesh_scale).
        let maxExtent = 0;
        for (const uuid of visibleElements) {
            const el = elementsByUuid[uuid];
            for (let i = 0; i < 3; i++) {
                const inf = el.inflate || 0;
                maxExtent = Math.max(maxExtent, Math.abs(el.from[i] - inf - origin[i]), Math.abs(el.to[i] + inf - origin[i]));
            }
        }
        const meshScale = maxExtent > 24 ? Math.ceil(maxExtent / 24 * 1000) / 1000 : 1;

        const bone = {
            name: boneName,
            original_name: group.name,
            parent: parentName,
            origin: origin,
            rotation: group.rotation || [0, 0, 0],
            custom_model_data: cmdCounter++,
            element_count: visibleElements.length,
            visible: isVisible,
            mesh_scale: meshScale
        };
        if (meshScale !== 1) log(`  [SCALE] ${boneName}: mesh is ${maxExtent}px from its pivot (> 24), stored at 1/${meshScale}`);
        bones.push(bone);
        log(`  Bone: ${boneName.padEnd(30)} elements=${visibleElements.length} visible=${isVisible} cmd=${bone.custom_model_data}`);
    }

    const anims = {};
    const warnings = [];
    function warn(msg) {
        warnings.push(msg);
        log(`  [WARN] ${msg}`);
    }

    /** Numeric value of one keyframe axis. Molang that is not a plain number cannot run in the plugin. */
    function dataPoint(dp, channel, where) {
        const fallback = channel === 'scale' ? 1 : 0;
        return ['x', 'y', 'z'].map(axis => {
            const raw = dp ? dp[axis] : undefined;
            if (raw === undefined || raw === null || raw === '') return fallback;
            const n = Number(raw);
            if (Number.isFinite(n)) return parseFloat(n.toFixed(5));
            warn(`${where} ${axis}: Molang "${raw}" is not supported, using ${fallback}`);
            return fallback;
        });
    }

    /** Effect position: a locator (-> its bone) or a bone name; unknown names warn and use the driver. */
    function withBone(ev, locator, where) {
        const name = (locator || '').trim().toLowerCase();
        if (!name) return ev;
        const bone = locatorBone[name] || (boneNameToGroup[name] ? name : null);
        if (bone) ev.bone = bone;
        else warn(`${where}: locator "${locator}" is not a locator or bone, played at the entity`);
        return ev;
    }

    log(`\n--- Exporting Animations ---`);
    for (const anim of bbmodel.animations) {
        let animName = anim.name;
        const prefix = `animation.${entityId}.`;
        if (animName.startsWith(prefix)) animName = animName.substring(prefix.length);
        if (animName.startsWith('animation.')) animName = animName.split('.').pop();
        animName = animName.toLowerCase();

        let loopMode = 'once';
        if (anim.loop === 'loop') loopMode = 'loop';
        else if (anim.loop === 'hold') loopMode = 'hold';
        else if (anim.loop === true) loopMode = 'loop';

        const animEntry = { length: anim.length, loop: loopMode, bones: {} };
        const animators = anim.animators || {};
        let kfCount = 0;

        for (const [uuid, animator] of Object.entries(animators)) {
            const group = groupsByUuid[uuid];
            if (!group) continue;
            const boneName = group.name.toLowerCase();
            const keyframes = animator.keyframes || [];
            if (keyframes.length === 0) continue;
            const boneKfs = [];
            for (const kf of keyframes) {
                const channel = kf.channel;
                if (channel !== 'position' && channel !== 'rotation' && channel !== 'scale') continue;
                const where = `${animName}/${boneName}/${channel}@${kf.time}`;
                const entry = {
                    channel: channel,
                    time: parseFloat(kf.time.toFixed(5)),
                    interpolation: kf.interpolation || 'linear',
                    vector: dataPoint(kf.data_points[0], channel, where)
                };
                if (kf.interpolation === 'bezier') warn(`${where}: bezier is played as linear`);
                // Split keyframe (Blockbench "pre/post"): second data point is the value leaving it.
                if (kf.data_points.length > 1) entry.post = dataPoint(kf.data_points[1], channel, where);
                boneKfs.push(entry);
                kfCount++;
            }
            if (animator.rotation_global) warn(`${animName}/${boneName}: global rotation is not supported, played as local`);
            boneKfs.sort((a, b) => a.time - b.time || a.channel.localeCompare(b.channel));
            animEntry.bones[boneName] = boneKfs;
        }

        // Effect keyframes (Blockbench "Effects" row): sound / particle / timeline script.
        const effects = animators.effects;
        const events = [];
        for (const kf of (effects && effects.keyframes) || []) {
            const time = parseFloat(Number(kf.time).toFixed(5));
            for (const dp of kf.data_points || []) {
                const where = `${animName}/effects/${kf.channel}@${kf.time}`;
                if (kf.channel === 'sound' && dp.effect) {
                    events.push(withBone({ time, type: 'sound', sound: String(dp.effect).trim() }, dp.locator, where));
                } else if (kf.channel === 'particle' && dp.effect) {
                    events.push(withBone({ time, type: 'particle', particle: String(dp.effect).trim() }, dp.locator, where));
                } else if (kf.channel === 'timeline' && dp.script && String(dp.script).trim()) {
                    events.push({ time, type: 'script', script: String(dp.script).trim() });
                }
            }
        }
        if (events.length) {
            events.sort((a, b) => a.time - b.time);
            animEntry.events = events;
        }
        anims[animName] = animEntry;
        log(`  Anim: ${animName.padEnd(25)} length=${anim.length}s loop=${loopMode} keyframes=${kfCount}` +
            (animEntry.events ? ` events=${animEntry.events.length}` : ''));
    }

    // Texture (first one, embedded) and one item model + item definition per bone.
    let texture = null;
    if (bbmodel.textures && bbmodel.textures.length > 0 && bbmodel.textures[0].source) {
        const m = bbmodel.textures[0].source.match(/^data:image\/png;base64,(.*)$/);
        if (m) texture = Buffer.from(m[1], 'base64');
    }
    const texW = bbmodel.resolution.width;
    const texH = bbmodel.resolution.height;
    const models = {};
    const items = {};
    for (const bone of bones) {
        const boneName = bone.name;
        const group = boneNameToGroup[boneName];
        const elements = getDirectElementUuids(group.uuid)
            .map(uuid => elementsByUuid[uuid])
            .filter(el => el && el.export !== false && (el.type || 'cube') === 'cube');

        const mcElements = [];
        for (const el of elements) {
            const k = bone.mesh_scale || 1;
            const local = (v, i) => (v - bone.origin[i]) / k + 8;
            const inf = el.inflate || 0;
            const localFrom = el.from.map((v, i) => local(v - inf, i));
            const localTo = el.to.map((v, i) => local(v + inf, i));

            const mcFaces = {};
            for (const [faceName, faceData] of Object.entries(el.faces || {})) {
                if (faceData.texture === null || faceData.texture === undefined) continue;
                const uv = faceData.uv;
                mcFaces[faceName] = {
                    uv: [uv[0] / texW * 16, uv[1] / texH * 16, uv[2] / texW * 16, uv[3] / texH * 16],
                    texture: '#0'
                };
                if (faceData.rotation) mcFaces[faceName].rotation = faceData.rotation;
            }

            const mcElement = {
                from: localFrom.map(v => parseFloat(v.toFixed(4))),
                to: localTo.map(v => parseFloat(v.toFixed(4))),
                faces: mcFaces
            };
            if (el.rotation && (el.rotation[0] !== 0 || el.rotation[1] !== 0 || el.rotation[2] !== 0)) {
                const elOriginLocal = (el.origin || [0, 0, 0]).map((v, i) => local(v, i));
                // 25w46a+ (MC 1.21.11, so 26.x) element rotation: any angle on any axes, applied X then Y
                // then Z, which is Blockbench's cube order (three.js 'ZYX' = Rz*Ry*Rx). No axis/angle
                // fields: they would take precedence and bring back the single-axis, +-45 limit.
                mcElement.rotation = {
                    origin: elOriginLocal.map(v => parseFloat(v.toFixed(4))),
                    x: el.rotation[0] || 0,
                    y: el.rotation[1] || 0,
                    z: el.rotation[2] || 0
                };
            }
            mcElements.push(mcElement);
        }

        models[boneName] = {
            credit: 'EXeaEntityCore Blockbench Exporter',
            textures: {
                '0': `exea:item/${entityId}/${entityId}`,
                particle: `exea:item/${entityId}/${entityId}`
            },
            elements: mcElements,
            display: { head: { translation: [0, 0, 0], scale: [1, 1, 1] } }
        };
        items[boneName] = { model: { type: 'minecraft:model', model: `exea:item/${entityId}/${boneName}` } };
    }

    return { bones, anims, warnings, texture, models, items };
}

/** Writes models/<id>_bones.json and _anims.json under {@code modelsDir}. Returns the two paths. */
function writeModels(modelsDir, entityId, result) {
    fs.mkdirSync(modelsDir, { recursive: true });
    const bonesPath = path.join(modelsDir, `${entityId}_bones.json`);
    const animsPath = path.join(modelsDir, `${entityId}_anims.json`);
    fs.writeFileSync(bonesPath, JSON.stringify(result.bones, null, 2), 'utf8');
    fs.writeFileSync(animsPath, JSON.stringify(result.anims, null, 2), 'utf8');
    return { bonesPath, animsPath };
}

/**
 * Writes this model into a shared pack folder: its own folders are replaced, other models stay, and
 * pack.mcmeta is (re)written. Returns the number of bone models.
 */
function writePack(packDir, entityId, result, description, log = () => {}) {
    for (const sub of [['models', 'item'], ['items'], ['textures', 'item']]) {
        fs.rmSync(path.join(packDir, 'assets', 'exea', ...sub, entityId), { recursive: true, force: true });
    }
    const modelsDir = path.join(packDir, 'assets', 'exea', 'models', 'item', entityId);
    const itemsDir = path.join(packDir, 'assets', 'exea', 'items', entityId);
    const texturesDir = path.join(packDir, 'assets', 'exea', 'textures', 'item', entityId);
    for (const d of [modelsDir, itemsDir, texturesDir]) fs.mkdirSync(d, { recursive: true });

    const packMcmeta = {
        pack: {
            // Free-angle element rotations need 1.21.6+; min/max_format is the 1.21.9+ field.
            pack_format: 75,
            supported_formats: [46, 999],
            min_format: 46,
            max_format: 999,
            description
        }
    };
    fs.writeFileSync(path.join(packDir, 'pack.mcmeta'), JSON.stringify(packMcmeta, null, 2), 'utf8');

    if (result.texture) {
        fs.writeFileSync(path.join(texturesDir, `${entityId}.png`), result.texture);
        log(`  Texture: ${entityId}.png (${result.texture.length} bytes)`);
    }
    let count = 0;
    for (const [boneName, model] of Object.entries(result.models)) {
        fs.writeFileSync(path.join(modelsDir, `${boneName}.json`), JSON.stringify(model, null, 2), 'utf8');
        fs.writeFileSync(path.join(itemsDir, `${boneName}.json`), JSON.stringify(result.items[boneName], null, 2), 'utf8');
        count++;
    }
    return count;
}

module.exports = { convert, writeModels, writePack };
