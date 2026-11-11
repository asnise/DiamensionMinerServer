/**
 * EXea 3D Inspector Application Coordinator
 * Connects 3D Viewport, Animation Engine, Model Loader, and UI Controller.
 */
document.addEventListener('DOMContentLoaded', () => {
    // 1. Initialize Subsystems
    const loader = new EXeaModelLoader();
    const animEngine = new EXeaAnimationEngine();
    const viewport = new EXeaViewport3D('render-canvas');
    const ui = new EXeaUIController();

    ui.setReferences(viewport, animEngine);

    // 2. Animation Update Loop
    let lastTime = performance.now();
    function animationLoop(now) {
        requestAnimationFrame(animationLoop);
        const dt = (now - lastTime) / 1000;
        lastTime = now;

        animEngine.update(dt);
    }
    requestAnimationFrame(animationLoop);

    // 3. Staged Import Files Cache
    const stagedFiles = {
        bbmodel: null,
        bones: null,
        anims: null,
        textureUrl: null
    };

    // 4. Setup Import Modal & File Inputs
    const importModal = document.getElementById('import-modal');
    const btnOpenImport = document.getElementById('btn-open-import');
    const btnCloseModal = document.getElementById('btn-close-modal');
    const btnApplyImport = document.getElementById('btn-apply-import');
    const btnResetPreset = document.getElementById('btn-reset-preset');
    const btnLoadPresetModal = document.getElementById('btn-load-preset-modal');
    const dropZone = document.getElementById('drop-zone');

    const fileBbmodel = document.getElementById('file-bbmodel');
    const fileBones = document.getElementById('file-bones');
    const fileAnims = document.getElementById('file-anims');
    const fileTexture = document.getElementById('file-texture');

    btnOpenImport.addEventListener('click', () => {
        importModal.classList.remove('hidden');
    });

    btnCloseModal.addEventListener('click', () => {
        importModal.classList.add('hidden');
    });

    // Close on overlay click
    importModal.addEventListener('click', (e) => {
        if (e.target === importModal) importModal.classList.add('hidden');
    });

    // File Input Handlers
    fileBbmodel.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
            document.getElementById('name-bbmodel').textContent = file.name;
            const reader = new FileReader();
            reader.onload = (evt) => {
                try {
                    stagedFiles.bbmodel = JSON.parse(evt.target.result);
                } catch (err) {
                    alert('Error parsing .bbmodel JSON: ' + err.message);
                }
            };
            reader.readAsText(file);
        }
    });

    fileBones.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
            document.getElementById('name-bones').textContent = file.name;
            const reader = new FileReader();
            reader.onload = (evt) => {
                try {
                    stagedFiles.bones = JSON.parse(evt.target.result);
                } catch (err) {
                    alert('Error parsing bones JSON: ' + err.message);
                }
            };
            reader.readAsText(file);
        }
    });

    fileAnims.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
            document.getElementById('name-anims').textContent = file.name;
            const reader = new FileReader();
            reader.onload = (evt) => {
                try {
                    stagedFiles.anims = JSON.parse(evt.target.result);
                } catch (err) {
                    alert('Error parsing anims JSON: ' + err.message);
                }
            };
            reader.readAsText(file);
        }
    });

    fileTexture.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
            document.getElementById('name-texture').textContent = file.name;
            const reader = new FileReader();
            reader.onload = (evt) => {
                stagedFiles.textureUrl = evt.target.result;
            };
            reader.readAsDataURL(file);
        }
    });

    // Drag & Drop Handling
    function handleDropFiles(files) {
        for (const file of files) {
            const name = file.name.toLowerCase();
            if (name.endsWith('.bbmodel')) {
                document.getElementById('name-bbmodel').textContent = file.name;
                const reader = new FileReader();
                reader.onload = (evt) => {
                    try { stagedFiles.bbmodel = JSON.parse(evt.target.result); } catch (e) {}
                };
                reader.readAsText(file);
            } else if (name.includes('bone') && name.endsWith('.json')) {
                document.getElementById('name-bones').textContent = file.name;
                const reader = new FileReader();
                reader.onload = (evt) => {
                    try { stagedFiles.bones = JSON.parse(evt.target.result); } catch (e) {}
                };
                reader.readAsText(file);
            } else if (name.includes('anim') && name.endsWith('.json')) {
                document.getElementById('name-anims').textContent = file.name;
                const reader = new FileReader();
                reader.onload = (evt) => {
                    try { stagedFiles.anims = JSON.parse(evt.target.result); } catch (e) {}
                };
                reader.readAsText(file);
            } else if (name.endsWith('.png')) {
                document.getElementById('name-texture').textContent = file.name;
                const reader = new FileReader();
                reader.onload = (evt) => { stagedFiles.textureUrl = evt.target.result; };
                reader.readAsDataURL(file);
            }
        }
    }

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('drag-over');
    });
    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('drag-over');
    });
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('drag-over');
        if (e.dataTransfer && e.dataTransfer.files) {
            handleDropFiles(e.dataTransfer.files);
        }
    });

    // Also support dropping anywhere on workspace
    window.addEventListener('dragover', (e) => e.preventDefault());
    window.addEventListener('drop', (e) => {
        e.preventDefault();
        if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
            handleDropFiles(e.dataTransfer.files);
            importModal.classList.remove('hidden');
        }
    });

    // Apply Import Button
    btnApplyImport.addEventListener('click', () => {
        let loadedModel = null;
        if (stagedFiles.bbmodel) {
            loadedModel = loader.parseBBModel(stagedFiles.bbmodel);
            if (stagedFiles.textureUrl) {
                loadedModel.textureDataUrl = stagedFiles.textureUrl;
            }
        } else if (stagedFiles.bones) {
            loadedModel = loader.assembleFromExported(
                stagedFiles.bones,
                stagedFiles.anims || {},
                stagedFiles.textureUrl,
                window.DEFAULT_MODEL_PRESET ? window.DEFAULT_MODEL_PRESET.bbmodel : null
            );
        }

        if (loadedModel) {
            viewport.loadModel(loadedModel);
            ui.loadModelData(loadedModel);
            importModal.classList.add('hidden');
        } else {
            alert('Please select either a .bbmodel file or bones JSON to import!');
        }
    });

    // Reset to Default Preset
    function loadDefaultPreset() {
        if (window.DEFAULT_MODEL_PRESET) {
            const preset = window.DEFAULT_MODEL_PRESET;
            let modelData;
            if (preset.bbmodel) {
                modelData = loader.parseBBModel(preset.bbmodel);
            } else {
                modelData = loader.assembleFromExported(preset.bones, preset.anims, preset.textureDataUrl, null);
            }
            if (preset.textureDataUrl) {
                modelData.textureDataUrl = preset.textureDataUrl;
            }

            viewport.loadModel(modelData);
            ui.loadModelData(modelData);
        }
    }

    btnResetPreset.addEventListener('click', loadDefaultPreset);
    btnLoadPresetModal.addEventListener('click', () => {
        loadDefaultPreset();
        importModal.classList.add('hidden');
    });

    // 5. Initial Load (Instant Default Preview!)
    loadDefaultPreset();
});
