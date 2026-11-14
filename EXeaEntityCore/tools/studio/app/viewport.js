// The 3D canvas: scene, camera, model meshes posed every frame, overlays (grid, hitbox, pivots, selection).
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { boneGeometry, loadTexture } from './core/mesh.js';

/** Vanilla hitbox [width, height] in blocks at scale 1, for the meta "driver" key. */
export const DRIVERS = {
    husk: [0.6, 1.95], zombie: [0.6, 1.95], drowned: [0.6, 1.95], zombie_villager: [0.6, 1.95],
    skeleton: [0.6, 1.99], stray: [0.6, 1.99], wither_skeleton: [0.7, 2.4], piglin: [0.6, 1.95],
    piglin_brute: [0.6, 1.95], vindicator: [0.6, 1.95], pillager: [0.6, 1.95], evoker: [0.6, 1.95],
    armor_stand: [0.5, 1.975], iron_golem: [1.4, 2.7], ravager: [1.95, 2.2], warden: [0.9, 2.9],
    enderman: [0.6, 2.9], blaze: [0.6, 1.8], spider: [1.4, 0.9], cave_spider: [0.7, 0.5],
    wolf: [0.6, 0.85], hoglin: [1.3965, 1.4], zoglin: [1.3965, 1.4], polar_bear: [1.4, 1.4],
    vex: [0.4, 0.8], allay: [0.35, 0.6], creeper: [0.6, 1.7], witch: [0.6, 1.95], villager: [0.6, 1.95],
};

export class Viewport {
    constructor(el) {
        this.el = el;
        this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
        this.renderer.setPixelRatio(window.devicePixelRatio);
        this.renderer.outputColorSpace = THREE.SRGBColorSpace;
        el.appendChild(this.renderer.domElement);

        this.scene = new THREE.Scene();
        this.camera = new THREE.PerspectiveCamera(45, 1, 0.05, 500);
        this.camera.position.set(3.5, 2.6, 5.5);
        this.controls = new OrbitControls(this.camera, this.renderer.domElement);
        this.controls.target.set(0, 1, 0);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.12;
        this.controls.screenSpacePanning = true;

        this.scene.add(new THREE.HemisphereLight(0xffffff, 0x50505a, 1.6));
        const sun = new THREE.DirectionalLight(0xffffff, 1.4);
        sun.position.set(4, 8, 6);
        this.scene.add(sun);

        this.grid = this.makeGrid();
        this.scene.add(this.grid);
        this.facing = this.makeFacingArrow();
        this.scene.add(this.facing);

        this.modelRoot = new THREE.Group();
        this.scene.add(this.modelRoot);
        this.overlay = new THREE.Group();
        this.scene.add(this.overlay);
        this.fxGroup = new THREE.Group();
        this.scene.add(this.fxGroup);

        this.meshes = {};      // bone name -> Mesh
        this.pivots = {};      // bone name -> marker
        this.hitbox = null;
        this.selBox = new THREE.BoxHelper(undefined, 0x0d99ff);
        this.selBox.visible = false;
        this.selBox.material.depthTest = false;
        this.selBox.renderOrder = 10;
        this.overlay.add(this.selBox);
        this.view = { grid: true, hitbox: true, pivots: false, wire: false };
        this.selected = null;
        this.fx = [];
        this.onPick = null;

        this.raycaster = new THREE.Raycaster();
        let down = null;
        this.renderer.domElement.addEventListener('pointerdown', e => { down = [e.clientX, e.clientY]; });
        this.renderer.domElement.addEventListener('pointerup', e => {
            if (!down || Math.hypot(e.clientX - down[0], e.clientY - down[1]) > 4) return;
            this.pick(e);
        });
        new ResizeObserver(() => this.resize()).observe(el);
        this.resize();
        this.setTheme('dark');
    }

    setTheme(theme) {
        const dark = theme !== 'light';
        this.scene.background = new THREE.Color(dark ? 0x1e1e1e : 0xf5f5f5);
        this.grid.children[0].material.color.set(dark ? 0x2e2e2e : 0xe2e2e2);
        this.grid.children[1].material.color.set(dark ? 0x454545 : 0xc8c8c8);
    }

