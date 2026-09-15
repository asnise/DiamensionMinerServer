# Dimensional Miner — Game Design Concepts (จาก GDD ฉบับ 0.1)

> บทสรุปแนวคิดหลักจาก `design.md` (GDD • Technical Architecture • Risk Review)
> เพื่อให้ repo นี้เป็นเอกสารอ้างอิงเดียวกับทีม plugin/web/dev
> เครื่องหมาย [เดิม] = ข้อกำหนดจากต้นฉบับ, [เสนอ] = ข้อเสนอของทีมออกแบบ, [ต้องยืนยัน] = ต้องคุยกับเจ้าของเกมก่อน

## 1. วิสัยทัศน์และ Core Loop

**4 เสาหลัก**
1. **ขุดอย่างมีเป้าหมาย** — ทุกมิติมีวัตถุดิบ/แผนที่/บอสเฉพาะ การขุดเลือกอัปเกรดขั้นต่อไปได้
2. **ความก้าวหน้าจากอุปกรณ์** — Server Level เปิดสิทธิ์ แต่ไม่บวกดาเมจตรง ๆ ของต่ำยังต้องยาก
3. **ผลงานไม่สูญหายเงียบ ๆ** — กระเป๋าเต็มไม่ทำของตก, ย้าย channel ไม่มีของซ้ำสองชุด, ทุกธุรกรรมตรวจย้อนหลังได้
4. **นักขุดและนักคราฟต์พึ่งพากัน** — ต่อสู้ซื้อยา/วัสดุจาก Workspace; คราฟต์รับแร่/ชิ้นส่วนจากเหมืองและบอส

**Core loop:** เตรียมตัวที่ Hub → ขุด/ต่อสู้ → แปรรูป/สร้าง/อัปเกรด → ทำเงื่อนไข/กุญแจ → บอสส่วนตัว → ปลดล็อก/ขาย → มิติยากขึ้น

**ไม่อยู่ใน baseline:** คลาสเต็มรูปแบบ, PvP, guild war, โลกขุดไม่จำกัด, cross-region active-active, auto-Folia

## 2. มิติ / Channel / การปลดล็อก

- หนึ่ง Tier = หนึ่ง World (ตัวอย่าง [เสนอ]: Tier1 "เกาะเครื่องจักร" สอนขุด, Tier2 "หมู่เกาะผลึก" เฟืass Fragment/Engrave, Tier3 "วิหารรอยแยก" บอส) — ชื่อนี้ตัวอย่างเท่านั้น
- **ปลดล็อก Tier = Server Level ถึงเกณฑ์ AND เควสหลักผ่าน AND มีหลักฐานชนะบอส Tier ก่อน**
- **หนึ่ง Channel = Paper process แยกจริง** มีสำเนา World ของ Tier; ผู้เล่นต่าง Channel ไม่เห็น/ไม่โจมตีกัน
  Channel ไม่ใช่ thread ย่อยของ Main World Server
- Hub ทุก Channel ใช้ตลาด/เมล/ stash กลางคนละ Channel; World Boss มี encounter ต่อ Channel แต่โควตารางวัลระดับบัญชีข้าม Channel
- [เสนอ] วาร์ป cooldown 30s + เตรียม 5s, ยกเลิกเมื่อโดนดาเมจ/ขยับ; ตรวจสิทธิ์ซ้ำฝั่ง backend เสมอ
- Safe Zone ปิด PvP/damage/แก้บล็อก; เขตขุดห้ามวาง block/น้ำ/ระเบิด/ลูกสูบที่เปลี่ยน node

## 3. แร่เกิดใหม่และระบบ Node

