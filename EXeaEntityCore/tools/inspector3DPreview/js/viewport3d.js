/**
 * EXea 3D Viewport
 * Three.js WebGL Scene, Forward Kinematics Hierarchy, Element UV Mapping & Raycasting
 */
class EXeaViewport3D {
    constructor(canvasId) {
        this.canvas = document.getElementById(canvasId);
        this.container = this.canvas.parentElement;

        this.scene = null;
        this.camera = null;
        this.renderer = null;
        this.controls = null;

        // Display mode: 'exea_mc' | 'bb_raw' | 'skeleton_only'
        this.viewMode = 'exea_mc';

        // Options
        this.showGrid = true;
        this.showBones = true;
        this.showWireframe = false;
        this.showBoundingBox = true;
        this.showTextures = true;

        // Hierarchy and Objects
        this.currentModel = null;
        this.modelRoot = new THREE.Group();
        this.boneGroups = {}; // boneName -> THREE.Group
        this.bonePivots = {}; // boneName -> THREE.AxesHelper / marker
        this.cubeMeshes = []; // array of all THREE.Mesh cubes
        this.selectedBone = null;

        // Texture and Materials
        this.loadedTexture = null;
        this.texturedMaterial = null;
        this.plainMaterial = null;
        this.wireframeMaterial = null;
        this.selectionBoxHelper = null;

        // Hierarchy Lines Helper
        this.skeletonLines = null;

        // Callbacks
        this.onBoneSelected = null;
        this.onFpsUpdate = null;
        this.onCameraUpdate = null;

        // Animation timing
        this.lastTime = performance.now();
        this.frameCount = 0;
        this.fpsTimer = performance.now();

        this.init();
    }

    init() {
        // 1. Scene
        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0x0e1017);
        this.scene.add(this.modelRoot);

        // 2. Camera
        const aspect = this.container.clientWidth / this.container.clientHeight;
        this.camera = new THREE.PerspectiveCamera(45, aspect, 0.1, 2000);
        this.resetCamera();

        // 3. WebGL Renderer
        this.renderer = new THREE.WebGLRenderer({
            canvas: this.canvas,
            antialias: true,
            alpha: true,
            powerPreference: 'high-performance'
        });
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.renderer.setSize(this.container.clientWidth, this.container.clientHeight);
        this.renderer.outputEncoding = THREE.sRGBEncoding;

        // 4. OrbitControls
        this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.08;
        this.controls.target.set(0, 24, 0); // Model center (around 1.5 blocks high)
        this.controls.update();

        // 5. Lighting
        const ambientLight = new THREE.AmbientLight(0xffffff, 0.65);
        this.scene.add(ambientLight);

        const dirLightFront = new THREE.DirectionalLight(0xffffff, 0.8);
        dirLightFront.position.set(30, 80, 50);
        this.scene.add(dirLightFront);

        const dirLightBack = new THREE.DirectionalLight(0xaaccff, 0.4);
        dirLightBack.position.set(-30, 40, -50);
        this.scene.add(dirLightBack);

        // 6. Grid & Helpers
        // Minecraft Grid: 64 blocks wide (1024 units), step 16 units (1 block)
        this.gridHelper = new THREE.GridHelper(512, 32, 0x00e5ff, 0x1f2438);
        this.gridHelper.position.y = 0;
        this.scene.add(this.gridHelper);

        // Selection Box Helper
        this.selectionBoxHelper = new THREE.BoxHelper(new THREE.Mesh(), 0x00e5ff);
        this.selectionBoxHelper.visible = false;
        this.scene.add(this.selectionBoxHelper);

        // 7. Base Materials
        this.plainMaterial = new THREE.MeshLambertMaterial({ color: 0x90a4ae });
        this.wireframeMaterial = new THREE.MeshBasicMaterial({ color: 0x00e5ff, wireframe: true });

        // 8. Event Listeners
        window.addEventListener('resize', () => this.onResize());
        this.canvas.addEventListener('pointerdown', (e) => this.onPointerDown(e));

