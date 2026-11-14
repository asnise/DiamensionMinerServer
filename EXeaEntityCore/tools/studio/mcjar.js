// Reads assets straight out of the Minecraft client jar installed on this computer, in memory.
// Nothing from the jar is copied to disk or shipped with EXea Studio: Mojang's textures stay in the
// user's own game install and are only used to draw the preview.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

/** Client jars in the default launcher folder, newest first, preferring {@code prefer} (e.g. "26.2"). */
function findJars(prefer) {
    const base = path.join(process.env.APPDATA || '', '.minecraft', 'versions');
    let dirs = [];
    try {
        dirs = fs.readdirSync(base, { withFileTypes: true }).filter(d => d.isDirectory()).map(d => d.name);
    } catch {
        return [];
    }
    const jars = dirs
        .filter(n => /^\d+(\.\d+)+$/.test(n)) // plain releases, not loader profiles
        .map(n => ({ version: n, file: path.join(base, n, n + '.jar') }))
        .filter(j => fs.existsSync(j.file));
    const num = v => v.split('.').map(Number);
    jars.sort((a, b) => {
        if (a.version === prefer) return -1;
        if (b.version === prefer) return 1;
        const x = num(a.version), y = num(b.version);
        for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] || 0) !== (y[i] || 0)) return (y[i] || 0) - (x[i] || 0);
        return 0;
    });
    return jars;
}

class Jar {
    constructor(file) {
        this.file = file;
        this.buf = fs.readFileSync(file);
        this.entries = new Map();
        this.index();
    }

    /** Central directory → name → { method, size, offset }. */
    index() {
        const b = this.buf;
        let eocd = -1;
        for (let i = b.length - 22; i >= Math.max(0, b.length - 65557); i--) {
            if (b.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new Error('not a zip file');
        const count = b.readUInt16LE(eocd + 10);
        let p = b.readUInt32LE(eocd + 16);
        for (let i = 0; i < count; i++) {
            if (b.readUInt32LE(p) !== 0x02014b50) break;
            const method = b.readUInt16LE(p + 10), csize = b.readUInt32LE(p + 20);
            const nlen = b.readUInt16LE(p + 28), elen = b.readUInt16LE(p + 30), clen = b.readUInt16LE(p + 32);
            const offset = b.readUInt32LE(p + 42);
            const name = b.toString('utf8', p + 46, p + 46 + nlen);
            this.entries.set(name, { method, csize, offset });
            p += 46 + nlen + elen + clen;
        }
    }

    read(name) {
        const e = this.entries.get(name);
        if (!e) return null;
        const b = this.buf;
        const start = e.offset + 30 + b.readUInt16LE(e.offset + 26) + b.readUInt16LE(e.offset + 28);
        const data = b.subarray(start, start + e.csize);
        return e.method === 0 ? Buffer.from(data) : zlib.inflateRawSync(data);
    }

    list(prefix) {
        return [...this.entries.keys()].filter(n => n.startsWith(prefix));
    }
}

module.exports = { findJars, Jar };
