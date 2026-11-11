# Dimensional Miner — ตารางงาน & Handoff

อ้างอิง GDD: [docs/gdd-concepts.md](docs/gdd-concepts.md) · ภาพรวมระบบ: [CLAUDE.md](CLAUDE.md) · อัปเดตล่าสุด: 2026-11-12

## สถานะ

| ไอคอน | ความหมาย |
|---|---|
| 🔍 | ยังไม่เริ่ม  **แก้แล้ว:** claim ใน DB ก่อน (`WHERE state='AVAILABLE' AND player_id=?`) แล้วค่อยให้ของ, กันคลิกซ้อนด้วย `claimsInFlight`, ผู้เล่นออกก่อนได้ของ → คืน grant ให้ ·  **แก้แล้ว:** cancel ทุก click/drag ตอนเมนูเปิด รับเฉพาะคลิกซ้าย/ขวาบนช่องของเมนู ·  **แก้แล้ว:** 1 ช่อง = 1 grant รับทั้งก้อน, เช็คที่ว่างด้วย inventory จำลอง, แบ่ง stack ตามขนาดจริง, ของที่ใส่ไม่ลงส่งกลับเป็น grant ใหม่, payload อ่านไม่ได้ → แสดงเป็น barrier ห้ามรับ ·  **แก้แล้ว:** `InventorySync.shutdown()` รอให้ save ทุกตัวเสร็จ (สูงสุด 20 วิ) ก่อนปิด pool ·  **แก้แล้ว:** ไม่ save จนกว่า load สำเร็จ (`loaded` set), load ล้มเหลว → kick ·  **แก้แล้ว:** load/save ของผู้เล่นคนเดียวกันต่อคิวกันบน thread pool ของตัวเอง (`enqueue`) commit ตามลำดับที่ snapshot, javadoc แก้ให้ตรงความจริง ·  **แก้แล้ว:** ใช้ `GrantsMenu.MenuListener` ตัวเดียวลงทะเบียนตอน `onEnable` · |
| 🟡 | มีโค้ดบางส่วน (ดูคอลัมน์ "ที่ยังขาด") |
| 🔍 | ทำเสร็จแล้ว **รอผู้ใช้ตรวจ** |
| ✅ | ผู้ใช้ตรวจผ่านแล้ว |

