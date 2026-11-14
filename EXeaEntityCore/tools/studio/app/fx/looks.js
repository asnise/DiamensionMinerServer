// How the preview draws Minecraft particles and blocks without Mojang textures: a colour, size,
// lifetime and drift per particle key, and a flat colour per block. Approximate on purpose.

const P = (color, size, life, opts = {}) => ({ color, size, life, gravity: 0, rise: 0, drag: 0.96, jitter: 0.01, ...opts });

export const PARTICLES = {
    flame: P(0xff8a1f, 0.18, 0.7, { rise: 0.004 }),
    soul_fire_flame: P(0x34e1ff, 0.18, 0.7, { rise: 0.004 }),
    small_flame: P(0xffa040, 0.1, 0.5),
    lava: P(0xff5a00, 0.16, 1.2, { gravity: 0.03, jitter: 0.08 }),
    smoke: P(0x5c5c5c, 0.22, 1.2, { rise: 0.01 }),
    large_smoke: P(0x4a4a4a, 0.42, 1.6, { rise: 0.01 }),
    white_smoke: P(0xd9d9d9, 0.32, 1.6, { rise: 0.004 }),
    campfire_cosy_smoke: P(0xbdbdbd, 0.7, 6, { rise: 0.02, drag: 0.99 }),
    campfire_signal_smoke: P(0xbdbdbd, 0.8, 8, { rise: 0.04, drag: 0.99 }),
    cloud: P(0xf2f2f2, 0.45, 1.2, { drag: 0.9 }),
    poof: P(0xe6e6e6, 0.3, 0.6, { drag: 0.9 }),
    explosion: P(0xe8e8e8, 1.4, 0.4, { jitter: 0 }),
    explosion_emitter: P(0xe8e8e8, 2.2, 0.5, { jitter: 0 }),
    crit: P(0xffe08a, 0.12, 0.5, { gravity: 0.02 }),
    enchanted_hit: P(0x7fd8ff, 0.12, 0.5, { gravity: 0.02 }),
    electric_spark: P(0xdff5ff, 0.08, 0.3),
    end_rod: P(0xffffff, 0.14, 1.4, { drag: 0.98 }),
    portal: P(0xb14cff, 0.12, 1.4),
    reverse_portal: P(0xc77dff, 0.12, 1.2),
    witch: P(0xa63dff, 0.14, 0.9, { rise: 0.01 }),
    enchant: P(0xfaffff, 0.1, 1.2),
    dragon_breath: P(0xd04aff, 0.2, 2),
    sweep_attack: P(0xffffff, 1.2, 0.3, { jitter: 0 }),
    sonic_boom: P(0x7dfff0, 1.2, 0.5, { jitter: 0 }),
    glow: P(0x5fffd6, 0.1, 1.2),
    ash: P(0x777777, 0.08, 3, { gravity: 0.003 }),
    white_ash: P(0xdddddd, 0.08, 3, { gravity: 0.003 }),
    squid_ink: P(0x101018, 0.3, 0.8),
    heart: P(0xff4a6e, 0.3, 1),
    happy_villager: P(0x5cff5c, 0.12, 1),
    angry_villager: P(0x5a1a1a, 0.3, 1),
    snowflake: P(0xffffff, 0.12, 2, { gravity: 0.004 }),
    dust: P(0xff0000, 0.16, 1, { jitter: 0.004 }),
    dust_color_transition: P(0xff0000, 0.16, 1, { jitter: 0.004 }),
    block: P(0x888888, 0.12, 1, { gravity: 0.04, jitter: 0.05 }),
    falling_dust: P(0x888888, 0.12, 2, { gravity: 0.006 }),
    dust_pillar: P(0x888888, 0.16, 1, { rise: 0.02 }),
    item: P(0xcccccc, 0.12, 1, { gravity: 0.04, jitter: 0.05 }),
    entity_effect: P(0xffffff, 0.14, 1, { rise: 0.01 }),
};
export const DEFAULT_PARTICLE = P(0xffffff, 0.15, 1);

export const BLOCKS = {
    ground: 0x6f8f4e, grass_block: 0x6f8f4e, dirt: 0x8a5d3b, coarse_dirt: 0x775338, stone: 0x7d7d7d, cobblestone: 0x6f6f6f,
    deepslate: 0x4b4b52, cobbled_deepslate: 0x4b4b52, andesite: 0x8b8b8b, granite: 0x9b6a5a, diorite: 0xbcbcbc,
    sand: 0xdbcf9c, red_sand: 0xb8662a, gravel: 0x857f7d, netherrack: 0x6e2f2f, blackstone: 0x2a2427, basalt: 0x4a4a52,
    obsidian: 0x14101e, magma_block: 0x9b3d10, end_stone: 0xdcdcab, snow_block: 0xf7ffff, ice: 0x93b7ff,
    black_concrete: 0x08090d, purple_concrete: 0x64209c, red_concrete: 0x8e2020, white_concrete: 0xcfd5d6,
    red_stained_glass: 0xb03030, oak_planks: 0xa2824e, glowstone: 0xf2c45c, sea_lantern: 0xb4d8d0,
};

export function particleLook(key) {
    const k = String(key || '').replace(/^minecraft:/, '').toLowerCase();
    return PARTICLES[k] || DEFAULT_PARTICLE;
}

export function blockColor(key) {
    const k = String(key || 'ground').replace(/^minecraft:/, '').toLowerCase();
    if (BLOCKS[k] !== undefined) return BLOCKS[k];
    if (k.includes('glass')) return 0x9fc5d6;
    if (k.includes('log') || k.includes('wood') || k.includes('plank')) return 0x8b6a3e;
    if (k.includes('ore')) return 0x8d8d8d;
    return 0x8a8a8a;
}

export function hexColor(s, fallback = 0xffffff) {
    if (typeof s !== 'string' || !s.startsWith('#')) return fallback;
    const v = parseInt(s.slice(-6), 16);
    return Number.isFinite(v) ? v : fallback;
}

/** Block list: one key, a list, or "ground". */
export function pickBlock(block, rnd) {
    const list = Array.isArray(block) ? block : [block ?? 'ground'];
    return list.length ? list[Math.floor(rnd() * list.length)] : 'ground';
}
