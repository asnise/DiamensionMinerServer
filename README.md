# Dimensional Miner — Technical Starter

Game backend และระบบสถาปัตยกรรมสำหรับ **Minecraft Java Edition (Paper 26.2)** — พื้นฐาน MMORPG แบบ Gear-Based / Mining / Dynamic Instance Dungeon / Web Console Management

---

## 1. สถาปัตยกรรมระบบ (System Architecture)

ระบบแบ่งฐานข้อมูลออกเป็น **2 Database อิสระขาดจากกัน** ใน PostgreSQL 16+ เพื่อแยก Master Game Design Content ออกจาก Server Runtime อย่างเด็ดขาด:

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

1. **`dimensional_content` (Content Master Database)**:
   - ดูแล Master Definitions ของเกม: Items, Weapons, Armor, Tools, Crafting Recipes, Loot Tables, Dungeons, Passive Generators, Upgrades
   - จัดการและแก้ไขผ่าน Web Console Admin (`/admin/editor`) และ Export ออกมาเป็นไฟล์ [content_registry.json](file:///d:/Dimensional_Miner_Technical_Starter/database/content_registry.json)
   - ปลอดข้อมูลขยะของผู้เล่น (Zero Player Data Pollution)
   - ดูรายละเอียด DDL: [database/schema_content.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_content.sql)

2. **`dimensional_game` (Server Runtime Database)**:
   - จัดการ Runtime State ของเซิร์ฟเวอร์:
     - **Identity Layer**: `user_accounts` (Core Identity) เชื่อมโยงกับ `player_identities` (`MINECRAFT`, `DISCORD`, `WEB`) แทนการใช้ Minecraft UUID เป็น ID หลัก
     - **Economy**: Wallets (`accounts`), Double-entry Ledger (`ledger_entries`)
     - **Inventory & Assets**: `asset_locations`, `material_stacks`, `items` (Decoupled จาก Content definitions)
     - **Party & Instance Dungeon**: `parties`, `party_members`, `instance_slots`, `instance_runs`, `run_members`, `run_rewards`
     - **Authentication & Web Shop**: `web_login_keys` (OTP Login), `server_shop_products`, `reward_grants` (Mailbox)
   - ดูรายละเอียด DDL: [database/schema_game.sql](file:///d:/Dimensional_Miner_Technical_Starter/database/schema_game.sql)

---

## 2. โครงสร้างโฟลเดอร์ในโปรเจกต์

```
.
├── DimensionalMinerCore/             # Paper plugin (Java 25 + Gradle 9.x)
│   └── src/main/java/com/dimensionalminer/core/dimensionalMinerCore/
│       ├── DimensionalMinerCore.java # Plugin main class
│       ├── DatabaseManager.java      # HikariCP pool (เชื่อมต่อ dimensional_game)
│       ├── ContentRegistryManager.java# โหลด/จัดการ Content จาก content_registry.json
│       ├── InventorySync.java        # ซิงค์ Inventory ระหว่างเกมและ DB แบบ Batch
│       ├── party/                    # ระบบ Party (PartyManager, PartyCommand)
│       ├── dungeon/                  # ระบบ Dungeon & Instance Engine
│       │   ├── runtime/              # DungeonInstanceManager, DungeonInstanceSession
│       │   ├── DungeonEventListener.java # ตรวจจับการตีม็อบ/โต้ตอบสวิตช์ในดันเจี้ยน
│       │   ├── DungeonCommand.java   # /dungeon (enter, create, join, list, destroy)
│       │   └── ExitDungeonCommand.java # /exitdungeon วาร์ปกลับ Hub
│       ├── CustomItemCommand.java    # /dm (list, give, reload)
│       ├── BalanceCommand.java       # /balance
│       ├── ProfileCommand.java       # /profile
│       └── WebLoginCommand.java      # /weblogin
├── web-console/                      # Modern Next.js 16 Web Console & Admin Editor
│   ├── app/                          # App Router (Shop, Profile, Admin Editor, Login)
│   ├── components/                   # UI Components, DungeonEditor, Sub-editors
│   ├── lib/db.js                     # Dual Pool (gamePool & contentPool)
│   └── lib/content.js                # จัดการ Content Definitions และ Export
├── database/                         # DDL Schemas & Master Snapshots
│   ├── schema_content.sql            # Schema สำหรับ dimensional_content
│   ├── schema_game.sql               # Schema สำหรับ dimensional_game
│   ├── content_registry.json         # Master Content Snapshot สำหรับรันเซิร์ฟเวอร์
│   └── README.md                     # เอกสารสถาปัตยกรรม Database ฉบับสมบูรณ์
├── server/                           # Paper Server Runtime (Paper 26.2)
│   ├── plugins/                      # โฟลเดอร์ปลั๊กอิน (DimensionalMinerCore.jar)
│   ├── overworld_t0/                 # เวิลด์ Hub สาธารณะ
│   └── ind_t0/                       # Template เวิลด์ต้นแบบสำหรับคัดลอกเป็น Instance
└── docs/                             # เอกสารระบบ (Database Reference, GDD Concepts)
```

---

## 3. ความต้องการของระบบ (Prerequisites)

| ส่วนประกอบ | เวอร์ชัน | หมายเหตุ |
|---|---|---|
| PostgreSQL | 16+ | ใช้งานแบบ 2 Database (`dimensional_content`, `dimensional_game`) |
| Node.js | 18+ (LTS) | สำหรับ Web Console (Next.js 16) |
| Java | 25 (Adoptium) | สำหรับ Paper 26.2 และปลั๊กอิน |
| Paper | 26.2 | รันเซิร์ฟเวอร์ Minecraft |
| PowerShell | 5.1+ / 7+ | แนะนำสำหรับรันสคริปต์บน Windows |

---

## 4. ขั้นตอนการติดตั้งและเริ่มต้นระบบ

### 4.1. การตั้งค่าฐานข้อมูล (Database Bootstrap)
รันสคริปต์ Node.js ผ่าน PowerShell เพื่อสร้างฐานข้อมูลทั้งสองก้อนและใส่ข้อมูลเริ่มต้น:

```powershell
cd web-console
node scripts/init_databases.mjs
```

### 4.2. รัน Web Console
```powershell
cd web-console
npm install
npm run dev        # รันที่ http://localhost:3000
```
- `/profile`: ดูโปรไฟล์ เงิน และช่องเก็บไอเทมของผู้เล่น
- `/shop`: ร้านค้าออนไลน์ในเกม (ซื้อแพ็กเกจแล้วของส่งเข้า Mailbox ผ่าน `reward_grants`)
- `/admin/editor`: Visual Content Editor สำหรับจัดการ:
  - **Item & Gear**: อาวุธ ชุดเกราะ ค่าสเตตัส และ Rarity
  - **Recipe & Loot Table**: สูตรคราฟต์และตารางดรอปไอเทม
  - **Dungeon Progression**: ตั้งค่าขั้นตอน (Steps), เป้าหมาย (Goals), พิกัด ($X, Y, Z, r$), ม็อบสปอว์น, และรางวัล
  - **Export Content Registry**: กดปุ่มส่งออกข้อมูลทั้งหมดไปยัง `content_registry.json`

### 4.3. คอมไพล์ปลั๊กอิน Minecraft (Paper)
```powershell
cd DimensionalMinerCore
.\gradlew.bat jar
```
*(ไฟล์ `.jar` จะถูกบิลด์และวางใน [server/plugins/DimensionalMinerCore.jar](file:///d:/Dimensional_Miner_Technical_Starter/server/plugins/DimensionalMinerCore.jar) อัตโนมัติ)*

---

## 5. คู่มือคำสั่งในเกม (In-Game Commands Reference)

### ระบบ Dungeon & Instance
| คำสั่ง | คำอธิบาย |
|---|---|
| `/dungeon enter <dungeon_id>` | สร้าง Instance ดันเจี้ยนและวาร์ปปาร์ตี้เข้าสู่ดันเจี้ยนทันที (หากเล่นคนเดียว ระบบจะสร้าง Solo Party ให้โดยอัตโนมัติ) |
| `/dungeon create <dungeon_id>` | สร้าง Instance ดันเจี้ยนและรับ `sessionId` เพื่อให้ผู้เล่นอื่นมาร่วมจอย |
| `/dungeon join <sessionId>` | จอยและวาร์ปเข้าสู่ Instance ดันเจี้ยนที่มีอยู่แล้ว |
| `/dungeon list` | ดูรายชื่อ Dungeon Templates และ Active Sessions ที่กำลังเปิดอยู่ |
| `/dungeon destroy <sessionId>` | [Admin/OP] ทำลาย Instance ดันเจี้ยนและวาร์ปผู้เล่นกลับสู่ Hub |
| `/exitdungeon` | ออกจากดันเจี้ยนและวาร์ปกลับสู่ Hub สาธารณะ (`overworld_t0`) |

### ระบบ Party
| คำสั่ง | คำอธิบาย |
|---|---|
| `/party create` | สร้างปาร์ตี้ใหม่ |
| `/party invite <player>` | เชิญผู้เล่นเข้าร่วมปาร์ตี้ |
| `/party accept` | ตอบรับคำเชิญเข้าปาร์ตี้ |
| `/party leave` | ออกจากปาร์ตี้ (ถูกล็อคห้ามออกขณะอยู่ในดันเจี้ยน) |
| `/party disband` | ยุบปาร์ตี้ (เฉพาะหัวหน้าปาร์ตี้) |
| `/party list` | ดูรายชื่อสมาชิกในปาร์ตี้ |

### ระบบทั่วไป & Web Console
| คำสั่ง | คำอธิบาย |
|---|---|
| `/dm list` | ดูรายชื่อไอเทม Custom ทั้งหมดที่โหลดจาก Content Registry |
| `/dm give <player> <item_id> [amount]` | เสกไอเทม Custom ให้กับผู้เล่น |
| `/dm reload` | โหลด Content Definitions ใหม่จากไฟล์ `content_registry.json` |
| `/balance` | ตรวจสอบยอดเงิน `COIN` และ `ENERGY` |
| `/profile` | ดูข้อมูลโปรไฟล์ |
| `/grants` | ตรวจสอบและรับของรางวัลจาก Mailbox (ของจาก Shop หรือ ดันเจี้ยน) |
| `/weblogin` | สร้างรหัส OTP 6 ตัวอักษร สำหรับล็อกอินเข้าใช้งาน Web Console |