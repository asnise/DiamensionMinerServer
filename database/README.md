# Dimensional Miner — Database System Architecture

สถาปัตยกรรมฐานข้อมูลของ **Dimensional Miner** ใช้รูปแบบ **Master Dual-Database Architecture** (แยก 2 ฐานข้อมูลอิสระขาดจากกันใน PostgreSQL 16+) เพื่อรักษาความบริสุทธิ์ของ Game Design Content และประสิทธิภาพสูงสุดของ Server Runtime

---

## 1. การแบ่งแยกฐานข้อมูล (Dual-Database Decoupling)

```
┌────────────────────────────────────────────────────────┐
│                   PostgreSQL Cluster                   │
├────────────────────────────┬───────────────────────────┤
│    dimensional_content     │     dimensional_game      │
│  (Content Master Database) │ (Server Runtime Database) │
├────────────────────────────┼───────────────────────────┤
│ • content_definitions      │ • user_accounts           │
│   - ItemBase (Weapon/Armor)│ • player_identities       │
│   - Recipe                 │ • accounts (Wallets)      │
│   - LootTable              │ • ledger_entries          │
│   - Dungeon (Steps/Goals)  │ • asset_locations         │
│   - PassiveGenerator       │ • items & material_stacks │
│   - UpgradeTree            │ • parties & party_members │
│ • content_releases         │ • instance_slots & runs   │
│                            │ • reward_grants (Mailbox) │
│                            │ • web_login_keys          │
│                            │ • server_shop_products    │
│                            │ • operations & outbox     │
└────────────────────────────┴───────────────────────────┘
```

### ทำไมต้องแยก 2 ฐานข้อมูล?
1. **Zero Player Data Pollution**: ข้อมูลการเล่นของผู้เล่น (เช่น ขุดดิน เก็บหิน ไม้ ในเซิร์ฟเวอร์) จะไม่มีวันไหลไปเจือปนในฐานข้อมูล Master Content
2. **Deterministic Versioning**: Game Designers สามารถสร้าง แก้ไข และทดสอบ Content บน Web Console ได้อย่างอิสระ เมื่อพร้อมแล้วจึงทำการ **Export Content Registry** ออกมาเป็นไฟล์ `content_registry.json` เพื่อให้เซิร์ฟเวอร์ Paper โหลดไปใช้งาน
3. **High Performance & Safety**: เซิร์ฟเวอร์ Paper มีหน้าที่อ่าน `content_registry.json` เป็นแคชในหน่วยความจำ (RAM) และเขียน Runtime State เฉพาะลง `dimensional_game` ผ่าน Connection Pool (HikariCP)

---

## 2. ไฟล์สคริปต์ในโฟลเดอร์ `database/`

