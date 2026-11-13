# 📋 เอกสารส่งมอบงาน (Handover Specification & Progress Summary)
**โครงการ:** EXeaEntityCore (Custom 3D Bedrock-Style Animated Entity Engine) — บิลด์กับ **Paper 26.2** (`build.gradle.kts`), `plugin.yml` ตั้ง `api-version: '1.21'`  
**วันที่:** 18 กันยายน 2026 (ตรวจทานล่าสุด 12 พฤศจิกายน 2026)  
**สถานะ:** ⚠️ **ต้นแบบที่ใช้งานได้กับโมเดลเดียว (`void_overseer`)** — ไม่ใช่ "สมบูรณ์ 100%" ตามที่เอกสารฉบับแรกเขียนไว้

> ## ✅ สถานะจริงหลังรีวิวโค้ด (12 พ.ย. 2026, Task.md R15 / R29 / R35 / R38)
>
> **ทำงานได้จริง:** เรนเดอร์กระดูกด้วย `ItemDisplay`, เล่นแอนิเมชัน (idle / move / attack), ระบบ hitbox ผ่าน Husk/ArmorStand, คำสั่ง `/exea`, Blockbench exporter และ inspector
>
> **แก้ไขแล้ว:**
> - กระดูกไม่ถูกบันทึกลง disk แล้ว และกระดูกที่ค้างจาก build เก่าจะถูกลบเมื่อ chunk โหลด (R15)
> - chunk ticket นับจำนวน, การเก็บกวาดลบเฉพาะ entity ที่มี tag ของ EXea, ฉากตาย 3 วินาทีเล่นจริงแล้ว (R29)
>
> **ข้อความในเอกสารเดิมที่ไม่ตรงกับโค้ด:**
> | เอกสารเดิมเขียนว่า | ความจริงในโค้ด |
> |---|---|
> | Hermite interpolation | ไม่มี — มีแค่ Lerp (ตำแหน่ง/สเกล) และ Slerp (การหมุน) ใน `KeyframeTimeline` |
> | Animation Events มี "ดาเมจกวาด" | `TimelineEvent` มีฟิลด์ `hitboxRange` / `hitboxDamage` แต่**ไม่มีโค้ดไหนอ่านไปใช้** — ยังไม่มีดาเมจจากแอนิเมชัน |
> | Standalone ใช้กับโมเดลอะไรก็ได้ | ผูกกับ `void_overseer` ตายตัว: item-model key `exea:void_overseer/...`, ชื่อ "Void Overseer", HP 250 ใน `EntityManager.spawn` |
> | Paper 1.21.4 | บิลด์กับ Paper 26.2 |
> | Self-Healing Renderer | ทำงาน แต่ถ้ากระดูกชิ้นเดียวหายจะสร้างใหม่**ทั้งชุด** |
> | `/exea pack generate` | เป็นแค่ข้อความ ยังไม่ได้สร้าง resource pack จริง |
>
> **แก้ให้ตรงกับ Blockbench แล้ว (13 พ.ย. 2026, Task.md 12.1):** ลำดับ Euler ZYX + บวกมุม, catmullrom/step, ชดเชย ItemDisplay 180°, `mesh_scale`, element rotation หลายแกน, โหลดได้ทุกโมเดล + `<id>_meta.json` (ชื่อ/HP/bone เรืองแสง), pack รวมทุกโมเดล (รายละเอียดในหัวข้อ "ข้อตกลงพิกัด" ด้านล่าง)
>
> **ยังค้าง:** ต้นทุนต่อ tick (teleport ทีละกระดูก), Molang ในค่า keyframe, bezier (เล่นเป็น linear), global rotation, `inspector.html` ยังใช้สูตรเก่า

## ข้อตกลงพิกัด Blockbench → เกม (ห้ามแก้โดยไม่เข้าใจ)

