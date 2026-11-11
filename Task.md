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
| 6 | Instance Dungeon | 0 | 3 | 0 | 1 |
| 7 | World Boss | 4 | 0 | 0 | 0 |
| 8 | Workspace & Market | 4 | 0 | 0 | 0 |
| 9 | Mailbox & EXP/Energy | 1 | 1 | 0 | 2 |
| 10 | Server Transfer | 3 | 0 | 0 | 0 |
| 11 | Web Console | 2 | 0 | 1 | 0 |
| 12 | Resource Pack & 3D | 2 | 1 | 0 | 0 |
| 0 | ระบบพื้นฐานที่มีอยู่แล้ว | 0 | 2 | 1 | 4 |
| R | แก้งานเก่าจากการรีวิว (P0 15 · P1 15 · P2 8) | 0 | 2 | 0 | 36 |

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
| R1 | `/dm give` / `/dm reload` ไม่มี permission | ✅ | `core/CustomItemCommand.java` (เช็ค `dimensionalminer.admin` ทั้งใน `onCommand` และ tab-complete), `plugin.yml` (เพิ่ม permission ให้คำสั่ง + บล็อก `permissions:` ตั้ง `default: op`) | **วิธีตรวจ:** บิลด์ jar → ใช้บัญชีที่ไม่ใช่ OP พิมพ์ `/dm give <ชื่อตัวเอง> <item> 1` ต้องขึ้นว่าไม่มีสิทธิ์ และกด Tab แล้วไม่มีอะไรเด้งขึ้น → `/op` ตัวเองแล้วลองใหม่ ต้องใช้ได้ |
| R2 | Mailbox: ให้ของก่อนแล้วค่อย mark CLAIMED แบบ async และ `UPDATE` ไม่มี `AND state='AVAILABLE'` | ✅ | `core/GrantsMenu.java` (`claim`, `markClaimed`), `GrantsCommand.java` (ลบโค้ดตายทิ้ง) | **แก้แล้ว:** claim ใน DB ก่อน (`WHERE grant_id=? AND player_id=? AND state='AVAILABLE'` → ต้องได้ 1 แถว) แล้วค่อยให้ของ · กันคลิกซ้อนด้วย `claimsInFlight` · ผู้เล่นออกก่อนได้ของ → คืน grant กลับเป็น AVAILABLE · **วิธีตรวจ:** คลิกรัวๆ ที่ reward เดียว / เปิด `/grants` แล้วคลิกพร้อมกันจาก 2 บัญชีไม่ได้ (ต้องเป็นของเรา) → ได้ของครั้งเดียว และใน DB `state='CLAIMED'` |
| R3 | GrantsMenu ไม่ cancel การ double-click ในกระเป๋าตัวเอง | ✅ | `core/GrantsMenu.java` (`MenuListener`) | **แก้แล้ว:** cancel ทุก click/drag ตอนเมนูเปิด (รวมฝั่งกระเป๋าตัวเอง) และรับแค่คลิกซ้าย/ขวาบนช่องของเมนู · **วิธีตรวจ:** ถือไอเทมชนิดเดียวกับ reward แล้ว double-click / shift-click / กดเลข 1-9 / ลาก → ไม่มีอะไรขยับ |
| R4 | Claim ของชิ้นเดียว แต่ทั้ง grant ถูก mark ว่ารับแล้ว ของเกิน 64 ถูกตัดทิ้ง ส่วน leftover ตอนกระเป๋าเต็มหายไป | ✅ | `core/GrantsMenu.java` (`buildStacks`, `fitsInInventory`, `deliver`, `remail`) | **แก้แล้ว:** 1 ช่อง = 1 grant รับทั้งก้อน · เช็คที่ว่างกับ inventory จำลอง · แบ่ง stack ตามขนาดจริง (128 ชิ้น = 2 stack) · ใส่ไม่ลง → ส่งกลับเป็น grant ใหม่ · payload อ่านไม่ได้ → แสดงเป็น barrier และห้ามรับ (ไม่ส่งของครึ่งๆ) · รองรับ key `item`/`qty` ของ dungeon reward ด้วย · **วิธีตรวจ:** grant 128 ชิ้นได้ครบ 2 stack · กระเป๋าเต็มแล้วกดรับ → ขึ้นว่าที่ไม่พอ และ reward ยังอยู่ |
| R5 | `onDisable` สั่ง save แบบ async แล้วปิด pool ทันที | ✅ | `core/InventorySync.java` (`shutdown`), `DimensionalMinerCore.java` (`onDisable`) | **แก้แล้ว:** `shutdown()` snapshot ทุกคน แล้วรอให้ save ทั้งหมด commit (สูงสุด 20 วิ) ก่อนปิด pool · **วิธีตรวจ:** หยิบของใหม่แล้ว `stop` ทันที → เปิดเซิร์ฟใหม่ ของยังอยู่ |
| R6 | Save ทำงานได้ก่อน load inventory เสร็จ (หรือตอน load ล้มเหลว) | ✅ | `core/InventorySync.java` (`loaded`, `loadAsync`) | **แก้แล้ว:** ไม่ save จนกว่า load สำเร็จ · load ล้มเหลว → kick ผู้เล่นพร้อมข้อความ · ย้าย `upsertPlayer` ออกจาก main thread · **วิธีตรวจ:** ปิด DB แล้วเข้าเกม → ถูก kick และของใน DB ไม่ถูกลบ |
| R7 | Save หลาย batch commit สลับลำดับกันได้ และ "fencing" ใน javadoc ไม่มีอยู่จริง | ✅ | `core/InventorySync.java` (`enqueue`, `ioExecutor`) | **แก้แล้ว:** load/save ของผู้เล่นคนเดียวกันต่อคิวกันบน thread pool ของตัวเอง → commit ตามลำดับที่ snapshot, ออกแล้วเข้าใหม่เร็วๆ load จะรอ save ตอนออกก่อน · javadoc แก้ให้ตรงความจริง (fencing ข้ามเซิร์ฟยังไม่มี → งาน 10.2) · **วิธีตรวจ:** ย้ายของไปมาแล้วออก-เข้าทันทีหลายรอบ → ของครบไม่ซ้ำ |
| R8 | `/api/admin/*`, `/api/content/[kind]`, `/api/export` ไม่มีการเช็คสิทธิ์ (proxy ดักแค่ `/admin/*`) | ✅ | `web-console/proxy.js`, `lib/auth.js` (`requireAdmin`), `app/api/admin/**`, `app/api/content/[kind]`, `app/api/export` | **แก้แล้ว:** proxy ดักทั้ง `/admin/*` และ `/api/admin/*` (API ตอบ 401) และทุก handler เรียก `requireAdmin()` ซ้ำอีกชั้น · content จำกัด `kind` 6 ชนิด + `definition_id` ต้องเป็น `a-z0-9_` · **ทดสอบแล้ว:** 8 endpoint ไม่ login → 401, login แล้ว → 200 |
| R9 | ไม่มี identity ฝั่ง server: `/api/shop/buy` เชื่อ `player_id` จาก client และหน้า login รับ UUID ตรงๆ | ✅ | `lib/auth.js`, `app/api/auth/{login-key,me,logout}`, `app/api/shop/buy`, `app/api/player/[id]/transactions`, `lib/session.js`, `app/login`, `app/shop` | **แก้แล้ว:** แลก OTP แล้วได้ cookie `dm_session` (httpOnly, sign ด้วย HMAC, 7 วัน) · buy ใช้ `player_id` จาก cookie เท่านั้น · ประวัติธุรกรรมดูได้แค่เจ้าของหรือ admin · ลบการ login ด้วย UUID · ฝั่ง browser อ่าน session จาก `/api/auth/me` · **ทดสอบแล้ว:** ซื้อด้วย UUID คนอื่น → 401, cookie ที่แก้ไข → ไม่ผ่าน, login/shop/logout ใน browser ทำงาน |
| R10 | Admin cookie = `base64("admin:"+password)`, default `admin123` | ✅ | `lib/auth.js`, `app/api/admin/login/route.js`, `proxy.js`, `app/admin/login/page.jsx` | **แก้แล้ว:** cookie เป็น token ที่ sign ด้วย `SESSION_SECRET` (ไม่มีรหัสผ่านอยู่ข้างใน, หมดอายุ 8 ชม.) · ไม่มีรหัส default (ไม่ตั้ง `ADMIN_PASSWORD` = เข้าไม่ได้) · เทียบรหัสแบบ constant-time · ผิด 10 ครั้ง/15 นาที → ล็อก · **ทดสอบแล้ว:** cookie แบบเก่า → 401 |
| R11 | OTP: เช็คแล้วค่อย mark ว่าใช้แล้วแยกกัน 2 คำสั่ง (error ถูกกลืน), ไม่มี rate limit, GET ใส่ key ใน URL | ✅ | `app/api/auth/login-key/route.js`, `lib/rate-limit.js` | **แก้แล้ว:** เช็คและใช้ key ใน `UPDATE … RETURNING` คำสั่งเดียว · ผิด 10 ครั้ง/10 นาทีต่อ IP → 429 · ตัด GET ทิ้ง (ลิงก์ `/login?code=` จากในเกมยังใช้ได้ เพราะหน้าเว็บส่ง POST ให้) · **ทดสอบแล้ว:** ยิง key เดียวพร้อมกัน 3 ครั้ง → ผ่าน 1 ครั้ง · ข้อจำกัด: rate limit อยู่ใน memory ของ process เดียว |
| R12 | รหัสผ่าน Neon อยู่ใน `config.yml` ซึ่งไม่อยู่ใน .gitignore และถูก bundle เข้าไปใน jar | ✅ | ผู้ใช้ rotate รหัสแล้ว (รหัสเก่าต่อไม่ได้แล้ว, รหัสใหม่ต่อได้) · ใส่รหัสใหม่ใน `web-console/.env.local`, `.env`, `server/plugins/DimensionalMinerCore/config.yml` · `src/main/resources/config.yml` ใช้ `CHANGE_ME` + คำเตือน · `.gitignore` เพิ่ม `env.txt`, `*/bin/` | **วิธีตรวจ:** เปิด `npm run dev` แล้วเข้า `/profile` ได้ · รีบิลด์ jar (ตัวเก่าใน `server/plugins/` ยังมีรหัสเก่าที่ตายแล้วอยู่) แล้วเซิร์ฟต่อ DB ได้ · ลบ `env.txt` ทิ้ง |
| R13 | Unload world ทั้งที่การวาร์ป async ยังไม่เสร็จ, ไม่เช็คผล unload แล้วลบโฟลเดอร์ต่อเลย | ✅ | `core/dungeon/runtime/DungeonInstanceManager.java` (`destroyInstance`, `unloadThenDelete`, `evacuateNow`) | **แก้แล้ว:** วาร์ปทุกคนที่อยู่ใน world ออกแบบ sync (ทั้งสมาชิกและคนที่ `/dungeon join` เข้ามา) ถ้าวาร์ปไม่ได้จะ kick · เช็คผล `unloadWorld` ถ้าไม่ผ่านจะลองใหม่ทุก 2 วิ (สูงสุด 5 ครั้ง) · **ลบโฟลเดอร์ต่อเมื่อ unload สำเร็จเท่านั้น** · ล้าง `playerToSession` ของทุกคนใน session · **วิธีตรวจ:** `/dungeon enter` แล้ว `/dungeon destroy <id>` ระหว่างที่ยังยืนอยู่ข้างใน → ถูกวาร์ปออก, log ไม่มี "did not unload", โฟลเดอร์ `ind_t0_<id>` หายไป |
| R14 | `advanceStep()` ไม่มีตัวกันเรียกซ้ำ | ✅ | `core/dungeon/runtime/DungeonInstanceSession.java` (`advancing`, `advanceStep`, `runStep`, `finishDungeon`) | **แก้แล้ว:** flag `advancing` กันจบ step ซ้ำ (ทั้งตอนฆ่ามอน, กดสวิตช์ และ goal timer) · จับ index ของ step ถัดไปไว้ตั้งแต่ตอน schedule · `finishDungeon` ทำงานได้ครั้งเดียว · **วิธีตรวจ:** ใช้ดันเจี้ยนทดสอบ `dungeon_test_training` (`/dm reload` → `/dungeon enter dungeon_test_training`) ฆ่า Husk 5 ตัว (ลองใช้ TNT ให้ตายพร้อมกัน) → รอด 10 วิ → ฆ่า Test Boss · ข้อความ `[TEST] Step N on_end ran` ต้องขึ้น**ข้อละครั้งเดียว** · VICTORY ขึ้นครั้งเดียว · `/grants` ได้รางวัล 1 ชิ้น (prim_metal x2 + bread x3) |
| R15 | EXea: bone ตั้ง `setPersistent(true)` แต่ root ไม่ persistent | ✅ | `EXeaEntityCore/.../render/DisplayRenderer.java`, `EXeaEntityPlugin.java` (`onEntitiesLoad`) | **แก้แล้ว:** bone เป็น non-persistent เหมือน root (ไม่ถูกบันทึกลง disk) · ตอน chunk โหลด entity ที่มี tag `exea_bone`/`exea_entity` ที่ค้างมาจาก build เก่าจะถูกลบทิ้ง · **วิธีตรวจ:** spawn void_overseer แล้ว kill เซิร์ฟ (ไม่ใช้ stop) → เปิดใหม่แล้วไปที่เดิม ต้องไม่มีโมเดลค้าง |