| ไฟล์ | หน้าที่หลัก |
|---|---|
| [schema_content.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_content.sql) | DDL สำหรับฐานข้อมูล `dimensional_content` (สร้างตาราง `content_definitions`, `content_releases`) |
| [schema_game.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_game.sql) | DDL สำหรับฐานข้อมูล `dimensional_game` (สร้างโครงสร้างระบบบัญชี, กระเป๋า, เงิน, ปาร์ตี้, ดันเจี้ยน, ร้านค้า และ Web Auth) |
| [content_registry.json](file:///d:/Dimensional_Miner_Technical_Starter/database/content_registry.json) | ไฟล์ Snapshot ล่าสุดที่ Export จาก Web Console สำหรับใช้เป็นต้นแบบและแคชของเซิร์ฟเวอร์ |
| `db-start.bat` / `db-stop.bat` / `db-status.bat` | สคริปต์จัดการ Service ฐานข้อมูลในเครื่อง Local |

---

## 3. โครงสร้างตารางใน `dimensional_content`

### `content_definitions` (Master Registry)
ตารางหลักเก็บนิยามคอนเทนต์ทุกชนิดของเกมแบบ Versioned และ Immutable:
- `definition_id` (TEXT, PK): เช่น `prim_metal`, `thing_sword`, `dungeon_ancient_ruins_t0`
- `version` (INTEGER, PK): เลขเวอร์ชันของนิยามคอนเทนต์ (เริ่มต้นที่ 1)
- `kind` (TEXT): ชนิดของคอนเทนต์
  - `ItemBase`: นิยามไอเทม, อาวุธ, ชุดเกราะ, เครื่องมือ (รองรับ Rarity, Base Stats, Icon Vanilla Model)
  - `Recipe`: สูตรการคราฟต์ที่โต๊ะคราฟต์หรือเตา
  - `LootTable`: ตารางดรอปไอเทมพร้อมค่าน้ำหนัก (Weight) และจำนวนการสุ่ม (Rolls)
  - `Dungeon`: ดันเจี้ยนอินสแตนซ์พร้อมขั้นตอนความคืบหน้า (`steps`), เงื่อนไขเป้าหมาย (`goal`: `KILL_MOBS`, `KILL_BOSS`, `HOLD_ZONE`, `SURVIVE_TIME`, `INTERACT_DUMMY`), และ Trigger Actions (`on_start`, `on_current`, `on_end`)
  - `PassiveGenerator`: เครื่องจักรขุดแร่อัตโนมัติใน Workspace
  - `UpgradeTree`: ผังสกิลและการอัปเกรด
- `payload` (JSONB): รายละเอียดโครงสร้างคอนเทนต์ทั้งหมด
- `checksum` (TEXT): SHA-256 Hash ของ Payload เพื่อตรวจสอบความถูกต้อง

### `content_releases`
เก็บบันทึกประวัติการปล่อยเวอร์ชัน Release และ Manifest การ Export

---

## 4. โครงสร้างตารางใน `dimensional_game`

### ก. Identity & Account Layer
- **`user_accounts`**: บัญชีกลางของระบบ (`account_id` UUID, `username`, `status`)
- **`player_identities`**: ผูกตัวตนจากภายนอกเข้ากับบัญชี เช่น `MINECRAFT` (Minecraft UUID), `DISCORD`, `WEB`
- **`players` (View)**: View สะดวกสำหรับคิวรีชื่อและ Minecraft UUID ย้อนหลัง

### ข. Economy & Double-Entry Ledger
- **`accounts` (Wallets)**: กระเป๋าเงินของผู้เล่น รองรับหลายสกุลเงิน (`COIN`, `ENERGY`, `SERVER_XP`)
- **`ledger_entries`**: รายการธุรกรรมการเงินทุกยอด (Delta บวก/ลบ พร้อมเหตุผล) อิงตาม Double-Entry Accounting ป้องกันปัญหาเงินเสกหรือเงินหาย

### ค. Inventory & Asset Ownership
- **`asset_locations`**: จัดการตำแหน่งที่อยู่ของทรัพย์สิน (`INVENTORY`, `EQUIPPED`, `STASH`, `MARKET_ESCROW`, `GRANT`) แยกความสัมพันธ์แบบ 1:1 กับไอเทม
- **`items`**: ไอเทมชิ้นเดี่ยวที่มีคุณสมบัติเฉพาะ (UUID, Rarity, Advancement, Refine, Durability, Sockets)
- **`material_stacks`**: วัสดุและแร่ที่สามารถซ้อนทับกันได้ (Stackable Material)

### ง. Party & Instance Dungeon System
- **`parties`**: จัดการกลุ่มปาร์ตี้ของผู้เล่น (`party_id`, `leader_id`, `state`)
- **`party_members`**: สมาชิกในปาร์ตี้และสถานะ Ready
- **`instance_slots`**: สล็อตโหนดสำหรับอินสแตนซ์ดันเจี้ยนแบบ Warm World Slots
- **`instance_runs`**: บันทึกรอบการลงดันเจี้ยน (`run_id`, `party_id`, `state`, `key_state`, `outcome`)
- **`run_members`**: บันทึกผู้เล่นในรอบนั้นๆ และสิทธิ์การรับรางวัล
- **`run_rewards`**: บันทึกรางวัลที่ได้รับในดันเจี้ยนเชื่อมกับ `reward_grants`

### จ. Web Console Authentication & Shop
- **`web_login_keys`**: รหัสเข้าสู่ระบบชั่วคราวที่สร้างจากคำสั่ง `/weblogin` ในเกม (อายุ 5 นาที) เพื่อล็อกอินเข้าสู่ Web Console โดยไม่ต้องพิมพ์รหัสผ่าน
- **`server_shop_products`**: รายการแพ็กเกจสินค้าใน Web Shop ที่ซื้อด้วย `COIN` ในเกม
- **`reward_grants` (Mailbox)**: ตู้จดหมายรับของรางวัล เมื่อผู้เล่นซื้อของใน Web Shop หรือเคลียร์ดันเจี้ยน ของรางวัลจะถูกสร้างเป็น Grant สถานะ `AVAILABLE` เพื่อให้ผู้เล่นกดรับในเกมผ่านคำสั่ง `/grants`

### ฉ. Operations & Transactional Outbox
- **`operations`**: บันทึกคำสั่งและ Request Hash เพื่อรับประกันความถูกต้องแบบ Idempotent ป้องกันการกดซ้ำ (Double-action)
- **`outbox_events`**: Transactional Outbox สำหรับส่งต่อ Event ไปยัง Consumer ภายนอก
- **`audit_log`**: บันทึกประวัติการกระทำสำคัญของระบบและแอดมิน

---

## 5. วิธีการ Bootstrap ฐานข้อมูล

รันสคริปต์ Node.js ในโฟลเดอร์ `web-console`:

```powershell
cd d:\Dimensional_Miner_Technical_Starter\web-console
node scripts/init_databases.mjs
```

สคริปต์จะดำเนินการ:
1. สร้างฐานข้อมูล `dimensional_content` และรัน [schema_content.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_content.sql)
2. สร้างฐานข้อมูล `dimensional_game` และรัน [schema_game.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_game.sql)
3. เตรียมข้อมูลผู้เล่นเริ่มต้น (Initial Player Identity & Wallets)