| เรื่อง | Blockbench | ในโค้ด |
|---|---|---|
| พื้นที่ของโมเดล | ฉาก three.js = พิกัดโมเดล Minecraft (หน่วย px, 16 px = 1 บล็อก, หน้าโมเดลหัน -Z) | `AnimationController` คูณ 1/16 |
| Matrix ของกระดูก | `T(origin − parentOrigin + animPos) · R · S` | `updateBoneRecursive` |
| การหมุน | มุมพัก + มุมแอนิเมชัน บวกกันแบบ Euler แล้วสร้างด้วยลำดับ `'ZYX'` = Rz·Ry·Rx | `rotationZYX(z, y, x)` |
| ค่า keyframe | ใน `.bbmodel` เป็นพิกัดฉากอยู่แล้ว: Blockbench 5.2 **บวกตรงๆ** (`mesh.rotation.x += v`, `mesh.position.x += v`) ไม่กลับเครื่องหมายในทุก format (ตรวจจาก source + วัดค่าจริงผ่าน MCP) | `ModelLoader` ใช้ค่าตรงๆ (position ÷16) — **ห้ามกลับเครื่องหมาย** (รอบแรกเคยกลับผิด) |
| Interpolation | linear / catmullrom (THREE.SplineCurve ใช้ key ข้างเคียง) / step, keyframe แยก pre/post | `KeyframeTimeline.evaluate` (port จาก `BoneAnimator.interpolate`) |
| ItemDisplay | client หมุนไอเทม 180° รอบ Y เองก่อนวาด | root = `FLIP_Y` (หันหน้าตามม็อบ) และต่อท้าย `FLIP_Y` ทุกกระดูก (หักล้าง) |
| Mesh | element อยู่รอบ pivot (+8), ต้องอยู่ใน −16..32 | exporter ย่อด้วย `mesh_scale` ถ้าเกิน 24 px จาก pivot, เกมขยายกลับ |
| Element rotation | หมุนได้ทุกแกน ลำดับ X→Y→Z | export เป็น `rotation: {origin, x, y, z}` (รูปแบบ 1.21.11+) |

IK: EXea เล่นแบบ FK เท่านั้น → ใช้ IK ใน Blockbench ตอนทำท่า แล้ว `node EXeaEntityCore/tools/bbbake.mjs` (bake IK เป็น rotation keyframe ทุก 0.05 วิ) ก่อน export · exporter ข้าม null object

**อัปเดตทุกโมเดลในคำสั่งเดียว:** ดับเบิลคลิก `update-exea.bat` (หรือ `update-exea.ps1`) ที่ root ของ repo จะ export ทุกโฟลเดอร์ใน `Entity Model/` ที่มี `<id>_meta.json` คู่กับ `.bbmodel` แล้ว build jar (หยุดถ้าเซิร์ฟเปิดอยู่) · **ผู้เล่นได้ pack อัตโนมัติ** ตอนเข้าเซิร์ฟ: `PackServer` เสิร์ฟ pack ที่ฝังใน jar (`exeapack/`) ที่ port 8086 ส่งด้วย `addResourcePack` หลัง pack UI ของ DimensionalMinerCore (ซึ่งใช้ `setResourcePack` ที่ล้าง pack อื่น) และส่งซ้ำเองถ้าโดนล้าง · ตั้งค่าใน `plugins/EXeaEntityCore/config.yml` (`resource_pack.public_url` สำหรับผู้เล่นจากเครื่องอื่น) · `/exea pack` = ส่ง pack ให้ตัวเองอีกครั้ง

