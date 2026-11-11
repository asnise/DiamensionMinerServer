(function () {
    let export_action;

    Plugin.register('exea_entity_exporter', {
        title: 'EXea Entity Exporter',
        author: 'EXea Team',
        description: 'Export Blockbench models directly into EXeaEntityCore JSON format & Minecraft Resource Pack models.',
        icon: 'view_in_ar',
        version: '1.0.0',
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

                bones.push({
                    name: boneName,
                    original_name: g.name,
                    parent: parentName,
                    origin: [g.origin[0], g.origin[1], g.origin[2]],
                    rotation: [g.rotation[0] || 0, g.rotation[1] || 0, g.rotation[2] || 0],
                    custom_model_data: cmdCounter++,
                    element_count: visibleElements.length,
                    visible: isVisible
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
                        const time = kf.time;
                        const dp = kf.data_points && kf.data_points[0] ? kf.data_points[0] : { x: 0, y: 0, z: 0 };

                        const x = parseFloat(dp.x) || 0;
                        const y = parseFloat(dp.y) || 0;
                        const z = parseFloat(dp.z) || 0;

                        boneKfs.push({
                            channel: channel,
                            time: parseFloat(time.toFixed(5)),
                            vector: [
                                parseFloat(x.toFixed(5)),
                                parseFloat(y.toFixed(5)),
                                parseFloat(z.toFixed(5))
                            ]
                        });
                    });

                    boneKfs.sort((a, b) => a.time - b.time || a.channel.localeCompare(b.channel));
                    animEntry.bones[boneName] = boneKfs;
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
                        message: `Successfully exported ${entityId}_bones.json & ${entityId}_anims.json! Place them in EXeaEntityCore/src/main/resources/models/ or server plugins.`
                    });
                });
            });
        });
    }
})();