- Node: `node_id, dimension_id, channel_id, ตำแหน่ง, ชนิดแร่, generation, state, respawn_at`
- สถานะ: `READY → RESERVED(ในหน่วยความจำ) → DEPLETED → READY`; **generation +1 ทุก respawn**
- เริ่มขุด: ตรวจระยะ/เครื่องมือ/สิทธิ์ Tier/state บน game thread → สำเร็จ → **จอง node atomic ต่อ Channel** → มีผู้ชนะหนึ่งคน → ยกเลิก vanilla drop/EXP
- รางวัลสร้างด้วย grant key `mine/{channel}/{node}/{generation}` + ปิด node ใน **ธุรกรรมเดียว**; DB ล่ม → ไม่แจกของจาก RAM
- กระเป๋าเต็ม → overflow grant (ไม่ spawn Item entity); บล็อกแสดงเป็นหินระหว่างรอ respawn
- ใช้ time queue ไม่งสร้าง scheduler ต่อบล็อก; มี budget ต่อ tick; save generation ก่อนอนุญาตขุด (กัน crash ย้อน generation)
- Anti-abuse: ตรวจอัตรา/ระยะเอื้อม/ลำดับตำแหน่ง/เครื่องมือจริง; flag review ไม่แบนอัตโนมัติ

## 4. ระบบอุปกรณ์และสูตรสเตตัส

- ทุกชิ้น: UUID ถาวร, definition(+version), owner, item_version, Rarity, Advancement (0–100), Refine (0–10),
  Engrave (≤3), Socket, Unique Effect, binding, durability
- **สูตร (GearMath):**
  - `Core_s = (Base_s + Advancement × Growth_s) × (1 + RefineBonus[Refine])`
  - `Final_s = clamp((Core_s + FlatEngrave_s + FlatSocket_s + FlatSet_s) × (1 + PercentEngrave_s + PercentSocket_s + PercentSet_s), Min_s, Cap_s)`
  - Refine มีผลเฉพาะ Base/Advancement; กลุ่ม % รวมกันก่อนคูณ (ไม่คูณทวีคูณ); fixed-point; ปัดที่แสดงผล/ดาเมจสุดท้าย
  - Golden test: Base ATK 100, Growth 2, Adv 50, Refine 20%, Flat 15, Percent 10% → **ATK 280.5**
- เกราะ: `DamageTaken = Raw × K/(K+Defense)` (K > 0) — ไม่คิด armor vanilla ซ้ำ
- Unique Effect: trigger + internal CD + proc chance + stacking group; proc ห้าม trigger ตัวเอง; lifesteal จากดาเมจจริงมีเพดาน
- Set effect นับช่องที่สวมจริงเท่านั้น; vanilla enchant ใช้ whitelist (Disabled Sharpness/Protection หรือแปลงเข้าสาย custom เดียว)

## 5. Crafting / Refine / Engrave / Inheritance

- Crafting UI โชว์วัตถุดิบ/Energy/เวลา/ผลลัพธ์/กฎ binding ก่อนยืนยัน; recipe_version; **quote หมดอายุ + ตรวจใหม่ใน transaction ตอนกดจริง**
- Advancement สำเร็จแน่นอน (จ่ายวัสดุ+Energy); Refine ใช้ตารางโอกาส + pity เปิดเผย, พังแล้วไม่ลด level/ไม่แตกชิ้น (baseline)
- Engrave ≤3 ช่อง, ห้าม duplicate exclusive affix; ถอด fragment ด้วย consumable แล้วคืน (ไม่ทำลาย)
- **Inheritance: เลือก 1 ใน ADVANCEMENT/REFINE/ENGRAVE ต่อครั้ง;** ต้นทางกับปลายทางเจ้าของเดียวกัน, ไม่ equip/ไม่อยู่ตลาด/ไม่ใน job, หมวดอุปกรณ์เข้ากัน
  [เสนอ]**ทำลายต้นทางทั้งหมด** โอนเฉพาะประเภทที่เลือกไปแทนที่ (ไม่บวกสะสม); ยืนยัน 3 ข้อกับเจ้าของเกม — การทำลายต้นทาง, การแทนที่, ความเข้ากันได้

## 6. Combat & การตาย

