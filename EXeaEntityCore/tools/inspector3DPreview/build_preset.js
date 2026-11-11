const fs = require('fs');
const path = require('path');

const baseDir = path.resolve(__dirname, '../../..');
const bbmodelPath = path.join(baseDir, 'Entity Model/VoidOverseer/Void Overseer.bbmodel');
const bonesPath = path.join(baseDir, 'Entity Model/VoidOverseer/exea_export/models/void_overseer_bones.json');
const animsPath = path.join(baseDir, 'Entity Model/VoidOverseer/exea_export/models/void_overseer_anims.json');

console.log('Reading bbmodel:', bbmodelPath);
const bbmodel = JSON.parse(fs.readFileSync(bbmodelPath, 'utf8'));

console.log('Reading bones:', bonesPath);
const bones = JSON.parse(fs.readFileSync(bonesPath, 'utf8'));

console.log('Reading anims:', animsPath);
const anims = JSON.parse(fs.readFileSync(animsPath, 'utf8'));

let textureDataUrl = '';
if (bbmodel.textures && bbmodel.textures[0] && bbmodel.textures[0].source) {
    textureDataUrl = bbmodel.textures[0].source;
} else {
    const pngPath = path.join(baseDir, 'Entity Model/VoidOverseer/void_overseer_texture.png');
    if (fs.existsSync(pngPath)) {
        const b64 = fs.readFileSync(pngPath).toString('base64');
        textureDataUrl = 'data:image/png;base64,' + b64;
    }
}

const outPath = path.join(__dirname, 'js/default_model_data.js');
const content = `/** Auto-generated default model preset (Void Overseer) **/
window.DEFAULT_MODEL_PRESET = {
    id: "void_overseer",
    name: "Void Overseer",
    textureDataUrl: ${JSON.stringify(textureDataUrl)},
    bbmodel: ${JSON.stringify(bbmodel)},
    bones: ${JSON.stringify(bones)},
    anims: ${JSON.stringify(anims)}
};
`;

fs.writeFileSync(outPath, content, 'utf8');
console.log('Wrote default_model_data.js:', outPath, 'Bytes:', content.length);
