# Dimensional Miner - Development Tasks

ระบบที่ต้องพัฒนาต่อ อ้างอิงจาก GDD: `docs/gdd-concepts.md` (สามารถเช็ครายการได้ที่นี่)

## 1. ระบบมิติและ Channel (Dimensions & Channels)
- [ ] พัฒนาระบบโหลด World แยกตาม Tier
- [ ] ระบบเงื่อนไขการปลดล็อก Tier (Server Level, เควส, หลักฐานชนะบอส)
- [ ] ระบบ Channel แยก instance ของ World (การจัดการ Channel)
- [ ] ระบบวาร์ป (Cooldown 30s, ยกเลิกเมื่อโดนดาเมจ/ขยับ)
- [ ] Safe Zone (ปิด PvP, ห้ามเปลี่ยนแปลง Block ภายในเขต)

## 2. ระบบ Node เหมืองแร่ (Mining Nodes)
- [ ] โครงสร้างข้อมูล Node (`node_id`, `dimension_id`, `channel_id`, สเตตัส, generation)
- [ ] ระบบจอง Node แบบ Atomic (ป้องกันการขุดชนกัน)
- [ ] ควบคุมการแจกรางวัลและจัดการ Generation เพื่อป้องกันช่องโหว่
- [ ] การเปลี่ยนบล็อกหินระหว่างรอ Respawn (Visual)
- [ ] Anti-abuse (ตรวจอัตราการขุด, ระยะเอื้อม, เครื่องมือ)

## 3. ระบบอุปกรณ์และสเตตัส (Gear & Stats)
- [ ] โครงสร้างไอเทม (UUID, Rarity, Advancement, Refine, Engrave, Socket)
- [ ] ระบบคํานวณสเตตัส (GearMath: `Core_s`, `Final_s`)
- [ ] ระบบป้องกัน/เกราะ และการคำนวณดาเมจที่ได้รับ
- [ ] Unique Effect (Cooldown, Proc Chance, ห้าม Trigger ตัวเอง)
- [ ] Set effect (เช็คเฉพาะช่องที่สวมใส่จริง)

## 4. ระบบ Crafting & ตีบวก (Crafting, Refine, Engrave, Inherit)
- [ ] UI โชว์ผลลัพธ์และกฎผูกมัดก่อนยืนยัน
- [ ] ระบบ Advancement (อัปเกรดชิ้นส่วน 0-100)
- [ ] ระบบ Refine (สุ่มตีบวก 0-10, มีโอกาสล้มเหลว)
- [ ] ระบบ Engrave (ใส่ Fragment สูงสุด 3 ช่อง)
- [ ] ระบบ Inheritance (โอนค่า Advancement/Refine/Engrave ทับชิ้นใหม่และทำลายชิ้นเก่า)

## 5. ระบบต่อสู้และความตาย (Combat Pipeline & Death)
- [ ] ท่อคำนวณดาเมจแบบศูนย์กลาง (CombatService)
- [ ] Anti-abuse ยิงข้ามเขตปลอดภัย หรือสลับอาวุธ
- [ ] ระบบตายในเหมือง (เสีย Durability, ไม่ดรอปไอเทม, ส่งกลับ Hub)
- [ ] ระบบจัดการ Durability ของอุปกรณ์

## 6. ระบบดันเจี้ยนส่วนตัว (Instance Dungeon & Keys)
- [ ] วงจรชีวิตของ Instance (Requested -> Reserved -> ... -> Closed)
- [ ] ระบบกุญแจและปาร์ตี้ (ตัดกุญแจเมื่อเริ่ม, แช่แข็งปาร์ตี้)
- [ ] ระบบแจกรางวัลลงฐานข้อมูลก่อนเล่น Animation ชนะ
- [ ] ระบบจัดการ World/Template แบบ Async

## 7. ระบบ World Boss (World Boss & Contribution)
- [ ] ตรวจจับผู้เล่นที่เข้าร่วมและสร้างความเสียหาย
- [ ] คำนวณเซ็ตผู้ได้รับรางวัล (Standard Reward, Top 3 Damage, Last Shot)
- [ ] ระบบแจก Server EXP อิงตามสัดส่วนดาเมจ (E_pool)
- [ ] ระบบโควตาบัญชีรางวัลป้องกันการกระโดด Channel (Channel Hopping)

## 8. ระบบพื้นที่ส่วนตัวและเศรษฐกิจ (Workspace & Market)
- [ ] ระบบ Workspace (โหลดโลกเฉพาะเมื่อใช้งาน, ACL)
- [ ] ระบบ Processing Job (คำนวณเวลาการทำงานเมื่อออฟไลน์)
- [ ] ระบบเหรียญและภาษี (Coin sources & sinks)
- [ ] ระบบตลาดการประมูล / AH (ซื้อแบบ Transaction เดียว, หักเงินจ่ายของทันที)
- [ ] ไม่มีระบบ P2P Trade

## 9. ระบบกล่องจดหมายและเลเวล (Mailbox & EXP/Energy)
- [ ] กล่องจดหมายแบบไร้ขีดจำกัด (Pagination, แจกรางวัลไม่มีหมดอายุ)
- [ ] ตรรกะการกดรับของและโอนสิทธิ์ลง Inventory (Transaction เดียว)
- [ ] เปลี่ยน Vanilla EXP เป็น Energy Points สำหรับอุปกรณ์
- [ ] แยกระบบ Server Level / Core ออกจาก Energy ปกติ

## 10. ระบบโอนย้ายเซิร์ฟเวอร์ (Server Transfer & Handoff)
- [ ] กลไกส่งต่อผู้เล่นข้ามโหนด (Freeze -> Lock -> Token -> Hydrate)
- [ ] การจัดการ Epoch (เพิ่ม Epoch ป้องกันของซ้ำเมื่อโอนเสร็จสิ้น)
- [ ] การรองรับ Velocity Proxy Routing

## 11. เว็บไซต์และ API (Web Console Additions)
- [ ] API สร้าง Job / แสดง Job
- [ ] แสดงประวัติการทำธุรกรรม (Transaction log)
- [ ] ระบบ Market หน้าเว็บ (ถ้ามี)

## 12. ระบบ Resource Pack และ 3D Model (Blockbench Built-in)
- [ ] รองรับและเชื่อมต่อการทำงานของโมเดล 3D จาก Blockbench 
- [ ] จัดการ Resource Pack อาวุธ/ชุดเกราะ/ไอเทม (Custom Model Data)
- [ ] แสดงผลแอนิเมชันและเอฟเฟกต์อาวุธแบบกำหนดเอง