- **หนึ่ง damage pipeline เดียว** (CombatService): hit rule → base/skill → crit → defense/resist → shields → HP → contribution → proc; บันทึก owner ของ projectile/pet/summon
- อัตราโจมตีจากเซิร์ฟเวอร์; กัน abuse: ยิงข้าม Safe Zone, สลับอาวุธกลางกระสุน, proc chain
- **ตายในเหมือง: ไม่เสียของ/เงิน/energy** — เสีย durability แล้วกลับ Hub; durability 0 = ปิดโบนัสชิ้น ไม่ลบ item (ซ่อมเป็น resource sink)
- Instance: ตาย = downed/spectator; run จบเมื่อ Solo ตาย หรือ party wipe; [เสนอ] reconnect 120s ถ้า run ยังอยู่, ไม่ pause boss; disconnected ที่ eligible ยังรับ grant หลังชนะ

## 7. Instance Dungeon & กุญแจ

- run = พื้นที่แยก ไม่จำเป็นต้อง process ใหม่เสมอ → ใช้ **warm world slots จาก template immutable**
- State: `REQUESTED → RESERVED → LOADING → READY → ACTIVE → (SUCCEEDED|FAILED) → RETURNING → CLEANING → CLOSED`;
  ก่อน ACTIVE abort ได้และคืน reservation; CLEANING ล้มเหลว → QUARANTINED
- Party 1–4; หัวหน้าจ่ายกุญแจ 1 ดอกต่อ run; ทุกคนผ่าน unlock + ยืนยัน ready; **roster freeze หลังเริ่ม** (กันรับรางวัลหลายชุด)
- กุญแจ: reserve ตอนขอ → cut ตอน entry policy + ACTIVE ใน durable transaction; แพ้หลัง ACTIVE ไม่คืน;
  server incident → compensating grant ครั้งเดียวตาม policy (ไม่คืนทุก disconnect)
- **Commit outcome+grant (unique(run_id, player_id, reward_slot)) ก่อนส่ง animation** หลังชนะ
- ด้วย I/O โลก: template copy async; create/load/unload บน scheduler ที่ถูก; cleanup ไม่ลบ world ที่ยัง loaded; allocator ดู available slots/heap/MSPT/heartbeat

## 8. World Boss & ความยุติธรรม

- [เดิม] Top 3 Damage + Last Shot ได้ rare • [เสนอ] ทั้งหมดเป็น **เซตผู้รับ unique** (คนเดียวนึงที่ไม่ซ้ำ) —
  ติดทั้งสองได้ rare หนึ่งชุด; ผู้มีสิทธิ์ทุกคนได้ standard reward
- Contribution นับ damage ต่อ HP จริง (ไม่รวม overkill/self-heal/invulnerable), รวม pet damage; eligibility
  = อยู่ใน encounter + damage ≥ 1% HP หรือ support ≥ เกณฑ์; active time ≥ 30s (กัน AFK)
- **Server EXP:** `E_i = E_pool × (0.30/N + 0.70 × d_i/Σd)`; largest remainder ปัดให้ยอดรวม = pool เสมอ
- Top 3 ตัด effective damage; เสมอกัน → เวลาแรกถึง → UUID (deterministic); Last Shot ต้องผ่าน eligibility
- กัน channel hopping: claim scope `worldboss/{boss}/{window}/{player}` ข้าม Channel (เป็นโควตารางวัล ไม่ห้ามช่วยเพื่อน)

## 9. Workspace / แปรรูป / เศรษฐกิจ

- Workspace = logical instance ถาวร, โหลดโลกเฉพาะเมื่อใช้งาน; MVP เจ้าของถาวรหลังเควส (ไม่ใช้ค่าเช่า)
- ACL: VISIT / USE_STATION / STORAGE; เจ้าของเท่านั้นเริ่มต้น; visitor ห้ามเปิด storage
- **Processing job เดินต่อเมื่อ offline** — เก็บ recipe_version, started_at, finishes_at; คำนวณจาก timestamp ไม่จำลองเตา
- รับวัสดุเข้า escrow + สร้าง job ใน transaction เดียว; เมื่อครบเวลา worker สร้าง durable output grant ครั้งเดียว
  (ไม่มี vanilla furnace เป็นกระเป๋าจริง)