### P1 — บั๊กที่เจอแน่ตอนเล่นจริง

| ID | ปัญหา | สถานะ | ไฟล์ | สิ่งที่เกิดได้ → วิธีแก้ |
|---|---|---|---|---|
| R16 | เก็บแค่ `defId` + จำนวน ไม่เก็บ enchant/durability/NBT, ID ที่ไม่รู้จักกลายเป็น STONE, ไม่มี handler ตอนปิด inventory (ทั้งที่ javadoc บอกว่ามี) | ✅ | `core/InventorySync.java` (`collectChanged`, `writeSlot`, `loadInventory`, `toItemStack`, `hashItem`), `database/schema_game.sql` (คอลัมน์ `material_stacks.item_data`) | **แก้แล้ว:** เก็บ ItemStack ทั้งชิ้น (`serializeAsBytes`) ลงคอลัมน์ใหม่ `item_data` (เพิ่มใน Neon แล้ว) · ตอนโหลดคืนของแบบเป๊ะ · แถวเก่าที่ไม่มี `item_data` ใช้วิธีเดิม · ID ที่ไม่รู้จักไม่กลายเป็น STONE แล้ว (ปล่อยช่องว่าง + log เตือน แถวใน DB ไม่ถูกลบ) · hash ครอบคลุม meta ทั้งหมด จึงเปลี่ยนแค่ enchant/durability ก็ save · **วิธีตรวจ:** ถือดาบ แล้ว enchant (`/enchant`) + ตีมอนให้ดาบสึก + ตั้งชื่อด้วย anvil → ออกแล้วเข้าเกมใหม่ ต้องอยู่ครบทุกอย่าง · ถือของเกิน stack ปกติไม่ได้หายไป · **หมายเหตุ:** ของที่ save ก่อนอัปเดตนี้จะยังไม่มี enchant (ข้อมูลเก่าไม่ได้เก็บไว้) แต่จะถูกเก็บครบตั้งแต่ save ครั้งถัดไป |
| R17 | ใช้ Minecraft UUID ในจุดที่ schema ต้องการ `account_id` | ✅ | `DatabaseManager.java` (`getAccountId`, `findAccountId`, `upsertPlayer`, `getBalances`, `createWebLoginKey`), `BalanceCommand`, `WebLoginCommand` | **แก้แล้ว:** `getAccountId` ไม่ fallback เป็น Minecraft UUID แล้ว (หาไม่เจอหรือ DB error → throw ให้ผู้เรียกจัดการ ซึ่งทุกจุดมี catch อยู่แล้ว) · `/weblogin` และ `/balance` ใช้ account_id · `upsertPlayer` คืน account_id และ throw เมื่อพัง (ตอน join → kick แทนเล่นต่อแบบไม่มีบัญชี) · `ensureAccounts` กลายเป็น private ใช้ภายใน `upsertPlayer` · **วิธีตรวจ:** `/weblogin` → ล็อกอินเว็บได้ · `/balance` ขึ้นยอดตรงกับเว็บ · `/grants` ยังเห็นของ |
| R18 | DB I/O บน main thread | ✅ | `BalanceCommand`, `WebLoginCommand` (async), `ShopCommand`, `ProfileCommand` (ไม่แตะ DB แล้ว), `InventorySync` (upsert ตอน join อยู่บนคิว IO ตั้งแต่ R6) | **แก้แล้ว:** ไม่มีคำสั่งไหนแตะ DB บน main thread แล้ว · `/shop` กับ `/profile` แค่ส่งลิงก์ เพราะบัญชีและกระเป๋าเงินถูกสร้างตอน join อยู่แล้ว · **วิธีตรวจ:** พิมพ์ `/balance`, `/weblogin`, `/shop`, `/profile` รัวๆ → เซิร์ฟไม่กระตุก (ดู TPS ด้วย `/tps` หรือ `/mspt`) และทุกคำสั่งตอบปกติ |
| R19 | ต่อ DB ไม่ได้แต่ปลั๊กอินยังเปิดทำงาน (exception ถูกกลืน) | ✅ | `DatabaseManager` (`connect`, `testConnection`, `getConnection`), `DimensionalMinerCore.onEnable` | **แก้แล้ว:** `connect()`/`testConnection()` throw แทนการกลืน error → `onEnable` ปิดปลั๊กอินพร้อมข้อความชัดเจนใน console · ไม่มีรหัส/URL default ฝังในโค้ดแล้ว (config ไม่ครบ → error บอกว่าขาดอะไร) · ถ้ารหัสยังเป็น `CHANGE_ME` จะบอกตรงๆ · **วิธีตรวจ:** แก้ `password:` ใน `server/plugins/DimensionalMinerCore/config.yml` ให้ผิด → เปิดเซิร์ฟ → console ต้องขึ้น "DimensionalMinerCore is DISABLED" และ `/plugins` แสดงเป็นสีแดง → ใส่รหัสกลับแล้วเปิดใหม่ต้องใช้ได้ |
| R20 | `/dm reload` เคลียร์ registry ก่อนแล้วค่อยเติมใหม่ (ว่างชั่วคราว) + `loadFromDatabase` query ตารางที่ไม่มีใน game DB | ✅ | `core/ContentRegistryManager.java` (`loadAll`, `loadFromFile`), `CustomItemCommand.java` (`/dm reload`) | **แก้แล้ว:** โหลดไฟล์ลง map ใหม่แล้วสลับทีเดียว (ไม่มีช่วงที่ registry ว่าง) · ไฟล์เสียหรือหาไม่เจอ → ใช้ registry เดิมต่อ และ `/dm reload` แจ้งสีแดง · definition เสียตัวเดียวถูกข้าม (log เตือน) ไม่ทำให้ทั้งไฟล์พัง · ตัด `loadFromDatabase` ทิ้ง (ปลั๊กอินไม่มีตาราง content_definitions) · **วิธีตรวจ:** `/dm reload` → ขึ้น "Reloaded N items, M dungeons" · ลองทำให้ `server/plugins/DimensionalMinerCore/content_registry.json` เสีย (ลบ `}` ตัวท้าย) แล้ว `/dm reload` → ขึ้นสีแดงว่า reload ไม่สำเร็จ แต่ `/dm list` ยังเห็นของเดิม → แก้ไฟล์คืน |
| R21 | `members` ของ session เป็น view สดของ party | ✅ | `dungeon/DungeonCommand.java:127`, `DungeonInstanceSession.java:68` | **แก้แล้ว:** session ทำสำเนารายชื่อตอนเริ่ม (`DungeonInstanceSession` constructor) คนที่เข้าปาร์ตี้ทีหลังไม่ได้อยู่ใน run · รางวัลให้เฉพาะคนที่อยู่ในรายชื่อตอนจบ · **วิธีตรวจ:** เริ่ม run คนเดียว → ให้เพื่อนรับ invite ระหว่าง run ต้องโดนปฏิเสธ (party lock) และไม่ได้รางวัล |
| R22 | `/dungeon join` ใครก็เข้าได้ และ `/dungeon enter` ซ้ำได้ | ✅ | `DungeonInstanceManager.java:104-116`, `DungeonCommand.java:90-136` | **แก้แล้ว:** `/dungeon join` รับเฉพาะสมาชิกปาร์ตี้เจ้าของ run · เช็ค `max_players` · คนที่อยู่ run อื่นเข้าไม่ได้ · `/dungeon enter` ซ้ำ หรือสมาชิกคนไหนอยู่ใน run อยู่แล้ว → ปฏิเสธพร้อมเหตุผล (`isPartyBusy`, `provisioningParties`) · **วิธีตรวจ:** พิมพ์ `/dungeon enter dungeon_test_training` 2 ครั้งติด → ครั้งที่ 2 ขึ้น "already in a dungeon" · ผู้เล่นนอกปาร์ตี้ `/dungeon join <id>` → ขึ้น "only join a dungeon started by your own party" |
| R23 | ดันเจี้ยนที่ไม่มีคนอยู่ไม่มีวันจบ: ไม่บังคับ time limit, ไม่ cleanup ตอน `onDisable`, โฟลเดอร์ orphan | ✅ | `DungeonInstanceManager`, `DimensionalMinerCore.onDisable` | **แก้แล้ว:** watchdog ทุก 5 วิ: เกิน `time_limit_seconds` → ปิด · ไม่มีใครจาก run อยู่ใน instance เกิน 60 วิ → ปิด · `onDisable` ปิดทุก instance (`shutdownAll`) · ตอนเปิดเซิร์ฟลบโฟลเดอร์ `<template>_<8hex>` ที่ค้างจาก crash (`sweepOrphanInstanceFolders`) · **วิธีตรวจ:** เข้า run แล้ว `/exitdungeon` → ภายใน ~65 วิ console ขึ้น "Closing empty instance" และโฟลเดอร์หาย · เปิดเซิร์ฟใหม่ → โฟลเดอร์ `ind_t0_08290480` / `ind_t0_2d7b22b7` ที่ค้างอยู่ต้องถูกลบ (log "Removing leftover instance folder") |
| R24 | `/dungeon destroy` ไม่ cancel task ที่รออยู่ | ✅ | `DungeonInstanceSession.java` (`cleanup`, `runStep`) | **แก้แล้ว (พร้อม R14):** `cleanup()` ตั้ง `completed=true` แล้ว step ที่รออยู่กับ action ที่หน่วงเวลาจะเช็ค flag นี้และหยุด ส่วน interval/goal timer ถูก cancel อยู่แล้ว · **วิธีตรวจ:** ใน `dungeon_test_training` พอข้อความ `Step 2 on_end ran` ขึ้น ให้พิมพ์ `/dungeon destroy <sessionId>` ทันที (มีเวลา 2 วิ) → Test Boss ต้องไม่เกิด และไม่มี error ใน log |
| R25 | ประกาศว่าแจกรางวัลแล้วก่อนเขียนลง DB จริง และถ้าเขียนไม่สำเร็จก็ไม่มีใครรู้ (= งาน 6.3) | ✅ | `DungeonInstanceSession.java:240-247,447-479` | **แก้แล้ว:** บันทึกรางวัลใน transaction ต่อคนก่อน แล้วค่อยขึ้น VICTORY + ข้อความผลจริง (บันทึกไม่ได้ → แจ้งสีแดง + log) · กันจ่ายซ้ำด้วย operation `dungeon_reward/<sessionId>` · **รางวัลเงิน (COIN/ENERGY/SERVER_XP) จ่ายเข้ากระเป๋าแล้ว** แบบ double-entry (+ผู้เล่น / − บัญชีระบบ `DUNGEON_REWARDS`) · คนที่ `/exitdungeon` ไปแล้วไม่ได้รางวัล · **วิธีตรวจ:** จบ `dungeon_test_training` → ข้อความ "Rewards recorded: 2x prim_metal, 3x bread" ขึ้นหลัง VICTORY · `/grants` มีของ · (ถ้าแก้ `dungeon_ancient_ruins_t0` ให้จบได้ COIN 1000 ต้องเข้า `/balance`) |
| R26 | ไม่มี template แล้วระบบสร้างโลกสุ่มขึ้นมาแทน | ✅ | `DungeonInstanceManager.java:52-63` | **แก้แล้ว:** ไม่มีโฟลเดอร์ template → ปฏิเสธพร้อมข้อความ ไม่สร้างโลกสุ่ม · copy พังกลางทาง → ลบโฟลเดอร์ครึ่งๆ ทิ้ง · **วิธีตรวจ:** แก้ `template_map` ของดันเจี้ยนทดสอบเป็นชื่อที่ไม่มี → `/dm reload` → `/dungeon enter` ต้องขึ้น "Template map ... was not found" |
| R27 | Party: เข้าได้ 2 ปาร์ตี้, ออกปาร์ตี้ระหว่างอยู่ในดันเจี้ยนได้ (lock โดนเลี่ยงได้ด้วย `/dimensionalminercore:party`), `party_members` ใน DB ไม่ตรงกับ memory | ✅ | `party/PartyManager.java:93-110,225-231`, `DungeonEventListener.java:64` | **แก้แล้ว:** `acceptInvite` ปฏิเสธถ้าอยู่ปาร์ตี้อื่นอยู่แล้ว · ระหว่าง run ห้าม invite/accept/leave/disband (เช็คใน `PartyManager` จึงเลี่ยงด้วย alias ไม่ได้ ลบตัวดัก command เดิมออก) · คนที่ `/exitdungeon` แล้ว (ที่ไม่ใช่หัวหน้า) ออกปาร์ตี้ได้ · disband ลบแถว `party_members` ด้วย · **วิธีตรวจ:** ระหว่าง run พิมพ์ `/party leave` และ `/dimensionalminercore:party leave` → โดน "[Party Lock]" ทั้งคู่ |
| R28 | Ledger ซื้อของบันทึกฝั่งเดียว (ไม่ใช่ double-entry), idempotency key ใช้ `Date.now()` | ✅ | `web-console/lib/ledger.js` (ใหม่: `lockWallet`, `transfer`), `app/api/shop/buy/route.js`, `app/shop/page.jsx`, `scripts/grant_coin.mjs` | **แก้แล้ว:** ซื้อของลงบัญชี 2 ฝั่ง (ผู้เล่น − / บัญชีระบบ `SHOP_REVENUE` +) · หน้าเว็บสร้าง `idempotency_key` ครั้งละหนึ่งตัวต่อการเปิดหน้ายืนยัน ส่งซ้ำ = ได้ผลเดิม ไม่หักเงินซ้ำ · key เดิมแต่สินค้าอื่น → 409 · `grant_coin.mjs` ไม่ฝัง player id แล้ว ใช้ `<ชื่อ|player_id> <จำนวน>` และลงบัญชี 2 ฝั่งกับ `ADMIN_GRANTS` (ชื่อซ้ำหลายคน → ให้ใช้ player_id) · **ทดสอบแล้ว 10/10:** key ซ้ำ → replayed, ยิงพร้อมกัน 5 ครั้ง → หักครั้งเดียว grant เดียว, ledger ทุก operation รวมเป็น 0 · ยังไม่ได้ทำ: constraint ใน DB ที่บังคับว่าผลรวมต้องเป็น 0 (→ R36) · **วิธีตรวจ:** ซื้อของในหน้า `/shop` แล้วกดยืนยันรัวๆ → หัก COIN ครั้งเดียว และ `/grants` มีของชิ้นเดียว |
| R29 | EXea: chunk ticket ไม่นับ reference, cleanup ลบ entity ที่ชื่อมี "Void Overseer" หรือ CMD 1001-1022 ของปลั๊กอินอื่นด้วย, ฉากตายไม่เคยเล่น | ✅ | `EXeaEntityCore/.../manager/ChunkTickets.java` (ใหม่), `EXActiveEntity.java` (`updateChunkTicket`, `releaseChunkTicket`, `startDeathSequence`, `isDying`), `EntityManager.cleanupOrphanedEntities`, `EXeaEntityPlugin.onLethalDamage` | **แก้แล้ว:** chunk ticket นับจำนวนคนถือ (ตัวแรกขอ ticket ตัวสุดท้ายคืน) · cleanup ลบเฉพาะ entity ที่มี tag `exea_entity`/`exea_bone` ของเราเอง (เลิกดูจากชื่อและ CMD 1001–1022) · ดาเมจที่จะทำให้ตายถูกยกเลิก แล้วเล่นฉากตาย 3 วินาทีแทน (ระหว่างนั้นอมตะและหยุด AI) ครอบคลุมทุกสาเหตุ (ตี, ธนู, ไฟ, ตก) ยกเว้น `/kill` · **วิธีตรวจ:** `/exea spawn void_overseer` 2 ตัวชิดกัน แล้วเดินพาตัวหนึ่งออกไปไกล → อีกตัวต้องยังขยับและไม่หายไป · ตีจนตาย → ต้องเห็นเสียง Wither + ควันเขียว และโมเดลค้างให้ดูประมาณ 3 วินาทีก่อนหาย · ปลั๊กอินอื่นที่ใช้ชื่อ "Void Overseer" หรือ CMD 1001–1022 ไม่ถูกลบตอน `/exea kill all` |
| R30 | Web ส่ง `err.message` ของ Postgres กลับไปให้ client | ✅ | `web-console/lib/api.js` (`fail`), `login-key/route.js` | **แก้แล้ว (พร้อม R8–R11):** error 5xx จะ log ฝั่ง server แล้วตอบ `Internal server error` ส่วน 4xx ยังส่งข้อความให้ผู้ใช้ตามเดิม |

