// Particle and block textures for the preview, read from the user's own Minecraft client jar (main
// process, mcjar.js). Nothing is copied into Studio. Without a jar the preview falls back to colours.
import * as THREE from 'three';

const S = window.studio;
const CELL = 16, GRID = 64; // 1024 px atlas, 4096 tiles

/** Particles whose sprite is picked once at random (vanilla does that) instead of animated by age. */
const RANDOM_SPRITE = new Set(['enchant', 'portal', 'reverse_portal', 'nautilus', 'effect', 'glitter']);
/** Sprites that are already coloured: drawn untinted. */
export const COLORED = new Set(['flame', 'soul_fire_flame', 'small_flame', 'copper_fire_flame', 'lava', 'heart', 'angry_villager',
    'sonic_boom', 'note', 'damage_indicator', 'totem_of_undying', 'cherry_leaves', 'pale_oak_leaves', 'sculk_soul', 'sculk_charge', 'shriek']);

export class McTextures {
    constructor() {
        this.info = null;
        this.defs = new Map();        // particle key → [texture names]
        this.tiles = new Map();       // texture path → tile index (or -1 while loading)
        this.next = 0;
        this.blocks = new Map();      // block key → { top, side, bottom } THREE textures (null = not found)
        this.canvas = document.createElement('canvas');
        this.canvas.width = this.canvas.height = CELL * GRID;
        this.ctx2d = this.canvas.getContext('2d');
        this.ctx2d.imageSmoothingEnabled = false;
        this.atlas = new THREE.CanvasTexture(this.canvas);
        this.atlas.magFilter = THREE.NearestFilter;
        this.atlas.minFilter = THREE.NearestFilter;
        this.atlas.generateMipmaps = false;
        this.atlas.colorSpace = THREE.SRGBColorSpace;
        this.onChange = null;
    }

    async init() {
        this.info = await S.mc.info();
        this.defs.clear();
        if (!this.info) return this.info;
        const files = await S.mc.list('assets/minecraft/particles/');
        await Promise.all(files.filter(f => f.endsWith('.json')).map(async (f) => {
            try {
                const j = JSON.parse(atob(await S.mc.read(f)));
                if (Array.isArray(j.textures)) this.defs.set(f.slice(f.lastIndexOf('/') + 1, -5), j.textures.map(t => t.replace(/^minecraft:/, '')));
            } catch { /* skip */ }
        }));
        return this.info;
    }

    async pickJar() {
        const info = await S.mc.pickJar();
        if (!info) return null;
        this.tiles.clear();
        this.blocks.clear();
        this.next = 0;
        this.ctx2d.clearRect(0, 0, this.canvas.width, this.canvas.height);
        await this.init();
        this.onChange?.();
        return info;
    }

    get ready() { return !!this.info; }

    /** Tile index of a texture in the atlas; -1 while it loads or if missing (then the caller draws a dot). */
    tile(texPath) {
        if (!this.ready) return -1;
        if (this.tiles.has(texPath)) return this.tiles.get(texPath);
        this.tiles.set(texPath, -1);
        if (this.next >= GRID * GRID) return -1;
        S.mc.read(texPath).then((b64) => {
            if (!b64) return;
            const img = new Image();
            img.onload = () => {
                const i = this.next++;
                const side = Math.min(img.width, img.height); // animated strips: first frame
                this.ctx2d.drawImage(img, 0, 0, img.width, side, (i % GRID) * CELL, Math.floor(i / GRID) * CELL, CELL, CELL);
                this.atlas.needsUpdate = true;
                this.tiles.set(texPath, i);
            };
            img.src = 'data:image/png;base64,' + b64;
        });
        return -1;
    }

    /**
     * Sprites of a particle: { tiles, random } (tiles may contain -1 while loading), or null when the
     * game has no sprite for it (block particles use blockTile).
     */
    particle(key) {
        const k = String(key || '').replace(/^minecraft:/, '').toLowerCase();
        const names = this.defs.get(k);
        if (!names || !names.length) return null;
        return { tiles: names.map(n => this.tile(`assets/minecraft/textures/particle/${n}.png`)), random: RANDOM_SPRITE.has(k) };
    }

    /** A block's texture as one particle tile (block / falling_dust particles). */
    blockTile(key) {
        const k = blockKey(key);
        return this.tile(`assets/minecraft/textures/block/${k === 'grass_block' ? 'dirt' : k}.png`);
    }

    /** Top / side / bottom textures of a block, or null while loading / not found. */
    block(key) {
        if (!this.ready) return null;
        const k = blockKey(key);
        if (this.blocks.has(k)) return this.blocks.get(k);
        this.blocks.set(k, null);
        const load = async (names) => {
            for (const n of names) {
                const b64 = await S.mc.read(`assets/minecraft/textures/block/${n}.png`);
                if (b64) {
                    const t = await new THREE.TextureLoader().loadAsync('data:image/png;base64,' + b64);
                    t.magFilter = THREE.NearestFilter;
                    t.minFilter = THREE.NearestFilter;
                    t.colorSpace = THREE.SRGBColorSpace;
                    // animated textures (magma, sea lantern) are strips: show the first frame
                    if (t.image.height > t.image.width) { t.repeat.set(1, t.image.width / t.image.height); t.offset.set(0, 1 - t.image.width / t.image.height); }
                    return t;
                }
            }
            return null;
        };
        (async () => {
            const side = await load([k, k + '_side', k + '_front', k + '_top']);
            if (!side) return;
            const top = (await load([k + '_top', k])) || side;
            const bottom = (await load([k + '_bottom', k === 'grass_block' ? 'dirt' : k + '_top', k])) || side;
            this.blocks.set(k, { top, side, bottom, grass: k === 'grass_block' });
            this.onChange?.();
        })();
        return null;
    }
}

/** "ground" previews as grass; minecraft: prefix dropped. */
function blockKey(key) {
    const k = String(key || 'ground').replace(/^minecraft:/, '').toLowerCase();
    return k === 'ground' ? 'grass_block' : k;
}

export { CELL, GRID };
