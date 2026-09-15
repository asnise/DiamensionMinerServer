# Dimensional Miner — Technical Starter

Game backend สำหรับ **Minecraft Java Edition (Paper 26.2)** — พื้นฐาน MMORPG แบบ Gear-Based / Mining / Dungeon /
Instance (ดูแนวคิดเต็มใน GDD: `docs/gdd-concepts.md`)

> Repo นี้คัดเฉพาะ **ปลั๊กอิน Core + Web Console + เอกสาร** เท่านั้น
> ตัวรันไทม์ (Paper server, เนื้อ World) และของกลาง dev ไม่อยู่ใน repo

## โครงสร้าง

```
.
├── dimensional-miner/Plugin Project/DimensionalMinerCore/   # Paper plugin (Java 25)
│   └── src/main/java/com/dimensionalminer/core/dimensionalMinerCore/
│       ├── DimensionalMinerCore.java      # main class, onEnable/onDisable, register commands+events
│       ├── DatabaseManager.java           # HikariCP pool (PostgreSQL)
│       ├── Progress/ProfileCommand.java   # /profile — เปิด dashboard บน web
│       ├── BalanceCommand.java            # /balance — แสดง COIN / ENERGY
│       └── InventorySync.java             # sync inventory เกม <-> DB (projection, diff, batch tx)
├── web/                                    # Web console (Node.js + Express + pg)
│   ├── server.js                           # API + SSR pages (port 3001)
│   ├── public/style.css
│   ├── start.ps1                           # wrapper restart อัตโนมัติ
│   └── seed.sql                             # demo seed (ไม่บังคับ)
└── docs/
    ├── schema.sql                          # ต้นฉบับ DDL (PostgreSQL 16)
    ├── database-schema.md                  # โครงสร้าง DB แบบละเอียด + relations/invariants
    └── gdd-concepts.md                     # concept หลักจาก GDD (design.md)
```

## Requirements

| ส่วน | เวอร์ชัน |
|---|---|
| PostgreSQL | 16 |
| Node.js | 18+ (LTS) |
| Java | 25 (Adoptium) — ใช้รัน Paper 26.2 และ compile plugin |
| Paper | 26.2.build.124-stable (`paper-api` compileOnly) |
| Gradle | 9.4.0 (ใช้ build plugin) |

## Database (PostgreSQL 16)

1. สร้าง database `dimensional_miner`
2. รัน DDL: `psql -U postgres -d dimensional_miner -f docs\schema.sql`
3. ใช้ user/password สำหรับ local dev (ไม่ใช่ค่า production)

> โครงสร้างตารางทั้งหมด 31 ตาราง อธิบายไว้ใน `docs/database-schema.md`

## Web Console

```powershell
cd web
npm install
npm start          # หรือ ./start.ps1 (พอร์ต 3001, auto-restart)
```

เปิด `http://localhost:3001`

| Route | รายละเอียด |
|---|---|
| `/` | ภาพรวม: players, servers, mining, world boss, stats |
| `/player/:id` | โปรไฟล์ผู้เล่น + inventory (จาก DB) |
| `/profile` / `/profile/:id` | ค้นหาผู้เล่น |
| `/api/health` | health check |
| `/api/players`, `/api/player/:id` | JSON ข้อมูลผู้เล่น |
| `POST /api/player/:id/items` | grant item (transactional + idempotent + outbox) |

## Plugin (Paper)

- Build: `gradle build` → ไฟล์ `build/libs/DimensionalMinerCore-1.0-SNAPSHOT.jar`
- ติดตั้ง: วาง jar ใน `server/plugins/` แล้ว restart เซิร์ฟเวอร์
- `plugin.yml` ประกาศ `libraries:` ให้ Paper โหลด HikariCP + PostgreSQL JDBC driver
- Command:
  - `/profile` — เปิดโปรไฟล์ผู้เล่นบน web console (port 3001)
  - `/balance` (`/bal`, `/money`) — แสดง COIN / ENERGY จาก `accounts`
- Inventory sync (ดูหลักการใน `docs/database-schema.md`) —
  บันทึกเฉพาะช่องที่เปลี่ยน ใน 1 transaction (session epoch fencing + idempotency + outbox)
  โหลดจาก DB ตอน join, บันทึกตอน quit + timer ทุก ~15 วินาที

## เอกสาร

- `docs/gdd-concepts.md` — สรุปแนวคิดหลักจาก GDD เวอร์ชัน 0.1 (pillars, dimensions, gear, combat,
  instance/boss, economy, ownership/sync, transactions, security, acceptance tests)
- `docs/database-schema.md` — ข้อกำหนดตาราง 31 ตารางพร้อม relation, constraint และ invariants