### P2 — ประสิทธิภาพ / ความสะอาดของโค้ด

| ID | ปัญหา | สถานะ | ไฟล์ |
|---|---|---|---|
| R31 | Channel clone dimension บน main thread ตอน constructor, heuristic "<5 region" ทำให้ clone ใหม่ทุกครั้งที่บูต, `/reload` อาจลบ world ที่โหลดอยู่ | ✅ | `channel/ChannelManager.java` · **แก้แล้ว:** copy แมพ channel บน async thread แล้วค่อยโหลด world บน main (channel ใช้ไม่ได้จนกว่าจะโหลดเสร็จ `/channel` จะบอก) · ใช้ไฟล์ marker `.dm_channel_clone` แทนการเดาจากจำนวน region (copy ครั้งเดียว ถ้าพังกลางทางจะ copy ใหม่รอบหน้า) · world ที่โหลดอยู่แล้ว (เช่นหลัง `/reload`) จะไม่ถูกแตะไฟล์ · ตัด path สำรองที่ชี้ไปโฟลเดอร์ `Map/` ของเครื่อง dev ทิ้ง · **วิธีตรวจ:** เปิดเซิร์ฟ → console ขึ้น "Cloned map template for channel" แค่ครั้งแรก (ครั้งต่อไปไม่ copy ซ้ำ) และ `/channel 2`, `/channel 3` ใช้ได้ |
| R32 | `Files.walk` ไม่ปิด stream, copy template ไปไว้ 2 ที่, copy พังกลางทางแล้วเหลือโฟลเดอร์ครึ่งๆ | ✅ | `dungeon/runtime/DungeonInstanceManager.java`, `util/WorldFiles.java` · **แก้แล้ว:** ยืนยันจาก log ว่า Paper 26.2 โหลด instance เป็น dimension (`level 'minecraft:ind_t0_xxxx'`) จาก `overworld_t0/dimensions/minecraft/` → เลิก copy ไป root ของเซิร์ฟ (ประหยัด I/O ครึ่งหนึ่ง) · ตอนลบยังเช็ค path เก่าให้ · stream ปิดครบ, copy พังลบโฟลเดอร์ครึ่งๆ · **วิธีตรวจ:** `/dungeon enter dungeon_test_training` → ใน `server/` ต้องไม่มีโฟลเดอร์ `ind_t0_xxxx` ใหม่ (มีแค่ใน `overworld_t0/dimensions/minecraft/`) และเล่นได้ปกติ |
| R33 | `/exitdungeon` ไม่เอาผู้เล่นออกจาก session (rejoin แล้วถูกวาร์ปกลับเข้าไป), ตายในดันเจี้ยนไม่มี handler, ถ้า content ไม่มี `display_text` จะ NPE จน step ค้าง | ✅ | **แก้แล้ว:** `/exitdungeon` เอาออกจาก run จริง (ไม่ถูกวาร์ปกลับตอน rejoin, ไม่ได้รางวัล) · ตายใน run → เกิดใหม่ที่จุดเกิดของ instance (`onPlayerRespawn`) · `display_text` ไม่มีก็ไม่ค้าง · **วิธีตรวจ:** ตายใน run → เกิดในดันเจี้ยน · `/exitdungeon` แล้วออก-เข้าเกม → ต้องอยู่ที่ hub |
| R34 | GrantsMenu ลงทะเบียน listener ใหม่ทุกครั้งที่เปิดเมนู ทำให้ listener รั่ว | ✅ | **แก้แล้ว (พร้อม R2–R4):** `GrantsMenu.MenuListener` ตัวเดียว ลงทะเบียนครั้งเดียวใน `onEnable` |
| R35 | EXea: per-tick สร้าง Matrix/Quaternion ใหม่ทุก bone, teleport ทีละ bone, โมเดลเอียงตาม pitch หัว, ลำดับ Euler XYZ (Blockbench ใช้ ZYX), slerp 0→360° ไม่หมุน | 🟡 | `AnimationController.java`, `DisplayRenderer.java` · **แก้แล้ว:** ไม่ส่ง transformation ซ้ำถ้ากระดูกไม่ขยับ (ลด packet ของโมเดลที่ยืนนิ่ง/ท่าค้าง) · cache การหมุนเริ่มต้นของแต่ละกระดูก · โมเดลไม่เอียงตาม pitch ของหัว Husk แล้ว · **ยังไม่ทำ (ตั้งใจ):** ลำดับ Euler XYZ→ZYX และ slerp 0°→360° เพราะเปลี่ยนแล้วท่าทางที่ดูถูกอยู่ตอนนี้อาจเพี้ยน ต้องตรวจด้วยตาเทียบกับ Blockbench ก่อน · teleport กระดูกทีละชิ้น (ทางแก้คือทำเป็น passenger ซึ่งเปลี่ยนเยอะ) · **วิธีตรวจ:** spawn void_overseer แล้วมองขึ้น-ลงใกล้ๆ → ตัวโมเดลต้องไม่เอียงตาม · แอนิเมชันยังลื่นเหมือนเดิม |
| R36 | Schema: ขาด index (`ledger_entries.operation_id` ฯลฯ), `item_transactions` ON DELETE CASCADE, item กับ stack อยู่ slot เดียวกันได้, login key เก็บเป็น plaintext | 🟡 | `database/migrations/2026-11-12_r36_constraints.sql` (ต่อท้ายใน `schema_game.sql` ด้วย) · **แก้แล้ว (รันบน Neon แล้ว รันซ้ำได้):** index ที่ขาด 8 ตัว · UNIQUE (operation_id, account_id) ใน ledger · **trigger บังคับว่า ledger ของทุก operation ต้องรวมเป็น 0 ตอน commit** (ทดสอบแล้ว: ฝั่งเดียว → ถูกปฏิเสธ, 2 ฝั่ง → ผ่าน) · ลบไอเทมไม่ลบประวัติแล้ว (`ON DELETE SET NULL`) · CHECK ช่องกระเป๋า 0–35, accumulated_amount ≥ 0, ขายแล้วต้องมี grant · **ยังไม่ทำ:** บังคับ `claim_operation_id` ตอน CLAIMED (โค้ดยังไม่ได้ใส่), hash login key (ใช้ครั้งเดียว + 10 นาที + rate limit อยู่แล้ว), กันไอเทมกับ stack อยู่ช่องเดียวกัน · มี operation เก่า 4 รายการ (ก่อนแก้ R28) ที่ ledger ไม่สมดุล เก็บไว้เป็นประวัติ |
| R37 | Dead code (`GrantsCommand.processGrants/deliverGrants`, `displayFromPayload`), helper ถูก copy ซ้ำหลายที่, exception ถูกกลืน, javadoc อ้างระบบที่ไม่มีจริง, ใช้ `ChatColor` ปนกับ Adventure | ✅ | ทั้ง `core/` · **แก้แล้ว:** helper copy/ลบ world ที่ซ้ำ 2 ที่ รวมเป็น `util/WorldFiles` · exception ที่ถูกกลืนเงียบทุกจุดตอนนี้เขียน log แล้ว (party DB, stat ที่ไม่ใช่ตัวเลข, `vanilla_type` ที่ไม่มีจริง) · comment และ CLAUDE.md ที่อ้างว่า "save ตอนปิด inventory" แก้ให้ตรงกับความจริง · โค้ดที่ไม่ได้ใช้ถูกลบไปแล้วตั้งแต่รอบก่อน (GrantsCommand, InventorySync) · **ยังไม่ทำ:** เปลี่ยน `ChatColor` ทั้งหมดเป็น Adventure (11 ไฟล์ แค่เรื่องรูปแบบโค้ด ไม่กระทบการทำงาน) |
| R38 | `HANDOVER_EXEA_CORE.md` อ้างเกินจริง (Hermite, ดาเมจกวาด, standalone, 1.21.4, 100%) ทั้งที่จริงเป็นต้นแบบสำหรับ `void_overseer` ตัวเดียว | ✅ | `HANDOVER_EXEA_CORE.md` · **แก้แล้ว:** เพิ่มกล่อง "สถานะจริง" บนสุด: เป็นต้นแบบสำหรับ void_overseer ตัวเดียว, ตารางเทียบสิ่งที่เอกสารเดิมอ้างกับโค้ดจริง (Hermite ไม่มี, ดาเมจกวาดไม่ถูกใช้, ผูกกับโมเดลเดียว, Paper 26.2 ไม่ใช่ 1.21.4, pack generate เป็นแค่ข้อความ), สิ่งที่แก้แล้ว (R15, R29) และสิ่งที่ค้าง (R35) · เนื้อหาเดิมเก็บไว้เป็นประวัติ |