- **Coin source:** quest, payout NPC จำกัดโควตา, boss participation • **Coin sink:** AH tax, repair, craft/upgrade fee
  **Material source:** mine/boss • **sink:** recipes/refine/potions/keys
- **AH:** fixed-price + ภาษี 5% floor ในรุ่นแรก (bid = extension) — Buy ใน transaction เดียว:
  ตรวจ ACTIVE → หักCoin ผู้ซื้อ → credit ผู้ขาย+treasury → escrow → buyer grant → SOLD
- **ไม่เปิด P2P trade / ไม่ทิ้ง custom item ใน MVP** (กันเส้นทาง duping)
- ติดตาม: ราคากลาง, ปริมาณเข้าใหม่/ใช้แล้ว, coins ต่อ active account, ความเข้มข้นความมั่งคั่ง, bot farming

## 10. Mailbox / Overflow / สองเลเวล

- Mailbox "ไม่จำกัด" = **ไม่มีเพดานจดหมายใน UI และ reward ที่ commit แล้วไม่ expiry** (ข้อจำกัดทางกายภาพจัดการโดยหน้า 50 รายการ, cursor pagination, lazy load, coalesce แร่, archive)
- claim = ย้ายสิทธิ์ logical inventory + mark CLAIMED ใน transaction เดียว; กระเป๋าไม่พอ → **ปฏิเสธทั้งชุด (MVP ไม่มี partial)**
- พื้นที่ DB ใกล้เต็ม → ลด/หยุดสร้าง reward ใหม่ก่อน + แจ้ง maintenance (ห้ามรับแล้วทิ้งเงียบ)
- **Vanilla EXP bar = Energy points** (เก็บเป็น integer points ไม่เก็บ level เพราะแปลงไม่เชิงเส้น); ปิด orb farming/enchant/anvil/grindstone หรือ route ผ่าน EnergyService
- **Server EXP/Core แยก ledger จาก Energy** — Server Level เปิด Tier/gear/workspace แต่ไม่บวก damage; ใช้ milestone+quest ยืนยัน unlock

## 11. UX / Content Data / Admin

- เมนูหลัก: Dimension/Channel, Character & Gear, Craft/Refine/Engrave/Inherit, Party&Dungeon, Workspace, Mailbox, Market
- ทุก action มีค่าใช้จ่าย ต้องมี preview + result code; server คำนวณ input/output ใหม่
  (ไม่เชื่อชื่อไอเทม/slot จาก client; ตรวจ click/drag/shift/hotbar/double-click/offhand)
- **Content registry:** definition_id + version สำหรับ item/recipe/dimension/boss/loot table/affix/upgrade;
  validation รวม: reference หาย, น้ำหนักติดลบ, recipe cycle, socket เกิน
- Publish immutable version; run/job/quote **pin version**; เปลี่ยน loot table กลาง fight ไม่เปลี่ยนผลย้อนหลัง
- GM: inspect UUID, trace operation/grant, quarantine, pause economy, drain node, compensate incident (RBAC + reason)
  **GM ห้ามแก้ SQL ตรง** — คืนของต้องสร้าง compensating operation

## 12. Topology และโมดูล

```
Clients → Velocity Proxy → Main Channel A/B | Instance Node A/B
แต่ละ backend → Gameplay Core (private authenticated API) → PostgreSQL (+ transactional outbox)
Redis = cache/presence/notification เท่านั้น (ไม่ใช่ที่เก็บสินทรัพย์)
```

