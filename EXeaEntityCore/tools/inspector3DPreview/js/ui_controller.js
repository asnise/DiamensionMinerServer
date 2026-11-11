/**
 * EXea UI Controller
 * Manages Hierarchy Tree, Inspector Panels, Timeline Scrubber, and Diagnostics
 */
class EXeaUIController {
    constructor() {
        this.viewport = null;
        this.animEngine = null;
        this.currentModel = null;
        this.selectedBone = null;
        this.currentFilter = 'all'; // 'all' | 'rendered' | 'hidden'
        this.searchQuery = '';

        this.initDOMElements();
        this.bindEvents();
    }

    initDOMElements() {
        // Stats
        this.statBones = document.getElementById('stat-bones');
        this.statDisplays = document.getElementById('stat-displays');
        this.statElements = document.getElementById('stat-elements');
        this.statAnims = document.getElementById('stat-anims');
        this.modelSubtitle = document.getElementById('model-subtitle');

        // Tree
        this.treeContainer = document.getElementById('bone-tree-container');
        this.boneSearch = document.getElementById('bone-search');
        this.btnExpandAll = document.getElementById('btn-expand-all');
        this.btnCollapseAll = document.getElementById('btn-collapse-all');

        // Inspector Tab 1: Transform
        this.inspBoneName = document.getElementById('insp-bone-name');
        this.inspBoneParent = document.getElementById('insp-bone-parent');
        this.inspBoneCmd = document.getElementById('insp-bone-cmd');
        this.inspBoneVis = document.getElementById('insp-bone-vis');
        this.inspOrigX = document.getElementById('insp-orig-x');
        this.inspOrigY = document.getElementById('insp-orig-y');
        this.inspOrigZ = document.getElementById('insp-orig-z');
        this.inspRotX = document.getElementById('insp-rot-x');
        this.inspRotY = document.getElementById('insp-rot-y');
        this.inspRotZ = document.getElementById('insp-rot-z');
        this.inspRelpX = document.getElementById('insp-relp-x');
        this.inspRelpY = document.getElementById('insp-relp-y');
        this.inspRelpZ = document.getElementById('insp-relp-z');
        this.inspAnimPosX = document.getElementById('insp-anim-pos-x');
        this.inspAnimPosY = document.getElementById('insp-anim-pos-y');
        this.inspAnimPosZ = document.getElementById('insp-anim-pos-z');
        this.inspAnimRotX = document.getElementById('insp-anim-rot-x');
        this.inspAnimRotY = document.getElementById('insp-anim-rot-y');
        this.inspAnimRotZ = document.getElementById('insp-anim-rot-z');
        this.inspAnimScaleX = document.getElementById('insp-anim-scale-x');
        this.inspAnimScaleY = document.getElementById('insp-anim-scale-y');
        this.inspAnimScaleZ = document.getElementById('insp-anim-scale-z');

        // Inspector Tab 2: Elements
        this.tabElemCount = document.getElementById('tab-elem-count');
        this.elementsCountSummary = document.getElementById('elements-count-summary');
        this.elementsListContainer = document.getElementById('elements-list-container');

        // Inspector Tab 3: Diagnostics
        this.auditBadge = document.getElementById('audit-badge');
        this.auditSummaryBox = document.getElementById('audit-summary-box');
        this.auditHeadline = document.getElementById('audit-headline');
        this.auditDescription = document.getElementById('audit-description');
        this.auditCardsContainer = document.getElementById('audit-cards-container');

        // Bottom Timeline
        this.selectAnim = document.getElementById('select-animation');
        this.selectSpeed = document.getElementById('select-speed');
        this.btnPlayPause = document.getElementById('btn-play-pause');
        this.btnStop = document.getElementById('btn-stop');
        this.btnStepPrev = document.getElementById('btn-step-prev');
        this.btnStepNext = document.getElementById('btn-step-next');
        this.timeCurrent = document.getElementById('time-current');
        this.timeTotal = document.getElementById('time-total');
        this.tickCurrent = document.getElementById('tick-current');
        this.tickTotal = document.getElementById('tick-total');
        this.loopModeBadge = document.getElementById('loop-mode-badge');
        this.timelineRail = document.getElementById('timeline-rail');
        this.timelineProgressFill = document.getElementById('timeline-progress-fill');
        this.timelineThumb = document.getElementById('timeline-thumb');
        this.timelineKfMarkers = document.getElementById('timeline-kf-markers');
        this.timelineEventMarkers = document.getElementById('timeline-event-markers');

        // Viewport HUD
        this.hudFps = document.getElementById('hud-fps');
        this.hudCamPos = document.getElementById('hud-cam-pos');
        this.hudSelectedBone = document.getElementById('hud-selected-bone');
    }

