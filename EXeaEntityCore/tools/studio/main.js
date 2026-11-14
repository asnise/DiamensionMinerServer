// EXea Studio main process: window, file dialogs, file access and file watching for the renderer.
const { app, BrowserWindow, dialog, ipcMain, shell, Menu } = require('electron');
const fs = require('fs');
const path = require('path');

let win = null;
const watchers = new Map();

function createWindow() {
    win = new BrowserWindow({
        width: 1480,
        height: 900,
        minWidth: 980,
        minHeight: 600,
        backgroundColor: '#1e1e1e',
        title: 'EXea Studio',
        autoHideMenuBar: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
    });
    Menu.setApplicationMenu(null);
    // A model path on the command line (or a file dropped on the exe) is opened at start.
    const arg = process.argv.slice(1).find(a => /\.(bbmodel|json)$/i.test(a) && fs.existsSync(a));
    const query = {};
    if (arg) query.open = path.resolve(arg);
    const mode = process.argv.find(a => a.startsWith('--mode='));
    if (mode) query.mode = mode.slice(7);
    for (const k of ['fx', 'layer', 'skill']) {
        const a = process.argv.find(x => x.startsWith(`--${k}=`));
        if (a) query[k] = a.slice(k.length + 3);
    }
    for (const [flag, key] of [['--live-url=', 'liveUrl'], ['--live-token=', 'liveToken']]) {
        const a = process.argv.find(x => x.startsWith(flag));
        if (a) query[key] = a.slice(flag.length);
    }
    if (process.argv.includes('--send-live')) { query.sendLive = '1'; query.mode = 'entity'; }
    const exp = process.argv.find(x => x.startsWith('--export-to='));
    if (exp) { query.exportTo = path.resolve(exp.slice(12)); query.mode = 'entity'; }
    win.loadFile(path.join(__dirname, 'app', 'index.html'), { query });
    // Links open in the user's browser, never inside the app.
    win.webContents.setWindowOpenHandler(({ url }) => {
        if (/^https?:/.test(url)) shell.openExternal(url);
        return { action: 'deny' };
    });
    win.webContents.on('before-input-event', (_e, input) => {
        if (input.type === 'keyDown' && input.key === 'F12') win.webContents.toggleDevTools();
        if (input.type === 'keyDown' && input.key === 'F5' && input.control) win.webContents.reloadIgnoringCache();
    });
}