- Velocity: login/routing/lifecycle; ไม่ clone world, ไม่ sync inventory, ไม่เป็น load balancer อัตโนมัติ
- Main Channel = Paper + Hub + mining + World Boss + Workspace slots; Instance Node = Paper + warmed boss worlds
- **Modular Monolith ก่อน** (Core เดียว) — อย่าแยก microservices ตั้งแต่แรก; scale อ่าน/worker ก่อนแยกโดเมน
- Paper plugin = adapter (block/entity/UI บน game thread); Core = state/transaction/permission/ledger;
  plugin messaging ไม่ใช่ transaction bus โดยไม่ตรวจ source
- โมดูลที่วางแผน: `:domain`, `:core-api`, `:core-persistence`, `:player-session`, `:inventory-economy`,
  `:gear-crafting`, `:progression-content`, `:world-mining`, `:combat-boss`, `:instance-workspace`,
  `:paper-adapter`, `:velocity-router`, `:workers-ops`

## 13. ทรัพย์สิน / ย้ายเซิร์ฟเวอร์ / กันของซ้ำ

- **DB เป็น authoritative**; ItemStack ในเกม = projection สร้างใหม่ได้; PDC เก็บ uuid/version เป็น pointer เท่านั้น
- ต้นทุน: ต้อง intercept ทุกเส้นทางของ custom asset (move/use/drop/death/container/craft/hopper) + reconcile
- **Transfer handoff:** (1) source freeze inventory (2) Core lock session + finalize operations
  (3) transfer record + one-time token/hash + expiry (4) Proxy ส่ง (5) destination verify token + **CAS เพิ่ม epoch**
  (6) hydrate snapshot (7) ack ACTIVE, unfreeze — source เก่าถูกปฏิเสธทันทีหลัง handoff
- Lease ใช้เวลา DB; **ห้ามพึ่ง Redis lock อย่างเดียว**; destination เข้าไม่ได้ → resume source ก่อน consume token
- **ไม่มี atomic transaction ข้าม Postgres กับ Bukkit** → ใช้ durable intent + idempotent projection + reconciliation;
  callback กลับ game thread ต้องตรวจ epoch/version อีกครั้ง ถ้า stale ให้โหลด snapshot ใหม่

## 14. ธุรกรรมและ Recovery

- Pattern: auth server+session → hash request → begin tx → lock เรียง UUID → lookup/insert operation →
  validate → mutate → outbox → response → commit → project
- **Read Committed + SELECT FOR UPDATE**; unique constraints เส้นกันซ้ำ; Serializable ต้อง retry ทั้ง tx ด้วย key เดิม
- Lottery/Refine: สุ่มฝั่ง server + persist ภายใต้ operation เดียว; ห้าม retry สุ่มใหม่
- Outbox: worker ใช้ `FOR UPDATE SKIP LOCKED`; publish เกิดซ้ำ → consumer dedup (processed_events)
- Mail claim / Market buy / Inheritance / Instance cleanup เป็น transaction template ใน docs/database-schema.md
- Recovery worker: สแกน expired transfer, job ค้าง, orphan run, outbox retry, escrow ไม่สัมพันธ์; quarantine ก่อนแก้

## 15. API / Events / Security

- OpenAPI 3.1: `openapi.json` — internal HTTP contract (ไม่ใช่ public API เกม)
- Mutation ทุกคำสั่ง: `Idempotency-Key`, `X-Node-Id`, `X-Session-Epoch`; canonical request hash
- Event envelope: event_id, type, schema_version, aggregate_id, aggregate_version, operation_id, occurred_at
  — at-least-once, consumer dedup, ใช้ aggregate_version ถ้ากระโดดให้ reload snapshot
- Security: online-auth + modern forwarding + secret; backend รับเฉพาะ private network; firewall แทนแค่ปิด online-mode
- Paper: ทุกงาน World/Player/Entity/Inventory บน scheduler context; SQL/HTTP async; **ห้าม .join() บน tick**
- Budget: 50ms/tick @ 20 TPS; เป้า p95 MSPT < 40ms (acceptance candidate)