---

## 0. ระบบพื้นฐานที่มีอยู่แล้ว (ไม่อยู่ในรายการเดิม)

| ID | งาน | สถานะ | หลักฐาน | วิธีตรวจ / หมายเหตุ |
|---|---|---|---|---|
| 0.1 | Dual Database (content / game) + bootstrap | 🔍 | `database/schema_*.sql`, `web-console/scripts/init_databases.mjs`, `init_neon.mjs` | รัน init แล้วเช็คว่ามีตารางครบ (ข้อจำกัดของ schema ดู R36) |
| 0.2 | Content Editor + Export `content_registry.json` | ✅ | `web-console/app/admin/editor`, `lib/content.js`, `core/ContentRegistryManager.java` | R8 ✅ · R20 รอตรวจ |
| 0.3 | Inventory Sync กับ DB | 🟡 | `core/InventorySync.java` | R5–R7, R16 ✅ · ยังเหลือ fencing ข้ามเซิร์ฟ (10.2) |
| 0.4 | Party (create/invite/accept/leave/disband) | ✅ | `core/party/` | R27 แก้แล้ว รอตรวจ |
| 0.5 | Web Login OTP (`/weblogin`) | ✅ | `core/WebLoginCommand.java`, `app/api/auth/login-key` | R9, R11 ✅ · R17 รอตรวจ |
| 0.6 | Web Shop → Mailbox | ✅ | `app/shop`, `app/api/shop/buy`, `core/ShopCommand.java` | R8, R9 ✅ · R28 รอตรวจ |
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
| 6.2 | กุญแจและปาร์ตี้ (ตัดกุญแจเมื่อเริ่ม, แช่แข็งปาร์ตี้) | 🟡 | `core/dungeon/DungeonCommand.java`, `party/PartyManager.java` | แช่แข็งปาร์ตี้ระหว่าง run แล้ว (R27) · ยังไม่มีระบบกุญแจ |
| 6.3 | แจกรางวัลลง DB **ก่อน** เล่น Animation ชนะ | ✅ | `DungeonInstanceSession.java` (`finishDungeon`, `grantFinalRewards`, `announceVictory`) | ทำแล้วพร้อม R25 |
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
| 9.1 | Mailbox ไม่จำกัด (Pagination, ไม่หมดอายุ) | ✅ | `core/GrantsMenu.java` (หน้าละ 45 grant) | ตรวจพร้อม R2–R4 |
| 9.2 | กดรับของลง Inventory ใน transaction เดียว | ✅ | `core/GrantsMenu.java` | claim ใน DB ก่อนแล้วค่อยให้ของ (กันของซ้ำได้แล้ว) · ข้อจำกัด: ถ้าเซิร์ฟล่มในช่วงสั้นๆ หลังให้ของแต่ก่อน save inventory ของรอบนั้นจะหาย (ไม่ซ้ำ) — แก้ได้เต็มเมื่อย้าย inventory เข้า transaction เดียวกัน |
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

