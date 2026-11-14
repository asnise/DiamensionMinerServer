const fs = require('fs');
const path = require('path');
const { convert, writeModels, writePack } = require('./lib/convert');

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
const entityId = ARGS[1] || path.basename(bbmodelPath, '.bbmodel').toLowerCase().replace(/\s+/g, '_');
const outputDir = SERVER_DIR || ARGS[2] || path.join(path.dirname(bbmodelPath), 'exea_export');

const bbmodel = JSON.parse(fs.readFileSync(bbmodelPath, 'utf8'));

console.log(`\n=== EXea Entity Exporter ===`);
console.log(`Model: ${bbmodel.name || bbmodelPath}`);
console.log(`Entity ID: ${entityId}`);
console.log(`Output: ${outputDir}`);
console.log(`Groups: ${bbmodel.groups.length}`);
console.log(`Elements: ${bbmodel.elements.length}`);
console.log(`Animations: ${bbmodel.animations.length}`);
console.log(`Texture Resolution: ${bbmodel.resolution.width}x${bbmodel.resolution.height}\n`);

const result = convert(bbmodel, entityId, line => console.log(line));
const warnings = result.warnings;
const { bonesPath: bonesJsonPath, animsPath: animsJsonPath } = writeModels(path.join(outputDir, 'models'), entityId, result);
console.log(`\n[OK] Bones JSON -> ${bonesJsonPath}`);
console.log(`[OK] Animations JSON -> ${animsJsonPath}`);

console.log(`\n--- Generating Resource Pack ---`);
// One shared pack for every EXea model (EXeaEntityCore/pack): this model's folders are replaced,
// other models stay, so the zip always holds all of them.
const EXEA_ROOT = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(EXEA_ROOT, '..');
const packDir = SERVER_DIR ? path.join(SERVER_DIR, 'pack') : path.join(EXEA_ROOT, 'pack');
const modelCount = writePack(packDir, entityId, result,
    SERVER_DIR ? 'EXea entity models' : `EXea Entity Resource Pack - ${entityId}`, line => console.log(line));
console.log(`  Generated ${modelCount} bone model files`);
console.log(`  Generated ${modelCount} item definition files`);

// Optional per-model files kept next to the .bbmodel: settings (name, HP, glow bones) and skills.
const sideFiles = ['meta', 'moveset']
    .map(kind => ({ kind, src: path.join(path.dirname(bbmodelPath), `${entityId}_${kind}.json`) }))
    .filter(f => fs.existsSync(f.src));

if (SERVER_DIR) {
    for (const f of sideFiles) {
        fs.copyFileSync(f.src, path.join(SERVER_DIR, 'models', `${entityId}_${f.kind}.json`));
        console.log(`[OK] ${entityId}_${f.kind}.json copied`);
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
const metaTargets = [pluginModelsDir, path.join(REPO_ROOT, 'server', 'plugins', 'EXeaEntityCore', 'models')];
for (const f of sideFiles) {
    // The moveset only goes to the server data folder: a bundled moveset would change every server's mob.
    const targets = f.kind === 'meta' ? metaTargets : metaTargets.slice(1);
    for (const dir of targets) { mkdirp(dir); fs.copyFileSync(f.src, path.join(dir, `${entityId}_${f.kind}.json`)); }
    console.log(`[OK] ${entityId}_${f.kind}.json copied`);
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
for (const bone of result.bones) {
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