    makeGrid() {
        const g = new THREE.Group();
        const lines = (step, size, color) => {
            const pts = [];
            for (let i = -size; i <= size; i += step) {
                pts.push(-size, 0, i, size, 0, i, i, 0, -size, i, 0, size);
            }
            const geo = new THREE.BufferGeometry();
            geo.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
            return new THREE.LineSegments(geo, new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.9 }));
        };
        g.add(lines(1, 16, 0x2e2e2e));      // one block
        g.add(lines(16, 16, 0x454545));     // one chunk edge
        const axes = new THREE.BufferGeometry();
        axes.setAttribute('position', new THREE.Float32BufferAttribute([-16, 0.001, 0, 16, 0.001, 0, 0, 0.001, -16, 0, 0.001, 16], 3));
        axes.setAttribute('color', new THREE.Float32BufferAttribute([0.95, 0.28, 0.13, 0.95, 0.28, 0.13, 0.05, 0.6, 1, 0.05, 0.6, 1], 3));
        g.add(new THREE.LineSegments(axes, new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.55 })));
        return g;
    }

    /** Small arrow on the floor: where a mob with this body yaw looks (+Z at yaw 0). */
    makeFacingArrow() {
        const shape = new THREE.Shape();
        shape.moveTo(-0.12, 0); shape.lineTo(0.12, 0); shape.lineTo(0, 0.28); shape.lineTo(-0.12, 0);
        const m = new THREE.Mesh(new THREE.ShapeGeometry(shape), new THREE.MeshBasicMaterial({ color: 0x0d99ff, side: THREE.DoubleSide, transparent: true, opacity: 0.9 }));
        m.rotation.x = Math.PI / 2;
        m.position.set(0, 0.002, 0.45);
        const g = new THREE.Group();
        g.add(m);
        return g;
    }

    async setModel(model) {
        this.clearModel();
        this.model = model;
        if (!model) return;
        const tex = await loadTexture(model.texture);
        const glow = new Set((model.meta?.glow_bones || []).map(s => String(s).toLowerCase()));
        for (const b of model.bones) {
            const geo = boneGeometry(b);
            if (!geo) continue;
            const opts = { map: tex, alphaTest: 0.1, side: THREE.FrontSide };
            if (!tex) opts.color = 0x9a9aa2;
            const mat = glow.has(b.name) ? new THREE.MeshBasicMaterial(opts) : new THREE.MeshLambertMaterial(opts);
            const mesh = new THREE.Mesh(geo, mat);
            mesh.matrixAutoUpdate = false;
            mesh.userData.bone = b.name;
            mesh.visible = b.visible;
            this.modelRoot.add(mesh);
            this.meshes[b.name] = mesh;
        }
        const pivotGeo = new THREE.SphereGeometry(0.035, 10, 8);
        for (const b of model.bones) {
            const p = new THREE.Mesh(pivotGeo, new THREE.MeshBasicMaterial({ color: b.elements.length ? 0xffc021 : 0x9747ff, depthTest: false }));
            p.matrixAutoUpdate = false;
            p.renderOrder = 11;
            p.visible = this.view.pivots;
            this.overlay.add(p);
            this.pivots[b.name] = p;
        }
        this.setHitbox(model.meta);
        this.applyView();
    }

    clearModel() {
        for (const m of Object.values(this.meshes)) { m.geometry.dispose(); m.material.map?.dispose?.(); m.material.dispose(); this.modelRoot.remove(m); }
        for (const p of Object.values(this.pivots)) { p.material.dispose(); this.overlay.remove(p); }
        if (this.hitbox) { this.overlay.remove(this.hitbox); this.hitbox = null; }
        this.meshes = {};
        this.pivots = {};
        this.selBox.visible = false;
        this.selected = null;
    }

    setHitbox(meta) {
        if (this.hitbox) this.overlay.remove(this.hitbox);
        const driver = String(meta?.driver || 'husk').toLowerCase().replace('minecraft:', '');
        const [w, h] = DRIVERS[driver] || DRIVERS.husk;
        const s = Number(meta?.hitbox_scale) || 1;
        const geo = new THREE.EdgesGeometry(new THREE.BoxGeometry(w * s, h * s, w * s));
        this.hitbox = new THREE.LineSegments(geo, new THREE.LineDashedMaterial({ color: 0xf24822, dashSize: 0.08, gapSize: 0.05 }));
        this.hitbox.computeLineDistances();
        this.hitbox.position.y = h * s / 2;
        this.hitbox.userData = { driver, w: w * s, h: h * s };
        this.hitbox.visible = this.view.hitbox;
        this.overlay.add(this.hitbox);
    }

    setView(key, on) {
        this.view[key] = on;
        this.applyView();
    }

    applyView() {
        this.grid.visible = this.view.grid;
        this.facing.visible = this.view.grid;
        if (this.hitbox) this.hitbox.visible = this.view.hitbox;
        for (const p of Object.values(this.pivots)) p.visible = this.view.pivots;
        for (const m of Object.values(this.meshes)) m.material.wireframe = this.view.wire;
    }

    setBoneVisible(name, on) {
        if (this.meshes[name]) this.meshes[name].visible = on;
    }

    /** Places every bone mesh / pivot at its posed matrix. */
    applyPose(pose, yaw) {
        for (const [name, m] of Object.entries(pose)) {
            if (this.meshes[name]) { this.meshes[name].matrix.copy(m); this.meshes[name].matrixWorldNeedsUpdate = true; }
            if (this.pivots[name]) { this.pivots[name].matrix.copy(m); this.pivots[name].matrixWorldNeedsUpdate = true; }
        }
        this.facing.rotation.y = -yaw * Math.PI / 180;
        if (this.selected && this.meshes[this.selected]) {
            this.meshes[this.selected].updateMatrixWorld(true);
            this.selBox.setFromObject(this.meshes[this.selected]);
            this.selBox.visible = true;
        } else this.selBox.visible = false;
        this.lastPose = pose;
    }

    select(name) {
        this.selected = name;
        for (const [n, p] of Object.entries(this.pivots)) {
            p.scale.setScalar(n === name ? 1.8 : 1);
            p.visible = this.view.pivots || n === name;
        }
    }

    pick(e) {
        this.onPick?.(this.boneAt(e.clientX, e.clientY));
    }

    /** The bone whose mesh is under a screen point, or null. */
    boneAt(clientX, clientY) {
        const r = this.renderer.domElement.getBoundingClientRect();
        const ndc = new THREE.Vector2(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
        this.modelRoot.updateMatrixWorld(true);
        this.raycaster.setFromCamera(ndc, this.camera);
        const hits = this.raycaster.intersectObjects(Object.values(this.meshes).filter(m => m.visible), false);
        return hits.length ? hits[0].object.userData.bone : null;
    }

    /** Frames the posed model (or the hitbox when empty). */
    fit() {
        const box = new THREE.Box3();
        for (const m of Object.values(this.meshes)) if (m.visible) { m.updateMatrixWorld(true); box.expandByObject(m); }
        if (box.isEmpty()) box.setFromCenterAndSize(new THREE.Vector3(0, 1, 0), new THREE.Vector3(1, 2, 1));
        const size = box.getSize(new THREE.Vector3());
        const center = box.getCenter(new THREE.Vector3());
        const radius = Math.max(size.x, size.y, size.z) * 0.6 + 0.3;
        const dist = radius / Math.sin(this.camera.fov * Math.PI / 360);
        // From the front-right: the model faces +Z in game space.
        const dir = new THREE.Vector3(0.55, 0.35, 1).normalize();
        this.controls.target.copy(center);
        this.camera.position.copy(center).addScaledVector(dir, dist);
        this.camera.near = Math.max(0.01, dist / 200);
        this.camera.far = dist * 50;
        this.camera.updateProjectionMatrix();
        this.controls.update();
    }

    /** Short-lived marker for an animation event (hit area, sound, particle) at a bone. */
    flashEvent(ev, matrix, yaw) {
        const pos = new THREE.Vector3();
        if (matrix) pos.setFromMatrixPosition(matrix);
        else pos.set(0, 1, 0);
        let obj;
        if (ev.kind === 'hit') {
            const r = ev.radius || 3;
            const cone = Math.min(360, ev.cone || 360);
            const geo = new THREE.RingGeometry(Math.max(0.01, r - 0.08), r, 48, 1, 0, cone * Math.PI / 180);
            obj = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: 0xf24822, side: THREE.DoubleSide, transparent: true, opacity: 0.9, depthTest: false }));
            const fill = new THREE.Mesh(new THREE.CircleGeometry(r, 48, 0, cone * Math.PI / 180), new THREE.MeshBasicMaterial({ color: 0xf24822, side: THREE.DoubleSide, transparent: true, opacity: 0.18, depthTest: false }));
            obj.add(fill);
            // Lying flat (local +Y -> world -Z), the angle phi points at (cos phi, 0, -sin phi). The body
            // faces (-sin yaw, 0, cos yaw), i.e. phi = -90° - yaw; the sector is centred on it.
            obj.rotation.x = -Math.PI / 2;
            obj.rotation.z = (-90 - yaw - cone / 2) * Math.PI / 180;
            obj.position.copy(pos);
        } else {
            const color = ev.kind === 'sound' ? 0x9747ff : ev.kind === 'particle' ? 0x14ae5c : 0xffc021;
            obj = new THREE.Mesh(new THREE.SphereGeometry(0.12, 12, 8), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.9, depthTest: false }));
            obj.position.copy(pos);
        }
        obj.renderOrder = 12;
        this.fxGroup.add(obj);
        this.fx.push({ obj, t: 0, life: 0.9 });
    }

    resize() {
        const w = this.el.clientWidth || 1, h = this.el.clientHeight || 1;
        this.renderer.setSize(w, h, false);
        this.renderer.domElement.style.width = w + 'px';
        this.renderer.domElement.style.height = h + 'px';
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
    }

    render(dt) {
        for (let i = this.fx.length - 1; i >= 0; i--) {
            const f = this.fx[i];
            f.t += dt;
            const k = 1 - f.t / f.life;
            f.obj.traverse(o => { if (o.material) o.material.opacity = Math.max(0, k) * (o.material.userData.base ??= o.material.opacity); });
            if (f.t >= f.life) {
                f.obj.traverse(o => { o.geometry?.dispose(); o.material?.dispose(); });
                this.fxGroup.remove(f.obj);
                this.fx.splice(i, 1);
            }
        }
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
    }
}