## 16. Acceptance Tests & Release Gate

| ข้อ | เรื่อง | สาระสำคัญ |
|---|---|---|
| T01 | Gear math | ATK ตัวอย่าง = 280.5; property test 0–100 / 0–10, no overflow |
| T02 | Inheritance | key เดิม ×100 → ผลเดียว, ต้นทางทำลายครั้งเดียว, เลือก 1 ประเภท |
| T03 | Mail | สอง backend claim grant เดียว → สำเร็จ 1; crash ก่อน/หลัง commit; เต็มไม่ mark CLAIMED |
| T04 | Market | 50 buyers ชน 1 listing → winner 1; buy แข่ง cancel/expiry |
| T05 | Transfer | inject disconnect ทุกขั้น → epoch writer เดียว + snapshot เดียว |
| T06 | Mining | ขุดพร้อมกัน → grant เดียว/generation; restart ไม่แจก generation เดิมซ้ำ, ไม่มี vanilla drop |
| T07 | Instance | burst/partial join/wipe/reconnect/node kill/cleanup fail — ไม่ reward ซ้ำ, ไม่คืน key ซ้ำ |
| T08 | World Boss | overlap policy, deterministic tie, quota ข้าม channel, EXP sum = pool |
| T09 | Workspace | offline job จบครั้งเดียว, visitor อ่าน stash ไม่ได้, version pin |
| T10 | Security | replay/forged UUID/stale epoch/unauthorized API/direct backend login ถูกปฏิเสธ |
| T11 | Performance | idle → incremental → target CCU → burst → soak (8h) |
| T12 | Recovery | restore จุดเดียว, invalidate sessions/transfers, reconcile grants/runs |

**Release gate:** P0 เปิดอยู่ไม่ผ่าน; asset sum change = 0 ใน tests; restore drill ผ่าน; content validation ผ่าน; metrics/kill switch/compensation ทำงาน; ประกาศ Beta wipe policy

## 17. ความเสี่ยงสำคัญ (Risk Register)

| Priority | ความเสี่ยง | มาตรการหลัก |
|---|---|---|
| P0 | ของซ้ำระหว่าง transfer | DB authority + epoch fencing + hydrate |
| P0 | claim/buy/inherit ซ้ำ | idempotency + row lock + domain unique key |
| P0 | เชื่อ item NBT/plugin message ปลอม | lookup authoritative item/session + firewall |
| P1 | world load ทำ tick ค้าง | warm pool, queue, budget, จำกัด slots |
| P1 | mailbox โตไม่จบ | paginate/coalesce/archive + stop emission ฉุกเฉิน |
| P1 | DPS ผูกขาด / channel farming | eligibility + claim scope + participation |
| P1 | เงินเฟ้อ | source budget / sinks / quotas / market metrics |
| P1 | proc/vanilla บวกซ้ำ | single damage authority + whitelist + golden tests |
| P1 | DB outage | fail closed + durable intent + recovery drill |
| P2 | workspace กิน RAM | lazy load + timestamp job + eviction |

**ข้อเสนอพิสูจน์เทคนิคก่อนทำคอนเทนต์เยอะ:** vertical slice — ขุด node → grant → สร้าง gear → ย้าย channel →
claim mail → ซื้อขาย → บอสหนึ่ง run พร้อม fault injection ทุกจุดที่ commit

## 18. การตัดสินใจค้างที่ต้องอนุมัติก่อน coding เต็ม

ทำลายต้นทาง inheritance หรือไม่ • Refine fail policy • binding policy • socket extraction •
key payer • party size • reconnect grace • Top3+LastShot ซ้อนกัน • AH fixed-price • Workspace ownership •
mailbox no-expiry • ข้อมูลเพิ่มที่ต้องขอ: ข้อความต้นฉบับส่วนหลัง 3.1, target CCU, hosting budget, resource pack, plugin ecosystem