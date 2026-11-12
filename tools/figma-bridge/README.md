# DM Figma Bridge

Figma plugin สำหรับให้ Agent (หรือคนก็ได้) ส่งสคริปต์ Figma Plugin API ไปรันในไฟล์ที่เปิดอยู่
ไม่ติดลิมิตจำนวนครั้งของ Figma MCP ทางการ ใช้ socket relay ตัวเดียวกับ Talk To Figma

## ติดตั้งครั้งแรก
1. Figma Desktop → Plugins → Development → **Import plugin from manifest…** → เลือก `tools/figma-bridge/manifest.json`
2. รัน socket relay ค้างไว้: `bunx cursor-talk-to-figma-socket` (พอร์ต 3055)
3. เปิดไฟล์ Figma → Plugins → Development → **DM Figma Bridge** → ขึ้น `connected: flowux`

แก้ `code.js` / `ui.html` แล้วแค่ปิด-เปิด plugin ใหม่ (ไม่ต้อง import ซ้ำ)

## ใช้งาน
```
node tools/figma-bridge/run.mjs my-script.js
node tools/figma-bridge/run.mjs shot.js --png board.png
```
สคริปต์รันเป็น async function มี `figma` ให้ใช้ และ `return` ค่ากลับมา (JSON) เช่น

```js
const node = await figma.getNodeByIdAsync('29:3');
return { png: figma.base64Encode(await node.exportAsync({ format: 'PNG', constraint: { type: 'SCALE', value: 0.5 } })) };
```