        // Start render loop
        this.renderLoop();
    }

    resetCamera() {
        this.camera.position.set(0, 32, 75);
        if (this.controls) {
            this.controls.target.set(0, 24, 0);
            this.controls.update();
        }
    }

    setCameraView(viewName) {
        if (viewName === 'front') {
            this.camera.position.set(0, 24, 80);
            this.controls.target.set(0, 24, 0);
        } else if (viewName === 'side') {
            this.camera.position.set(80, 24, 0);
            this.controls.target.set(0, 24, 0);
        } else if (viewName === 'top') {
            this.camera.position.set(0, 100, 0.001);
            this.controls.target.set(0, 24, 0);
        }
        this.controls.update();
    }

    onResize() {
        if (!this.container) return;
        const width = this.container.clientWidth;
        const height = this.container.clientHeight;
        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(width, height);
    }

    /**
     * Load Normalized Model into 3D Scene
     * @param {Object} modelData 
     */
    loadModel(modelData) {
        this.currentModel = modelData;
        this.clearModel();

        // 1. Setup Texture
        if (modelData.textureDataUrl) {
            const img = new Image();
            img.src = modelData.textureDataUrl;
            const texture = new THREE.Texture(img);
            img.onload = () => {
                texture.needsUpdate = true;
                texture.magFilter = THREE.NearestFilter; // Minecraft pixel crispness!
                texture.minFilter = THREE.NearestFilter;
            };
            this.loadedTexture = texture;
            this.texturedMaterial = new THREE.MeshLambertMaterial({
                map: texture,
                transparent: true,
                alphaTest: 0.1
            });
        } else {
            this.texturedMaterial = this.plainMaterial;
        }

        // 2. Build Bone Hierarchy Groups
        const boneMap = {};
        modelData.bones.forEach(b => {
            const group = new THREE.Group();
            group.name = `bone_${b.name}`;
            group.userData = {
                isBone: true,
                boneData: b,
                origin: new THREE.Vector3(...b.origin),
                defaultRotation: new THREE.Vector3(...b.rotation),
                visibleState: b.visible
            };
            boneMap[b.name] = group;
            this.boneGroups[b.name] = group;

            // Add Bone Pivot Axes Gizmo
            const axes = new THREE.AxesHelper(4);
            axes.visible = this.showBones;
            axes.name = `axes_${b.name}`;
            group.add(axes);
            this.bonePivots[b.name] = axes;

            // Build Cubes in this Bone
            if (Array.isArray(b.elements)) {
                b.elements.forEach(elem => {
                    const cubeMesh = this.createCubeMesh(elem, b, modelData.resolution);
                    if (cubeMesh) {
                        group.add(cubeMesh);
                        this.cubeMeshes.push(cubeMesh);
                    }
                });
            }
        });

        // 3. Assemble Parent-Child Hierarchy
        modelData.bones.forEach(b => {
            const group = boneMap[b.name];
            if (b.parent && boneMap[b.parent]) {
                boneMap[b.parent].add(group);
            } else {
                this.modelRoot.add(group);
            }
        });

        // 4. Update initial Rest Transforms
        this.updateTransforms(null, 0);
        this.applyViewMode();
    }

    clearModel() {
        while (this.modelRoot.children.length > 0) {
            const obj = this.modelRoot.children[0];
            this.modelRoot.remove(obj);
        }
        this.boneGroups = {};
        this.bonePivots = {};
        this.cubeMeshes = [];
        this.selectedBone = null;
        if (this.selectionBoxHelper) {
            this.selectionBoxHelper.visible = false;
        }
    }

    /**
     * Creates a textured box mesh matching Blockbench/Minecraft UV & face orientation
     */
    createCubeMesh(element, bone, resolution) {
        const from = element.from || [0, 0, 0];
        const to = element.to || [1, 1, 1];
        const origin = element.origin || bone.origin;
        const rot = element.rotation || [0, 0, 0];

        const dx = Math.abs(to[0] - from[0]);
        const dy = Math.abs(to[1] - from[1]);
        const dz = Math.abs(to[2] - from[2]);
        if (dx === 0 && dy === 0 && dz === 0) return null;

        const geometry = new THREE.BoxGeometry(dx, dy, dz);

        // Setup Custom UV Mapping for Minecraft / Blockbench
        this.mapBoxUVs(geometry, element.faces, resolution, dx, dy, dz);

        const mesh = new THREE.Mesh(geometry, this.showTextures ? this.texturedMaterial : this.plainMaterial);
        mesh.name = `cube_${element.uuid || element.name}`;
        mesh.userData = {
            isCube: true,
            boneName: bone.name,
            elementData: element
        };

        // In Blockbench:
        // Cube coordinates (from, to) are relative to bone origin in our local hierarchy!
        // Center of cube in bone-local space:
        const center = [
            (from[0] + to[0]) / 2 - bone.origin[0],
            (from[1] + to[1]) / 2 - bone.origin[1],
            (from[2] + to[2]) / 2 - bone.origin[2]
        ];

        // Element local rotation
        const hasElementRot = Math.abs(rot[0]) > 0.001 || Math.abs(rot[1]) > 0.001 || Math.abs(rot[2]) > 0.001;

        if (hasElementRot) {
            const pivotContainer = new THREE.Group();
            pivotContainer.name = `pivot_${element.name || 'elem'}`;
            // Pivot in bone local coords
            pivotContainer.position.set(
                origin[0] - bone.origin[0],
                origin[1] - bone.origin[1],
                origin[2] - bone.origin[2]
            );
            pivotContainer.rotation.set(
                THREE.MathUtils.degToRad(rot[0]),
                THREE.MathUtils.degToRad(rot[1]),
                THREE.MathUtils.degToRad(rot[2]),
                'XYZ'
            );

            // Mesh position relative to element pivot
            mesh.position.set(
                (from[0] + to[0]) / 2 - origin[0],
                (from[1] + to[1]) / 2 - origin[1],
                (from[2] + to[2]) / 2 - origin[2]
            );

            pivotContainer.add(mesh);
            return pivotContainer;
        } else {
            mesh.position.set(center[0], center[1], center[2]);
            return mesh;
        }
    }

    /**
     * Map BoxGeometry UVs matching Minecraft Face Order:
     * Three.js Box Face Indices:
     * 0: +X (Right / East)
     * 1: -X (Left / West)
     * 2: +Y (Top / Up)
     * 3: -Y (Bottom / Down)
     * 4: +Z (Front / South)
     * 5: -Z (Back / North)
     */
    mapBoxUVs(geometry, faces, resolution, dx, dy, dz) {
        if (!faces) return;
        const resW = (resolution && resolution.width) || 128;
        const resH = (resolution && resolution.height) || 128;

        const faceMap = {
            east:  { index: 0 },
            west:  { index: 1 },
            up:    { index: 2 },
            down:  { index: 3 },
            south: { index: 4 },
            north: { index: 5 }
        };

        const uvAttribute = geometry.attributes.uv;
        const uvs = uvAttribute.array;

        for (const [faceKey, faceData] of Object.entries(faces)) {
            const target = faceMap[faceKey];
            if (!target || !faceData.uv) continue;

            const rawUv = faceData.uv; // [u1, v1, u2, v2]
            const u1 = rawUv[0] / resW;
            const v1 = 1.0 - (rawUv[1] / resH);
            const u2 = rawUv[2] / resW;
            const v2 = 1.0 - (rawUv[3] / resH);

            const offset = target.index * 8; // 4 vertices * 2 floats
            // BoxGeometry face vertices: (Top-Left, Bottom-Left, Top-Right, Bottom-Right)
            if (faceKey === 'up') {
                uvs[offset + 0] = u1; uvs[offset + 1] = v2;
                uvs[offset + 2] = u1; uvs[offset + 3] = v1;
                uvs[offset + 4] = u2; uvs[offset + 5] = v2;
                uvs[offset + 6] = u2; uvs[offset + 7] = v1;
            } else if (faceKey === 'down') {
                uvs[offset + 0] = u1; uvs[offset + 1] = v1;
                uvs[offset + 2] = u1; uvs[offset + 3] = v2;
                uvs[offset + 4] = u2; uvs[offset + 5] = v1;
                uvs[offset + 6] = u2; uvs[offset + 7] = v2;
            } else {
                uvs[offset + 0] = u1; uvs[offset + 1] = v1;
                uvs[offset + 2] = u1; uvs[offset + 3] = v2;
                uvs[offset + 4] = u2; uvs[offset + 5] = v1;
                uvs[offset + 6] = u2; uvs[offset + 7] = v2;
            }
        }
        uvAttribute.needsUpdate = true;
    }

    /**
     * Update Forward Kinematics Transforms for all bones
     * 1:1 Parity with DisplayRenderer.java & AnimationController.java
     */
    updateTransforms(animationEngine, currentTime) {
        if (!this.currentModel) return;

        this.currentModel.bones.forEach(b => {
            const group = this.boneGroups[b.name];
            if (!group) return;

            // 1. Calculate relative pivot (Offset from parent pivot)
            let relPivot = new THREE.Vector3(...b.origin);
            if (b.parent && this.boneGroups[b.parent]) {
                const parentGroup = this.boneGroups[b.parent];
                relPivot.sub(parentGroup.userData.origin);
            }

            // 2. Evaluate Animation Channels (Pos, Rot, Scale)
            let animPos = new THREE.Vector3(0, 0, 0);
            let animRot = new THREE.Quaternion();
            let animScale = new THREE.Vector3(1, 1, 1);

            if (animationEngine && animationEngine.activeTimeline) {
                animPos = animationEngine.evaluatePosition(b.name, currentTime);
                animRot = animationEngine.evaluateRotation(b.name, currentTime);
                animScale = animationEngine.evaluateScale(b.name, currentTime);
            }

            // 3. Set Position
            group.position.copy(relPivot).add(animPos);

            // 4. Default Rest Rotation
            const defRot = new THREE.Euler(
                THREE.MathUtils.degToRad(b.rotation[0]),
                THREE.MathUtils.degToRad(b.rotation[1]),
                THREE.MathUtils.degToRad(b.rotation[2]),
                'XYZ'
            );
            const defQuat = new THREE.Quaternion().setFromEuler(defRot);

            // 5. Combined Rotation: defQuat * animRot
            defQuat.multiply(animRot);
            group.quaternion.copy(defQuat);

            // 6. Scale
            group.scale.copy(animScale);
        });

        // Update bounding box if selected bone active
        if (this.selectedBone && this.boneGroups[this.selectedBone] && this.showBoundingBox) {
            const selectedGroup = this.boneGroups[this.selectedBone];
            this.selectionBoxHelper.setFromObject(selectedGroup);
            this.selectionBoxHelper.visible = true;
        }
    }

    /**
     * Set View Mode:
     * 'exea_mc': Hides bones with visible: false (matches ItemDisplay in Paper)
     * 'bb_raw': Shows all bones & elements
     * 'skeleton_only': Hides meshes, only shows bone gizmos & wireframes
     */
    setViewMode(mode) {
        this.viewMode = mode;
        this.applyViewMode();
    }

    applyViewMode() {
        if (!this.currentModel) return;

        this.currentModel.bones.forEach(b => {
            const group = this.boneGroups[b.name];
            if (!group) return;

            if (this.viewMode === 'skeleton_only') {
                this.setGroupCubesVisibility(group, false);
            } else if (this.viewMode === 'exea_mc') {
                // In Minecraft, DisplayRenderer only spawns an ItemDisplay if visible === true and has elements!
                const isRenderedInMC = b.visible !== false && (b.elements && b.elements.length > 0);
                this.setGroupCubesVisibility(group, isRenderedInMC);
            } else {
                // 'bb_raw': show all
                this.setGroupCubesVisibility(group, true);
            }
        });
    }

    setGroupCubesVisibility(group, visible) {
        group.traverse(child => {
            if (child.isMesh && child.userData.isCube) {
                child.visible = visible;
            }
        });
    }

    selectBone(boneName) {
        this.selectedBone = boneName;
        if (!boneName || !this.boneGroups[boneName]) {
            this.selectionBoxHelper.visible = false;
            return;
        }

        const group = this.boneGroups[boneName];
        if (this.showBoundingBox) {
            this.selectionBoxHelper.setFromObject(group);
            this.selectionBoxHelper.visible = true;
        }

        if (this.onBoneSelected) {
            this.onBoneSelected(boneName);
        }
    }

    onPointerDown(event) {
        if (event.button !== 0) return; // Only left click

        const rect = this.canvas.getBoundingClientRect();
        const mouse = new THREE.Vector2(
            ((event.clientX - rect.left) / rect.width) * 2 - 1,
            -((event.clientY - rect.top) / rect.height) * 2 + 1
        );

        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(mouse, this.camera);

        const intersects = raycaster.intersectObjects(this.cubeMeshes, true);
        if (intersects.length > 0) {
            const hitMesh = intersects[0].object;
            const boneName = hitMesh.userData.boneName;
            if (boneName) {
                this.selectBone(boneName);
            }
        }
    }

    // Toggle Helpers
    toggleGrid(val) {
        this.showGrid = val;
        this.gridHelper.visible = val;
    }

    toggleBones(val) {
        this.showBones = val;
        for (const axes of Object.values(this.bonePivots)) {
            axes.visible = val;
        }
    }

    toggleWireframe(val) {
        this.showWireframe = val;
        this.cubeMeshes.forEach(mesh => {
            mesh.material = val ? this.wireframeMaterial : (this.showTextures ? this.texturedMaterial : this.plainMaterial);
        });
    }

    toggleTextures(val) {
        this.showTextures = val;
        if (!this.showWireframe) {
            this.cubeMeshes.forEach(mesh => {
                mesh.material = val ? this.texturedMaterial : this.plainMaterial;
            });
        }
    }

    toggleBoundingBox(val) {
        this.showBoundingBox = val;
        if (!val) {
            this.selectionBoxHelper.visible = false;
        } else if (this.selectedBone && this.boneGroups[this.selectedBone]) {
            this.selectionBoxHelper.setFromObject(this.boneGroups[this.selectedBone]);
            this.selectionBoxHelper.visible = true;
        }
    }

    renderLoop() {
        requestAnimationFrame(() => this.renderLoop());

        this.controls.update();
        this.renderer.render(this.scene, this.camera);

        // FPS meter
        this.frameCount++;
        const now = performance.now();
        if (now - this.fpsTimer >= 500) {
            const fps = Math.round((this.frameCount * 1000) / (now - this.fpsTimer));
            if (this.onFpsUpdate) this.onFpsUpdate(fps);
            this.frameCount = 0;
            this.fpsTimer = now;
        }

        if (this.onCameraUpdate) {
            this.onCameraUpdate(this.camera.position);
        }
    }
}

window.EXeaViewport3D = EXeaViewport3D;
