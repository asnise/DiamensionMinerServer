# Party Bots — ทดสอบระบบ Party / Dungeon คนเดียว

บอท [Mineflayer](https://github.com/PrismarineJS/mineflayer) ที่เข้าเซิร์ฟ dev มาเป็นเพื่อนร่วมปาร์ตี้
**ใช้กับเซิร์ฟในเครื่องตัวเองเท่านั้น**

## ต้องเตรียมครั้งเดียว

1. **ViaVersion + ViaBackwards** (ปลั๊กอิน Paper)
   Mineflayer 4.39 รองรับถึง Minecraft **26.1** แต่เซิร์ฟเป็น **26.2**
   ViaBackwards ทำให้ client เวอร์ชันเก่ากว่าเข้าเซิร์ฟเวอร์ใหม่ได้
   ติดตั้งแล้ว: `ViaVersion-5.12.2.jar` + `ViaBackwards-5.12.2.jar` (จาก GitHub ทางการ ViaVersion/ViaVersion และ ViaVersion/ViaBackwards)
   ถ้าต้องลงใหม่: ดาวน์โหลดจาก release ของทั้ง 2 repo (หรือ https://hangar.papermc.io/ViaVersion) เลือกเวอร์ชันที่รองรับเซิร์ฟ แล้ววางใน `server/plugins/`
2. ติดตั้ง dependency:
   ```powershell
   cd tools\party-bots
   npm install
   ```

## ทุกครั้งที่จะทดสอบ

```powershell
.\switch-online-mode.ps1 offline     # จาก root ของโปรเจกต์ แล้วรีสตาร์ทเซิร์ฟ
cd tools\party-bots
node bots.mjs                        # บอท 2 ตัว: TestBot1, TestBot2
```

ทดสอบเสร็จแล้ว **ต้องสลับกลับ**:
```powershell
.\switch-online-mode.ps1 online      # แล้วรีสตาร์ทเซิร์ฟ
```

> ⚠️ ระหว่าง offline mode ตัวคุณเองจะได้ UUID ใหม่ (ระบบมองเป็นผู้เล่นใหม่ ไม่มีของ/COIN)
> ข้อมูลจริงไม่หาย สลับกลับเป็น online ก็เห็นตามเดิม · อย่าเปิดเซิร์ฟให้คนนอกเข้าตอน offline

## สั่งบอท

| จากไหน | รูปแบบ | ตัวอย่าง |
|---|---|---|
| หน้าต่าง terminal ที่รันบอท | `<ชื่อบอท\|all> <ข้อความหรือ /คำสั่ง>` | `TestBot1 /dungeon join 1a2b3c4d` |
| แชทในเกม (พิมพ์โดยคุณ) | `!<ชื่อบอท\|all> <ข้อความหรือ /คำสั่ง>` | `!all /party leave` |
| ให้บอทออก | `<ชื่อบอท\|all> quit` | `TestBot2 quit` |

- บอท**รับ party invite อัตโนมัติ**
- ทุกข้อความที่บอทเห็นในเกมจะขึ้นใน terminal นำหน้าด้วยชื่อบอท
- ตัวเลือก: `--count 3` จำนวนบอท · `--prefix Pal` ชื่อบอท · `--version 26.1` · `--host` · `--port` · `--stagger 5000` (ms ระหว่างบอทแต่ละตัว)
- ปิดบอททั้งหมด: `Ctrl+C`