## ทดสอบ Party / Dungeon แบบหลายคนด้วยบอท (R21, R22, R27)

ใช้บอทแทนเพื่อน ดูวิธีติดตั้งที่ [tools/party-bots/README.md](tools/party-bots/README.md) (ต้องมี ViaVersion + ViaBackwards และสลับเป็น offline mode ก่อน)

| # | ทดสอบ | ทำอะไร | ผลที่ต้องได้ |
|---|---|---|---|
| B1 | สร้างปาร์ตี้ 3 คน | `/party create` → `/party invite TestBot1` → `/party invite TestBot2` | บอทรับเอง · `/party list` เห็น 3 คน |
| B2 | R27 อยู่ 2 ปาร์ตี้ไม่ได้ | `TestBot2 /party leave` → `TestBot2 /party create` → `/party invite TestBot2` | terminal ขึ้น "already in a party" · TestBot2 ไม่เข้าปาร์ตี้เรา |
| B3 | R21 รายชื่อ run คงที่ | ปาร์ตี้มี TestBot1 → `TestBot1 quit` (ให้ออฟไลน์) → `/dungeon enter dungeon_test_training` | run เริ่ม · TestBot1 อยู่ในรายชื่อ (ออฟไลน์) |
| B4 | R22 เข้าทีหลังได้เฉพาะคนในปาร์ตี้ | รันบอทใหม่ (`node bots.mjs --count 1`) → `TestBot1 /dungeon join <id>` | TestBot1 เข้า run ได้ |
| B5 | R22 คนนอกเข้าไม่ได้ | รันบอทชื่ออื่น (`node bots.mjs --count 1 --prefix Stranger`) → `Stranger1 /dungeon join <id>` | ขึ้น "only join a dungeon started by your own party" |
| B6 | R27 ล็อกปาร์ตี้ระหว่าง run | `TestBot1 /party leave` และ `TestBot1 /dimensionalminercore:party leave` · `/party invite Stranger1` | โดน "[Party Lock]" ทุกคำสั่ง |
| B7 | R27 ออกจาก run แล้วออกปาร์ตี้ได้ | `TestBot1 /exitdungeon` → `TestBot1 /party leave` | ออกได้ (ไม่ใช่หัวหน้า) |
| B8 | R21/R25 รางวัลเฉพาะคนที่อยู่ถึงจบ | เล่น run จนจบ (TestBot1 ออกไปแล้วใน B7) | คุณได้รางวัลใน `/grants` · TestBot1 ไม่ได้ข้อความ "Rewards recorded" |