    setReferences(viewport, animEngine) {
        this.viewport = viewport;
        this.animEngine = animEngine;

        this.viewport.onBoneSelected = (boneName) => this.selectBone(boneName);
        this.viewport.onFpsUpdate = (fps) => { this.hudFps.textContent = fps; };
        this.viewport.onCameraUpdate = (pos) => {
            this.hudCamPos.textContent = `${pos.x.toFixed(0)}, ${pos.y.toFixed(0)}, ${pos.z.toFixed(0)}`;
        };

        this.animEngine.onTick = (time) => this.onTimelineTick(time);
        this.animEngine.onEvent = (evt) => this.onAnimationEvent(evt);
        this.animEngine.onFinish = () => {
            this.btnPlayPause.textContent = '▶ Play';
            this.btnPlayPause.classList.remove('active');
        };
    }

    bindEvents() {
        // Tab switching
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                btn.classList.add('active');
                const targetTab = document.getElementById(btn.dataset.tab);
                if (targetTab) targetTab.classList.add('active');
            });
        });

        // Search & Filter
        this.boneSearch.addEventListener('input', (e) => {
            this.searchQuery = e.target.value.toLowerCase().trim();
            this.renderBoneTree();
        });

        document.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this.currentFilter = btn.dataset.filter;
                this.renderBoneTree();
            });
        });

        // View Mode Selector
        const selectViewMode = document.getElementById('select-view-mode');
        selectViewMode.addEventListener('change', (e) => {
            this.viewport.setViewMode(e.target.value);
            this.renderBoneTree();
        });

        // Viewport Toolbar Buttons
        this.bindToggle('toggle-grid', (val) => this.viewport.toggleGrid(val));
        this.bindToggle('toggle-bones', (val) => this.viewport.toggleBones(val));
        this.bindToggle('toggle-wireframe', (val) => this.viewport.toggleWireframe(val));
        this.bindToggle('toggle-bbox', (val) => this.viewport.toggleBoundingBox(val));
        this.bindToggle('toggle-textures', (val) => this.viewport.toggleTextures(val));

        // Camera views
        document.getElementById('btn-camera-front').addEventListener('click', () => this.viewport.setCameraView('front'));
        document.getElementById('btn-camera-side').addEventListener('click', () => this.viewport.setCameraView('side'));
        document.getElementById('btn-camera-top').addEventListener('click', () => this.viewport.setCameraView('top'));
        document.getElementById('btn-camera-reset').addEventListener('click', () => this.viewport.resetCamera());

        // Timeline playback controls
        this.btnPlayPause.addEventListener('click', () => {
            if (this.animEngine.isPlaying) {
                this.animEngine.pause();
                this.btnPlayPause.textContent = '▶ Play';
                this.btnPlayPause.classList.remove('active');
            } else {
                this.animEngine.play();
                this.btnPlayPause.textContent = '⏸ Pause';
                this.btnPlayPause.classList.add('active');
            }
        });

        this.btnStop.addEventListener('click', () => {
            this.animEngine.stop();
            this.btnPlayPause.textContent = '▶ Play';
            this.btnPlayPause.classList.remove('active');
            this.viewport.updateTransforms(this.animEngine, 0);
            this.updateLiveTransformDisplay();
        });

        this.btnStepPrev.addEventListener('click', () => {
            this.animEngine.step(-0.05); // 1 tick
            this.viewport.updateTransforms(this.animEngine, this.animEngine.currentTime);
            this.updateLiveTransformDisplay();
        });

        this.btnStepNext.addEventListener('click', () => {
            this.animEngine.step(0.05); // 1 tick
            this.viewport.updateTransforms(this.animEngine, this.animEngine.currentTime);
            this.updateLiveTransformDisplay();
        });

        this.selectSpeed.addEventListener('change', (e) => {
            this.animEngine.setSpeed(e.target.value);
        });

        this.selectAnim.addEventListener('change', (e) => {
            this.onAnimationSelected(e.target.value);
        });

        // Timeline Scrubbing
        let isScrubbing = false;
        const handleScrub = (e) => {
            const rect = this.timelineRail.getBoundingClientRect();
            const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            if (this.animEngine.activeTimeline) {
                const targetTime = ratio * this.animEngine.activeTimeline.length;
                this.animEngine.seek(targetTime);
                this.viewport.updateTransforms(this.animEngine, targetTime);
                this.updateLiveTransformDisplay();
            }
        };

        this.timelineRail.addEventListener('pointerdown', (e) => {
            isScrubbing = true;
            handleScrub(e);
            window.addEventListener('pointermove', handlePointerMove);
            window.addEventListener('pointerup', handlePointerUp);
        });

        const handlePointerMove = (e) => {
            if (isScrubbing) handleScrub(e);
        };
        const handlePointerUp = () => {
            isScrubbing = false;
            window.removeEventListener('pointermove', handlePointerMove);
            window.removeEventListener('pointerup', handlePointerUp);
        };
    }

    bindToggle(btnId, callback) {
        const btn = document.getElementById(btnId);
        if (!btn) return;
        btn.addEventListener('click', () => {
            const isActive = btn.classList.toggle('active');
            callback(isActive);
        });
    }

    /**
     * Load Model Data into UI
     */
    loadModelData(modelData) {
        this.currentModel = modelData;
        this.modelSubtitle.textContent = `${modelData.name} (${modelData.id})`;

        // 1. Update Header Badges
        this.statBones.textContent = modelData.bones.length;
        const renderedDisplays = modelData.bones.filter(b => b.visible !== false && b.elements.length > 0).length;
        this.statDisplays.textContent = renderedDisplays;
        const totalElements = modelData.bones.reduce((sum, b) => sum + (b.elements ? b.elements.length : 0), 0);
        this.statElements.textContent = totalElements;
        const animCount = Object.keys(modelData.animations || {}).length;
        this.statAnims.textContent = animCount;

        // 2. Build Animation Dropdown
        this.populateAnimationDropdown(modelData.animations);

        // 3. Render Diagnostics
        this.renderDiagnostics(modelData.diagnostics);

        // 4. Render Hierarchy Tree
        this.renderBoneTree();

        // 5. Select Root Bone by default
        if (modelData.bones.length > 0) {
            this.selectBone(modelData.bones[0].name);
        }
    }

    populateAnimationDropdown(animations) {
        this.selectAnim.innerHTML = '';
        const animKeys = Object.keys(animations || {});
        if (animKeys.length === 0) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = 'No animations found';
            this.selectAnim.appendChild(opt);
            return;
        }

        animKeys.forEach(name => {
            const opt = document.createElement('option');
            opt.value = name;
            opt.textContent = `${name} (${animations[name].length}s)`;
            this.selectAnim.appendChild(opt);
        });

        // Default to 'idle' if present, otherwise first
        const defaultAnim = animKeys.find(k => k.includes('idle')) || animKeys[0];
        this.selectAnim.value = defaultAnim;
        this.onAnimationSelected(defaultAnim);
    }

    onAnimationSelected(animName) {
        if (!this.currentModel || !this.currentModel.animations[animName]) return;
        const timeline = this.currentModel.animations[animName];
        this.animEngine.setTimeline(timeline);

        this.timeTotal.textContent = `${timeline.length.toFixed(2)}s`;
        this.tickTotal.textContent = `${Math.round(timeline.length * 20)}`;
        this.loopModeBadge.textContent = (timeline.loop || 'once').toUpperCase();

        this.onTimelineTick(0);
        this.renderTimelineMarkers(timeline);
        this.viewport.updateTransforms(this.animEngine, 0);
        this.updateLiveTransformDisplay();
    }

    onTimelineTick(time) {
        if (!this.animEngine.activeTimeline) return;
        const total = this.animEngine.activeTimeline.length;
        const ratio = total > 0 ? (time / total) * 100 : 0;

        this.timelineProgressFill.style.width = `${ratio}%`;
        this.timelineThumb.style.left = `${ratio}%`;
        this.timeCurrent.textContent = `${time.toFixed(2)}s`;
        this.tickCurrent.textContent = `${Math.round(time * 20)}`;

        this.viewport.updateTransforms(this.animEngine, time);
        this.updateLiveTransformDisplay();
    }

    onAnimationEvent(evt) {
        // Visual notification for event (sound, particle)
        const toast = document.createElement('div');
        toast.style.cssText = `
            position: absolute; top: 70px; left: 50%; transform: translateX(-50%);
            background: rgba(0, 229, 255, 0.9); color: #000; font-weight: 700;
            padding: 6px 14px; border-radius: 20px; font-family: var(--font-mono);
            box-shadow: 0 0 15px #00e5ff; pointer-events: none; z-index: 100;
            animation: fadeOut 1.5s forwards;
        `;
        toast.textContent = `⚡ EVENT: ${evt.tag} (${evt.particle || evt.sound || ''})`;
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 1500);
    }

    renderTimelineMarkers(timeline) {
        this.timelineKfMarkers.innerHTML = '';
        this.timelineEventMarkers.innerHTML = '';
        if (!timeline || timeline.length <= 0) return;

        // 1. Keyframe markers for selected bone
        if (this.selectedBone && timeline.bones[this.selectedBone]) {
            const kfs = timeline.bones[this.selectedBone];
            kfs.forEach(kf => {
                const ratio = (kf.time / timeline.length) * 100;
                const dot = document.createElement('div');
                dot.className = 'kf-marker';
                dot.style.left = `${ratio}%`;
                dot.title = `KF: ${kf.channel} @ ${kf.time}s`;
                this.timelineKfMarkers.appendChild(dot);
            });
        }

        // 2. Timeline Event markers
        if (Array.isArray(timeline.events)) {
            timeline.events.forEach(evt => {
                const ratio = (evt.time / timeline.length) * 100;
                const marker = document.createElement('div');
                marker.className = 'event-marker';
                marker.style.left = `${ratio}%`;
                marker.title = `Event: ${evt.tag} (${evt.sound || ''})`;
                this.timelineEventMarkers.appendChild(marker);
            });
        }
    }

    /**
     * Render Bone Hierarchy Tree
     */
    renderBoneTree() {
        this.treeContainer.innerHTML = '';
        if (!this.currentModel) return;

        // Build bone tree map
        const childrenMap = {};
        const rootBones = [];

        this.currentModel.bones.forEach(b => {
            if (b.parent) {
                if (!childrenMap[b.parent]) childrenMap[b.parent] = [];
                childrenMap[b.parent].push(b);
            } else {
                rootBones.push(b);
            }
        });

        const renderNode = (bone, container) => {
            const isRenderedInMC = bone.visible !== false && (bone.elements && bone.elements.length > 0);

            // Filter check
            if (this.currentFilter === 'rendered' && !isRenderedInMC) return;
            if (this.currentFilter === 'hidden' && isRenderedInMC) return;
            if (this.searchQuery && !bone.name.includes(this.searchQuery)) {
                // If has children matching search, still show
                const hasMatchingChild = (childrenMap[bone.name] || []).some(c => c.name.includes(this.searchQuery));
                if (!hasMatchingChild) return;
            }

            const nodeWrap = document.createElement('div');
            nodeWrap.className = 'tree-node';

            const row = document.createElement('div');
            row.className = `tree-node-content ${this.selectedBone === bone.name ? 'selected' : ''}`;
            row.dataset.boneName = bone.name;

            const children = childrenMap[bone.name] || [];
            const hasChildren = children.length > 0;

            // Expander toggle
            const expander = document.createElement('span');
            expander.className = 'tree-expander';
            expander.textContent = hasChildren ? '▼' : '•';
            row.appendChild(expander);

            // Visibility eye button
            const eye = document.createElement('button');
            eye.className = `tree-eye-btn ${isRenderedInMC ? '' : 'hidden-bone'}`;
            eye.innerHTML = isRenderedInMC
                ? `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`
                : `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>`;
            eye.title = isRenderedInMC ? 'ItemDisplay Spawned' : 'Hidden Bone (No Display Spawned)';
            row.appendChild(eye);

            // Label
            const label = document.createElement('span');
            label.className = 'tree-node-label';
            label.textContent = bone.name;
            row.appendChild(label);

            // Elements count badge
            if (bone.elements && bone.elements.length > 0) {
                const badge = document.createElement('span');
                badge.className = 'tree-node-badge';
                badge.textContent = `${bone.elements.length}c`;
                row.appendChild(badge);
            }

            row.addEventListener('click', (e) => {
                if (e.target.closest('.tree-expander') && hasChildren) {
                    const childrenContainer = nodeWrap.querySelector('.tree-children');
                    if (childrenContainer) {
                        const isCollapsed = childrenContainer.style.display === 'none';
                        childrenContainer.style.display = isCollapsed ? 'block' : 'none';
                        expander.textContent = isCollapsed ? '▼' : '▶';
                    }
                    return;
                }
                this.selectBone(bone.name);
            });

            nodeWrap.appendChild(row);

            if (hasChildren) {
                const childrenContainer = document.createElement('div');
                childrenContainer.className = 'tree-children';
                children.forEach(childBone => renderNode(childBone, childrenContainer));
                nodeWrap.appendChild(childrenContainer);
            }

            container.appendChild(nodeWrap);
        };

        rootBones.forEach(b => renderNode(b, this.treeContainer));
    }

    /**
     * Select Bone and populate property inspector
     */
    selectBone(boneName) {
        this.selectedBone = boneName;
        this.hudSelectedBone.textContent = boneName || 'None';

        // Update Tree selection highlight
        document.querySelectorAll('.tree-node-content').forEach(el => {
            el.classList.toggle('selected', el.dataset.boneName === boneName);
        });

        if (!this.currentModel) return;
        const bone = this.currentModel.bones.find(b => b.name === boneName);
        if (!bone) return;

        // 1. Fill Identity
        this.inspBoneName.textContent = bone.name;
        this.inspBoneParent.textContent = bone.parent || '(Root Bone)';
        this.inspBoneCmd.textContent = `CMD: ${bone.custom_model_data} (exea:${this.currentModel.id}/${bone.name})`;

        const isRendered = bone.visible !== false && bone.elements.length > 0;
        this.inspBoneVis.textContent = isRendered ? 'SPAWNED (Visible)' : 'HIDDEN (No Display)';
        this.inspBoneVis.className = `status-tag ${isRendered ? 'visible' : 'hidden'}`;

        // 2. Fill Origin & Rest Rotation
        this.inspOrigX.value = (parseFloat(bone.origin[0]) || 0).toFixed(2);
        this.inspOrigY.value = (parseFloat(bone.origin[1]) || 0).toFixed(2);
        this.inspOrigZ.value = (parseFloat(bone.origin[2]) || 0).toFixed(2);

        this.inspRotX.value = (parseFloat(bone.rotation[0]) || 0).toFixed(2);
        this.inspRotY.value = (parseFloat(bone.rotation[1]) || 0).toFixed(2);
        this.inspRotZ.value = (parseFloat(bone.rotation[2]) || 0).toFixed(2);

        // Relative Pivot to parent in blocks
        let relP = [bone.origin[0], bone.origin[1], bone.origin[2]];
        if (bone.parent) {
            const pBone = this.currentModel.bones.find(b => b.name === bone.parent);
            if (pBone) {
                relP = [
                    (bone.origin[0] - pBone.origin[0]) / 16.0,
                    (bone.origin[1] - pBone.origin[1]) / 16.0,
                    (bone.origin[2] - pBone.origin[2]) / 16.0
                ];
            }
        } else {
            relP = [bone.origin[0] / 16.0, bone.origin[1] / 16.0, bone.origin[2] / 16.0];
        }
        this.inspRelpX.value = relP[0].toFixed(4);
        this.inspRelpY.value = relP[1].toFixed(4);
        this.inspRelpZ.value = relP[2].toFixed(4);

        // 3. Tab 2: Elements in this Bone
        this.renderBoneElements(bone);

        // 4. Update timeline keyframe markers for selected bone
        if (this.animEngine.activeTimeline) {
            this.renderTimelineMarkers(this.animEngine.activeTimeline);
        }

        // 5. Update live transform
        this.updateLiveTransformDisplay();
    }

    renderBoneElements(bone) {
        const count = (bone.elements || []).length;
        this.tabElemCount.textContent = count;
        this.elementsCountSummary.textContent = `${count} Elements in Bone "${bone.name}"`;
        this.elementsListContainer.innerHTML = '';

        if (count === 0) {
            this.elementsListContainer.innerHTML = `
                <div style="color: var(--text-muted); font-size: 0.8rem; text-align: center; padding: 20px;">
                    This bone acts as a pure pivot/joint without direct cubes.
                </div>
            `;
            return;
        }

        bone.elements.forEach((el, idx) => {
            const card = document.createElement('div');
            card.className = 'element-card';

            const from = el.from || [0, 0, 0];
            const to = el.to || [1, 1, 1];
            const size = [
                Math.abs(to[0] - from[0]).toFixed(1),
                Math.abs(to[1] - from[1]).toFixed(1),
                Math.abs(to[2] - from[2]).toFixed(1)
            ];

            const rot = el.rotation || [0, 0, 0];
            const hasRot = Math.abs(rot[0]) > 0.001 || Math.abs(rot[1]) > 0.001 || Math.abs(rot[2]) > 0.001;

            card.innerHTML = `
                <div class="element-card-title">
                    <span>#${idx + 1} ${el.name || 'Cube'}</span>
                    <span style="color: var(--text-muted);">${size.join(' × ')}</span>
                </div>
                <div class="element-card-info">
                    <div>From: [${from.map(v => (parseFloat(v) || 0).toFixed(1)).join(', ')}]</div>
                    <div>To: [${to.map(v => (parseFloat(v) || 0).toFixed(1)).join(', ')}]</div>
                    <div>Origin: [${(el.origin || bone.origin).map(v => (parseFloat(v) || 0).toFixed(1)).join(', ')}]</div>
                    <div style="${hasRot ? 'color: var(--accent-amber); font-weight: bold;' : ''}">
                        Rot: [${rot.join(', ')}°]
                    </div>
                </div>
            `;
            this.elementsListContainer.appendChild(card);
        });
    }

    updateLiveTransformDisplay() {
        if (!this.selectedBone || !this.viewport.boneGroups[this.selectedBone]) return;
        const group = this.viewport.boneGroups[this.selectedBone];

        // Animated Position Offset
        const animPos = this.animEngine.evaluatePosition(this.selectedBone, this.animEngine.currentTime);
        this.inspAnimPosX.value = animPos.x.toFixed(2);
        this.inspAnimPosY.value = animPos.y.toFixed(2);
        this.inspAnimPosZ.value = animPos.z.toFixed(2);

        // Animated Rotation (Euler degrees)
        const euler = new THREE.Euler().setFromQuaternion(group.quaternion, 'XYZ');
        this.inspAnimRotX.value = THREE.MathUtils.radToDeg(euler.x).toFixed(1);
        this.inspAnimRotY.value = THREE.MathUtils.radToDeg(euler.y).toFixed(1);
        this.inspAnimRotZ.value = THREE.MathUtils.radToDeg(euler.z).toFixed(1);

        // Animated Scale
        this.inspAnimScaleX.value = group.scale.x.toFixed(2);
        this.inspAnimScaleY.value = group.scale.y.toFixed(2);
        this.inspAnimScaleZ.value = group.scale.z.toFixed(2);
    }

    /**
     * Render Minecraft Compatibility Diagnostics
     */
    renderDiagnostics(diagnostics) {
        this.auditCardsContainer.innerHTML = '';
        const list = diagnostics || [];
        this.auditBadge.textContent = list.length;

        if (list.length === 0) {
            this.auditSummaryBox.className = 'audit-summary-box';
            this.auditHeadline.textContent = '✓ 100% Minecraft Compatible';
            this.auditDescription.textContent = 'All elements, pivots, and rotations comply with Minecraft ItemDisplay and item model constraints.';
            return;
        }

        const hasErrors = list.some(d => d.type === 'err');
        const hasWarnings = list.some(d => d.type === 'warn');

        if (hasErrors) {
            this.auditSummaryBox.className = 'audit-summary-box has-errors';
            this.auditHeadline.textContent = '❌ Critical Export Conflicts';
            this.auditDescription.textContent = `${list.length} item(s) found that will cause rendering errors or element drops in Minecraft.`;
        } else if (hasWarnings) {
            this.auditSummaryBox.className = 'audit-summary-box has-warnings';
            this.auditHeadline.textContent = '⚠️ Minecraft Rotation / Visibility Warnings';
            this.auditDescription.textContent = `${list.length} issue(s) detected. Review multi-axis rotations and hidden bones below.`;
        }

        list.forEach(diag => {
            const card = document.createElement('div');
            card.className = `audit-item ${diag.type || 'info'}`;

            const icon = diag.type === 'err' ? '❌' : (diag.type === 'warn' ? '⚠️' : 'ℹ️');
            card.innerHTML = `
                <div class="audit-icon">${icon}</div>
                <div class="audit-body">
                    <strong>${diag.title} [Bone: ${diag.bone}]</strong>
                    <p>${diag.message}</p>
                </div>
            `;
            this.auditCardsContainer.appendChild(card);
        });
    }
}

window.EXeaUIController = EXeaUIController;
