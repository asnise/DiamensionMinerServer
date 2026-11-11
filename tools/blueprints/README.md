# Blueprints & `/dmb` — เครื่องมือแต่งโลกสำหรับ Admin

ใช้ได้เฉพาะ OP (permission `dimensionalminer.admin`)

> ⚠️ **โลกทุกโลกในเซิร์ฟนี้ตั้ง `autoSave=false`** (แมพถือว่า stateless) ถ้าแก้โลกด้วยวิธีอื่นแล้วไม่สั่ง save ของจะหายตอนรีสตาร์ท
> คำสั่ง `/dmb` ทุกคำสั่ง **save ให้อัตโนมัติ** เมื่อทำเสร็จ หรือสั่ง `/dmb save` เองก็ได้

> 🔁 แก้ใน hub (`overworld_t0`) หรือ channel ไหนก็ตาม ระบบจะทำ**ให้ทุก channel พร้อมกัน** (`overworld_t0`, `_ch2`, `_ch3`) ทั้งบล็อกและ mining node
> บล็อกที่เป็น mining node จะไม่ถูกทับ

## คำสั่งในเกม

| คำสั่ง | ทำอะไร |
|---|---|
| `/dmb wand` | ได้ไม้เลือกพื้นที่ (Blaze Rod): คลิกซ้ายบล็อก = pos1, คลิกขวา = pos2 |
| `/dmb pos1` / `/dmb pos2` | ตั้งจุดเป็นบล็อกที่มองอยู่ (หรือที่ยืน) |
| `/dmb sel` | ดูพื้นที่ที่เลือก |
| `/dmb fill <block>` | เติมบล็อกทั้งพื้นที่ เช่น `/dmb fill stone`, `/dmb fill air` |
| `/dmb replace <from> <to>` | แทนที่ เช่น `/dmb replace stone deepslate` |
| `/dmb ores <ore_type> <percent> [all]` | เปลี่ยนหินในพื้นที่ให้เป็น **mining node** ตาม % (ค่าเริ่มต้นเฉพาะหินที่ติดอากาศ = ขุดถึงได้; ใส่ `all` = ทุกก้อน) |
| `/dmb list` | รายชื่อ blueprint |
| `/dmb preview <name>` | ดูว่าจะเปลี่ยนกี่บล็อก/สร้างกี่ node/marker อยู่ตรงไหน — **ยังไม่สร้าง** |
| `/dmb run <name>` | สร้าง blueprint ตรงหน้าที่ยืนอยู่ (ทยอยสร้างทีละ 6,000 บล็อก/tick) |
| `/dmb undo` | ย้อนการกระทำล่าสุดของคุณ (เก็บไว้ 5 ครั้ง) รวมถึงลบ node ที่สร้างไป |
| `/dmb save` | save โลกปัจจุบัน + channel ทั้งหมด |

ขนาดสูงสุดต่อครั้ง 1,000,000 บล็อก

## Blueprint คืออะไร

ไฟล์ JSON ใน `server/plugins/DimensionalMinerCore/blueprints/<name>.json` ที่บอกขั้นตอนการสร้างทีละคำสั่ง
ตัวอย่างที่ติดมากับปลั๊กอิน: [`tutorial_mine_t1.json`](../../DimensionalMinerCore/src/main/resources/blueprints/tutorial_mine_t1.json) (ปลั๊กอิน copy ไปที่โฟลเดอร์ blueprints ให้ตอนเปิดครั้งแรก — ไฟล์ที่แก้แล้วจะไม่ถูกทับ)

### พิกัด
ทุกพิกัดเป็น **`[ข้างหน้า, ขึ้น, ขวา]`** นับจาก**บล็อกที่ผู้เล่นยืน** และ**หมุนตามทิศที่ผู้เล่นหันหน้า** (N/E/S/W)
→ blueprint จะสร้าง "ตรงหน้า" เสมอ ไม่ว่าหันไปทางไหน

### โครงสร้างไฟล์
```json
{
  "description": "ข้อความสั้นๆ ที่แสดงใน /dmb list",
  "seed": 12345,
  "ops": [ { "op": "...", ... }, ... ]
}
```
`seed` ทำให้สุ่มเหมือนเดิมทุกครั้ง (อุโมงค์คดเคี้ยว, ตำแหน่งแร่) — เปลี่ยน seed = ได้รูปร่างใหม่