ขั้นตอน export: บันทึก `.bbmodel` → `node EXeaEntityCore/tools/exea_exporter.js "<file>.bbmodel" <id>` (Blockbench plugin เขียนแค่ JSON กระดูก/แอนิเมชัน ไม่สร้าง pack) → ดูบรรทัด `[WARN]` (ส่วนที่เกมจะไม่ตรง Blockbench) → build jar → ใส่ pack ใหม่
>
> **ถ้าจะใช้เป็นบอสในดันเจี้ยน / World Boss (Task 12.4 เฟส 1, 13 พ.ย. 2026):** บอส EXea ไม่ส่ง `EntityDeathEvent` ตามปกติ (ยกเลิกดาเมจสุดท้ายเพื่อเล่นฉากตาย) → ให้ listen event ของ EXea แทน:
> - `EXeaEntitySpawnEvent(entity, templateId)` หลัง spawn
> - `EXeaEntityDeathEvent` 1 ครั้งตอนเริ่มฉากตาย: `getKiller()` (คนตีครั้งสุดท้าย, ลูกธนู = คนยิง), `getDamageByAttacker()` (UUID → HP ที่ทำ, รวมไม่เกิน HP เต็ม)
> - `EXeaEntityRemoveEvent` 1 ครั้งตอนลบจริง: `getReason()` = DIED / REMOVED / DRIVER_LOST / PLUGIN_DISABLE
> - `EXeaAPI.getInstance()`: `spawnEntity`, `getModelIds`, `getModelId`, `getDamageByAttacker`, `isDying`, `kill` (เล่นฉากตาย), `remove` (ลบทันที)
> - ไม่มีโมเดล fallback ในโค้ดแล้ว (เดิม void_overseer) และ `/exea spawn` ต้องใส่ id · แผนเฟสต่อไป: `EXeaEntityCore/docs/STANDALONE_CORE_PLAN.md`
>
> **ลดภาระต่อ tick (Task 12.6):** กระดูกทุกชิ้นนั่ง (passenger) บน carrier `ItemDisplay` ล่องหน 1 ตัวที่ตามตัวขับ → เดิน = teleport 1 ครั้งต่อโมเดล (เดิม 1 ครั้งต่อกระดูก) · หันตัว = ใส่ yaw ใน matrix ของกระดูก (`AnimationController` root = Ry(-bodyYaw)·FLIP_Y, display yaw 0) · ไม่มี client ติดตาม (`getTrackedBy` ว่าง) = ไม่คำนวณ/ไม่ส่งท่า แต่นาฬิกาท่าและ event ยังเดิน (`getBoneLocation` คำนวณให้ตอนเรียก) · Matrix4f ใช้ซ้ำต่อกระดูก · ตัวขับไม่มี passenger → ปลั๊กอินอื่น teleport ข้ามโลกได้ (EXea respawn กระดูกเอง) · ดูตัวเลข `/exea stats`
> **คู่มือภาษาไทยสำหรับคนเขียนปลั๊กอิน: `EXeaEntityCore/docs/API_GUIDE_TH.md`** (โค้ดทุกตัวอย่าง compile ผ่าน: `docs/examples/GuideExamples.java`)
> **คู่มือนักพัฒนา (อังกฤษ): `EXeaEntityCore/docs/API.md`** · API jar: `cd EXeaEntityCore; .\gradlew.bat publishToMavenLocal` → ปลั๊กอินอื่นใช้ `compileOnly("com.exea:exea-entity-core-api:1.1.0")` + `depend: [EXeaEntityCore]` · ปลายทาง plugin jar ตั้งได้: `.\gradlew.bat jar -PoutDir=<folder>` (ค่าเริ่มต้น `../server/plugins`) · version ใน plugin.yml มาจาก `gradle.properties` (1.1.0)
> **API สำหรับปลั๊กอินอื่น (Task 12.4 เฟส 2):** `EXeaAPI.getInstance().spawn(id, loc, SpawnOptions.create().driver(EntityType.ZOMBIE).health(2000))` → `EXeaEntity` (`playAnimation(name, speed, loop)`, `getBoneLocation("hand_r")`, `getBones`, `kill`, `remove`, `getDamageByAttacker`) · `get(entity)`, `getAll()`, `registerBehavior(modelId หรือชื่อ, e -> new MyBehavior())` · `EXeaBehavior` (onSpawn/onTick/onDamaged/onAttack/onAnimationEnd/onDeath/onRemove) — extend `DefaultBehavior` เพื่อแก้แค่บางส่วน (เช่น `pickAttack`) · engine ยังคุมการวาด ฉากตาย และ event เสมอ
> **เพิ่มโมเดลโดยไม่ build jar (เฟส 3):** `node EXeaEntityCore/tools/exea_exporter.js "<file>.bbmodel" <id> --server <server>/plugins/EXeaEntityCore` → เขียน `models/` + `pack/` ในโฟลเดอร์นั้นเท่านั้น → ในเกม `/exea reload` (โหลดโมเดลใหม่ + สร้าง pack ใหม่ (jar `exeapack/` + data folder `pack/`, ไฟล์ใน data folder ชนะ) + ส่งให้ผู้เล่นที่ออนไลน์ถ้า pack เปลี่ยน) · ไม่ใส่ `--server` = workflow เดิมของ repo (ไม่เปลี่ยน)
> **Effect keyframe ของ Blockbench → เกม (เฟส 3):** exporter ทั้งสองตัวเขียน `events` ต่อท่าใน `_anims.json` · Sound / Particle (locator = ชื่อ locator หรือ bone) / Timeline script: `hit radius= damage= [cone=] [bone=] [knockback=]` ทำดาเมจผ่าน event ปกติ (attacker = driver, `getSkillId()` = ชื่อท่า, ไม่เรียก `onAttack` ซ้ำ) · บรรทัดอื่น → `EXeaAnimationEventTriggerEvent(tag = บรรทัดนั้น)` · **ลบเสียง/อนุภาค "slash" ที่เคยฝังในโค้ดแล้ว** → ท่า `attack_slash` ของ Void Overseer จะเงียบจนกว่าจะใส่ effect keyframe ใน Blockbench
> **meta ใหม่ (ไม่ใส่ = ค่าเดิม):** `driver` (`"husk"`, `"zombie"`, `"armor_stand"`...), `follow_range`, `behavior`, `hurt_sound` / `death_sound` (key เสียง, `""` = ไม่มี), `hurt_particle` / `death_particle`, `death_ticks` · ทดสอบตำแหน่งกระดูก: `/exea bone <bone>`
>
> เนื้อหาด้านล่างคือเอกสารฉบับเดิม เก็บไว้เป็นประวัติ — ให้เชื่อกล่องนี้ก่อนเมื่อขัดกัน

