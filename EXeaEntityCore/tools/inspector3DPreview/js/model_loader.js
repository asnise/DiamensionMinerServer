/**
 * EXea Model Loader
 * Parses both Blockbench (.bbmodel) and EXea Exported JSONs (_bones.json, _anims.json)
 * into a standardized runtime representation.
 */
class EXeaModelLoader {
    constructor() {
        this.diagnostics = [];
    }

    /**
     * Parse raw .bbmodel project data
     * @param {Object} bbmodel 
     * @returns {Object} normalizedModel
     */
    parseBBModel(bbmodel) {
        this.diagnostics = [];
        const resWidth = (bbmodel.resolution && bbmodel.resolution.width) || 128;
        const resHeight = (bbmodel.resolution && bbmodel.resolution.height) || 128;

        // 1. Extract texture
        let textureDataUrl = null;
        if (bbmodel.textures && bbmodel.textures.length > 0) {
            const tex = bbmodel.textures[0];
            if (tex && tex.source) {
                textureDataUrl = tex.source;
            }
        }

        // 2. Map elements by uuid
        const elementsByUuid = {};
        if (Array.isArray(bbmodel.elements)) {
            bbmodel.elements.forEach(el => {
                elementsByUuid[el.uuid] = el;
            });
        }

        // 3. Map group hierarchy from outliner
        const parentMap = {};
        const groupElementsMap = {};

        function walkOutliner(nodes, parentGroupUuid) {
            for (const node of nodes) {
                if (typeof node === 'string') {
                    if (parentGroupUuid) {
                        if (!groupElementsMap[parentGroupUuid]) groupElementsMap[parentGroupUuid] = [];
                        groupElementsMap[parentGroupUuid].push(node);
                    }
                } else if (node && node.uuid) {
                    if (parentGroupUuid) {
                        parentMap[node.uuid] = parentGroupUuid;
                    }
                    if (Array.isArray(node.children)) {
                        walkOutliner(node.children, node.uuid);
                    }
                }
            }
        }

        if (Array.isArray(bbmodel.outliner)) {
            walkOutliner(bbmodel.outliner, null);
        }

        const groupUuidToName = {};
        const groups = bbmodel.groups || [];
        groups.forEach(g => {
            groupUuidToName[g.uuid] = g.name.toLowerCase();
        });

        // 4. Build bones and audit elements
        let totalCubes = 0;
        let cmdCounter = 1001;
        const bones = [];

        for (const group of groups) {
            const boneName = group.name.toLowerCase();
            const parentUuid = parentMap[group.uuid];
            const parentName = parentUuid ? groupUuidToName[parentUuid] : null;
            const isVisible = group.visibility !== false;

            const elemUuids = groupElementsMap[group.uuid] || [];
            const boneElements = [];

            for (const uuid of elemUuids) {
                const el = elementsByUuid[uuid];
                if (!el || el.export === false) continue;

                // Validate Minecraft rotation constraint (-45 to 45 deg on single axis)
                const rot = el.rotation || [0, 0, 0];
                let nonZeroAxes = 0;
                if (Math.abs(rot[0]) > 0.001) nonZeroAxes++;
                if (Math.abs(rot[1]) > 0.001) nonZeroAxes++;
                if (Math.abs(rot[2]) > 0.001) nonZeroAxes++;

                if (nonZeroAxes > 1) {
                    this.diagnostics.push({
                        type: 'warn',
                        bone: boneName,
                        element: el.name || 'Cube',
                        title: 'Multi-Axis Element Rotation',
                        message: `Element "${el.name || uuid}" in bone "${boneName}" rotates on ${nonZeroAxes} axes [${rot.join(', ')}]. Minecraft item models only support rotation on a single axis! Remaining axes will be dropped upon export.`
                    });
                } else if (nonZeroAxes === 1) {
                    const angle = rot[0] || rot[1] || rot[2];
                    const validAngles = [-45, -22.5, 0, 22.5, 45];
                    const isStandard = validAngles.some(a => Math.abs(a - angle) < 0.01);
                    if (!isStandard) {
                        this.diagnostics.push({
                            type: 'warn',
                            bone: boneName,
                            element: el.name || 'Cube',
                            title: 'Non-Standard Minecraft Angle',
                            message: `Angle ${angle}° on element "${el.name || uuid}" is outside standard Minecraft angles (-45, -22.5, 0, 22.5, 45). In-game display may clamp or distort.`
                        });
                    }
                }

                boneElements.push({
                    uuid: el.uuid,
                    name: el.name || 'cube',
                    from: el.from ? [...el.from] : [0, 0, 0],
                    to: el.to ? [...el.to] : [1, 1, 1],
                    origin: el.origin ? [...el.origin] : (group.origin ? [...group.origin] : [0, 0, 0]),
                    rotation: el.rotation ? [...el.rotation] : [0, 0, 0],
                    faces: el.faces || {}
                });
                totalCubes++;
            }

            if (!isVisible && boneElements.length > 0) {
                this.diagnostics.push({
                    type: 'info',
                    bone: boneName,
                    title: 'Hidden Bone with Mesh (Alternate Gear/State)',
                    message: `Bone "${boneName}" has ${boneElements.length} elements but is set to HIDDEN. DisplayRenderer will NOT spawn an ItemDisplay for it in-game unless activated.`
                });
            }

            bones.push({
                name: boneName,
                originalName: group.name,
                parent: parentName,
                origin: group.origin ? group.origin.map(v => parseFloat(v) || 0) : [0, 0, 0],
                rotation: group.rotation ? group.rotation.map(v => parseFloat(v) || 0) : [0, 0, 0],
                custom_model_data: cmdCounter++,
                visible: isVisible,
                elements: boneElements
            });
        }

        // 5. Build animations
        const animations = {};
        if (Array.isArray(bbmodel.animations)) {
            for (const anim of bbmodel.animations) {
                let cleanName = anim.name;
                const prefix = `animation.${(bbmodel.name || 'model').toLowerCase().replace(/\s+/g, '_')}.`;
                if (cleanName.startsWith(prefix)) cleanName = cleanName.substring(prefix.length);
                if (cleanName.startsWith('animation.')) cleanName = cleanName.split('.').pop();
                cleanName = cleanName.toLowerCase();

                let loopMode = 'once';
                if (anim.loop === 'loop' || anim.loop === true) loopMode = 'loop';
                else if (anim.loop === 'hold') loopMode = 'hold';

                const animEntry = {
                    name: cleanName,
                    length: anim.length || 0,
                    loop: loopMode,
                    bones: {},
                    events: []
                };

                // Infer timeline events if anim contains keywords (e.g. slash, slam)
                if (cleanName.includes('slash')) {
                    animEntry.events.push({
                        time: 0.52,
                        tag: 'slash_impact',
                        sound: 'ENTITY_PLAYER_ATTACK_SWEEP',
                        particle: 'SWEEP_ATTACK',
                        damage: 250
                    });
                }
                if (cleanName.includes('slam')) {
                    animEntry.events.push({
                        time: 0.70,
                        tag: 'slam_earthquake',
                        sound: 'ENTITY_GENERIC_EXPLODE',
                        particle: 'EXPLOSION',
                        damage: 400
                    });
                }

                const animators = anim.animators || {};
                for (const [uuid, animator] of Object.entries(animators)) {
                    const boneName = groupUuidToName[uuid];
                    if (!boneName) continue;

                    const keyframes = animator.keyframes || [];
                    if (keyframes.length === 0) continue;

                    const kfList = [];
                    for (const kf of keyframes) {
                        const dp = kf.data_points && kf.data_points[0] ? kf.data_points[0] : { x: 0, y: 0, z: 0 };
                        const kfTime = parseFloat(kf.time) || 0;
                        const x = parseFloat(dp.x) || 0;
                        const y = parseFloat(dp.y) || 0;
                        const z = parseFloat(dp.z) || 0;

                        kfList.push({
                            channel: kf.channel,
                            time: parseFloat(kfTime.toFixed(5)),
                            vector: [
                                parseFloat(x.toFixed(5)),
                                parseFloat(y.toFixed(5)),
                                parseFloat(z.toFixed(5))
                            ]
                        });
                    }

                    kfList.sort((a, b) => a.time - b.time || a.channel.localeCompare(b.channel));
                    animEntry.bones[boneName] = kfList;
                }

                animations[cleanName] = animEntry;
            }
        }

        return {
            id: (bbmodel.name || 'custom_model').toLowerCase().replace(/\s+/g, '_'),
            name: bbmodel.name || 'Custom Model',
            textureDataUrl: textureDataUrl,
            resolution: { width: resWidth, height: resHeight },
            bones: bones,
            animations: animations,
            diagnostics: this.diagnostics
        };
    }

