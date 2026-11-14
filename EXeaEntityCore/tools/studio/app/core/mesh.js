// Builds one BufferGeometry per bone from its elements, the way Minecraft draws an item model:
// per-face UV (+ rotation in 90° steps), element rotation about its origin in X→Y→Z order, one-sided faces.
import * as THREE from 'three';

const DEG = Math.PI / 180;

// Face corners (top-left, top-right, bottom-right, bottom-left as seen from outside the face),
// as indices into [x0|x1, y0|y1, z0|z1]: 0 = from, 1 = to.
const FACES = {
    north: [[1, 1, 0], [0, 1, 0], [0, 0, 0], [1, 0, 0]],
    south: [[0, 1, 1], [1, 1, 1], [1, 0, 1], [0, 0, 1]],
    east: [[1, 1, 1], [1, 1, 0], [1, 0, 0], [1, 0, 1]],
    west: [[0, 1, 0], [0, 1, 1], [0, 0, 1], [0, 0, 0]],
    up: [[0, 1, 0], [1, 1, 0], [1, 1, 1], [0, 1, 1]],
    down: [[0, 0, 1], [1, 0, 1], [1, 0, 0], [0, 0, 0]],
};
const NORMALS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

/** Geometry in blocks, relative to the bone pivot. Null when the bone has no faces. */
export function boneGeometry(bone) {
    const pos = [], uv = [], nor = [];
    const v = new THREE.Vector3();
    const n = new THREE.Vector3();
    for (const el of bone.elements) {
        let rot = null, normalRot = null, o = null;
        if (el.rotation) {
            const r = el.rotation;
            o = new THREE.Vector3(...r.origin);
            rot = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(r.x * DEG, r.y * DEG, r.z * DEG, 'ZYX'));
            normalRot = new THREE.Matrix3().setFromMatrix4(rot);
        }
        const lim = [el.from, el.to];
        for (const [face, f] of Object.entries(el.faces)) {
            const corners = FACES[face];
            if (!corners) continue;
            const [u1, v1, u2, v2] = f.uv;
            const uvc = [[u1, v1], [u2, v1], [u2, v2], [u1, v2]];
            const k = ((Math.round((f.rotation || 0) / 90) % 4) + 4) % 4;
            const p = corners.map(c => {
                v.set(lim[c[0]][0], lim[c[1]][1], lim[c[2]][2]);
                if (rot) v.sub(o).applyMatrix4(rot).add(o);
                return v.clone().multiplyScalar(1 / 16);
            });
            const t = [0, 1, 2, 3].map(i => uvc[(i - k + 4) % 4]);
            n.set(...NORMALS[face]);
            if (normalRot) n.applyMatrix3(normalRot);
            // two triangles: TL, BL, BR and TL, BR, TR (counter-clockwise from outside)
            for (const i of [0, 3, 2, 0, 2, 1]) {
                pos.push(p[i].x, p[i].y, p[i].z);
                uv.push(t[i][0], 1 - t[i][1]);
                nor.push(n.x, n.y, n.z);
            }
        }
    }
    if (pos.length === 0) return null;
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
    g.computeBoundingBox();
    g.computeBoundingSphere();
    return g;
}

export function loadTexture(texture) {
    if (!texture) return Promise.resolve(null);
    return new Promise((resolve) => {
        new THREE.TextureLoader().load(texture.url, (t) => {
            t.magFilter = THREE.NearestFilter;
            t.minFilter = THREE.NearestFilter;
            t.generateMipmaps = false;
            t.colorSpace = THREE.SRGBColorSpace;
            resolve(t);
        }, undefined, () => resolve(null));
    });
}