**วิธีอัปเดต (สำหรับ Agent):**
1. ก่อนเริ่มงาน ให้อ่านแถวของงานนั้นและไฟล์ในคอลัมน์ "หลักฐาน"
2. ทำเสร็จแล้วให้เปลี่ยนเป็น 🔍 เท่านั้น ห้ามเปลี่ยนเป็น ✅ เอง ต้องให้ผู้ใช้ตรวจก่อน
3. ใส่ไฟล์ที่แก้ลงคอลัมน์ "หลักฐาน" และเขียนวิธีทดสอบลงคอลัมน์ "วิธีตรวจ"
4. เพิ่มบรรทัดใน [Handoff Log](#handoff-log) ท้ายไฟล์
5. แก้ตาราง "สรุปความคืบหน้า" ให้ตรงกับตารางรายละเอียด

Path ย่อ: `core/` = `DimensionalMinerCore/src/main/java/com/dimensionalminer/core/dimensionalMinerCore/`

---

## สรุปความคืบหน้า

| # | หมวด | ⬜ | 🟡 | 🔍 | ✅ |
|---|---|---|---|---|---|
| 1 | มิติและ Channel | 3 | 2 | 0 | 0 |
| 2 | Node เหมืองแร่ | 5 | 0 | 0 | 0 |
| 3 | อุปกรณ์และสเตตัส | 4 | 1 | 0 | 0 |
| 4 | Crafting & ตีบวก | 5 | 0 | 0 | 0 |
| 5 | ต่อสู้และความตาย | 3 | 1 | 0 | 0 |
| 6 | Instance Dungeon | 0 | 4 | 0 | 0 |
| 7 | World Boss | 4 | 0 | 0 | 0 |
| 8 | Workspace & Market | 4 | 0 | 0 | 0 |
| 9 | Mailbox & EXP/Energy | 1 | 1 | 2 | 0 |
| 10 | Server Transfer | 3 | 0 | 0 | 0 |
| 11 | Web Console | 2 | 0 | 1 | 0 |
| 12 | Resource Pack & 3D | 2 | 1 | 0 | 0 |
| 0 | ระบบพื้นฐานที่มีอยู่แล้ว | 0 | 6 | 1 | 0 |
| R | แก้งานเก่าจากการรีวิว (P0 15 · P1 15 · P2 8) | 18 | 2 | 18 | 0 |

---

## R. แก้งานเก่าจากการรีวิวโค้ด (2026-11-12)

รีวิวโค้ดทั้งหมด (~14,000 บรรทัด) แบ่งเป็น 4 ส่วน ข้อ Critical ทุกข้อมีคนเปิดโค้ดยืนยันซ้ำแล้ว
**ภาพรวม:** SQL ทุกจุดใช้ parameter (ไม่เจอ SQL injection) และโค้ดอ่านง่าย แต่ระบบที่ comment/เอกสารอ้างว่ามี (fencing, idempotency, atomic claim, Hermite, เสร็จ 100%) **ไม่มีอยู่จริงในโค้ด** และ Web console แทบไม่มีระบบสิทธิ์เลย

**ลำดับความสำคัญ:**
- **P0** = exploit, ของซ้ำ/ของหาย หรือช่องโหว่ความปลอดภัย ต้องแก้ก่อนเปิดให้คนเล่น
- **P1** = บั๊กที่เจอแน่ในการเล่นจริง
- **P2** = ประสิทธิภาพและความสะอาดของโค้ด

### P0 — ต้องแก้ก่อนเปิดให้คนเล่น

| ID | ปัญหา | สถานะ | ไฟล์ | สิ่งที่เกิดได้ → วิธีแก้ |
|---|---|---|---|---|
| R1 | `/dm give` / `/dm reload` ไม่มี permission | 🔍 | `core/CustomItemCommand.java` (เช็ค `dimensionalminer.admin` ทั้งใน `onCommand` และ tab-complete), `plugin.yml` (เพิ่ม permission ให้คำสั่ง + บล็อก `permissions:` ตั้ง `default: op`) | **วิธีตรวจ:** บิลด์ jar → ใช้บัญชีที่ไม่ใช่ OP พิมพ์ `/dm give <ชื่อตัวเอง> <item> 1` ต้องขึ้นว่าไม่มีสิทธิ์ และกด Tab แล้วไม่มีอะไรเด้งขึ้น → `/op` ตัวเองแล้วลองใหม่ ต้องใช้ได้ |
| R2 | Mailbox: ให้ของก่อนแล้วค่อย mark CLAIMED แบบ async และ `UPDATE` ไม่มี `AND state='AVAILABLE'` | 🔍 | `core/GrantsMenu.java` (`claim`, `markClaimed`), `GrantsCommand.java` (ลบโค้ดตายทิ้ง) | **แก้แล้ว:** claim ใน DB ก่อน (`WHERE grant_id=? AND player_id=? AND state='AVAILABLE'` → ต้องได้ 1 แถว) แล้วค่อยให้ของ · กันคลิกซ้อนด้วย `claimsInFlight` · ผู้เล่นออกก่อนได้ของ → คืน grant กลับเป็น AVAILABLE · **วิธีตรวจ:** คลิกรัวๆ ที่ reward เดียว / เปิด `/grants` แล้วคลิกพร้อมกันจาก 2 บัญชีไม่ได้ (ต้องเป็นของเรา) → ได้ของครั้งเดียว และใน DB `state='CLAIMED'` |
| R3 | GrantsMenu ไม่ cancel การ double-click ในกระเป๋าตัวเอง | 🔍 | `core/GrantsMenu.java` (`MenuListener`) | **แก้แล้ว:** cancel ทุก click/drag ตอนเมนูเปิด (รวมฝั่งกระเป๋าตัวเอง) และรับแค่คลิกซ้าย/ขวาบนช่องของเมนู · **วิธีตรวจ:** ถือไอเทมชนิดเดียวกับ reward แล้ว double-click / shift-click / กดเลข 1-9 / ลาก → ไม่มีอะไรขยับ |
| R4 | Claim ของชิ้นเดียว แต่ทั้ง grant ถูก mark ว่ารับแล้ว ของเกิน 64 ถูกตัดทิ้ง ส่วน leftover ตอนกระเป๋าเต็มหายไป | 🔍 | `core/GrantsMenu.java` (`buildStacks`, `fitsInInventory`, `deliver`, `remail`) | **แก้แล้ว:** 1 ช่อง = 1 grant รับทั้งก้อน · เช็คที่ว่างกับ inventory จำลอง · แบ่ง stack ตามขนาดจริง (128 ชิ้น = 2 stack) · ใส่ไม่ลง → ส่งกลับเป็น grant ใหม่ · payload อ่านไม่ได้ → แสดงเป็น barrier และห้ามรับ (ไม่ส่งของครึ่งๆ) · รองรับ key `item`/`qty` ของ dungeon reward ด้วย · **วิธีตรวจ:** grant 128 ชิ้นได้ครบ 2 stack · กระเป๋าเต็มแล้วกดรับ → ขึ้นว่าที่ไม่พอ และ reward ยังอยู่ |
| R5 | `onDisable` สั่ง save แบบ async แล้วปิด pool ทันที | 🔍 | `core/InventorySync.java` (`shutdown`), `DimensionalMinerCore.java` (`onDisable`) | **แก้แล้ว:** `shutdown()` snapshot ทุกคน แล้วรอให้ save ทั้งหมด commit (สูงสุด 20 วิ) ก่อนปิด pool · **วิธีตรวจ:** หยิบของใหม่แล้ว `stop` ทันที → เปิดเซิร์ฟใหม่ ของยังอยู่ |
| R6 | Save ทำงานได้ก่อน load inventory เสร็จ (หรือตอน load ล้มเหลว) | 🔍 | `core/InventorySync.java` (`loaded`, `loadAsync`) | **แก้แล้ว:** ไม่ save จนกว่า load สำเร็จ · load ล้มเหลว → kick ผู้เล่นพร้อมข้อความ · ย้าย `upsertPlayer` ออกจาก main thread · **วิธีตรวจ:** ปิด DB แล้วเข้าเกม → ถูก kick และของใน DB ไม่ถูกลบ |
| R7 | Save หลาย batch commit สลับลำดับกันได้ และ "fencing" ใน javadoc ไม่มีอยู่จริง | 🔍 | `core/InventorySync.java` (`enqueue`, `ioExecutor`) | **แก้แล้ว:** load/save ของผู้เล่นคนเดียวกันต่อคิวกันบน thread pool ของตัวเอง → commit ตามลำดับที่ snapshot, ออกแล้วเข้าใหม่เร็วๆ load จะรอ save ตอนออกก่อน · javadoc แก้ให้ตรงความจริง (fencing ข้ามเซิร์ฟยังไม่มี → งาน 10.2) · **วิธีตรวจ:** ย้ายของไปมาแล้วออก-เข้าทันทีหลายรอบ → ของครบไม่ซ้ำ |
| R8 | `/api/admin/*`, `/api/content/[kind]`, `/api/export` ไม่มีการเช็คสิทธิ์ (proxy ดักแค่ `/admin/*`) | 🔍 | `web-console/proxy.js`, `lib/auth.js` (`requireAdmin`), `app/api/admin/**`, `app/api/content/[kind]`, `app/api/export` | **แก้แล้ว:** proxy ดักทั้ง `/admin/*` และ `/api/admin/*` (API ตอบ 401) และทุก handler เรียก `requireAdmin()` ซ้ำอีกชั้น · content จำกัด `kind` 6 ชนิด + `definition_id` ต้องเป็น `a-z0-9_` · **ทดสอบแล้ว:** 8 endpoint ไม่ login → 401, login แล้ว → 200 |
| R9 | ไม่มี identity ฝั่ง server: `/api/shop/buy` เชื่อ `player_id` จาก client และหน้า login รับ UUID ตรงๆ | 🔍 | `lib/auth.js`, `app/api/auth/{login-key,me,logout}`, `app/api/shop/buy`, `app/api/player/[id]/transactions`, `lib/session.js`, `app/login`, `app/shop` | **แก้แล้ว:** แลก OTP แล้วได้ cookie `dm_session` (httpOnly, sign ด้วย HMAC, 7 วัน) · buy ใช้ `player_id` จาก cookie เท่านั้น · ประวัติธุรกรรมดูได้แค่เจ้าของหรือ admin · ลบการ login ด้วย UUID · ฝั่ง browser อ่าน session จาก `/api/auth/me` · **ทดสอบแล้ว:** ซื้อด้วย UUID คนอื่น → 401, cookie ที่แก้ไข → ไม่ผ่าน, login/shop/logout ใน browser ทำงาน |
| R10 | Admin cookie = `base64("admin:"+password)`, default `admin123` | 🔍 | `lib/auth.js`, `app/api/admin/login/route.js`, `proxy.js`, `app/admin/login/page.jsx` | **แก้แล้ว:** cookie เป็น token ที่ sign ด้วย `SESSION_SECRET` (ไม่มีรหัสผ่านอยู่ข้างใน, หมดอายุ 8 ชม.) · ไม่มีรหัส default (ไม่ตั้ง `ADMIN_PASSWORD` = เข้าไม่ได้) · เทียบรหัสแบบ constant-time · ผิด 10 ครั้ง/15 นาที → ล็อก · **ทดสอบแล้ว:** cookie แบบเก่า → 401 |
| R11 | OTP: เช็คแล้วค่อย mark ว่าใช้แล้วแยกกัน 2 คำสั่ง (error ถูกกลืน), ไม่มี rate limit, GET ใส่ key ใน URL | 🔍 | `app/api/auth/login-key/route.js`, `lib/rate-limit.js` | **แก้แล้ว:** เช็คและใช้ key ใน `UPDATE … RETURNING` คำสั่งเดียว · ผิด 10 ครั้ง/10 นาทีต่อ IP → 429 · ตัด GET ทิ้ง (ลิงก์ `/login?code=` จากในเกมยังใช้ได้ เพราะหน้าเว็บส่ง POST ให้) · **ทดสอบแล้ว:** ยิง key เดียวพร้อมกัน 3 ครั้ง → ผ่าน 1 ครั้ง · ข้อจำกัด: rate limit อยู่ใน memory ของ process เดียว |
| R12 | รหัสผ่าน Neon อยู่ใน `config.yml` ซึ่งไม่อยู่ใน .gitignore และถูก bundle เข้าไปใน jar | 🔍 | ผู้ใช้ rotate รหัสแล้ว (รหัสเก่าต่อไม่ได้แล้ว, รหัสใหม่ต่อได้) · ใส่รหัสใหม่ใน `web-console/.env.local`, `.env`, `server/plugins/DimensionalMinerCore/config.yml` · `src/main/resources/config.yml` ใช้ `CHANGE_ME` + คำเตือน · `.gitignore` เพิ่ม `env.txt`, `*/bin/` | **วิธีตรวจ:** เปิด `npm run dev` แล้วเข้า `/profile` ได้ · รีบิลด์ jar (ตัวเก่าใน `server/plugins/` ยังมีรหัสเก่าที่ตายแล้วอยู่) แล้วเซิร์ฟต่อ DB ได้ · ลบ `env.txt` ทิ้ง |
| R13 | Unload world ทั้งที่การวาร์ป async ยังไม่เสร็จ, ไม่เช็คผล unload แล้วลบโฟลเดอร์ต่อเลย | 🔍 | `core/dungeon/runtime/DungeonInstanceManager.java` (`destroyInstance`, `unloadThenDelete`, `evacuateNow`) | **แก้แล้ว:** วาร์ปทุกคนที่อยู่ใน world ออกแบบ sync (ทั้งสมาชิกและคนที่ `/dungeon join` เข้ามา) ถ้าวาร์ปไม่ได้จะ kick · เช็คผล `unloadWorld` ถ้าไม่ผ่านจะลองใหม่ทุก 2 วิ (สูงสุด 5 ครั้ง) · **ลบโฟลเดอร์ต่อเมื่อ unload สำเร็จเท่านั้น** · ล้าง `playerToSession` ของทุกคนใน session · **วิธีตรวจ:** `/dungeon enter` แล้ว `/dungeon destroy <id>` ระหว่างที่ยังยืนอยู่ข้างใน → ถูกวาร์ปออก, log ไม่มี "did not unload", โฟลเดอร์ `ind_t0_<id>` หายไป |
| R14 | `advanceStep()` ไม่มีตัวกันเรียกซ้ำ | 🔍 | `core/dungeon/runtime/DungeonInstanceSession.java` (`advancing`, `advanceStep`, `runStep`, `finishDungeon`) | **แก้แล้ว:** flag `advancing` กันจบ step ซ้ำ (ทั้งตอนฆ่ามอน, กดสวิตช์ และ goal timer) · จับ index ของ step ถัดไปไว้ตั้งแต่ตอน schedule · `finishDungeon` ทำงานได้ครั้งเดียว · **วิธีตรวจ:** step แบบ KILL_MOBS ที่มอนตายพร้อมกันหลายตัว (เช่น ใช้ TNT) → ไปต่อทีละ step, ข้อความ VICTORY ขึ้นครั้งเดียว และ `reward_grants` มีแถวเดียวต่อคน |
| R15 | EXea: bone ตั้ง `setPersistent(true)` แต่ root ไม่ persistent | 🔍 | `EXeaEntityCore/.../render/DisplayRenderer.java`, `EXeaEntityPlugin.java` (`onEntitiesLoad`) | **แก้แล้ว:** bone เป็น non-persistent เหมือน root (ไม่ถูกบันทึกลง disk) · ตอน chunk โหลด entity ที่มี tag `exea_bone`/`exea_entity` ที่ค้างมาจาก build เก่าจะถูกลบทิ้ง · **วิธีตรวจ:** spawn void_overseer แล้ว kill เซิร์ฟ (ไม่ใช้ stop) → เปิดใหม่แล้วไปที่เดิม ต้องไม่มีโมเดลค้าง |

### P1 — บั๊กที่เจอแน่ตอนเล่นจริง

| ID | ปัญหา | สถานะ | ไฟล์ | สิ่งที่เกิดได้ → วิธีแก้ |
|---|---|---|---|---|
| R16 | เก็บแค่ `defId` + จำนวน ไม่เก็บ enchant/durability/NBT, ID ที่ไม่รู้จักกลายเป็น STONE, ไม่มี handler ตอนปิด inventory (ทั้งที่ javadoc บอกว่ามี) | ⬜ | `core/InventorySync.java:133-211,355-363` | enchant/ความทนทานหายทุกครั้งที่ reload → serialize ItemStack ทั้งชิ้น แล้ว hash จากรูปที่ serialize |
| R17 | ใช้ Minecraft UUID ในจุดที่ schema ต้องการ `account_id` | ⬜ | `GrantsMenu.java:89`, `DatabaseManager.java:111,189`, `Balance/Shop/WebLoginCommand` | พอผูก identity แล้ว grant จะไม่ขึ้นและ `/weblogin` FK fail → resolve ผ่าน `getAccountId` ทุกที่ และอย่า fallback |
| R18 | DB I/O บน main thread | ⬜ | `InventorySync.onJoin:62`, `Balance/Shop/WebLogin/ProfileCommand` | ใช้ Neon แล้วเซิร์ฟกระตุก 100ms ขึ้นไปต่อคำสั่ง → ย้ายไป async |
| R19 | ต่อ DB ไม่ได้แต่ปลั๊กอินยังเปิดทำงาน (exception ถูกกลืน) | ⬜ | `core/DimensionalMinerCore.java:22-30`, `DatabaseManager` | NPE กระจายทุกที่ → ให้ throw แล้ว disable ปลั๊กอิน |
| R20 | `/dm reload` เคลียร์ registry ก่อนแล้วค่อยเติมใหม่ (ว่างชั่วคราว) + `loadFromDatabase` query ตารางที่ไม่มีใน game DB | ⬜ | `core/ContentRegistryManager.java:45-51` | ไอเทมกลายเป็น STONE ช่วง reload → สร้าง map ใหม่แล้ว swap ทีเดียว, ตัด DB path ทิ้ง |
| R21 | `members` ของ session เป็น view สดของ party | ⬜ | `dungeon/DungeonCommand.java:127`, `DungeonInstanceSession.java:68` | คนเข้าปาร์ตี้กลางรอบได้รางวัล / คนที่ออกไม่ถูกวาร์ปออก → `Set.copyOf` ตอนสร้าง session |
| R22 | `/dungeon join` ใครก็เข้าได้ และ `/dungeon enter` ซ้ำได้ | ⬜ | `DungeonInstanceManager.java:104-116`, `DungeonCommand.java:90-136` | instance ซ้อน, คนแปลกหน้าเข้ามาในดันเจี้ยน → เช็คสมาชิกปาร์ตี้ + กันการเข้าซ้ำ |
| R23 | ดันเจี้ยนที่ไม่มีคนอยู่ไม่มีวันจบ: ไม่บังคับ time limit, ไม่ cleanup ตอน `onDisable`, โฟลเดอร์ orphan | ⬜ | `DungeonInstanceManager`, `DimensionalMinerCore.onDisable` | world และ task ค้างจนรีสตาร์ท, โฟลเดอร์ `ind_t0_*` สะสม → watchdog + destroy ตอน disable + sweep ตอน enable |
| R24 | `/dungeon destroy` ไม่ cancel task ที่รออยู่ | 🔍 | `DungeonInstanceSession.java` (`cleanup`, `runStep`) | **แก้แล้ว (พร้อม R14):** `cleanup()` ตั้ง `completed=true` แล้ว step ที่รออยู่กับ action ที่หน่วงเวลาจะเช็ค flag นี้และหยุด ส่วน interval/goal timer ถูก cancel อยู่แล้ว · **วิธีตรวจ:** `/dungeon destroy` ในช่วง 2 วินาทีที่กำลังเปลี่ยน step → ไม่มีมอนเกิดใหม่และไม่มี error ใน log |
| R25 | ประกาศว่าแจกรางวัลแล้วก่อนเขียนลง DB จริง และถ้าเขียนไม่สำเร็จก็ไม่มีใครรู้ (= งาน 6.3) | ⬜ | `DungeonInstanceSession.java:240-247,447-479` | → เขียนให้เสร็จใน transaction เดียวก่อน แล้วค่อยประกาศ, กำหนดเกณฑ์ว่าใครมีสิทธิ์ได้รางวัล · **พบเพิ่ม:** `distributeFinalRewards` กรองรางวัล `currency` (เช่น COIN 1000) ทิ้ง ผู้เล่นไม่เคยได้เงินจากดันเจี้ยน |
| R26 | ไม่มี template แล้วระบบสร้างโลกสุ่มขึ้นมาแทน | ⬜ | `DungeonInstanceManager.java:52-63` | → ให้ future fail |
| R27 | Party: เข้าได้ 2 ปาร์ตี้, ออกปาร์ตี้ระหว่างอยู่ในดันเจี้ยนได้ (lock โดนเลี่ยงได้ด้วย `/dimensionalminercore:party`), `party_members` ใน DB ไม่ตรงกับ memory | ⬜ | `party/PartyManager.java:93-110,225-231`, `DungeonEventListener.java:64` | → เช็คใน `acceptInvite`, lock ในตัว manager, ลบแถว DB |
| R28 | Ledger ซื้อของบันทึกฝั่งเดียว (ไม่ใช่ double-entry), idempotency key ใช้ `Date.now()` | ⬜ | `app/api/shop/buy/route.js:58,79-83`, `scripts/grant_coin.mjs`, `schema_game.sql:85-107` | ยอดรวม ledger ไม่สมดุล, retry แล้วซื้อซ้ำ → ลงบัญชีระบบฝั่งตรงข้าม + key จาก client + constraint ใน schema |
| R29 | EXea: chunk ticket ไม่นับ reference, cleanup ลบ entity ที่ชื่อมี "Void Overseer" หรือ CMD 1001-1022 ของปลั๊กอินอื่นด้วย, ฉากตายไม่เคยเล่น | ⬜ | `EXActiveEntity.java:527-556`, `EntityManager.java:62,193-219`, `EXeaEntityPlugin.java:107` | → นับ reference ของ ticket, ใช้ PDC tag ของตัวเอง, ทำ death sequence ก่อน cull |
| R30 | Web ส่ง `err.message` ของ Postgres กลับไปให้ client | 🔍 | `web-console/lib/api.js` (`fail`), `login-key/route.js` | **แก้แล้ว (พร้อม R8–R11):** error 5xx จะ log ฝั่ง server แล้วตอบ `Internal server error` ส่วน 4xx ยังส่งข้อความให้ผู้ใช้ตามเดิม |

### P2 — ประสิทธิภาพ / ความสะอาดของโค้ด

| ID | ปัญหา | สถานะ | ไฟล์ |
|---|---|---|---|
| R31 | Channel clone dimension บน main thread ตอน constructor, heuristic "<5 region" ทำให้ clone ใหม่ทุกครั้งที่บูต, `/reload` อาจลบ world ที่โหลดอยู่ | ⬜ | `channel/ChannelManager.java:33-63` |
| R32 | `Files.walk` ไม่ปิด stream, copy template ไปไว้ 2 ที่, copy พังกลางทางแล้วเหลือโฟลเดอร์ครึ่งๆ | 🟡 | `DungeonInstanceManager` ปิด stream แล้ว และ delete จะรายงานเมื่อลบไม่หมด · ยังเหลือ: copy ไป 2 ที่, โฟลเดอร์ครึ่งๆ ตอน copy พัง, `ChannelManager` |
| R33 | `/exitdungeon` ไม่เอาผู้เล่นออกจาก session (rejoin แล้วถูกวาร์ปกลับเข้าไป), ตายในดันเจี้ยนไม่มี handler, ถ้า content ไม่มี `display_text` จะ NPE จน step ค้าง | 🟡 | NPE ของ `display_text` แก้แล้ว (ถ้าไม่มีจะใช้ "Defeat <mob>") · ยังเหลือ: `/exitdungeon` และการตายในดันเจี้ยน |
| R34 | GrantsMenu ลงทะเบียน listener ใหม่ทุกครั้งที่เปิดเมนู ทำให้ listener รั่ว | 🔍 | **แก้แล้ว (พร้อม R2–R4):** `GrantsMenu.MenuListener` ตัวเดียว ลงทะเบียนครั้งเดียวใน `onEnable` |
| R35 | EXea: per-tick สร้าง Matrix/Quaternion ใหม่ทุก bone, teleport ทีละ bone, โมเดลเอียงตาม pitch หัว, ลำดับ Euler XYZ (Blockbench ใช้ ZYX), slerp 0→360° ไม่หมุน | ⬜ | `AnimationController.java`, `KeyframeTimeline.java`, `DisplayRenderer.java`, `ModelLoader.java:129` |
| R36 | Schema: ขาด index (`ledger_entries.operation_id` ฯลฯ), `item_transactions` ON DELETE CASCADE, item กับ stack อยู่ slot เดียวกันได้, login key เก็บเป็น plaintext | ⬜ | `database/schema_game.sql` |
| R37 | Dead code (`GrantsCommand.processGrants/deliverGrants`, `displayFromPayload`), helper ถูก copy ซ้ำหลายที่, exception ถูกกลืน, javadoc อ้างระบบที่ไม่มีจริง, ใช้ `ChatColor` ปนกับ Adventure | ⬜ | ทั้ง `core/` |
| R38 | `HANDOVER_EXEA_CORE.md` อ้างเกินจริง (Hermite, ดาเมจกวาด, standalone, 1.21.4, 100%) ทั้งที่จริงเป็นต้นแบบสำหรับ `void_overseer` ตัวเดียว | ⬜ | `HANDOVER_EXEA_CORE.md` |

---

## 0. ระบบพื้นฐานที่มีอยู่แล้ว (ไม่อยู่ในรายการเดิม)

| ID | งาน | สถานะ | หลักฐาน | วิธีตรวจ / หมายเหตุ |
|---|---|---|---|---|
| 0.1 | Dual Database (content / game) + bootstrap | 🔍 | `database/schema_*.sql`, `web-console/scripts/init_databases.mjs`, `init_neon.mjs` | รัน init แล้วเช็คว่ามีตารางครบ (ข้อจำกัดของ schema ดู R36) |
| 0.2 | Content Editor + Export `content_registry.json` | 🟡 | `web-console/app/admin/editor`, `lib/content.js`, `core/ContentRegistryManager.java` | ต้องแก้ R8, R20 |
| 0.3 | Inventory Sync กับ DB | 🟡 | `core/InventorySync.java` | ต้องแก้ R5, R6, R7, R16 |
| 0.4 | Party (create/invite/accept/leave/disband) | 🟡 | `core/party/` | ต้องแก้ R27 |
| 0.5 | Web Login OTP (`/weblogin`) | 🟡 | `core/WebLoginCommand.java`, `app/api/auth/login-key` | R9, R11 แก้แล้ว · เหลือ R17 |
| 0.6 | Web Shop → Mailbox | 🟡 | `app/shop`, `app/api/shop/buy`, `core/ShopCommand.java` | R8, R9 แก้แล้ว · เหลือ R28 |
| 0.7 | EXeaEntityCore (3D entity engine แยกปลั๊กอิน) | 🟡 | `EXeaEntityCore/` | ต้นแบบสำหรับโมเดลเดียว ต้องแก้ R15, R29, R35, R38 |

## 1. ระบบมิติและ Channel

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 1.1 | โหลด World แยกตาม Tier | ⬜ | — | ตอนนี้มีแค่ `overworld_t0` ที่ fix ไว้ในโค้ด |
| 1.2 | เงื่อนไขปลดล็อก Tier (Server Level, เควส, หลักฐานชนะบอส) | ⬜ | — | |
| 1.3 | Channel แยก instance ของ World | 🟡 | `core/channel/ChannelManager.java`, `ChannelCommand.java` | Fix ไว้ 3 channel (clone จาก `overworld_t0`) ยังไม่มีการจำกัดคน/เพิ่ม channel อัตโนมัติ |
| 1.4 | วาร์ป (Cooldown 30s, ยกเลิกเมื่อโดนดาเมจ/ขยับ) | ⬜ | — | |
| 1.5 | Safe Zone (ปิด PvP, ห้ามแก้ Block) | 🟡 | `core/MapProtectionListener.java` | ตอนนี้ห้ามแก้ block **ทั้งแมพ** ยังไม่แบ่งเป็นโซน และยังไม่ได้ปิด PvP |

## 2. ระบบ Node เหมืองแร่

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 2.1 | โครงสร้างข้อมูล Node (`node_id`, `dimension_id`, `channel_id`, สถานะ, generation) | ⬜ | — | |
| 2.2 | จอง Node แบบ Atomic | ⬜ | — | |
| 2.3 | แจกรางวัล + Generation กันช่องโหว่ | ⬜ | — | |
| 2.4 | เปลี่ยนบล็อกระหว่างรอ Respawn (Visual) | ⬜ | — | ต้องทำงานร่วมกับ 1.5 เพราะตอนนี้ห้ามทุบ block ทั้งแมพ |
| 2.5 | Anti-abuse (อัตราขุด, ระยะเอื้อม, เครื่องมือ) | ⬜ | — | |

## 3. ระบบอุปกรณ์และสเตตัส

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 3.1 | โครงสร้างไอเทม (UUID, Rarity, Advancement, Refine, Engrave, Socket) | 🟡 | `core/ItemDefinition.java`, ตาราง `items` ใน `schema_game.sql` | มี Rarity/Base Stats จาก content แล้ว แต่ยังไม่มี instance data ของ Advancement/Refine/Engrave/Socket |
| 3.2 | คำนวณสเตตัส (GearMath: `Core_s`, `Final_s`) | ⬜ | — | |
| 3.3 | ป้องกัน/เกราะ และการคำนวณดาเมจที่ได้รับ | ⬜ | — | |
| 3.4 | Unique Effect (Cooldown, Proc, ห้าม trigger ตัวเอง) | ⬜ | — | |
| 3.5 | Set effect (เช็คเฉพาะช่องที่สวมจริง) | ⬜ | — | |

## 4. ระบบ Crafting & ตีบวก

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 4.1 | UI โชว์ผลลัพธ์และกฎก่อนยืนยัน | ⬜ | — | มี `CraftingRestrictionListener` บล็อกคราฟต์ vanilla แล้ว |
| 4.2 | Advancement (0-100) | ⬜ | — | |
| 4.3 | Refine (0-10, มีโอกาสล้มเหลว) | ⬜ | — | |
| 4.4 | Engrave (Fragment สูงสุด 3 ช่อง) | ⬜ | — | |
| 4.5 | Inheritance (โอนค่าทับชิ้นใหม่ ทำลายชิ้นเก่า) | ⬜ | — | |

## 5. ระบบต่อสู้และความตาย

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 5.1 | ท่อคำนวณดาเมจกลาง (CombatService) | ⬜ | — | |
| 5.2 | Anti-abuse ยิงข้ามเขตปลอดภัย / สลับอาวุธ | ⬜ | — | |
| 5.3 | ตายในเหมือง (เสีย Durability, ไม่ดรอป, ส่งกลับ Hub) | 🟡 | `core/InventorySync.java` (`onDeath`), `keepInventory=true` | ไม่ดรอปของแล้ว แต่ยังไม่เสีย Durability และยังไม่ส่งกลับ Hub |
| 5.4 | ระบบ Durability ของอุปกรณ์ | ⬜ | — | |

## 6. ระบบดันเจี้ยนส่วนตัว (Instance Dungeon & Keys)

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 6.1 | วงจรชีวิต Instance (Requested → Reserved → … → Closed) | 🟡 | `core/dungeon/runtime/DungeonInstanceManager.java`, `DungeonInstanceSession.java` | สถานะเก็บแค่ใน memory ยังไม่ได้บันทึก `instance_slots` / `instance_runs` ลง DB ตามสเปก |
| 6.2 | กุญแจและปาร์ตี้ (ตัดกุญแจเมื่อเริ่ม, แช่แข็งปาร์ตี้) | 🟡 | `core/dungeon/DungeonCommand.java` (สร้าง Solo Party อัตโนมัติ) | ยังไม่มีระบบกุญแจ และยังไม่แช่แข็งปาร์ตี้ (ดู 0.4) |
| 6.3 | แจกรางวัลลง DB **ก่อน** เล่น Animation ชนะ | 🟡 | `DungeonInstanceSession.java` ~บรรทัด 240 | ตอนนี้ขึ้น title VICTORY ก่อน แล้วค่อยเขียน `reward_grants` แบบ async (ลำดับกลับกันกับสเปก) |
| 6.4 | จัดการ World/Template แบบ Async | 🟡 | `DungeonInstanceManager.java` | copy async แล้ว และ R13 (unload/ลบ) แก้แล้ว · ยังเหลือ R23, R26, R32 |

## 7. ระบบ World Boss

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 7.1 | ตรวจจับผู้เข้าร่วมและดาเมจ | ⬜ | — | อาจใช้ EXeaEntityCore (Void Overseer) เป็นตัวบอส |
| 7.2 | คำนวณรางวัล (Standard, Top 3, Last Shot) | ⬜ | — | |
| 7.3 | Server EXP ตามสัดส่วนดาเมจ (E_pool) | ⬜ | — | |
| 7.4 | โควตารางวัลกัน Channel Hopping | ⬜ | — | |

## 8. ระบบพื้นที่ส่วนตัวและเศรษฐกิจ

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 8.1 | Workspace (โหลดโลกเมื่อใช้, ACL) | ⬜ | — | |
| 8.2 | Processing Job (คำนวณเวลาตอนออฟไลน์) | ⬜ | — | |
| 8.3 | เหรียญและภาษี (sources & sinks) | ⬜ | — | มี wallet + ledger ใน DB แล้ว |
| 8.4 | ตลาดประมูล / AH (transaction เดียว) | ⬜ | — | |
| 8.5 | ไม่มีระบบ P2P Trade | — | — | เป็นกฎการออกแบบ: **ห้ามสร้าง** |

## 9. ระบบกล่องจดหมายและเลเวล

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 9.1 | Mailbox ไม่จำกัด (Pagination, ไม่หมดอายุ) | 🔍 | `core/GrantsMenu.java` (หน้าละ 45 grant) | ตรวจพร้อม R2–R4 |
| 9.2 | กดรับของลง Inventory ใน transaction เดียว | 🔍 | `core/GrantsMenu.java` | claim ใน DB ก่อนแล้วค่อยให้ของ (กันของซ้ำได้แล้ว) · ข้อจำกัด: ถ้าเซิร์ฟล่มในช่วงสั้นๆ หลังให้ของแต่ก่อน save inventory ของรอบนั้นจะหาย (ไม่ซ้ำ) — แก้ได้เต็มเมื่อย้าย inventory เข้า transaction เดียวกัน |
| 9.3 | เปลี่ยน Vanilla EXP เป็น Energy | 🟡 | `InventorySync.onDeath` (ไม่ดรอป EXP), currency `ENERGY` ใน `/balance` | ยังไม่ได้แปลง EXP ที่เก็บได้เป็น Energy |
| 9.4 | แยก Server Level / Core ออกจาก Energy | ⬜ | — | |

## 10. ระบบโอนย้ายเซิร์ฟเวอร์

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 10.1 | ส่งต่อผู้เล่นข้ามโหนด (Freeze → Lock → Token → Hydrate) | ⬜ | — | |
| 10.2 | Epoch กันของซ้ำ | ⬜ | — | |
| 10.3 | Velocity Proxy Routing | ⬜ | — | |

## 11. เว็บไซต์และ API

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 11.1 | API สร้าง/แสดง Job | ⬜ | — | ต้องทำ 8.2 ก่อน |
| 11.2 | แสดงประวัติธุรกรรม | 🔍 | `app/api/player/[id]/transactions`, `components/ProfileView.jsx` | เปิด `/profile?id=<uuid>` แล้วดูรายการธุรกรรม |
| 11.3 | Market หน้าเว็บ | ⬜ | — | ต้องทำ 8.4 ก่อน |

## 12. ระบบ Resource Pack และ 3D Model

| ID | งาน | สถานะ | หลักฐาน | ที่ยังขาด / วิธีตรวจ |
|---|---|---|---|---|
| 12.1 | เชื่อมโมเดล 3D จาก Blockbench | 🟡 | `EXeaEntityCore/` | ทำงานได้แล้วแต่เป็นปลั๊กอินแยก ยังไม่ได้เชื่อมกับ DimensionalMinerCore (เช่น spawn ในดันเจี้ยน) |
| 12.2 | Resource Pack อาวุธ/เกราะ/ไอเทม (Custom Model Data) | ⬜ | — | EXea ใช้ CMD 1001-1022 สำหรับกระดูกโมเดล ต้องระวังเลขชนกัน |
| 12.3 | แอนิเมชันและเอฟเฟกต์อาวุธ | ⬜ | — | |

---

## Handoff Log

บันทึกใหม่ไว้บนสุด ระบุวันที่, ID งาน, สิ่งที่ทำ และสิ่งที่ค้าง

| วันที่ | ID | สิ่งที่ทำ | ค้าง / ข้อควรระวัง |
|---|---|---|---|
| 2026-11-12 | R13–R15, R24 (+บางส่วนของ R32, R33) | Dungeon: unload world หลังวาร์ป sync แล้ว และลบโฟลเดอร์เฉพาะเมื่อ unload สำเร็จ, step ไปต่อได้ครั้งเดียว, destroy หยุดงานที่รออยู่ · EXea: bone เป็น non-persistent + sweep ตอนโหลด chunk · `compileJava` ผ่านทั้ง 2 ปลั๊กอิน | **P0 ครบทุกข้อแล้ว (รอตรวจ)** · ยังไม่ได้ทดสอบในเกม · ต้องบิลด์ jar ใหม่ทั้ง 2 ตัว · ยังไม่ได้ commit |
| 2026-11-12 | R8–R11, R30 | ระบบ session ใหม่ (`lib/auth.js`: cookie ที่ sign ด้วย HMAC สำหรับ admin และผู้เล่น), proxy + `requireAdmin` ทุก admin route, OTP แบบ atomic + rate limit, ซื้อของด้วย session, ซ่อน error 5xx · เพิ่ม `SESSION_SECRET` ใน `web-console/.env.local` · **ทดสอบแล้ว:** smoke test 30/30 ผ่านบน dev server (พอร์ต 3100) + login/shop/logout ใน browser | ยังไม่ได้ commit · lint error เดิมใน `app/shop/page.jsx:48` (setState ใน effect) ไม่ได้แตะ · หน้าแรก `/` และ `/api/players` ยังโชว์ผู้เล่นทุกคนพร้อมยอดเงินแบบ public (ต้องตัดสินใจว่าจะเปิดหรือปิด) · rate limit อยู่ใน memory ใช้ได้แค่ process เดียว |
| 2026-11-12 | R2–R7, R34 | เขียน `GrantsMenu` ใหม่ (claim ใน DB ก่อน, รับทั้ง grant, ล็อกทุก click, listener ตัวเดียว), `GrantsCommand` เหลือแค่เปิดเมนู, `InventorySync` มีคิวต่อผู้เล่น + gate ตอน load + `shutdown()` รอ save · `compileJava` ผ่าน | ยังไม่ได้ทดสอบในเกม · ต้องบิลด์ jar ใหม่ · **พบเพิ่ม:** ก่อนหน้านี้รางวัลดันเจี้ยน (key `item`/`qty`) ไม่เคยถูกอ่านเลย (ตอนนี้อ่านได้แล้ว) และรางวัล COIN ของดันเจี้ยนถูกกรองทิ้งตั้งแต่ตอนเขียน grant (ยังไม่แก้ → ใส่ไว้กับ R25) |
| 2026-11-12 | R12 | ผู้ใช้ rotate รหัส Neon แล้ว · ใส่รหัสใหม่ใน env/config ที่ใช้งานจริง · config ที่ bundle เข้า jar ใช้ placeholder แทน · web console ต่อ DB ได้ทั้ง 2 pool | jar ใน `server/plugins/` ต้องรีบิลด์ · ผู้ใช้ควรลบ `env.txt` · รหัสเก่าใน git history ตายแล้ว ไม่ต้องลบ history |
| 2026-11-12 | R1 | `/dm` เป็นคำสั่งของ admin เท่านั้น (permission `dimensionalminer.admin`, ค่าเริ่มต้นเป็น op) · `compileJava` ผ่าน | ยังไม่ได้บิลด์ jar ลงเซิร์ฟ (ต้องปิดเซิร์ฟก่อนแล้วรัน `gradlew.bat jar`) · รอผู้ใช้ตรวจ |
| 2026-11-12 | R1–R38 | รีวิวโค้ดเก่าทั้งหมด 4 ส่วน (ปลั๊กอินแกน, Dungeon/Party/Channel, Web console, EXea+schema) ยืนยันข้อ Critical ในโค้ดแล้ว ปรับสถานะ 0.x, 6.4, 9.1 ลงเป็น 🟡 | ยังไม่ได้แก้ข้อไหนเลย · ควรทำ P0 ก่อนงานฟีเจอร์ใหม่ · R12 ต้อง rotate รหัส Neon เอง |
| 2026-11-12 | — | สร้างตาราง handoff นี้จากการอ่านโค้ดจริง, สร้าง `CLAUDE.md`, ย้าย credential DB ออกจาก `web-console/lib/db.js` และ `init_neon.mjs` ไปไว้ใน `.env.local` | ยังไม่ได้ commit · ต้อง rotate รหัสผ่าน Neon (ค่าเก่ายังอยู่ใน git history) · `DimensionalMinerCore/bin/` ควรเพิ่มใน `.gitignore` |