---

## 1. วัตถุประสงค์และสถาปัตยกรรม (Core Architecture)
- **Standalone Core:** `EXeaEntityCore` พัฒนาแยกจาก `DimensionalMinerCore` 100% (ไม่มี Dependency ผูกมัด สามารถหยิบ Jar ไปรันเดี่ยวได้ทันที)
- **Rendering Engine:** ใช้ Minecraft 1.20+ `ItemDisplay` ซ้อนกระดูกเป็นลำดับชั้น (Bone Hierarchy Matrix / Forward Kinematics) เชื่อมโยงกับ Root Driver (Husk/ArmorStand)
- **Resource Pack Generation:** ควบคุม CustomModelData (1001-1022) และ Minecraft 1.21.4 `item_model` Definition ใน `assets/exea/`
- **Animation System:** KeyframeTimeline พร้อม Lerp/Slerp interpolation และ Animation Events (เสียง, อนุภาค) — ~~Hermite~~, ~~ดาเมจกวาด~~ ยังไม่มีในโค้ด (ดูกล่องสถานะจริงด้านบน)

---

## 2. สิ่งที่ได้ทำไปแล้วทั้งหมด (Completed Milestones)

### ก. ระบบ Rendering & Animation (Java Core)
1. **[DisplayRenderer.java](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/src/main/java/com/exea/entity/core/render/DisplayRenderer.java):**
   - แก้ไขปัญหา Display Entity กระตุก/หลุด Sync ด้วย `setTeleportDuration(1)`
   - ระบบ **Self-Healing Renderer:** ตรวจจับหาก ItemDisplay ตัวใดกลายเป็นซากหรือ Invalid ระบบจะ Auto-Respawn ทั้งชุดกระดูกทันที ณ พิกัดปัจจุบัน
   - รองรับ `forceSync()` เพื่อส่ง Packet ให้ Client ทุกครั้งที่ Respawn หรือ Join
2. **[AnimationController.java](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/src/main/java/com/exea/entity/core/animation/AnimationController.java):**
   - คำนวณ Matrix การหมุนและตำแหน่งตามเวลาจริง (Translation + Default Rotation + Anim Rotation/Scale)
   - ส่งออก Transformation ด้วย 1-tick interpolation (`setInterpolationDuration(1)`)
3. **[EXActiveEntity.java](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/src/main/java/com/exea/entity/core/manager/EXActiveEntity.java):**
   - ระบบ **Plugin Chunk Ticket:** จองและตรึง Chunk ที่ Entity อาศัยอยู่ ป้องกัน Paper Unload Chunk ทิ้งขณะผู้เล่นตาย
   - State Machine: `IDLE`, `MOVING`, `ATTACKING`, `DEAD` เชื่อมโยงกับ Husk AI
   - Hitbox damage & hurt particles (Wither sounds, Portal effects)
   - Death sequence 3 วินาที
4. **[ModelLoader.java](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/src/main/java/com/exea/entity/core/model/ModelLoader.java):**
   - เพิ่มการรองรับ `visible: boolean` จาก Blockbench Export
   - Bone ที่ผู้ใช้ติ๊ก Hide ตาไว้ (เช่น `head_1_riftarbiter`, `head_2_singularitymonolith`) จะไม่ถูกสร้าง ItemDisplay ขึ้นมาในโลก

### ข. การแก้ Bug สำคัญที่ผ่านมา (Critical Bug Fixes)
1. **แก้บั๊ก Model หลุด Sync เมื่อผู้เล่นตายแล้วเกิดใหม่:**
   - *สาเหตุ:* `onChunkUnload` เดิมแอบสั่ง `e.remove()` ใส่งานกระดูก `exea_bone` ทันทีที่ผู้เล่นตายแล้ววาปไปจุดเกิด + ขาด `teleportDuration(1)`
   - *การแก้ไข:* ลบ Event Unload อันตรายออก, เพิ่ม Chunk Ticket, ใส่ `PlayerRespawnEvent` และ `PlayerJoinEvent` สั่ง `forceSync()` ภายใน 2 ticks
