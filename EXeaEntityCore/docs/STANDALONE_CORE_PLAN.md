# แผนทำ EXeaEntityCore ให้เป็น Core ที่ปลั๊กอินอื่นเอาไปใช้ได้จริง (2026-11-13)

เป้าหมาย: เซิร์ฟไหนก็ได้ วาง `EXeaEntityCore.jar` + โฟลเดอร์โมเดลของตัวเอง แล้วปลั๊กอินของเขาเรียก API สร้าง/ควบคุม entity ได้ โดยไม่ต้องแก้หรือ build EXea ใหม่ และ Dimensional Miner ใช้ API เดียวกันนี้ (ไม่มีทางลัดพิเศษ)

## สิ่งที่ยังผูกตายตัวอยู่ (ตรวจจากโค้ด 2026-11-13)

| # | ปัญหา | ที่อยู่ |
|---|---|---|
| G1 | pack เสิร์ฟจาก `exeapack/` ใน jar เท่านั้น → เพิ่มโมเดลต้อง build jar ใหม่ | `PackServer.buildZip`, `build.gradle.kts` processResources |
| G2 | `EXeaEntityDeathEvent` ไม่เคยถูกส่ง, ไม่มี event ตอน spawn / ตอนลบ | `EXActiveEntity.startDeathSequence`, `EXeaEntityPlugin` |
| G3 | พฤติกรรมตายตัว: state machine IDLE/MOVING/ATTACKING/DEAD, ท่าโจมตี = สุ่ม `attack_*` ตอน Husk ตีโดน, เสียง/อนุภาคตอนเจ็บ/ตาย = Wither/Portal | `EXActiveEntity` |
| G4 | ม็อบตัวขับเลือกได้แค่ Husk (AI) / ArmorStand (ไม่มี AI), FOLLOW_RANGE 32 ตายตัว | `EntityManager.spawn` |
| G5 | event ในแอนิเมชันถูกเขียนในโค้ด: ทุกท่าที่ชื่อมี "slash" ได้เสียง+อนุภาคที่ 0.52 วิ, `hitboxDamage` ไม่มีใครอ่าน; exporter ไม่ export effect keyframe ของ Blockbench | `ModelLoader.loadAnimations`, `tools/exea_exporter.js` |
| G6 | ค่า default `void_overseer`: `/exea spawn` ไม่ใส่ id, โครงกระดูก fallback ในโค้ด, `EntityMeta.defaults` ใช้ค่า Void Overseer | `EXeaCommandExecutor:101`, `EntityManager.registerHardcodedVoidOverseer`, `EntityMeta` |
| G7 | API บาง: เป็น singleton ที่คืน `LivingEntity` ดิบ, ไม่มี handle ของ entity, ไม่มีตำแหน่งกระดูก, ไม่มีรายการโมเดล, ไม่มี reload | `api/EXeaAPI` |
| G8 | เครื่องมือผูกกับ repo นี้: jar ออกไป `../server/plugins`, exporter เขียนลง `EXeaEntityCore/pack` | `build.gradle.kts`, `exea_exporter.js` |
| G9 | ไม่มีเอกสารสำหรับนักพัฒนาภายนอก, ไม่มี API artifact ให้ใส่เป็น dependency | — |

## เฟส

### เฟส 1 — Lifecycle + event (ผู้ใช้ตรวจผ่าน 2026-11-13 — Task 12.4)
- ส่ง `EXeaEntitySpawnEvent`, `EXeaEntityDeathEvent` (ตอนเริ่มฉากตาย: killer = คนตีครั้งสุดท้าย + แผนที่ดาเมจต่อผู้เล่น), `EXeaEntityRemoveEvent` (ตอนลบจริง พร้อมเหตุผล: DIED / KILLED_BY_API / UNLOADED / PLUGIN_DISABLE)
- เก็บดาเมจต่อผู้โจมตีใน `EXActiveEntity` (ให้ World Boss / Top 3 ใช้ได้)
- G6: ลบโครงกระดูก fallback และ default `void_overseer`; `/exea spawn` ต้องใส่ id; `EntityMeta.defaults` เป็นค่ากลาง
- ตรวจ: `/exea spawn <id>` → ตีจนตาย → log จาก listener ทดสอบเห็น Spawn/Death/Remove ครบ 1 ครั้ง

