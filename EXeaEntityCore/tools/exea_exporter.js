const fs = require('fs');
const path = require('path');

const ARGS = process.argv.slice(2);
// --server <folder>: write straight into a server's plugins/EXeaEntityCore folder (models/ + pack/),
// nothing else. Players get it after /exea reload, no jar rebuild. Without it: the repo workflow below.
let SERVER_DIR = null;
const serverFlag = ARGS.indexOf('--server');
if (serverFlag >= 0) {
    SERVER_DIR = ARGS[serverFlag + 1];
    ARGS.splice(serverFlag, 2);
    if (!SERVER_DIR) { console.log('--server needs a folder (plugins/EXeaEntityCore)'); process.exit(1); }
    SERVER_DIR = path.resolve(SERVER_DIR);
}
if (ARGS.length < 1) {
    console.log('Usage: node exea_exporter.js <path/to/model.bbmodel> [entity_id] [output_dir]');
    console.log('       node exea_exporter.js <path/to/model.bbmodel> [entity_id] --server <server>/plugins/EXeaEntityCore');
    console.log('  entity_id   defaults to model filename lowercase');
    console.log('  output_dir  defaults to ./exea_export/');
    console.log('  --server    write models/ and pack/ into that plugin folder only, then run /exea reload');
    process.exit(1);
}

const bbmodelPath = ARGS[0];
const rawEntityId = ARGS[1] || path.basename(bbmodelPath, '.bbmodel').toLowerCase().replace(/\s+/g, '_');
const entityId = rawEntityId;
const outputDir = SERVER_DIR || ARGS[2] || path.join(path.dirname(bbmodelPath), 'exea_export');

const bbmodel = JSON.parse(fs.readFileSync(bbmodelPath, 'utf8'));

const groupsByUuid = {};
bbmodel.groups.forEach(g => { groupsByUuid[g.uuid] = g; });

const elementsByUuid = {};
bbmodel.elements.forEach(e => { elementsByUuid[e.uuid] = e; });

function findGroupParent(targetUuid, nodes, parentUuid) {
    for (const node of nodes) {
        if (typeof node === 'string') {
            continue;
        }
        if (node.uuid === targetUuid) {
            return parentUuid;
        }
        const found = findGroupParent(targetUuid, node.children || [], node.uuid);
        if (found !== undefined) return found;
    }
    return undefined;
}

function collectElementUuids(groupUuid) {
    const result = [];
    function walk(nodes) {
        for (const node of nodes) {
            if (typeof node === 'string') {
                result.push(node);
            } else if (node.uuid === groupUuid) {
                walkChildren(node.children || []);
            } else {
                walk(node.children || []);
            }
        }
    }
    function walkChildren(children) {
        for (const child of children) {
            if (typeof child === 'string') {
                result.push(child);
            }
        }
    }
    walk(bbmodel.outliner);
    return result;
}

function getDirectElementUuids(groupUuid) {
    let found = [];
    function walk(nodes) {
        for (const node of nodes) {
            if (typeof node === 'string') continue;
            if (node.uuid === groupUuid) {
                for (const child of (node.children || [])) {
                    if (typeof child === 'string') {
                        found.push(child);
                    }
                }
                return true;
            }
            if (walk(node.children || [])) return true;
        }
        return false;
    }
    walk(bbmodel.outliner);
    return found;
}

const parentMap = {};
for (const group of bbmodel.groups) {
    const parent = findGroupParent(group.uuid, bbmodel.outliner, null);
    if (parent) {
        parentMap[group.uuid] = parent;
    }
}

// Locator name -> bone it sits in, so effect keyframes can name a locator (or a bone) as their position.
const locatorBone = {};
for (const group of bbmodel.groups) {
    for (const uuid of getDirectElementUuids(group.uuid)) {
        const el = elementsByUuid[uuid];
        if (el && el.type === 'locator' && el.name) locatorBone[el.name.toLowerCase()] = group.name.toLowerCase();
    }
}

console.log(`\n=== EXea Entity Exporter ===`);
console.log(`Model: ${bbmodel.name || bbmodelPath}`);
console.log(`Entity ID: ${entityId}`);
console.log(`Output: ${outputDir}`);
console.log(`Groups: ${bbmodel.groups.length}`);
console.log(`Elements: ${bbmodel.elements.length}`);
console.log(`Animations: ${bbmodel.animations.length}`);
console.log(`Texture Resolution: ${bbmodel.resolution.width}x${bbmodel.resolution.height}\n`);

