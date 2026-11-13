(function () {
    let export_action;

    Plugin.register('exea_entity_exporter', {
        title: 'EXea Entity Exporter',
        author: 'EXea Team',
        description: 'Export Blockbench models directly into EXeaEntityCore JSON format & Minecraft Resource Pack models.',
        icon: 'view_in_ar',
        version: '1.2.0',
        variant: 'both',
        onload() {
            export_action = new Action('export_exea_entity', {
                name: 'Export EXea Entity Model',
                description: 'Export bones, animations, and resource pack files for EXeaEntityCore',
                icon: 'file_download',
                category: 'file',
                click: function () {
                    exportExeaModel();
                }
            });

            MenuBar.addAction(export_action, 'file.export');
        },
        onunload() {
            export_action.delete();
        }
    });

    function exportExeaModel() {
        if (!Format || !Project) {
            Blockbench.showQuickMessage('No project open!', 2000);
            return;
        }

        Blockbench.textPrompt('Enter Entity ID (lowercase, e.g. void_overseer):', Project.name ? Project.name.toLowerCase().replace(/\s+/g, '_') : 'custom_entity', (entityId) => {
            if (!entityId) return;

            // 1. Build Bones Structure
            const bones = [];
            const groupMap = {};
            let cmdCounter = 1001;

            // Map all groups
            Group.all.forEach(g => {
                const boneName = g.name.toLowerCase();
                groupMap[g.uuid] = boneName;
            });

            Group.all.forEach(g => {
                const boneName = g.name.toLowerCase();
                const parentGroup = g.parent instanceof Group ? g.parent : null;
                const parentName = parentGroup ? parentGroup.name.toLowerCase() : null;

                // Direct elements inside this group
                const directElements = (g.children || []).filter(child => child instanceof OutlinerElement);
                const visibleElements = directElements.filter(el => el.export !== false);

                const isVisible = g.visibility !== false;

                // Same rule as tools/exea_exporter.js: meshes reaching > 24px from the pivot are
                // stored shrunk in the item model and scaled back up in game.
                let maxExtent = 0;
                visibleElements.forEach(el => {
                    if (!el.from || !el.to) return;
                    const inf = el.inflate || 0;
                    for (let i = 0; i < 3; i++) {
                        maxExtent = Math.max(maxExtent, Math.abs(el.from[i] - inf - g.origin[i]), Math.abs(el.to[i] + inf - g.origin[i]));
                    }
                });
                const meshScale = maxExtent > 24 ? Math.ceil(maxExtent / 24 * 1000) / 1000 : 1;

                bones.push({
                    name: boneName,
                    original_name: g.name,
                    parent: parentName,
                    origin: [g.origin[0], g.origin[1], g.origin[2]],
                    rotation: [g.rotation[0] || 0, g.rotation[1] || 0, g.rotation[2] || 0],
                    custom_model_data: cmdCounter++,
                    element_count: visibleElements.length,
                    visible: isVisible,
                    mesh_scale: meshScale
                });
            });

            // 2. Build Animations Structure
            const animsOutput = {};
            Animation.all.forEach(anim => {
                let animName = anim.name;
                const prefix = `animation.${entityId}.`;
                if (animName.startsWith(prefix)) {
                    animName = animName.substring(prefix.length);
                }
                if (animName.startsWith('animation.')) {
                    animName = animName.split('.').pop();
                }
                animName = animName.toLowerCase();

                let loopMode = 'once';
                if (anim.loop === 'loop') loopMode = 'loop';
                else if (anim.loop === 'hold') loopMode = 'hold';
                else if (anim.loop === true) loopMode = 'loop';

                const animEntry = {
                    length: anim.length,
                    loop: loopMode,
                    bones: {}
                };

                for (const uuid in anim.animators) {
                    const animator = anim.animators[uuid];
                    const group = Group.all.find(g => g.uuid === uuid);
                    if (!group) continue;

                    const boneName = group.name.toLowerCase();
                    const keyframes = animator.keyframes || [];
                    if (keyframes.length === 0) continue;

                    const boneKfs = [];
                    keyframes.forEach(kf => {
                        const channel = kf.channel;
                        if (channel !== 'position' && channel !== 'rotation' && channel !== 'scale') return;
                        const fallback = channel === 'scale' ? 1 : 0;
                        const vec = dp => ['x', 'y', 'z'].map(axis => {
                            const n = Number(dp ? dp[axis] : undefined);
                            return Number.isFinite(n) ? parseFloat(n.toFixed(5)) : fallback;
                        });
                        const entry = {
                            channel: channel,
                            time: parseFloat(kf.time.toFixed(5)),
                            interpolation: kf.interpolation || 'linear',
                            vector: vec(kf.data_points && kf.data_points[0])
                        };
                        // Split keyframe: the second data point is the value leaving the keyframe.
                        if (kf.data_points && kf.data_points.length > 1) entry.post = vec(kf.data_points[1]);
                        boneKfs.push(entry);
                    });

                    boneKfs.sort((a, b) => a.time - b.time || a.channel.localeCompare(b.channel));
                    animEntry.bones[boneName] = boneKfs;
                }

                // Effect keyframes (sound / particle / timeline script); same format as tools/exea_exporter.js.
                const effects = anim.animators && anim.animators.effects;
                const events = [];
                const boneOf = locator => {
                    const name = (locator || '').trim().toLowerCase();
                    if (!name) return null;
                    const loc = (typeof Locator !== 'undefined' ? Locator.all : []).find(l => l.name.toLowerCase() === name);
                    if (loc && loc.parent instanceof Group) return loc.parent.name.toLowerCase();
                    return Group.all.some(g => g.name.toLowerCase() === name) ? name : null;
                };
                ((effects && effects.keyframes) || []).forEach(kf => {
                    const time = parseFloat(Number(kf.time).toFixed(5));
                    (kf.data_points || []).forEach(dp => {
                        let ev = null;
                        if (kf.channel === 'sound' && dp.effect) ev = { time, type: 'sound', sound: String(dp.effect).trim() };
                        else if (kf.channel === 'particle' && dp.effect) ev = { time, type: 'particle', particle: String(dp.effect).trim() };
                        else if (kf.channel === 'timeline' && dp.script && String(dp.script).trim()) ev = { time, type: 'script', script: String(dp.script).trim() };
                        if (!ev) return;
                        const bone = ev.type !== 'script' ? boneOf(dp.locator) : null;
                        if (bone) ev.bone = bone;
                        events.push(ev);
                    });
                });
                if (events.length) {
                    events.sort((a, b) => a.time - b.time);
                    animEntry.events = events;
                }

                animsOutput[animName] = animEntry;
            });

            // Ask user where to save zip or json
            Blockbench.export({
                resource_id: 'exea_export',
                type: 'JSON Files',
                extensions: ['json'],
                name: `${entityId}_bones.json`,
                content: JSON.stringify(bones, null, 2),
                savetype: 'json'
            }, () => {
                // Also export animations
                Blockbench.export({
                    resource_id: 'exea_anims_export',
                    type: 'JSON Files',
                    extensions: ['json'],
                    name: `${entityId}_anims.json`,
                    content: JSON.stringify(animsOutput, null, 2),
                    savetype: 'json'
                }, () => {
                    Blockbench.showMessageBox({
                        title: 'EXea Export Complete',
                        icon: 'info',
                        message: `Successfully exported ${entityId}_bones.json & ${entityId}_anims.json! Place them in EXeaEntityCore/src/main/resources/models/ or server plugins. This plugin does not write the resource pack: save the .bbmodel and run tools/exea_exporter.js for the bone item models.`
                    });
                });
            });
        });
    }
})();