### เฟส 2 — API แบบ handle + พฤติกรรมที่เปลี่ยนได้ (ผู้ใช้ตรวจผ่าน 2026-11-13 — Task 12.4)
- `EXeaAPI` (static): `spawn(id, loc, SpawnOptions)`, `get(Entity)`, `all()`, `models()`, `animations(id)`, `reload()`
- `SpawnOptions`: driver (`EntityType` ใดก็ได้ที่เป็น `Mob` หรือ ArmorStand / ไม่มี driver), AI on/off, override HP/ชื่อ/scale/ความเร็ว, `persistent=false` เสมอ
- `EXeaEntity` (handle): `id()`, `driver()`, `play(name)` / `play(name, PlayOptions{speed, loop, then})`, `stop()`, `current()`, `boneLocation(bone)` (ไว้ปล่อยกระสุน/เอฟเฟกต์จากมือ), `setBoneVisible`, `damageBy(attacker)`, `kill()`, `remove()`
- `EXeaBehavior` interface (`onSpawn/onTick/onDamaged/onAttack/onDeath`) + `registerBehavior(templateId, factory)`; พฤติกรรมปัจจุบันย้ายไปเป็น `DefaultBehavior` (ผลในเกมเหมือนเดิม)
- meta เพิ่ม: `driver`, `follow_range`, `hurt_sound`, `death_sound`, `hurt_particle`, `death_ticks`, `behavior` (ชื่อ behavior ที่ลงทะเบียน)
- ตรวจ: ปลั๊กอินทดสอบเล็ก ๆ (ไม่อยู่ใน jar) spawn บอสแบบ ZOMBIE driver, สั่งท่าเอง, ยิง particle ที่ `boneLocation("hand_r")`

### เฟส 3 — เพิ่มโมเดลโดยไม่ต้อง build jar (G1, G5, G8) (ผู้ใช้ตรวจผ่าน 2026-11-13 — Task 12.4)
- โครงโฟลเดอร์: `plugins/EXeaEntityCore/models/<id>_{bones,anims,meta}.json` (มีอยู่แล้ว) + `plugins/EXeaEntityCore/pack/assets/exea/...` (ใหม่)
- `PackServer` รวม `exeapack/` ใน jar + `pack/` ใน data folder เป็น zip เดียว (ไฟล์ใน data folder ชนะ), สร้างใหม่ตอน `/exea reload` แล้วส่งให้ผู้เล่นออนไลน์ใหม่ (hash ใหม่)
- exporter: `--out <dataFolder>` เขียน JSON + pack ไปที่ไหนก็ได้; ค่าเริ่มต้นยังเป็นที่เดิม (workflow ของ repo นี้ไม่เปลี่ยน)
- exporter export **effect keyframe ของ Blockbench** (sound / particle / timeline instruction) เป็น `events` ใน `_anims.json`; ลบ event "slash" ที่เขียนในโค้ด
- instruction ใน timeline keyframe: `hit radius=3.5 damage=20 [cone=90]` → engine ทำดาเมจรอบตัว (ผ่าน `IDamageProvider`) / ข้อความอื่น → ส่ง `EXeaAnimationEventTriggerEvent` ให้ปลั๊กอินอื่นจัดการ
- ตรวจ: วางโมเดลใหม่ใน data folder → `/exea reload` → `/exea spawn <id>` เห็น texture ถูก โดยไม่ build jar

### เฟส 4 — แจกจ่าย (G9) (เสร็จ 2026-11-13, รอตรวจ — Task 12.4)
- แยก package `com.exea.entity.api` เป็น API ที่เสถียร (ของใน `core` เป็น internal)
- Gradle: task `apiJar` + `maven-publish` (ลง `mavenLocal`) ให้ปลั๊กอินอื่นใส่ `compileOnly`; ปลายทาง jar ตั้งได้ (`-PoutDir=`) ค่าเริ่มต้นยัง `../server/plugins`
- เอกสาร `EXeaEntityCore/docs/API.md` (อังกฤษ, สำหรับนักพัฒนาภายนอก): ติดตั้ง, ทำโมเดล, export, ตัวอย่างโค้ด, event, behavior, รายการ meta
- อัปเดต `HANDOVER_EXEA_CORE.md`

### นอกแผนนี้ (แยกงานได้)
- ลดภาระต่อ tick (เลิก teleport กระดูกทีละชิ้น), Molang/bezier/global rotation, `inspector.html` สูตรใหม่
- ฝั่งเกม: World Boss / บอสดันเจี้ยนใช้ EXea ผ่าน API เฟส 1–2 → เซสชันเกมหลักทำ (EXea ส่งแค่ API + เอกสาร)

## การประสานงาน
- ไฟล์ที่ต้องแก้ (`EXActiveEntity`, `EntityManager`, `ModelLoader`, `EXeaEntityPlugin`, `EXeaCommandExecutor`, `PackServer`, `exea_exporter.js`) มีการแก้ค้างที่ยังไม่ commit จากเซสชัน exea-fix → ถามก่อนเริ่มแต่ละเฟส, แก้ต่อจากไฟล์ล่าสุดบนดิสก์
- ไม่แตะ `.bbmodel` / export ของโมเดลใด ๆ; การ export ใหม่ (เฟส 3) ใช้ไฟล์ล่าสุดของผู้ใช้
- Task.md: เพิ่มแถว EXea ใหม่ (Standalone core) เมื่อเริ่มทำ