let cmdCounter = 1001;

const bones = [];
const boneNameToGroup = {};
const groupUuidToName = {};

for (const group of bbmodel.groups) {
    const boneName = group.name.toLowerCase();
    groupUuidToName[group.uuid] = boneName;
    boneNameToGroup[boneName] = group;

    const directElements = getDirectElementUuids(group.uuid);
    const visibleElements = directElements.filter(uuid => {
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
    if (meshScale !== 1) console.log(`  [SCALE] ${boneName}: mesh is ${maxExtent}px from its pivot (> 24), stored at 1/${meshScale}`);

    bones.push(bone);
    console.log(`  Bone: ${boneName.padEnd(30)} elements=${visibleElements.length} visible=${isVisible} cmd=${bone.custom_model_data}`);
}

const bonesJsonPath = path.join(outputDir, 'models', `${entityId}_bones.json`);
mkdirp(path.dirname(bonesJsonPath));
fs.writeFileSync(bonesJsonPath, JSON.stringify(bones, null, 2), 'utf8');
console.log(`\n[OK] Bones JSON -> ${bonesJsonPath}`);

console.log(`\n--- Exporting Animations ---`);
const animsOutput = {};
const warnings = [];

for (const anim of bbmodel.animations) {
    let animName = anim.name;
    const prefix = `animation.${entityId}.`;
    if (animName.startsWith(prefix)) {
        animName = animName.substring(prefix.length);
    }
    if (animName.startsWith('animation.')) {
        animName = animName.split('.').pop();
    }
    animName = animName.toLowerCase();

    let loopMode = 'once';
    if (anim.loop === 'loop') loopMode = 'loop';
    else if (anim.loop === 'hold') loopMode = 'hold';
    else if (anim.loop === true) loopMode = 'loop';

    const animEntry = {
        length: anim.length,
        loop: loopMode,
        bones: {}
    };

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

    animsOutput[animName] = animEntry;
    console.log(`  Anim: ${animName.padEnd(25)} length=${anim.length}s loop=${loopMode} keyframes=${kfCount}` +
        (animEntry.events ? ` events=${animEntry.events.length}` : ''));
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

function warn(msg) {
    warnings.push(msg);
    console.log(`  [WARN] ${msg}`);
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

const animsJsonPath = path.join(outputDir, 'models', `${entityId}_anims.json`);
fs.writeFileSync(animsJsonPath, JSON.stringify(animsOutput, null, 2), 'utf8');
console.log(`[OK] Animations JSON -> ${animsJsonPath}`);

console.log(`\n--- Generating Resource Pack ---`);

// One shared pack for every EXea model (EXeaEntityCore/pack): this model's folders are replaced,
// other models stay, so the zip always holds all of them.
const EXEA_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(EXEA_ROOT, '..');
const packDir = SERVER_DIR ? path.join(SERVER_DIR, 'pack') : path.join(EXEA_ROOT, 'pack');
for (const sub of [['models', 'item'], ['items'], ['textures', 'item']]) {
    fs.rmSync(path.join(packDir, 'assets', 'exea', ...sub, entityId), { recursive: true, force: true });
}
const modelsDir = path.join(packDir, 'assets', 'exea', 'models', 'item', entityId);
const itemsDir = path.join(packDir, 'assets', 'exea', 'items', entityId);
const texturesDir = path.join(packDir, 'assets', 'exea', 'textures', 'item', entityId);

mkdirp(modelsDir);
mkdirp(itemsDir);
mkdirp(texturesDir);

const packMcmeta = {
    pack: {
        // Free-angle element rotations need 1.21.6+; min/max_format is the 1.21.9+ field.
        pack_format: 75,
        supported_formats: [46, 999],
        min_format: 46,
        max_format: 999,
        description: `EXea Entity Resource Pack - ${entityId}`
    }
};
if (SERVER_DIR) packMcmeta.pack.description = 'EXea entity models';
fs.writeFileSync(path.join(packDir, 'pack.mcmeta'), JSON.stringify(packMcmeta, null, 2), 'utf8');

if (bbmodel.textures && bbmodel.textures.length > 0) {
    const tex = bbmodel.textures[0];
    if (tex.source) {
        const base64Match = tex.source.match(/^data:image\/png;base64,(.*)$/);
        if (base64Match) {
            const pngBuffer = Buffer.from(base64Match[1], 'base64');
            const texFilename = `${entityId}.png`;
            fs.writeFileSync(path.join(texturesDir, texFilename), pngBuffer);
            console.log(`  Texture: ${texFilename} (${pngBuffer.length} bytes)`);
        }
    }
}

const texW = bbmodel.resolution.width;
const texH = bbmodel.resolution.height;

let modelCount = 0;
for (const bone of bones) {
    const boneName = bone.name;
    const group = boneNameToGroup[boneName];

    const directElemUuids = getDirectElementUuids(group.uuid);
    const elements = directElemUuids
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
                uv: [
                    uv[0] / texW * 16,
                    uv[1] / texH * 16,
                    uv[2] / texW * 16,
                    uv[3] / texH * 16
                ],
                texture: '#0'
            };
            if (faceData.rotation) {
                mcFaces[faceName].rotation = faceData.rotation;
            }
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

    const mcModel = {
        credit: 'EXeaEntityCore Blockbench Exporter',
        textures: {
            '0': `exea:item/${entityId}/${entityId}`,
            particle: `exea:item/${entityId}/${entityId}`
        },
        elements: mcElements,
        display: {
            head: {
                translation: [0, 0, 0],
                scale: [1, 1, 1]
            }
        }
    };

    const modelPath = path.join(modelsDir, `${boneName}.json`);
    fs.writeFileSync(modelPath, JSON.stringify(mcModel, null, 2), 'utf8');

    const itemDef = {
        model: {
            type: 'minecraft:model',
            model: `exea:item/${entityId}/${boneName}`
        }
    };
    const itemPath = path.join(itemsDir, `${boneName}.json`);
    fs.writeFileSync(itemPath, JSON.stringify(itemDef, null, 2), 'utf8');

    modelCount++;
}

console.log(`  Generated ${modelCount} bone model files`);
console.log(`  Generated ${modelCount} item definition files`);

if (SERVER_DIR) {
    const metaSrc = path.join(path.dirname(bbmodelPath), `${entityId}_meta.json`);
    if (fs.existsSync(metaSrc)) {
        fs.copyFileSync(metaSrc, path.join(SERVER_DIR, 'models', `${entityId}_meta.json`));
        console.log(`[OK] Meta ${entityId}_meta.json copied`);
    }
    console.log(`\n=== Export Complete! ===`);
    if (warnings.length) console.log(`\n${warnings.length} warning(s): the game will differ from Blockbench there (see [WARN] above).`);
    console.log(`\nWritten to ${SERVER_DIR} (models/ + pack/). In game: /exea reload, then /exea spawn ${entityId}\n`);
    process.exit(0);
}

console.log(`\n--- Creating Resource Pack ZIP ---`);
const zipPath = path.join(outputDir, `exea-resourcepack.zip`);
createZip(packDir, zipPath);
console.log(`[OK] Resource Pack ZIP -> ${zipPath}`);

const mcResPackDir = path.join(process.env.APPDATA || '', '.minecraft', 'resourcepacks');
if (fs.existsSync(mcResPackDir)) {
    const destZip = path.join(mcResPackDir, 'exea-resourcepack.zip');
    fs.copyFileSync(zipPath, destZip);
    console.log(`[OK] Copied to Minecraft -> ${destZip}`);
}

const pluginModelsDir = path.join(EXEA_ROOT, 'src', 'main', 'resources', 'models');
if (fs.existsSync(path.dirname(pluginModelsDir))) {
    mkdirp(pluginModelsDir);
    fs.copyFileSync(bonesJsonPath, path.join(pluginModelsDir, `${entityId}_bones.json`));
    fs.copyFileSync(animsJsonPath, path.join(pluginModelsDir, `${entityId}_anims.json`));
    console.log(`[OK] Copied to plugin resources -> ${pluginModelsDir}`);
}
// Optional per-model settings (name, HP, glow bones) kept next to the .bbmodel.
const metaSrc = path.join(path.dirname(bbmodelPath), `${entityId}_meta.json`);
const metaTargets = [pluginModelsDir, path.join(REPO_ROOT, 'server', 'plugins', 'EXeaEntityCore', 'models')];
if (fs.existsSync(metaSrc)) {
    for (const dir of metaTargets) { mkdirp(dir); fs.copyFileSync(metaSrc, path.join(dir, `${entityId}_meta.json`)); }
    console.log(`[OK] Meta ${entityId}_meta.json copied`);
}

const pluginDataModels = path.join(REPO_ROOT, 'server', 'plugins', 'EXeaEntityCore', 'models');
mkdirp(pluginDataModels);
fs.copyFileSync(bonesJsonPath, path.join(pluginDataModels, `${entityId}_bones.json`));
fs.copyFileSync(animsJsonPath, path.join(pluginDataModels, `${entityId}_anims.json`));
console.log(`[OK] Copied to server plugin data -> ${pluginDataModels}`);

const serverPackDir = path.join(REPO_ROOT, 'server', 'resourcepack');
if (fs.existsSync(serverPackDir)) {
    fs.copyFileSync(zipPath, path.join(serverPackDir, 'exea-resourcepack.zip'));
    console.log(`[OK] Copied to server -> ${serverPackDir}`);
}

console.log(`\n=== Export Complete! ===`);
if (warnings.length) console.log(`\n${warnings.length} warning(s): the game will differ from Blockbench there (see [WARN] above).`);
console.log(`\nVisibility Summary:`);
for (const bone of bones) {
    if (!bone.visible) {
        console.log(`  [HIDDEN] ${bone.name} — will NOT spawn an ItemDisplay in-game`);
    }
}
console.log(`\nNext steps:`);
console.log(`  1. Rebuild plugin:  cd EXeaEntityCore && gradlew.bat jar`);
console.log(`  2. Reload server:   /reload confirm`);
console.log(`  3. Load the resource pack in Minecraft`);
console.log(`  4. /exea spawn ${entityId}\n`);


function mkdirp(dir) {
    fs.mkdirSync(dir, { recursive: true });
}

/** Minimal deflate zip writer (no PowerShell / external tools). */
function createZip(sourceDir, outPath) {
    const zlib = require('zlib');
    const files = [];
    (function walk(dir) {
        for (const name of fs.readdirSync(dir).sort()) {
            const full = path.join(dir, name);
            if (fs.statSync(full).isDirectory()) walk(full);
            else files.push(full);
        }
    })(sourceDir);
    const locals = [], centrals = [];
    let offset = 0;
    for (const full of files) {
        const name = Buffer.from(path.relative(sourceDir, full).split(path.sep).join('/'), 'utf8');
        const data = fs.readFileSync(full);
        const comp = zlib.deflateRawSync(data);
        const crc = zlib.crc32(data) >>> 0;
        const head = Buffer.alloc(30);
        head.writeUInt32LE(0x04034b50, 0); head.writeUInt16LE(20, 4); head.writeUInt16LE(0x0800, 6);
        head.writeUInt16LE(8, 8); head.writeUInt32LE(0, 10); head.writeUInt32LE(crc, 14);
        head.writeUInt32LE(comp.length, 18); head.writeUInt32LE(data.length, 22);
        head.writeUInt16LE(name.length, 26); head.writeUInt16LE(0, 28);
        const cen = Buffer.alloc(46);
        cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6);
        cen.writeUInt16LE(0x0800, 8); cen.writeUInt16LE(8, 10); cen.writeUInt32LE(0, 12);
        cen.writeUInt32LE(crc, 16); cen.writeUInt32LE(comp.length, 20); cen.writeUInt32LE(data.length, 24);
        cen.writeUInt16LE(name.length, 28); cen.writeUInt32LE(offset, 42);
        locals.push(head, name, comp);
        centrals.push(cen, name);
        offset += 30 + name.length + comp.length;
    }
    const cenBuf = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
    end.writeUInt32LE(cenBuf.length, 12); end.writeUInt32LE(offset, 16);
    fs.writeFileSync(outPath, Buffer.concat([...locals, cenBuf, end]));
}
