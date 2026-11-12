// Generates the Dimensional Miner UI resource pack into
// DimensionalMinerCore/src/main/resources/pack/ (the plugin zips and serves it at runtime).
//   node tools/resourcepack/generate.mjs
// Everything is drawn in code (no image editor needed): GUI backgrounds for the Craft and Forge
// windows (font glyphs shown in the inventory title) and small item sprites (button overlay,
// check/cross, Advancement bar, Refine pips). Edit the layout constants below and re-run.
import { deflateSync, crc32 } from 'node:zlib';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../DimensionalMinerCore/src/main/resources/pack');

// ---------------------------------------------------------------- tiny RGBA canvas + PNG
const hex = (h, a = 255) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a]; };
function canvas(w, h) { return { w, h, d: new Uint8Array(w * h * 4) }; }
function px(c, x, y, col) {
  if (x < 0 || y < 0 || x >= c.w || y >= c.h) return;
  const i = (y * c.w + x) * 4, a = col[3] / 255;
  if (a >= 1 || c.d[i + 3] === 0) { c.d.set(col, i); return; }
  for (let k = 0; k < 3; k++) c.d[i + k] = Math.round(col[k] * a + c.d[i + k] * (1 - a));
  c.d[i + 3] = Math.max(c.d[i + 3], col[3]);
}
function rect(c, x, y, w, h, col) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) px(c, x + i, y + j, col); }
function clear(c, x, y) { c.d.fill(0, (y * c.w + x) * 4, (y * c.w + x) * 4 + 4); }
function png(c) {
  const raw = Buffer.alloc((c.w * 4 + 1) * c.h);
  for (let y = 0; y < c.h; y++) { raw[y * (c.w * 4 + 1)] = 0; Buffer.from(c.d.buffer, y * c.w * 4, c.w * 4).copy(raw, y * (c.w * 4 + 1) + 1); }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(c.w, 0); ihdr.writeUInt32BE(c.h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

// ---------------------------------------------------------------- 5x7 pixel font for labels
const FONT = {
  A: ['01110', '10001', '10001', '11111', '10001', '10001', '10001'], B: ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
  C: ['01111', '10000', '10000', '10000', '10000', '10000', '01111'], D: ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
  E: ['11111', '10000', '10000', '11110', '10000', '10000', '11111'], F: ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
  G: ['01111', '10000', '10000', '10011', '10001', '10001', '01111'], H: ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
  I: ['11111', '00100', '00100', '00100', '00100', '00100', '11111'], K: ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
  L: ['10000', '10000', '10000', '10000', '10000', '10000', '11111'], M: ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
  N: ['10001', '11001', '10101', '10011', '10001', '10001', '10001'], O: ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
  P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'], R: ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
  S: ['01111', '10000', '10000', '01110', '00001', '00001', '11110'], T: ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
  U: ['10001', '10001', '10001', '10001', '10001', '10001', '01110'], V: ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
  Q: ['01110', '10001', '10001', '10001', '10101', '10010', '01101'], W: ['10001', '10001', '10001', '10101', '10101', '11011', '10001'],
  Y: ['10001', '10001', '01010', '00100', '00100', '00100', '00100'], '<': ['00010', '00100', '01000', '10000', '01000', '00100', '00010'],
  '>': ['01000', '00100', '00010', '00001', '00010', '00100', '01000'], ' ': ['00000', '00000', '00000', '00000', '00000', '00000', '00000'],
};
function text(c, x, y, s, col, shadow) {
  for (const ch of s) {
    const g = FONT[ch] || FONT[' '];
    for (let j = 0; j < 7; j++) for (let i = 0; i < 5; i++) if (g[j][i] === '1') {
      if (shadow) px(c, x + i + 1, y + j + 1, shadow);
      px(c, x + i, y + j, col);
    }
    x += 6;
  }
}
const textWidth = (s) => s.length * 6 - 1;

// ---------------------------------------------------------------- shared GUI pieces
// Chest slot (row r, col c) frame top-left = (7 + 18c, 17 + 18r); player inventory rows at y=139, hotbar y=197.
const slotXY = (r, col) => [7 + 18 * col, 17 + 18 * r];
function slot(c, x, y, t) {
  rect(c, x, y, 18, 18, t.slotIn);
  rect(c, x, y, 18, 1, t.slotDark); rect(c, x, y, 1, 18, t.slotDark);
  rect(c, x, y + 17, 18, 1, t.slotLight); rect(c, x + 17, y, 1, 18, t.slotLight);
}
function panel(c, t) {
  rect(c, 0, 0, c.w, c.h, t.edge);
  rect(c, 1, 1, c.w - 2, c.h - 2, t.border);
  rect(c, 3, 3, c.w - 6, c.h - 6, t.bg);
  // subtle vertical shading
  for (let y = 3; y < c.h - 3; y++) rect(c, 3, y, c.w - 6, 1, [...t.shade, Math.round(40 * (y / c.h))]);
  for (const [x, y] of [[0, 0], [1, 0], [0, 1], [c.w - 1, 0], [c.w - 2, 0], [c.w - 1, 1], [0, c.h - 1], [1, c.h - 1], [0, c.h - 2], [c.w - 1, c.h - 1], [c.w - 2, c.h - 1], [c.w - 1, c.h - 2]]) clear(c, x, y);
}
function playerInventory(c, t) {
  rect(c, 3, 126, c.w - 6, 1, t.border);
  for (let r = 0; r < 3; r++) for (let col = 0; col < 9; col++) slot(c, 7 + 18 * col, 139 + 18 * r, t);
  for (let col = 0; col < 9; col++) slot(c, 7 + 18 * col, 197, t);
}
function button(c, x, y, w, h, label, fill, ink, t) {
  rect(c, x + 1, y, w - 2, h, t.edge); rect(c, x, y + 1, w, h - 2, t.edge);
  rect(c, x + 1, y + 1, w - 2, h - 2, fill);
  rect(c, x + 1, y + 1, w - 2, 1, hex('#ffffff', 90)); rect(c, x + 1, y + h - 2, w - 2, 1, hex('#000000', 80));
  text(c, x + Math.floor((w - textWidth(label)) / 2), y + Math.floor((h - 7) / 2), label, ink);
}
function bigFrame(c, x, y, col) { // 22x22 frame around an 18x18 slot at (x+2, y+2)
  rect(c, x, y, 22, 22, col); rect(c, x + 1, y + 1, 20, 20, hex('#000000', 120));
}

// ---------------------------------------------------------------- Craft background
const CRAFT = {
  edge: hex('#0f0c22'), border: hex('#e8b94a'), bg: hex('#2d2a5a'), shade: [0, 0, 0],
  slotIn: hex('#1d1a40'), slotDark: hex('#100d26'), slotLight: hex('#5a54a0'), label: hex('#e8b94a'), dim: hex('#b9b9d9'),
};
function craftBg() {
  const c = canvas(176, 222), t = CRAFT;
  panel(c, t);
  text(c, 8, 5, 'CRAFTING', t.label, hex('#000000', 140));
  // recipe list: cols 0-1, rows 0-4, page buttons on row 5
  rect(c, 5, 15, 40, 112, hex('#000000', 60));
  for (let r = 0; r < 5; r++) for (let col = 0; col < 2; col++) slot(c, ...slotXY(r, col), t);
  button(c, ...slotXY(5, 0), 18, 18, '<', hex('#4a4580'), t.label, t);
  button(c, ...slotXY(5, 1), 18, 18, '>', hex('#4a4580'), t.label, t);
  rect(c, 48, 17, 1, 108, hex('#e8b94a', 110));
  // result
  const [ox, oy] = slotXY(0, 3);
  bigFrame(c, ox - 2, oy - 2, t.border); slot(c, ox, oy, t);
  text(c, 86, 22, 'RESULT', t.dim);
  // inputs (row 2) + status (row 3)
  text(c, 62, 40, 'NEEDS', t.label, hex('#000000', 140));
  for (let col = 3; col < 9; col++) slot(c, ...slotXY(2, col), t);
  for (let col = 3; col < 9; col++) { const [x, y] = slotXY(3, col); rect(c, x + 3, y + 3, 12, 12, hex('#000000', 50)); }
  // energy
  slot(c, ...slotXY(4, 3), t);
  text(c, 86, 94, 'ENERGY', t.dim);
  // craft button (row 5, cols 5-7)
  const [bx, by] = slotXY(5, 5);
  button(c, bx, by, 54, 18, 'CRAFT', hex('#e8b94a'), hex('#2a1a00'), t);
  // mini-game button (row 5, cols 3-4)
  const [gx, gy] = slotXY(5, 3);
  button(c, gx, gy, 36, 18, 'FORGE', hex('#d9682a'), hex('#fff1e0'), t);
  playerInventory(c, t);
  return c;
}

// ---------------------------------------------------------------- Forge Heat (mini-game) background
const HEAT = {
  edge: hex('#160605'), border: hex('#ff5a2a'), bg: hex('#2a1210'), shade: [0, 0, 0],
  slotIn: hex('#1c0b09'), slotDark: hex('#0e0504'), slotLight: hex('#6e2f22'), label: hex('#ff7a3d'), dim: hex('#ffd2b8'),
};
// ---------------------------------------------------------------- Inheritance background
const INHERIT = {
  edge: hex('#0c0716'), border: hex('#b07aff'), bg: hex('#221838'), shade: [0, 0, 0],
  slotIn: hex('#160f26'), slotDark: hex('#0a0614'), slotLight: hex('#5a4490'), label: hex('#c89aff'), dim: hex('#e6d6ff'),
};
function inheritBg() {
  const c = canvas(176, 222), t = INHERIT;
  panel(c, t);
  text(c, 8, 5, 'INHERITANCE', t.label, hex('#000000', 140));
  slot(c, ...slotXY(0, 8), t); // help
  const [sx, sy] = slotXY(1, 1), [rx, ry] = slotXY(1, 4), [tx, ty] = slotXY(1, 7);
  bigFrame(c, sx - 2, sy - 2, hex('#ff5a5a')); slot(c, sx, sy, t);
  bigFrame(c, rx - 2, ry - 2, t.border); slot(c, rx, ry, t);
  bigFrame(c, tx - 2, ty - 2, hex('#6ee36e')); slot(c, tx, ty, t);
  text(c, sx + 9 - 17, sy + 22, 'SOURCE', hex('#ff9a9a'));
  text(c, rx + 9 - 17, ry + 22, 'RESULT', t.dim);
  text(c, tx + 9 - 17, ty + 22, 'TARGET', hex('#a8f0a8'));
  // arrows source -> result -> target
  for (const [x0, x1] of [[sx + 22, rx - 4], [rx + 22, tx - 4]]) {
    rect(c, x0, sy + 8, x1 - x0, 2, t.label);
    for (let i = 0; i < 4; i++) rect(c, x1 - 4 + i, sy + 5 + i, 1, 8 - 2 * i, t.label);
  }
  // choose one (row 3)
  const kinds = [['ADVANCE', 0, '#8a5a2a'], ['REFINE', 3, '#8a3a2a'], ['ENGRAVE', 6, '#6a3a8a']];
  for (const [label, col, fill] of kinds) { const [x, y] = slotXY(3, col); button(c, x, y, 54, 18, label, hex(fill), hex('#ffffff'), t); }
  // back + transfer (row 5)
  button(c, ...slotXY(5, 0), 18, 18, '<', hex('#3a2a5a'), t.label, t);
  const [gx, gy] = slotXY(5, 3);
  button(c, gx, gy, 54, 18, 'TRANSFER', hex('#d9682a'), hex('#fff1e0'), t);
  playerInventory(c, t);
  return c;
}

function heatBg() {
  const c = canvas(176, 222), t = HEAT;
  panel(c, t);
  text(c, 8, 5, 'FORGE HEAT', t.label, hex('#000000', 140));
  // result preview (0,1) and furnace heat (0,4)
  for (const col of [1, 4]) { const [x, y] = slotXY(0, col); bigFrame(c, x - 2, y - 2, t.border); slot(c, x, y, t); }
  text(c, 105, 22, 'HEAT', t.dim);
  slot(c, ...slotXY(0, 8), t); // help
  // bars: label in cols 0-2, track in cols 3-7
  const labels = ['PROGRESS', 'QUALITY', 'INTEGRITY', 'FOCUS'];
  labels.forEach((l, i) => {
    const r = i + 1, [x, y] = slotXY(r, 3);
    text(c, 8, y + 6, l, t.label, hex('#000000', 140));
    rect(c, x, y + 1, 18 * 5, 16, hex('#000000', 90));
  });
  // actions (row 5, cols 0-4) and STOP (cols 6-8)
  for (let col = 0; col < 5; col++) slot(c, ...slotXY(5, col), t);
  const [sx, sy] = slotXY(5, 6);
  button(c, sx, sy, 54, 18, 'STOP', hex('#7a2a20'), hex('#ffd2b8'), t);
  playerInventory(c, t);
  return c;
}

// ---------------------------------------------------------------- Forge background
const FORGE = {
  edge: hex('#1a0b04'), border: hex('#ff8a3d'), bg: hex('#3a1f12'), shade: [0, 0, 0],
  slotIn: hex('#24130a'), slotDark: hex('#140904'), slotLight: hex('#7a4a2a'), label: hex('#ff8a3d'), dim: hex('#ffd9a8'),
};
function forgeBg() {
  const c = canvas(176, 222), t = FORGE;
  panel(c, t);
  text(c, 8, 5, 'FORGE', t.label, hex('#000000', 140));
  // selected gear (row 1, col 1)
  const [gx, gy] = slotXY(1, 1);
  bigFrame(c, gx - 2, gy - 2, t.border); slot(c, gx, gy, t);
  text(c, 22, 59, 'GEAR', t.dim);
  // stats (row 1, col 3)
  slot(c, ...slotXY(1, 3), t);
  text(c, 86, 40, 'STATS', t.dim);
  // help (row 0, col 8)
  slot(c, ...slotXY(0, 8), t);
  // engrave slots (row 2, cols 3-5), violet frames
  const ENG = { ...t, slotIn: hex('#26122e'), slotDark: hex('#12081a'), slotLight: hex('#8a4ab0') };
  for (let col = 3; col <= 5; col++) slot(c, ...slotXY(2, col), ENG);
  text(c, 118, 59, 'ENGRAVE', hex('#d89aff'), hex('#000000', 140));
  // advancement bar (row 2, cols 3-7) and refine pips (row 3, cols 3-7)
  text(c, 26, 76, 'ADV', t.label, hex('#000000', 140));
  text(c, 8, 94, 'REFINE', t.label, hex('#000000', 140));
  for (const r of [3, 4]) { const [x, y] = slotXY(r, 3); rect(c, x - 1, y + 1, 18 * 5 + 2, 16, hex('#000000', 70)); }
  // buttons (row 5)
  const [ax, ay] = slotXY(5, 1);
  button(c, ax, ay, 54, 18, 'ADVANCE', hex('#d98a3a'), hex('#2a1200'), t);
  const [rx, ry] = slotXY(5, 5);
  button(c, rx, ry, 54, 18, 'REFINE', hex('#c0582a'), hex('#fff1e0'), t);
  // inheritance window button (row 1, cols 6-8)
  const [ix, iy] = slotXY(1, 6);
  button(c, ix, iy, 54, 18, 'INHERIT', hex('#6a3a8a'), hex('#f0dcff'), t);
  // self repair button (row 0, cols 5-7)
  const [px0, py0] = slotXY(0, 5);
  button(c, px0, py0, 54, 18, 'REPAIR', hex('#4a6a3a'), hex('#e8ffd8'), t);
  playerInventory(c, t);
  return c;
}

// ---------------------------------------------------------------- item sprites (16x16)
function sprite(draw) { const c = canvas(16, 16); draw(c); return c; }
const SPRITES = {
  dim: sprite(c => rect(c, 0, 0, 16, 16, hex('#000000', 150))),
  check: sprite(c => { const g = hex('#5be35b'), d = hex('#1b5e1b'); for (const [x, y] of [[3, 8], [4, 9], [5, 10], [6, 11], [7, 10], [8, 9], [9, 8], [10, 7], [11, 6], [12, 5]]) { rect(c, x, y + 1, 2, 2, d); rect(c, x, y, 2, 2, g); } }),
  cross: sprite(c => { const r = hex('#ff5a4a'), d = hex('#6e1a12'); for (let i = 3; i <= 11; i++) { rect(c, i, i + 1, 2, 2, d); rect(c, 14 - i, i + 1, 2, 2, d); } for (let i = 3; i <= 11; i++) { rect(c, i, i, 2, 2, r); rect(c, 14 - i, i, 2, 2, r); } }),
};
// Bars: 4 fill steps per sprite, drawn edge to edge; FULL_SLOT scales them to 18 px so neighbours touch.
const barSet = (name, track, fill, shine) => { for (let n = 0; n <= 4; n++) SPRITES[name + n] = sprite(c => {
  rect(c, 0, 5, 16, 6, hex('#000000')); rect(c, 0, 6, 16, 4, hex(track));
  if (n) { rect(c, 0, 6, n * 4, 4, hex(fill)); rect(c, 0, 6, n * 4, 1, hex(shine)); }
}); };
barSet('bar', '#2a170b', '#ffb347', '#ffe0a0');   // forge advancement
barSet('pbar', '#2a1208', '#ff8a3d', '#ffd0a0');  // heat: progress
barSet('qbar', '#0b1e2a', '#4ad9ff', '#c8f4ff');  // heat: quality
barSet('dbar', '#0e2410', '#6ee36e', '#d0ffd0');  // heat: integrity
barSet('fbar', '#1e0e2a', '#c07aff', '#ecd6ff');  // heat: focus

// Furnace heat flames
const flame = (outer, inner, core) => sprite(c => {
  const shape = ['0000000100000000', '0000001110000000', '0000011110000000', '0000011111000000', '0000111111100000',
    '0001111111100100', '0011111111101100', '0011111111111100', '0111111111111110', '0111111111111110',
    '0111111111111110', '0111111111111110', '0011111111111100', '0001111111111000', '0000111111110000', '0000000000000000'];
  shape.forEach((row, y) => [...row].forEach((v, x) => { if (v === '1') px(c, x, y, hex(outer)); }));
  for (let y = 7; y < 14; y++) for (let x = 4; x < 12; x++) if (shape[y][x - 2] === '1' && shape[y][x + 2] === '1') px(c, x, y, hex(inner));
  for (let y = 10; y < 13; y++) for (let x = 6; x < 10; x++) px(c, x, y, hex(core));
});
SPRITES.heat_normal = flame('#d9601a', '#ff9a3d', '#ffd27a');
SPRITES.heat_hot = flame('#b8160c', '#ff4a2a', '#ffb070');
SPRITES.heat_white = flame('#ffb347', '#fff1b0', '#ffffff');
SPRITES.heat_unstable = flame('#5a1a9a', '#b04aff', '#ff7ae0');

// Action icons (+ greyed "_off" variants)
const ACTION_ART = {
  hammer: c => { // handle + head
    for (let i = 0; i < 9; i++) { rect(c, 3 + i, 12 - i, 2, 2, hex('#5a3418')); px(c, 3 + i, 12 - i, hex('#8a5a30')); }
    rect(c, 8, 1, 7, 6, hex('#3a3a44')); rect(c, 9, 2, 5, 4, hex('#9a9aa8')); rect(c, 9, 2, 5, 1, hex('#d8d8e4'));
  },
  fold: c => { // stacked plates
    [[2, 3, '#7a7a88'], [4, 7, '#a8a8b8'], [2, 11, '#d0d0dc']].forEach(([x, y, col]) => {
      rect(c, x, y, 12, 3, hex('#202028')); rect(c, x + 1, y, 10, 2, hex(col));
    });
  },
  temper: c => { // sparkle
    const g = hex('#ffd34d'), w = hex('#fff6c8');
    rect(c, 7, 1, 2, 14, g); rect(c, 1, 7, 14, 2, g); rect(c, 5, 5, 6, 6, g); rect(c, 6, 6, 4, 4, w);
    for (const [x, y] of [[3, 3], [12, 3], [3, 12], [12, 12]]) px(c, x, y, g);
  },
  quench: c => { // water drop
    const rows = [[7, 8], [7, 8], [6, 9], [6, 9], [5, 10], [4, 11], [3, 12], [3, 12], [3, 12], [3, 12], [4, 11], [5, 10], [6, 9]];
    rows.forEach(([a, b], j) => { for (let x = a; x <= b; x++) px(c, x, 2 + j, hex('#2a7aff')); });
    rect(c, 5, 8, 2, 3, hex('#bfe0ff'));
  },
  rush: c => { // lightning
    const pts = [[9, 1], [8, 2], [7, 3], [6, 4], [5, 5], [4, 6], [4, 7], [5, 7], [6, 7], [7, 7], [8, 7], [7, 8], [6, 9], [5, 10], [4, 11], [3, 12], [3, 13]];
    for (const [x, y] of pts) { rect(c, x + 1, y + 1, 3, 2, hex('#6a4a00')); rect(c, x, y, 3, 2, hex('#ffe04a')); }
  },
};
const grey = (c) => { for (let i = 0; i < c.d.length; i += 4) { const v = Math.round((c.d[i] * 0.3 + c.d[i + 1] * 0.59 + c.d[i + 2] * 0.11) * 0.55); c.d[i] = c.d[i + 1] = c.d[i + 2] = v; } return c; };
for (const [name, draw] of Object.entries(ACTION_ART)) {
  SPRITES['act_' + name] = sprite(draw);
  SPRITES['act_' + name + '_off'] = grey(sprite(draw));
}
// Sprites that must cover the whole 18 px slot (button dimmer, bars): scaled up in the GUI.
const FULL_SLOT = new Set(['dim', ...Object.keys(SPRITES).filter(n => /^[pqdf]?bar\d$/.test(n))]);
const diamond = (c, cx, on) => {
  const rows = [[0, 0], [-1, 1], [-2, 2], [-3, 3], [-2, 2], [-1, 1], [0, 0]];
  rows.forEach(([a, b], j) => { for (let i = a; i <= b; i++) px(c, cx + i, 4 + j, hex('#000000')); });
  rows.slice(1, 6).forEach(([a, b], j) => { for (let i = a + 1; i <= b - 1; i++) px(c, cx + i, 5 + j, on ? hex('#ffd34d') : hex('#3b2414')); });
  if (on) px(c, cx - 1, 6, hex('#fff6c8'));
};
for (let n = 0; n <= 2; n++) SPRITES['pip' + n] = sprite(c => { diamond(c, 4, n >= 1); diamond(c, 11, n >= 2); });

// ---------------------------------------------------------------- write the pack
function out(path, data) { const p = join(ROOT, path); mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, data); }
const json = (o) => JSON.stringify(o, null, 2) + '\n';

rmSync(ROOT, { recursive: true, force: true });
out('pack.mcmeta', json({ pack: { description: 'Dimensional Miner UI', pack_format: 75, supported_formats: [46, 999], min_format: 46, max_format: 999 } }));
out('assets/dm/textures/gui/craft.png', png(craftBg()));
out('assets/dm/textures/gui/forge.png', png(forgeBg()));
out('assets/dm/textures/gui/heat.png', png(heatBg()));
out('assets/dm/textures/gui/inherit.png', png(inheritBg()));

// GUI font:  craft background,  forge background; \uF8xx negative/positive spaces.
const advances = {};
for (let i = 0; i <= 8; i++) { advances[String.fromCharCode(0xF800 + i)] = -(1 << i); advances[String.fromCharCode(0xF810 + i)] = 1 << i; }
out('assets/dm/font/gui.json', json({
  providers: [
    { type: 'space', advances },
    { type: 'bitmap', file: 'dm:gui/craft.png', ascent: 13, height: 222, chars: [''] },
    { type: 'bitmap', file: 'dm:gui/forge.png', ascent: 13, height: 222, chars: [''] },
    { type: 'bitmap', file: 'dm:gui/heat.png', ascent: 13, height: 222, chars: [''] },
    { type: 'bitmap', file: 'dm:gui/inherit.png', ascent: 13, height: 222, chars: [''] },
  ],
}));

out('assets/dm/items/blank.json', json({ model: { type: 'minecraft:empty' } }));
for (const [name, c] of Object.entries(SPRITES)) {
  out(`assets/dm/textures/item/${name}.png`, png(c));
  const model = { parent: 'minecraft:item/generated', textures: { layer0: `dm:item/${name}` } };
  // 16 px item -> 18 px: fills the slot border too, so a dimmed painted button has no bright seams.
  if (FULL_SLOT.has(name)) model.display = { gui: { scale: [1.125, 1.125, 1.125] } };
  out(`assets/dm/models/item/${name}.json`, json(model));
  out(`assets/dm/items/${name}.json`, json({ model: { type: 'minecraft:model', model: `dm:item/${name}` } }));
}
// preview images for humans (not part of the pack)
const PREVIEW = join(dirname(fileURLToPath(import.meta.url)), 'preview');
mkdirSync(PREVIEW, { recursive: true });
writeFileSync(join(PREVIEW, 'craft.png'), png(craftBg()));
writeFileSync(join(PREVIEW, 'forge.png'), png(forgeBg()));
writeFileSync(join(PREVIEW, 'heat.png'), png(heatBg()));
writeFileSync(join(PREVIEW, 'inherit.png'), png(inheritBg()));
console.log('pack written to', ROOT, '-', Object.keys(SPRITES).length, 'sprites');
