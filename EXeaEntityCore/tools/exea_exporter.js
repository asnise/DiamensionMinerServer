const fs = require('fs');
const path = require('path');

const ARGS = process.argv.slice(2);
if (ARGS.length < 1) {
    console.log('Usage: node exea_exporter.js <path/to/model.bbmodel> [entity_id] [output_dir]');
    console.log('  entity_id   defaults to model filename lowercase');
    console.log('  output_dir  defaults to ./exea_export/');
    process.exit(1);
}

const bbmodelPath = ARGS[0];
const rawEntityId = ARGS[1] || path.basename(bbmodelPath, '.bbmodel').toLowerCase().replace(/\s+/g, '_');
const entityId = rawEntityId;
const outputDir = ARGS[2] || path.join(path.dirname(bbmodelPath), 'exea_export');

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
        return el && el.export !== false;
    });

    const parentUuid = parentMap[group.uuid];
    const parentName = parentUuid ? (groupUuidToName[parentUuid] || null) : null;

    const isVisible = group.visibility !== false;

    const bone = {
        name: boneName,
        original_name: group.name,
        parent: parentName,
        origin: group.origin || [0, 0, 0],
        rotation: group.rotation || [0, 0, 0],
        custom_model_data: cmdCounter++,
        element_count: visibleElements.length,
        visible: isVisible
    };

    bones.push(bone);
    console.log(`  Bone: ${boneName.padEnd(30)} elements=${visibleElements.length} visible=${isVisible} cmd=${bone.custom_model_data}`);
}

const bonesJsonPath = path.join(outputDir, 'models', `${entityId}_bones.json`);
mkdirp(path.dirname(bonesJsonPath));
fs.writeFileSync(bonesJsonPath, JSON.stringify(bones, null, 2), 'utf8');
console.log(`\n[OK] Bones JSON -> ${bonesJsonPath}`);

console.log(`\n--- Exporting Animations ---`);
const animsOutput = {};

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
            const time = kf.time;
            const dp = kf.data_points[0];

            const x = parseFloat(dp.x) || 0;
            const y = parseFloat(dp.y) || 0;
            const z = parseFloat(dp.z) || 0;

            boneKfs.push({
                channel: channel,
                time: parseFloat(time.toFixed(5)),
                vector: [
                    parseFloat(x.toFixed(5)),
                    parseFloat(y.toFixed(5)),
                    parseFloat(z.toFixed(5))
                ]
            });
            kfCount++;
        }

        boneKfs.sort((a, b) => a.time - b.time || a.channel.localeCompare(b.channel));
        animEntry.bones[boneName] = boneKfs;
    }

    animsOutput[animName] = animEntry;
    console.log(`  Anim: ${animName.padEnd(25)} length=${anim.length}s loop=${loopMode} keyframes=${kfCount}`);
}

const animsJsonPath = path.join(outputDir, 'models', `${entityId}_anims.json`);
fs.writeFileSync(animsJsonPath, JSON.stringify(animsOutput, null, 2), 'utf8');
console.log(`[OK] Animations JSON -> ${animsJsonPath}`);

console.log(`\n--- Generating Resource Pack ---`);

const packDir = path.join(outputDir, 'resourcepack');
const modelsDir = path.join(packDir, 'assets', 'exea', 'models', 'item', entityId);
const itemsDir = path.join(packDir, 'assets', 'exea', 'items', entityId);
const texturesDir = path.join(packDir, 'assets', 'exea', 'textures', 'item', entityId);

mkdirp(modelsDir);
mkdirp(itemsDir);
mkdirp(texturesDir);

const packMcmeta = {
    pack: {
        pack_format: 61,
        description: `EXea Entity Resource Pack - ${entityId}`
    }
};
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
        .filter(el => el && el.export !== false);

    const mcElements = [];
    for (const el of elements) {
        const from = el.from.map(v => v - bone.origin[0] === v - bone.origin[0] ? v : v);

        const localFrom = [
            el.from[0] - bone.origin[0] + 8,
            el.from[1] - bone.origin[1] + 8,
            el.from[2] - bone.origin[2] + 8
        ];
        const localTo = [
            el.to[0] - bone.origin[0] + 8,
            el.to[1] - bone.origin[1] + 8,
            el.to[2] - bone.origin[2] + 8
        ];

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
            const elOriginLocal = [
                (el.origin[0] || 0) - bone.origin[0] + 8,
                (el.origin[1] || 0) - bone.origin[1] + 8,
                (el.origin[2] || 0) - bone.origin[2] + 8
            ];

            let axis = 'y';
            let angle = 0;
            if (el.rotation[0] !== 0) { axis = 'x'; angle = el.rotation[0]; }
            else if (el.rotation[1] !== 0) { axis = 'y'; angle = el.rotation[1]; }
            else if (el.rotation[2] !== 0) { axis = 'z'; angle = el.rotation[2]; }

            angle = Math.max(-45, Math.min(45, angle));

            mcElement.rotation = {
                origin: elOriginLocal.map(v => parseFloat(v.toFixed(4))),
                axis: axis,
                angle: parseFloat(angle.toFixed(4))
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

const pluginModelsDir = path.join(path.dirname(bbmodelPath), '..', '..', 'EXeaEntityCore', 'src', 'main', 'resources', 'models');
if (fs.existsSync(path.dirname(pluginModelsDir))) {
    mkdirp(pluginModelsDir);
    fs.copyFileSync(bonesJsonPath, path.join(pluginModelsDir, `${entityId}_bones.json`));
    fs.copyFileSync(animsJsonPath, path.join(pluginModelsDir, `${entityId}_anims.json`));
    console.log(`[OK] Copied to plugin resources -> ${pluginModelsDir}`);
}

const pluginDataModels = path.join(path.dirname(bbmodelPath), '..', '..', 'server', 'plugins', 'EXeaEntityCore', 'models');
mkdirp(pluginDataModels);
fs.copyFileSync(bonesJsonPath, path.join(pluginDataModels, `${entityId}_bones.json`));
fs.copyFileSync(animsJsonPath, path.join(pluginDataModels, `${entityId}_anims.json`));
console.log(`[OK] Copied to server plugin data -> ${pluginDataModels}`);

console.log(`\n=== Export Complete! ===`);
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

function createZip(sourceDir, outPath) {
    const { execSync } = require('child_process');
    try {
        execSync(`powershell -NoProfile -Command "Compress-Archive -Path '${sourceDir}\\*' -DestinationPath '${outPath}' -Force"`, { stdio: 'pipe' });
    } catch (e) {
        console.error('ZIP creation failed:', e.message);
    }
}