app.whenReady().then(() => {
    createWindow();
    // Self-check for development: EXEA_SHOT=<png> [EXEA_SHOT_DELAY=ms] saves a screenshot and quits.
    if (process.env.EXEA_SHOT) {
        win.webContents.on('console-message', (e) => console.log('[page]', e.message));
        win.webContents.once('did-finish-load', () => setTimeout(async () => {
            // EXEA_EVAL=<js>: runs in the page first (UI tests, e.g. simulated drags)
            if (process.env.EXEA_EVAL) {
                try { console.log('[eval]', await win.webContents.executeJavaScript(process.env.EXEA_EVAL)); } catch (e) { console.log('[eval error]', e.message); }
                await new Promise(r => setTimeout(r, 600));
            }
            const img = await win.webContents.capturePage();
            fs.writeFileSync(process.env.EXEA_SHOT, img.toPNG());
            app.quit();
        }, Number(process.env.EXEA_SHOT_DELAY) || 2500));
    }
    app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => {
    for (const w of watchers.values()) w.close();
    if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('dialog:open', async (_e, opts = {}) => {
    const res = await dialog.showOpenDialog(win, {
        title: opts.title || 'Open model',
        properties: opts.directory ? ['openDirectory', 'createDirectory', 'promptToCreate'] : ['openFile'],
        defaultPath: opts.defaultPath,
        filters: opts.filters || [
            { name: 'EXea models', extensions: ['bbmodel', 'json'] },
            { name: 'All files', extensions: ['*'] },
        ],
    });
    return res.canceled ? null : res.filePaths[0];
});

ipcMain.handle('fs:readText', (_e, p) => fs.promises.readFile(p, 'utf8'));
ipcMain.handle('fs:readBase64', async (_e, p) => (await fs.promises.readFile(p)).toString('base64'));
ipcMain.handle('fs:exists', (_e, p) => fs.existsSync(p));

// ---------------------------------------------------------------- projects
// A project is a folder: exea-project.json, models/ (.bbmodel + _meta + _moveset), effects/ (presets).

ipcMain.handle('project:create', async (_e, dir, name) => {
    const file = path.join(dir, 'exea-project.json');
    if (fs.existsSync(file)) throw new Error('That folder already has a project; use Open Project');
    for (const sub of ['models', 'effects']) await fs.promises.mkdir(path.join(dir, sub), { recursive: true });
    const proj = { format: 1, name: name || path.basename(dir), created: new Date().toISOString().slice(0, 10) };
    await fs.promises.writeFile(file, JSON.stringify(proj, null, 2) + '\n', 'utf8');
    return proj;
});

/** Reads a project folder (or its exea-project.json); null if it is not a project. */
ipcMain.handle('project:read', async (_e, p) => {
    const dir = /exea-project\.json$/i.test(p) ? path.dirname(p) : p;
    try {
        const proj = JSON.parse(await fs.promises.readFile(path.join(dir, 'exea-project.json'), 'utf8'));
        for (const sub of ['models', 'effects']) await fs.promises.mkdir(path.join(dir, sub), { recursive: true });
        return { dir, ...proj };
    } catch {
        return null;
    }
});

ipcMain.handle('project:write', async (_e, dir, data) => {
    const { dir: _d, ...rest } = data;
    await fs.promises.writeFile(path.join(dir, 'exea-project.json'), JSON.stringify(rest, null, 2) + '\n', 'utf8');
    return true;
});

/** Copies a model (.bbmodel or exported _bones.json) and its side files into a project's models/ folder. */
ipcMain.handle('project:importModel', async (_e, projectDir, file) => {
    const dest = path.join(projectDir, 'models');
    await fs.promises.mkdir(dest, { recursive: true });
    const srcDir = path.dirname(file);
    const copied = [];
    const copy = async (src) => {
        const to = path.join(dest, path.basename(src));
        if (path.resolve(src) === path.resolve(to)) return;
        await fs.promises.copyFile(src, to);
        copied.push(path.basename(src));
    };
    let id;
    if (/\.bbmodel$/i.test(file)) {
        await copy(file);
        id = path.basename(file, '.bbmodel').toLowerCase().replace(/\s+/g, '_');
    } else {
        id = path.basename(file).replace(/_(bones|anims|meta|moveset)\.json$/i, '');
        for (const k of ['bones', 'anims']) { const f = path.join(srcDir, `${id}_${k}.json`); if (fs.existsSync(f)) await copy(f); }
    }
    // side files: <id>_meta.json / _moveset.json, or the only *_meta.json next to it
    const names = await fs.promises.readdir(srcDir);
    for (const n of names) {
        if (n === `${id}_meta.json` || n === `${id}_moveset.json`) await copy(path.join(srcDir, n));
    }
    if (!names.includes(`${id}_meta.json`)) {
        const metas = names.filter(n => /_meta\.json$/i.test(n));
        if (metas.length === 1) {
            await copy(path.join(srcDir, metas[0]));
            const mid = metas[0].replace(/_meta\.json$/i, '');
            if (names.includes(`${mid}_moveset.json`)) await copy(path.join(srcDir, `${mid}_moveset.json`));
        }
    }
    return { copied, file: path.join(dest, path.basename(file)) };
});

ipcMain.handle('shell:open', async (_e, p) => {
    if (!p || !fs.existsSync(p)) throw new Error('Folder not found: ' + p);
    const err = await shell.openPath(p);
    if (err) throw new Error(err);
    return true;
});

ipcMain.handle('app:quit', () => app.quit());
ipcMain.handle('fs:list', async (_e, dir) => {
    try {
        return (await fs.promises.readdir(dir, { withFileTypes: true }))
            .map(d => ({ name: d.name, dir: d.isDirectory() }));
    } catch {
        return [];
    }
});

// Re-sends 'file:changed' when a watched file is saved (e.g. from Blockbench). Saves often come as
// several events in a row, so they are merged over 250 ms.
ipcMain.handle('fs:watch', (_e, files) => {
    for (const w of watchers.values()) w.close();
    watchers.clear();
    for (const f of files || []) {
        if (!fs.existsSync(f)) continue;
        let timer = null;
        const w = fs.watch(f, () => {
            clearTimeout(timer);
            timer = setTimeout(() => win && win.webContents.send('file:changed', f), 250);
        });
        watchers.set(f, w);
    }
    return true;
});

ipcMain.handle('app:devtools', () => win?.webContents.toggleDevTools());
ipcMain.handle('app:info', () => ({ version: app.getVersion(), platform: process.platform }));

// Bundled presets and schemas: next to the exe when packaged, the plugin sources when run from the repo.
function bundled(name, repoRel) {
    const packaged = path.join(process.resourcesPath || '', name);
    if (app.isPackaged && fs.existsSync(packaged)) return packaged;
    return path.resolve(__dirname, repoRel);
}
const PRESETS_DIR = bundled('presets', '../../src/main/resources/effects');
const SCHEMAS_DIR = bundled('schemas', '../../schemas');

ipcMain.handle('app:paths', () => ({ presets: PRESETS_DIR, schemas: SCHEMAS_DIR }));

/** Every .json under a folder (sub-folders make ids like fire/burst), with its text. */
ipcMain.handle('fs:readJsonTree', async (_e, dir) => {
    const out = [];
    async function walk(d, rel) {
        let entries = [];
        try { entries = await fs.promises.readdir(d, { withFileTypes: true }); } catch { return; }
        for (const en of entries) {
            const full = path.join(d, en.name);
            const r = rel ? rel + '/' + en.name : en.name;
            if (en.isDirectory()) await walk(full, r);
            else if (en.name.toLowerCase().endsWith('.json')) out.push({ id: r.slice(0, -5).toLowerCase(), file: full, text: await fs.promises.readFile(full, 'utf8') });
        }
    }
    await walk(dir, '');
    return out;
});

// Writes only .json files, only where the user chose (a save dialog or their effects folder).
ipcMain.handle('fs:writeJson', async (_e, p, text) => {
    if (!/\.json$/i.test(p)) throw new Error('Only .json files can be written');
    await fs.promises.mkdir(path.dirname(p), { recursive: true });
    await fs.promises.writeFile(p, text, 'utf8');
    return true;
});

ipcMain.handle('dialog:save', async (_e, opts = {}) => {
    const res = await dialog.showSaveDialog(win, {
        title: opts.title || 'Save',
        defaultPath: opts.defaultPath,
        filters: [{ name: 'JSON', extensions: ['json'] }],
    });
    return res.canceled ? null : res.filePath;
});

// JSON Schema validation (the same schemas the plugin docs and CI use).
let ajvValidators = null;
function validators() {
    if (ajvValidators) return ajvValidators;
    const Ajv2020 = require('ajv/dist/2020').default;
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    for (const f of ['common', 'effect', 'moveset', 'meta']) {
        ajv.addSchema(JSON.parse(fs.readFileSync(path.join(SCHEMAS_DIR, f + '.schema.json'), 'utf8')));
    }
    const base = 'https://github.com/asnise/EXeaEntityCore/schemas/';
    ajvValidators = {
        effect: ajv.getSchema(base + 'effect.schema.json'),
        moveset: ajv.getSchema(base + 'moveset.schema.json'),
        meta: ajv.getSchema(base + 'meta.schema.json'),
    };
    return ajvValidators;
}
ipcMain.handle('schema:validate', (_e, kind, data) => {
    const v = validators()[kind];
    if (!v) return [{ path: '', message: 'unknown schema ' + kind }];
    return v(data) ? [] : v.errors.map(e => ({ path: e.instancePath, message: e.message + (e.params?.allowedValues ? ': ' + e.params.allowedValues.join(', ') : '') }));
});
// Minecraft client assets for the effect preview (particle and block textures), read from the
// user's own installed game. Chosen jar is remembered in a small file next to the user data.
const { findJars, Jar } = require('./mcjar');
let mcJar = null;
const mcPrefFile = () => path.join(app.getPath('userData'), 'minecraft-jar.txt');
function openJar(file) {
    try {
        mcJar = new Jar(file);
        return true;
    } catch (e) {
        mcJar = null;
        return false;
    }
}
ipcMain.handle('mc:info', () => {
    if (!mcJar) {
        let saved = null;
        try { saved = fs.readFileSync(mcPrefFile(), 'utf8').trim(); } catch { /* none yet */ }
        const candidates = [saved, ...findJars('26.2').map(j => j.file)].filter(f => f && fs.existsSync(f));
        for (const f of candidates) if (openJar(f)) break;
    }
    return mcJar ? { file: mcJar.file, version: path.basename(mcJar.file, '.jar') } : null;
});
ipcMain.handle('mc:pickJar', async () => {
    const res = await dialog.showOpenDialog(win, {
        title: 'Minecraft client jar (.minecraft/versions/<version>/<version>.jar)',
        defaultPath: path.join(process.env.APPDATA || '', '.minecraft', 'versions'),
        filters: [{ name: 'Minecraft jar', extensions: ['jar'] }], properties: ['openFile'],
    });
    if (res.canceled || !openJar(res.filePaths[0])) return null;
    try { fs.writeFileSync(mcPrefFile(), mcJar.file); } catch { /* not remembered */ }
    return { file: mcJar.file, version: path.basename(mcJar.file, '.jar') };
});
ipcMain.handle('mc:list', (_e, prefix) => (mcJar ? mcJar.list(prefix) : []));
ipcMain.handle('mc:read', (_e, name) => {
    const b = mcJar ? mcJar.read(name) : null;
    return b ? b.toString('base64') : null;
});

// Export to a server's plugins/EXeaEntityCore folder with the same converter as the CLI exporter.
const EXPORTER_DIR = bundled('exporter', '../exporter/lib');
ipcMain.handle('export:model', async (_e, job) => {
    // job: { bbmodel?, exportedDir?, packDir?, id, serverDir, meta?, moveset?, effects?: [{ id, file }] }
    const { convert, writeModels, writePack } = require(path.join(EXPORTER_DIR, 'convert.js'));
    const out = [];
    const modelsDir = path.join(job.serverDir, 'models');
    await fs.promises.mkdir(modelsDir, { recursive: true });
    if (job.bbmodel) {
        const bb = JSON.parse(await fs.promises.readFile(job.bbmodel, 'utf8'));
        const res = convert(bb, job.id, () => {});
        writeModels(modelsDir, job.id, res);
        const n = writePack(path.join(job.serverDir, 'pack'), job.id, res, 'EXea entity models');
        out.push(`models/${job.id}_bones.json + _anims.json`, `pack: ${n} bone models` + (res.texture ? ' + texture' : ''));
        if (res.warnings.length) out.push(`${res.warnings.length} animation warning(s)`);
    } else if (job.exportedDir && path.resolve(job.exportedDir) !== path.resolve(modelsDir)) {
        // Already exported elsewhere: copy the model files and its pack folders.
        for (const k of ['bones', 'anims']) {
            const src = path.join(job.exportedDir, `${job.id}_${k}.json`);
            if (fs.existsSync(src)) await fs.promises.copyFile(src, path.join(modelsDir, `${job.id}_${k}.json`));
        }
        if (job.packDir) {
            for (const sub of [['models', 'item'], ['items'], ['textures', 'item']]) {
                const src = path.join(job.packDir, 'assets', 'exea', ...sub, job.id);
                if (fs.existsSync(src)) await fs.promises.cp(src, path.join(job.serverDir, 'pack', 'assets', 'exea', ...sub, job.id), { recursive: true });
            }
        }
        out.push('model files copied');
    }
    for (const kind of ['meta', 'moveset']) {
        if (job[kind]) {
            await fs.promises.writeFile(path.join(modelsDir, `${job.id}_${kind}.json`), JSON.stringify(job[kind], null, 2) + '\n', 'utf8');
            out.push(`models/${job.id}_${kind}.json`);
        }
    }
    for (const fx of job.effects || []) {
        const dest = path.join(job.serverDir, 'effects', fx.id + '.json');
        if (path.resolve(fx.file) === path.resolve(dest)) continue;
        await fs.promises.mkdir(path.dirname(dest), { recursive: true });
        await fs.promises.copyFile(fx.file, dest);
        out.push(`effects/${fx.id}.json`);
    }
    return out;
});

// ---------------------------------------------------------------- live link (server's /api/)

/** The same files as export:model, in memory, as { rel (path in plugins/EXeaEntityCore), data }. */
async function exportFiles(job) {
    const { convert } = require(path.join(EXPORTER_DIR, 'convert.js'));
    const files = [];
    const json = (o) => Buffer.from(JSON.stringify(o, null, 2), 'utf8');
    const packRel = (sub, name) => `pack/assets/exea/${sub}/${job.id}/${name}`;
    if (job.bbmodel) {
        const res = convert(JSON.parse(await fs.promises.readFile(job.bbmodel, 'utf8')), job.id, () => {});
        files.push({ rel: `models/${job.id}_bones.json`, data: json(res.bones) }, { rel: `models/${job.id}_anims.json`, data: json(res.anims) });
        for (const [bone, m] of Object.entries(res.models)) {
            files.push({ rel: packRel('models/item', bone + '.json'), data: json(m) }, { rel: packRel('items', bone + '.json'), data: json(res.items[bone]) });
        }
        if (res.texture) files.push({ rel: packRel('textures/item', job.id + '.png'), data: res.texture });
    } else if (job.exportedDir) {
        for (const k of ['bones', 'anims']) {
            const src = path.join(job.exportedDir, `${job.id}_${k}.json`);
            if (fs.existsSync(src)) files.push({ rel: `models/${job.id}_${k}.json`, data: await fs.promises.readFile(src) });
        }
        for (const sub of ['models/item', 'items', 'textures/item']) {
            const dir = job.packDir && path.join(job.packDir, 'assets', 'exea', ...sub.split('/'), job.id);
            if (!dir || !fs.existsSync(dir)) continue;
            for (const f of await fs.promises.readdir(dir)) files.push({ rel: packRel(sub, f), data: await fs.promises.readFile(path.join(dir, f)) });
        }
    }
    for (const kind of ['meta', 'moveset']) if (job[kind]) files.push({ rel: `models/${job.id}_${kind}.json`, data: json(job[kind]) });
    for (const fx of job.effects || []) files.push({ rel: `effects/${fx.id}.json`, data: await fs.promises.readFile(fx.file) });
    for (const fx of job.inlineEffects || []) files.push({ rel: `effects/${fx.id}.json`, data: json(fx.def) }); // not saved yet
    return files;
}

async function liveFetch(link, method, apiPath, body, contentType = 'application/json') {
    const base = String(link.url || 'http://localhost:8086').replace(/\/+$/, '');
    const res = await fetch(`${base}/api/${apiPath}`, {
        method, body, headers: { Authorization: `Bearer ${link.token || ''}`, ...(body !== undefined ? { 'Content-Type': contentType } : {}) },
        signal: AbortSignal.timeout(15000),
    });
    let data;
    try { data = await res.json(); } catch { data = {}; }
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
}

ipcMain.handle('live:call', async (_e, link, method, apiPath, body) =>
    liveFetch(link, method, apiPath, body === undefined ? undefined : JSON.stringify(body)));

/** Uploads a model / moveset / effects job, then reloads. Returns the reload status + a log. */
ipcMain.handle('live:send', async (_e, link, job) => {
    const files = await exportFiles(job);
    const log = [];
    if (job.bbmodel || job.exportedDir) log.push((await liveFetch(link, 'POST', `clear?model=${encodeURIComponent(job.id)}`)).ok);
    for (const f of files) await liveFetch(link, 'PUT', `file?path=${encodeURIComponent(f.rel)}`, f.data, 'application/octet-stream');
    log.push(`${files.length} file(s) uploaded`);
    const status = await liveFetch(link, 'POST', 'reload', '{}');
    log.push(status.pack || 'reloaded');
    return { status, log };
});

/** The token in a server folder's config.yml (live_link.token), so the user does not copy it by hand. */
ipcMain.handle('live:tokenFrom', async (_e, serverDir) => {
    try {
        const cfg = await fs.promises.readFile(path.join(serverDir, 'config.yml'), 'utf8');
        const m = cfg.match(/live_link:[\s\S]*?\n\s+token:\s*"?([^"\n]+)"?/);
        const enabled = /live_link:[\s\S]*?\n\s+enabled:\s*true/.test(cfg);
        return m ? { token: m[1].trim(), enabled } : null;
    } catch {
        return null;
    }
});

ipcMain.handle('schema:get', (_e, name) => JSON.parse(fs.readFileSync(path.join(SCHEMAS_DIR, name + '.schema.json'), 'utf8')));
