// Port of core/effect/Shape.java (local frame: x = right, y = up, z = forward; blocks).
// Keep it in step with the plugin when either changes.

export function parseShape(j) {
    const s = j || { type: 'point' };
    const type = String(s.type || 'point').toUpperCase();
    const num = (k, d) => (typeof s[k] === 'number' ? s[k] : d);
    const radius = num('radius', type === 'CONE' || type === 'CIRCLE' ? 3 : 1);
    const length = num('length', 3);
    const height = num('height', type === 'SPIRAL' || type === 'COLUMN' ? 2 : 0);
    return {
        type,
        radius, radiusEnd: num('radius_end', radius),
        width: num('width', type === 'RING' ? 0.2 : 0),
        angle: num('angle', 90),
        length, lengthEnd: num('length_end', length),
        height, heightEnd: num('height_end', height),
        size: Array.isArray(s.size) && s.size.length === 3 ? s.size : [1, 1, 1],
        hollow: !!s.hollow, turns: num('turns', 1), arms: Math.max(1, num('arms', 1) | 0),
        toTarget: s.to === 'target',
    };
}

const lerp = (a, b, t) => a + (b - a) * Math.max(0, Math.min(1, t));
export const radiusOf = (s, p, k) => lerp(s.radius, s.radiusEnd, p) * k;
export const lengthOf = (s, p, k) => lerp(s.length, s.lengthEnd, p) * k;
export const heightOf = (s, p, k) => lerp(s.height, s.heightEnd, p) * k;

/** One random point of the shape. */
export function sample(s, rnd, progress, scale, lineLength = -1) {
    const r = radiusOf(s, progress, scale);
    switch (s.type) {
        case 'SPHERE': {
            const u = rnd() * 2 - 1, phi = rnd() * Math.PI * 2, d = s.hollow ? r : r * Math.cbrt(rnd()), q = Math.sqrt(1 - u * u);
            return [d * q * Math.cos(phi), d * u, d * q * Math.sin(phi)];
        }
        case 'CIRCLE': { const a = rnd() * Math.PI * 2, d = r * Math.sqrt(rnd()); return [d * Math.cos(a), 0, d * Math.sin(a)]; }
        case 'RING': { const a = rnd() * Math.PI * 2, d = r + (rnd() - 0.5) * s.width * scale; return [d * Math.cos(a), 0, d * Math.sin(a)]; }
        case 'CONE': { const a = (rnd() - 0.5) * s.angle * Math.PI / 180, d = r * Math.sqrt(rnd()); return [d * Math.sin(a), 0, d * Math.cos(a)]; }
        case 'LINE': { const len = lineLength >= 0 ? lineLength : lengthOf(s, progress, scale); return [(rnd() - 0.5) * s.width * scale, 0, rnd() * len]; }
        case 'BOX': return [(rnd() - 0.5) * s.size[0] * scale, (rnd() - 0.5) * s.size[1] * scale, (rnd() - 0.5) * s.size[2] * scale];
        case 'COLUMN': { const a = rnd() * Math.PI * 2, d = s.hollow ? r : r * Math.sqrt(rnd()); return [d * Math.cos(a), rnd() * heightOf(s, progress, scale), d * Math.sin(a)]; }
        case 'SPIRAL': {
            const t = rnd(), arm = Math.floor(rnd() * s.arms), a = t * s.turns * Math.PI * 2 + arm * Math.PI * 2 / s.arms;
            return [r * Math.cos(a), t * heightOf(s, progress, scale), r * Math.sin(a)];
        }
        default: return [0, 0, 0];
    }
}

/** n points along the outline (telegraphs), local frame. */
export function outline(s, n, scale) {
    const out = [];
    const r = radiusOf(s, 0, scale);
    if (s.type === 'CONE') {
        const half = s.angle * Math.PI / 360;
        out.push([0, 0, 0]);
        for (let i = 0; i <= n; i++) { const a = -half + (2 * half) * i / n; out.push([r * Math.sin(a), 0, r * Math.cos(a)]); }
        out.push([0, 0, 0]);
    } else if (s.type === 'LINE') {
        const len = lengthOf(s, 0, scale), w = Math.max(0.3, s.width * scale) / 2;
        out.push([-w, 0, 0], [-w, 0, len], [w, 0, len], [w, 0, 0], [-w, 0, 0]);
    } else if (s.type === 'BOX') {
        const hx = s.size[0] * scale / 2, hz = s.size[2] * scale / 2;
        out.push([-hx, 0, -hz], [hx, 0, -hz], [hx, 0, hz], [-hx, 0, hz], [-hx, 0, -hz]);
    } else {
        for (let i = 0; i <= n; i++) { const a = Math.PI * 2 * i / n; out.push([r * Math.cos(a), 0, r * Math.sin(a)]); }
    }
    return out;
}

export function contains(s, x, y, z, scale, flatHeight = 1) {
    const r = radiusOf(s, 0, scale), flat = Math.hypot(x, z), inH = Math.abs(y) <= flatHeight;
    switch (s.type) {
        case 'SPHERE': return x * x + y * y + z * z <= r * r;
        case 'CIRCLE': return inH && flat <= r;
        case 'RING': return inH && Math.abs(flat - r) <= Math.max(0.5, s.width * scale);
        case 'CONE': return inH && flat <= r && (flat < 1e-6 || Math.abs(Math.atan2(x, z) * 180 / Math.PI) <= s.angle / 2);
        case 'LINE': return inH && z >= 0 && z <= lengthOf(s, 0, scale) && Math.abs(x) <= Math.max(0.5, s.width * scale / 2);
        case 'BOX': return Math.abs(x) <= s.size[0] * scale / 2 && Math.abs(y) <= s.size[1] * scale / 2 && Math.abs(z) <= s.size[2] * scale / 2;
        case 'COLUMN': case 'SPIRAL': return flat <= r && y >= -0.5 && y <= heightOf(s, 0, scale);
        default: return x * x + y * y + z * z <= 0.25 * scale * scale;
    }
}

/** Minecraft-yaw frame: local [right, up, forward] → world. */
export function frame(pos, yaw) {
    const r = yaw * Math.PI / 180;
    const fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
    return {
        pos, yaw,
        toWorld: (l) => [pos[0] + l[0] * rx + l[2] * fx, pos[1] + l[1], pos[2] + l[0] * rz + l[2] * fz],
        dir: (l) => [l[0] * rx + l[2] * fx, l[1], l[0] * rz + l[2] * fz],
        forward: [fx, 0, fz],
    };
}
