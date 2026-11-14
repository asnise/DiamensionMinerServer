// The open project: one folder that holds everything you make.
//   <project>/exea-project.json   name, server folder
//   <project>/models/             .bbmodel (or exported _bones.json) + <id>_meta.json + <id>_moveset.json
//   <project>/effects/            effect presets (<id>.json, sub-folders allowed)
// Without a project Studio still works on loose files (the effects folder is then set by hand).
const S = window.studio;

export class Project {
    constructor(store) {
        this.store = store;
        this.data = null;     // { dir, name, server?, ... }
        this.models = [];     // [{ name, file, kind }]
        this.listeners = new Set();
    }

    get open() { return !!this.data; }
    get dir() { return this.data?.dir || null; }
    get name() { return this.data?.name || null; }
    get modelsDir() { return this.dir ? S.path.join(this.dir, 'models') : null; }
    get effectsDir() { return this.dir ? S.path.join(this.dir, 'effects') : null; }
    get serverDir() { return this.data?.server || this.store.get('serverDir', null); }

    onChange(fn) { this.listeners.add(fn); }
    emit() { this.listeners.forEach(f => f(this)); }

    recent() { return this.store.get('recentProjects', []); }

    remember(dir) {
        this.store.set('recentProjects', [dir, ...this.recent().filter(d => d !== dir)].slice(0, 8));
    }

    async create(dir, name) {
        await S.project.create(dir, name);
        return this.load(dir);
    }

    /** Opens a project folder; false if the folder has no exea-project.json. */
    async load(dir) {
        const p = await S.project.read(dir);
        if (!p) return false;
        this.data = p;
        this.remember(p.dir);
        this.store.set('lastProject', p.dir);
        await this.scan();
        this.emit();
        return true;
    }

    close() {
        this.data = null;
        this.models = [];
        this.store.set('lastProject', null);
        this.emit();
    }

    /** Models in models/: every .bbmodel, and exported models (<id>_bones.json) that have no .bbmodel. */
    async scan() {
        if (!this.open) { this.models = []; return; }
        const list = await S.list(this.modelsDir);
        const bb = list.filter(e => !e.dir && /\.bbmodel$/i.test(e.name)).map(e => ({ name: e.name.replace(/\.bbmodel$/i, ''), file: S.path.join(this.modelsDir, e.name), kind: 'bbmodel' }));
        const ex = list.filter(e => !e.dir && /_bones\.json$/i.test(e.name)).map(e => ({ name: e.name.replace(/_bones\.json$/i, ''), file: S.path.join(this.modelsDir, e.name), kind: 'export' }))
            .filter(e => !bb.some(b => b.name.toLowerCase().replace(/\s+/g, '_') === e.name));
        this.models = [...bb, ...ex].sort((a, b) => a.name.localeCompare(b.name));
    }

    async setServer(dir) {
        if (this.open) {
            this.data.server = dir;
            await S.project.write(this.dir, this.data);
        } else {
            this.store.set('serverDir', dir);
        }
        this.emit();
    }

    async importModel(file) {
        if (!this.open) throw new Error('Open or create a project first');
        const r = await S.project.importModel(this.dir, file);
        await this.scan();
        this.emit();
        return r;
    }
}