2. **แก้บั๊กเสกแล้วยืนนิ่ง / ไม่มีแอนิเมชัน:**
   - *สาเหตุ:* Husk โดน Unregistered AI หรือ Template Clone ไม่แยก Node Display
   - *การแก้ไข:* ปรับ `ModelStructure.instantiate()` แยก Node และตรวจจับ Movement State เปลี่ยนจาก IDLE <-> MOVE อัตโนมัติ

---

## 3. สิ่งที่ส่งมอบเพิ่มเติมล่าสุด (Latest Deliverables)

### 1) Blockbench Export Plugin
- **ที่อยู่ไฟล์:** [`D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/blockbench_plugin/exea_entity_exporter.js`](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/blockbench_plugin/exea_entity_exporter.js)
- **วิธีติดตั้งใน Blockbench:**
  1. เปิด Blockbench
  2. ไปที่เมนู **File** -> **Plugins...**
  3. คลิกแท็บ **Load Plugin from File** (หรือกดรูปโฟลเดอร์)
  4. เลือกไฟล์ `exea_entity_exporter.js`
  5. เมื่อติดตั้งเสร็จ จะมีเมนู **File** -> **Export** -> **Export EXea Entity Model** ทันทีในโปรแกรม!

### 2) Model & Animation HTML Inspector (ตรวจสอบได้โดยไม่ต้องเข้าเกม)
- **ที่อยู่ไฟล์:** [`D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/inspector.html`](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/inspector.html)
- **ความสามารถ:**
  - ดับเบิ้ลคลิกเปิดผ่าน Web Browser ทั่วไปได้ทันที
  - แสดงสถานะกระดูกทุกชิ้น: อันไหน Rendered (สีเขียว) หรือ Hidden (สีแดง)
  - มี Animation Player: กด Play/Pause ดู Timeline, Loop mode, และตำแหน่ง Keyframe ทุกตัวของกระดูกแต่ละชิ้นแบบ Realtime

### 3) Standalone Exporter Script (CLI Mode)
- **ที่อยู่ไฟล์:** [`D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/exea_exporter.js`](file:///D:/Dimensional_Miner_Technical_Starter/EXeaEntityCore/tools/exea_exporter.js)
- **วิธีรัน:**
  ```powershell
  node "D:\Dimensional_Miner_Technical_Starter\EXeaEntityCore\tools\exea_exporter.js" "D:\Dimensional_Miner_Technical_Starter\Entity Model\VoidOverseer\Void Overseer.bbmodel" void_overseer
  ```

---

## 4. โครงสร้างไฟล์ในโปรเจกต์ (Project Tree Reference)
```text
D:\Dimensional_Miner_Technical_Starter\
├── EXeaEntityCore\
│   ├── src\main\java\com\exea\entity\
│   │   ├── EXeaEntityPlugin.java
│   │   ├── core\
│   │   │   ├── animation\ (AnimationController, KeyframeTimeline)
│   │   │   ├── manager\ (EntityManager, EXActiveEntity)
│   │   │   ├── model\ (ModelLoader, ModelStructure, BoneNode)
│   │   │   └── render\ (DisplayRenderer)
│   ├── tools\
│   │   ├── blockbench_plugin\
│   │   │   └── exea_entity_exporter.js   <-- Blockbench Plugin
│   │   ├── exea_exporter.js              <-- Node.js CLI Exporter
│   │   └── inspector.html                <-- HTML Visual Inspector
│   └── build.gradle.kts
├── Entity Model\VoidOverseer\
│   ├── Void Overseer.bbmodel
│   └── exea_export\                      <-- ข้อมูลที่ Export ออกมา
└── server\plugins\
    └── EXeaEntityCore.jar                <-- Plugin JAR ล่าสุด
```

---

## 5. คำแนะนำสำหรับ Agent ตัวถัดไป (Next Agent Instructions)
1. ตัว Engine สำหรับ Husk Driver + ItemDisplay Hierarchy ตอนนี้ทำงานเสร็จสมบูรณ์แล้ว
2. หากต้องการเพิ่ม Entity ตัวใหม่: ให้ใช้ Blockbench Plugin ส่งออก `[id]_bones.json` และ `[id]_anims.json`
3. การทดสอบในเกมสามารถทำได้ผ่านคำสั่ง:
   - `/reload confirm`
   - `/exea spawn [entity_id]`
   - `/exea anim play [anim_name]`
   - `/exea kill all`