    /**
     * Assemble model from exported files (bones JSON, anims JSON, texture URL, and optional bbmodel elements)
     */
    assembleFromExported(bonesData, animsData, textureDataUrl, bbmodelFallback) {
        this.diagnostics = [];
        const modelId = 'void_overseer';

        // If we have bbmodelFallback, extract element details to attach to bones
        let elementMap = {};
        let res = { width: 128, height: 128 };
        if (bbmodelFallback) {
            const parsed = this.parseBBModel(bbmodelFallback);
            parsed.bones.forEach(b => {
                elementMap[b.name] = b.elements;
            });
            if (!textureDataUrl) textureDataUrl = parsed.textureDataUrl;
            res = parsed.resolution;
        }

        const bones = (bonesData || []).map(b => {
            const boneName = b.name.toLowerCase();
            return {
                name: boneName,
                originalName: b.original_name || b.name,
                parent: b.parent ? b.parent.toLowerCase() : null,
                origin: b.origin ? b.origin.map(v => parseFloat(v) || 0) : [0, 0, 0],
                rotation: b.rotation ? b.rotation.map(v => parseFloat(v) || 0) : [0, 0, 0],
                custom_model_data: b.custom_model_data || 1000,
                visible: b.visible !== false,
                elements: elementMap[boneName] || []
            };
        });

        const animations = {};
        for (const [animKey, animObj] of Object.entries(animsData || {})) {
            const cleanName = animKey.toLowerCase();
            const events = [];
            if (cleanName.includes('slash')) {
                events.push({
                    time: 0.52,
                    tag: 'slash_impact',
                    sound: 'ENTITY_PLAYER_ATTACK_SWEEP',
                    particle: 'SWEEP_ATTACK',
                    damage: 250
                });
            }
            animations[cleanName] = {
                name: cleanName,
                length: animObj.length || 0,
                loop: (animObj.loop || 'once').toLowerCase(),
                bones: animObj.bones || {},
                events: events
            };
        }

        return {
            id: modelId,
            name: 'Void Overseer',
            textureDataUrl: textureDataUrl,
            resolution: res,
            bones: bones,
            animations: animations,
            diagnostics: this.diagnostics
        };
    }
}

window.EXeaModelLoader = EXeaModelLoader;
