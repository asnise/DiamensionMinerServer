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
> **ยังค้าง (ดู Task.md R35):** ต้นทุนต่อ tick สูง (สร้าง Matrix/Quaternion ใหม่ทุกกระดูกทุก tick, teleport ทีละกระดูก), โมเดลเอียงตาม pitch ของหัว Husk, ลำดับการหมุน Euler (XYZ ในโค้ด แต่ Blockbench ใช้ ZYX), slerp 0°→360° ไม่หมุน
>
> **ถ้าจะใช้เป็นบอสในดันเจี้ยน:** บอส EXea ไม่ส่ง `EntityDeathEvent` ตามปกติแล้ว (ยกเลิกดาเมจสุดท้ายเพื่อเล่นฉากตาย) ต้องเพิ่ม event ของ EXea ตอนจบฉากตายให้เป้าหมาย KILL_BOSS นับได้
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