คำสั่งทำงาน**ตามลำดับ** และเห็นผลของคำสั่งก่อนหน้า (เช่น `ore_vein` รู้ว่าตรงไหนถูกเจาะเป็นอากาศแล้ว)

### คำสั่ง (ops)

| op | ฟิลด์ | ทำอะไร |
|---|---|---|
| `box` | `from`, `to`, `block`, `hollow` (true/false) | กล่องทึบหรือกลวง |
| `sphere` | `center`, `radius` หรือ `radii: [หน้า, ขึ้น, ขวา]`, `block` (ค่าเริ่มต้น `air`) | ทรงกลม/วงรี — ใช้ `air` เพื่อเจาะห้อง |
| `tunnel` | `from`, `direction: [หน้า, ขึ้น, ขวา]`, `length`, `radius`, `wander` (0–1), `block` (ค่าเริ่มต้น `air`) | อุโมงค์แบบหนอน เดินทีละบล็อกและเลี้ยวนิดๆ ตาม `wander` |
| `replace` | `from`, `to`, `match: [...]`, `block` | แทนที่เฉพาะบล็อกที่ตรงกับ `match` |
| `ore_vein` | `from`, `to`, `ore`, `chance` (0–1), `max`, `exposed_only` (ค่าเริ่มต้น true), `replace: [...]` | เปลี่ยนหินในพื้นที่เป็น **mining node** ของ `OreType` นั้น |
| `node` | `at`, `ore` | mining node 1 จุด |
| `light` | `from`, `to`, `block` (ค่าเริ่มต้น `lantern`), `every` | วางไฟบนพื้นที่โล่ง ทุกๆ N บล็อก |
| `marker` | `name`, `at` | ไม่วางอะไร — แค่แสดงพิกัดจริงตอนสร้างเสร็จ (เอาไปใส่ config ดันเจี้ยน/วาร์ปได้) |

ชื่อบล็อกใช้ชื่อแบบ Minecraft (`stone`, `deepslate`, `lantern`) และใส่ state ได้ เช่น `"lantern[hanging=true]"`
`ore` ต้องเป็น `OreType` ที่โหลดอยู่ (ดู `/mine types`)

## ขั้นตอนใช้งาน (ทั้งคนและ Agent)

1. เขียน/แก้ไฟล์ blueprint ใน `server/plugins/DimensionalMinerCore/blueprints/`
   (ถ้าอยากเก็บใน git ให้เก็บต้นฉบับไว้ที่ `tools/blueprints/` แล้ว copy ไป)
2. ไปยืนตรงจุดที่จะสร้าง หันหน้าไปทางที่ต้องการ
3. `/dmb preview <name>` → ดูจำนวนบล็อก/node และพิกัด marker
4. `/dmb run <name>` → สร้าง (save ให้อัตโนมัติ)
5. ไม่ชอบ → `/dmb undo` แล้วแก้ไฟล์ (เปลี่ยนตัวเลข/seed) แล้ว run ใหม่

### ให้ Agent เขียนให้
บอก Agent ประมาณนี้: *"เขียน blueprint `<name>` เป็นถ้ำ/ห้อง/... ขนาด ... มีแร่ `<ore>` ประมาณ N จุด ตามรูปแบบใน `tools/blueprints/README.md`"*
Agent จะเขียนไฟล์ JSON ให้ คุณ preview → run → ดูผลในเกม → บอกให้แก้ต่อ

## ข้อจำกัด
- undo เก็บในหน่วยความจำ (หายเมื่อรีสตาร์ท) และย้อนทีเดียวทั้งก้อน (งานใหญ่มากอาจกระตุกแวบหนึ่ง)
- ยังแก้แมพต้นแบบดันเจี้ยน (`ind_t0`) ด้วย `/dmb` ไม่ได้ เพราะ world นั้นไม่ได้โหลดอยู่ตอนเล่นปกติ
- `light` วางบนพื้นเท่านั้น (ยังไม่วางติดผนัง/เพดาน)