ทดสอบเสร็จ: `.\switch-online-mode.ps1 online` แล้วรีสตาร์ทเซิร์ฟ

---

## Handoff Log

บันทึกใหม่ไว้บนสุด ระบุวันที่, ID งาน, สิ่งที่ทำ และสิ่งที่ค้าง

| วันที่ | ID | สิ่งที่ทำ | ค้าง / ข้อควรระวัง |
|---|---|---|---|
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R31 (channel copy ครั้งเดียว), R32 (instance copy ที่เดียว), R37, R38 · R35 ที่แก้ไป (ไม่เอียงตามหัว, แอนิเมชันลื่น) ผ่าน แต่ยังเป็น 🟡 เพราะส่วนการหมุนยังไม่ได้ทำ | เหลือ 🟡 R35 (ลำดับแกนหมุน/slerp 360°/teleport ทีละกระดูก) และ R36 (claim_operation_id, hash login key, item+stack ช่องเดียวกัน) |
| 2026-11-12 | P2: R31, R32, R35 (บางส่วน), R36 (บางส่วน), R37, R38 | Channel copy แบบ async + marker · instance copy ที่เดียว · `util/WorldFiles` ใช้ร่วม · log แทนการกลืน exception · EXea ไม่ส่ง transform ซ้ำ + ไม่เอียงตามหัว · migration R36 (index, trigger ledger สมดุล, FK, CHECK) รันบน Neon แล้ว · แก้ HANDOVER_EXEA_CORE.md · `compileJava` ผ่านทั้ง 2 ปลั๊กอิน | บิลด์ jar ทั้ง 2 ตัวแล้ว (03:23) · ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit · รอบแรกที่เปิดเซิร์ฟ channel 2–3 จะ copy ใหม่ 1 ครั้ง (ยังไม่มีไฟล์ marker) |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R28 (ซื้อของไม่หักซ้ำ), R29 (EXea ticket/cleanup/ฉากตาย), R30, R34, 0.6 · **P0 และ P1 ผ่านครบทุกข้อ** | เหลือ P2: R31, R32 (บางส่วน), R35–R38 |
| 2026-11-12 | R29 | EXea: chunk ticket นับจำนวน (`ChunkTickets`), cleanup ใช้ tag ของตัวเองเท่านั้น, ดาเมจที่จะทำให้ตายถูกยกเลิกแล้วเล่นฉากตายแทน · บิลด์ `EXeaEntityCore.jar` แล้ว | **P1 ครบทุกข้อแล้ว (รอตรวจ R28, R29)** · ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit · ถ้าวันหลังใช้ EXea เป็นบอสในดันเจี้ยน: บอสจะไม่ส่ง `EntityDeathEvent` ตามปกติแล้ว ต้องให้ EXea ยิง event ของตัวเองตอนจบฉากตายเพื่อให้ KILL_BOSS นับ |
| 2026-11-12 | R28 | ซื้อของในเว็บลงบัญชี 2 ฝั่ง (`SHOP_REVENUE`) + idempotency key จากหน้าเว็บ · `lib/ledger.js` ใช้ร่วมกัน · `grant_coin.mjs` รับชื่อ/จำนวนจาก argument และลงบัญชี 2 ฝั่ง (`ADMIN_GRANTS`) · ทดสอบกับเว็บที่รันอยู่ (พอร์ต 3000) 10/10 ผ่าน ใช้สินค้าทดสอบราคา 1 COIN แล้วลบทิ้ง และคืน 2 COIN ให้ Axnise (ยอดกลับเป็น 600) | **พบ:** มีผู้เล่นชื่อ Axnise 2 บัญชี (บัญชีที่ 2 มาจากตอนทดสอบ offline mode) · ยังไม่ได้ commit |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R12: ลบ `env.txt` แล้ว (ตรวจแล้วว่าไม่มีไฟล์) · merge `fix/p1-inventory-accounts-dungeon-party` เข้า main (3672b8c) | — |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R23 (ปิด instance ว่าง + ลบโฟลเดอร์ค้าง), R26 (ไม่มี template ไม่สร้างโลก), R33 (ตายแล้วเกิดในดันเจี้ยน, /exitdungeon ไม่ถูกดึงกลับ) | — |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ ทดสอบด้วยบอท B1–B8 ผ่านทั้งหมด → R21, R22, R25, R27, 0.4, 6.3 · สลับเซิร์ฟกลับเป็น online แล้ว | R23 (watchdog/ลบโฟลเดอร์ค้าง), R26 (ไม่มี template), R33 (ตายแล้วเกิดในดันเจี้ยน) ยังไม่ได้ยืนยันแยก |
| 2026-11-12 | เครื่องมือทดสอบหลายคน | `tools/party-bots/` (Mineflayer 4.39: บอทรับ party invite เอง, สั่งงานได้จาก terminal หรือแชท `!<bot> ...`) · `switch-online-mode.ps1` สลับ online/offline · ตาราง B1–B8 ในหัวข้อ "ทดสอบ Party / Dungeon แบบหลายคนด้วยบอท" · ทดสอบแล้วว่าสคริปต์รันได้และแจ้ง error ถูกเมื่อเซิร์ฟปิด | ลง `ViaVersion-5.12.2.jar` + `ViaBackwards-5.12.2.jar` (GitHub ทางการ, ขนาดตรง) ใน `server/plugins/` แล้ว เพื่อให้บอท 26.1 เข้าเซิร์ฟ 26.2 ได้ · ยังไม่ได้ลองต่อเซิร์ฟจริง (ต้องสลับ offline + รีสตาร์ท) · ยังไม่ได้ commit |
| 2026-11-12 | R21–R23, R25–R27, R33 (+R32 บางส่วน) | กลุ่มดันเจี้ยน/ปาร์ตี้: รายชื่อ run เป็นสำเนา, join/enter ซ้ำถูกจำกัด, watchdog เวลา+instance ว่าง, ปิดทุก instance ตอน disable, ลบโฟลเดอร์ค้างตอนเปิด, รางวัลบันทึกก่อนประกาศและจ่ายเงินแบบ double-entry, ไม่มี template ไม่สร้างโลก, party lock ใน PartyManager, ตายแล้วเกิดในดันเจี้ยน · บิลด์ jar แล้ว | ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit · ตอนเปิดเซิร์ฟครั้งแรกจะลบโฟลเดอร์ `ind_t0_08290480`, `ind_t0_2d7b22b7` ที่ค้างอยู่ |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R19 (ปลั๊กอินปิดตัวเองเมื่อรหัส DB ผิด), R20 (`/dm reload` กับไฟล์เสียใช้ registry เดิมต่อ), 0.2 | — |
| 2026-11-12 | R19, R20 | ต่อ DB ไม่ได้ → ปลั๊กอินปิดตัวเองพร้อมข้อความชัด (ไม่มีรหัส default ในโค้ด, เตือนถ้ายังเป็น `CHANGE_ME`) · `/dm reload` สลับ registry ทีเดียว ไฟล์เสียก็ใช้ของเดิมต่อ · ตัด `loadFromDatabase` ทิ้ง · บิลด์ jar แล้ว | ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R17, R18 (และ 0.5 Web Login OTP): /weblogin, /balance, /grants ทำงานถูก และคำสั่งไม่ทำให้เซิร์ฟกระตุก | — |
| 2026-11-12 | R17, R18 (+บางส่วนของ R19) | `getAccountId` ไม่ fallback เป็น Minecraft UUID แล้ว (throw แทน) · `/weblogin` ผูก key กับ account_id · `/balance`, `/weblogin` ทำ DB แบบ async · `/shop`, `/profile` ไม่แตะ DB · `upsertPlayer` throw เมื่อพัง · `getConnection()` throw ชัดเจนเมื่อไม่มี pool · `compileJava` ผ่าน | บิลด์ jar ลง `server/plugins` แล้ว (02:33) · ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R16: enchant, ความสึก และชื่อดาบอยู่ครบหลังออก-เข้าเกม | — |
| 2026-11-12 | R16 | InventorySync เก็บ ItemStack ทั้งชิ้นในคอลัมน์ใหม่ `material_stacks.item_data` (BYTEA) · รัน `ALTER TABLE ... ADD COLUMN IF NOT EXISTS item_data` บน Neon แล้ว และใส่ไว้ใน `schema_game.sql` (รันซ้ำได้) · บิลด์ jar ใหม่แล้ว | ถ้าใช้ DB local ต้องรัน `init_databases.mjs` หรือ ALTER เองหนึ่งครั้ง · ยังไม่ได้ทดสอบในเกม · ยังไม่ได้ commit |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R14, R24 ผ่านด้วย `dungeon_test_training` | P0 ที่ยังรอตรวจเหลือ R12 (ลบ `env.txt`) · R30, R34 ยังไม่ได้ทดสอบตรงๆ |
| 2026-11-12 | R14, R24 (เครื่องมือทดสอบ) | เพิ่มดันเจี้ยนทดสอบ `dungeon_test_training` 3 step (ฆ่า Husk 5 ตัว → รอด 10 วิ → ฆ่า Test Boss) ทุกอย่างเกิดที่จุดเกิดของ `ind_t0` (55, 91, -21) · ไฟล์ต้นฉบับ `database/test-content/dungeon_test_training.json` · สคริปต์ใหม่ `web-console/scripts/load_content_file.mjs` โหลดไฟล์ content ลง DB และ registry ทั้ง 2 ที่ · โหลดเรียบร้อยแล้ว | ในเกมต้องสั่ง `/dm reload` ก่อน · มอนอาจเกิดติดกำแพงถ้าตรงจุดเกิดแคบ · `dungeon_ancient_ruins_t0` เดิมยังเล่นไม่จบ (step 2 ไม่มี SUMMON_BOSS) |
| 2026-11-12 | ผลตรวจของผู้ใช้ | ✅ R1–R11, R13 (enter/destroy), R15, 9.1, 9.2 | **R14 ยังทดสอบไม่ได้:** `dungeon_ancient_ruins_t0` เล่นไม่จบ เพราะ step 2 (KILL_BOSS `void_overseer_boss`) ไม่มี `SUMMON_BOSS` ใน `on_start` จึงไม่มีบอสให้ฆ่า และ step 1 ใช้ dummy ที่พิกัด 0,64,0 → ต้องมีดันเจี้ยนทดสอบที่เล่นจบได้ · R12 รอลบ `env.txt` · R24, R30, R34 ยังไม่ได้ทดสอบตรงๆ |
| 2026-11-12 | R13–R15, R24 (+บางส่วนของ R32, R33) | Dungeon: unload world หลังวาร์ป sync แล้ว และลบโฟลเดอร์เฉพาะเมื่อ unload สำเร็จ, step ไปต่อได้ครั้งเดียว, destroy หยุดงานที่รออยู่ · EXea: bone เป็น non-persistent + sweep ตอนโหลด chunk · `compileJava` ผ่านทั้ง 2 ปลั๊กอิน | **P0 ครบทุกข้อแล้ว (รอตรวจ)** · ยังไม่ได้ทดสอบในเกม · ต้องบิลด์ jar ใหม่ทั้ง 2 ตัว · ยังไม่ได้ commit |
| 2026-11-12 | R8–R11, R30 | ระบบ session ใหม่ (`lib/auth.js`: cookie ที่ sign ด้วย HMAC สำหรับ admin และผู้เล่น), proxy + `requireAdmin` ทุก admin route, OTP แบบ atomic + rate limit, ซื้อของด้วย session, ซ่อน error 5xx · เพิ่ม `SESSION_SECRET` ใน `web-console/.env.local` · **ทดสอบแล้ว:** smoke test 30/30 ผ่านบน dev server (พอร์ต 3100) + login/shop/logout ใน browser | ยังไม่ได้ commit · lint error เดิมใน `app/shop/page.jsx:48` (setState ใน effect) ไม่ได้แตะ · หน้าแรก `/` และ `/api/players` ยังโชว์ผู้เล่นทุกคนพร้อมยอดเงินแบบ public (ต้องตัดสินใจว่าจะเปิดหรือปิด) · rate limit อยู่ใน memory ใช้ได้แค่ process เดียว |
| 2026-11-12 | R2–R7, R34 | เขียน `GrantsMenu` ใหม่ (claim ใน DB ก่อน, รับทั้ง grant, ล็อกทุก click, listener ตัวเดียว), `GrantsCommand` เหลือแค่เปิดเมนู, `InventorySync` มีคิวต่อผู้เล่น + gate ตอน load + `shutdown()` รอ save · `compileJava` ผ่าน | ยังไม่ได้ทดสอบในเกม · ต้องบิลด์ jar ใหม่ · **พบเพิ่ม:** ก่อนหน้านี้รางวัลดันเจี้ยน (key `item`/`qty`) ไม่เคยถูกอ่านเลย (ตอนนี้อ่านได้แล้ว) และรางวัล COIN ของดันเจี้ยนถูกกรองทิ้งตั้งแต่ตอนเขียน grant (ยังไม่แก้ → ใส่ไว้กับ R25) |
| 2026-11-12 | R12 | ผู้ใช้ rotate รหัส Neon แล้ว · ใส่รหัสใหม่ใน env/config ที่ใช้งานจริง · config ที่ bundle เข้า jar ใช้ placeholder แทน · web console ต่อ DB ได้ทั้ง 2 pool | jar ใน `server/plugins/` ต้องรีบิลด์ · ผู้ใช้ควรลบ `env.txt` · รหัสเก่าใน git history ตายแล้ว ไม่ต้องลบ history |
| 2026-11-12 | R1 | `/dm` เป็นคำสั่งของ admin เท่านั้น (permission `dimensionalminer.admin`, ค่าเริ่มต้นเป็น op) · `compileJava` ผ่าน | ยังไม่ได้บิลด์ jar ลงเซิร์ฟ (ต้องปิดเซิร์ฟก่อนแล้วรัน `gradlew.bat jar`) · รอผู้ใช้ตรวจ |
| 2026-11-12 | R1–R38 | รีวิวโค้ดเก่าทั้งหมด 4 ส่วน (ปลั๊กอินแกน, Dungeon/Party/Channel, Web console, EXea+schema) ยืนยันข้อ Critical ในโค้ดแล้ว ปรับสถานะ 0.x, 6.4, 9.1 ลงเป็น 🟡 | ยังไม่ได้แก้ข้อไหนเลย · ควรทำ P0 ก่อนงานฟีเจอร์ใหม่ · R12 ต้อง rotate รหัส Neon เอง |
| 2026-11-12 | — | สร้างตาราง handoff นี้จากการอ่านโค้ดจริง, สร้าง `CLAUDE.md`, ย้าย credential DB ออกจาก `web-console/lib/db.js` และ `init_neon.mjs` ไปไว้ใน `.env.local` | ยังไม่ได้ commit · ต้อง rotate รหัสผ่าน Neon (ค่าเก่ายังอยู่ใน git history) · `DimensionalMinerCore/bin/` ควรเพิ่มใน `.gitignore` |
