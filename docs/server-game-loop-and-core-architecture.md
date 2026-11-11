# 🌐 Dimensional Miner — Game Loop & Server Architecture Specification

> **เอกสารอธิบาย Game Loop และสถาปัตยกรรม Java Core Engine**  
> จัดทำสำหรับทีมพัฒนา Dimensional Miner Core & EXea Entity Engine  
> **Interactive Presentation (Local Web):** [http://localhost:8083/p/game-loop-flowcharts/](http://localhost:8083/p/game-loop-flowcharts/)

---

## 1. Main Server Game Loop (วงจรชีวิตหลักของผู้เล่น)

```mermaid
graph TD
    classDef startNode fill:#4c1d95,stroke:#8b5cf6,stroke-width:2px,color:#fff;
    classDef hubNode fill:#1e1b4b,stroke:#6366f1,stroke-width:2px,color:#fff;
    classDef actNode fill:#0f3747,stroke:#06b6d4,stroke-width:2px,color:#fff;
    classDef bossNode fill:#4c0519,stroke:#f43f5e,stroke-width:2px,color:#fff;
    classDef dbNode fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff;
    classDef decisionNode fill:#78350f,stroke:#f59e0b,stroke-width:2px,color:#fff;

    Start([ผู้เล่นเชื่อมต่อ Server / Proxy]):::startNode --> Auth[Velocity / Auth & Session Validation]
    Auth --> Hydrate[(ดึงข้อมูล Player Profile & Inventory<br/>จาก Neon PostgreSQL via HikariCP)]:::dbNode
    Hydrate --> SpawnHub[Spawn ณ Nexus Hub กลาง<br/>Safe Zone: ปิด PvP / ปิดแก้บล็อก]:::hubNode

    SpawnHub --> ActChoice{เลือกกิจกรรมที่ต้องการทำ}:::decisionNode

    %% Mining
    ActChoice -->|วาร์ปไปขุดแร่| MineBranch[เหมืองมิติตาม Tier<br/>Dimensional World Channels]:::actNode
    MineBranch --> MineAction[จอง Node ขุดแร่ Atomic<br/>รับแร่ & Energy Shards]
    MineAction --> OverflowCheck{กระเป๋าเต็มหรือไม่?}:::decisionNode
    OverflowCheck -->|เต็ม| MailboxDeposit[ส่งเข้า Overflow Mailbox<br/>ของไม่ตกพื้น ป้องกันการสูญหาย]
    OverflowCheck -->|ไม่เต็ม| InventoryKeep[เก็บในตัวพร้อมใช้งาน]

    %% Economy
    ActChoice -->|จับจ่าย / ซื้อขาย| EcoBranch[ระบบเศรษฐกิจ Hub<br/>Shop NPC & Market Board]:::actNode
    EcoBranch --> ShopTrade[ซื้อยา/เครื่องมือพื้นฐานจาก NPC]
    EcoBranch --> MarketTrade[ซื้อขายระหว่างผู้เล่นบน AH<br/>ACID Escrow Transaction]

    %% Forge
    ActChoice -->|คราฟต์ / อัปเกรด| ForgeBranch[Workspace & The Forge<br/>มิติเกาะส่วนตัว]:::actNode
    ForgeBranch --> ProcessOres[แปรรูปแร่ดิบ Offline Processing Job]
    ForgeBranch --> UpgradeGear[Advancement + Refine + Engrave]

    %% Dungeon
    ActChoice -->|ประลองฝีมือ / ปลด Tier| DungeonBranch[Instance Dungeon & World Boss<br/>ท้าทายศัตรูระดับมิติ]:::bossNode
    DungeonBranch --> KeyCheck{มี Key & Tier ผ่านไหม?}:::decisionNode
    KeyCheck -->|ไม่ผ่าน| ReturnHub[กลับไปฟาร์มของที่ Hub] --> SpawnHub
    KeyCheck -->|ผ่าน| BossBattle[เข้าสู้กับ Boss ด้วย Combat Engine<br/>เช่น Void Overseer 3D Boss]
    BossBattle --> VictoryCheck{ชนะบอสสำเร็จ?}:::decisionNode
    VictoryCheck -->|พ่ายแพ้| DurabilityLoss[เสีย Durability อุปกรณ์<br/>ส่งกลับ Hub ไม่เสียของ] --> SpawnHub
    VictoryCheck -->|ชนะ| RewardGrant[(บันทึก Boss Trophy & Proof<br/>ลงฐานข้อมูลแบบ Durable Transaction)]:::dbNode

    %% Progression Loop
    RewardGrant --> CheckNextTier{Server Level + Quest + Boss Proof ครบ?}:::decisionNode
    CheckNextTier -->|ยังไม่ครบ| LoopFarm[นำวัตถุดิบไปคราฟต์ของต่อ] --> ForgeBranch
    CheckNextTier -->|ครบเงื่อนไข!| UnlockTier[ปลดล็อก Dimensional Tier ถัดไป!<br/>เปิด World ใหม่ มอนสเตอร์ใหม่ แร่หายากใหม่]:::startNode
    UnlockTier --> SpawnHub
```

---

## 2. ระบบเศรษฐกิจ: Shop NPC & Market Board (AH)

```mermaid
graph TD
    classDef clientNode fill:#1e293b,stroke:#475569,stroke-width:2px,color:#fff;
    classDef npcNode fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff;
    classDef ahNode fill:#78350f,stroke:#f59e0b,stroke-width:2px,color:#fff;
    classDef txNode fill:#4c1d95,stroke:#8b5cf6,stroke-width:2px,color:#fff;

    Player([ผู้เล่น]):::clientNode --> EcoChoice{เลือกช่องทางเศรษฐกิจ}

    %% Shop NPC
    EcoChoice -->|คุยกับ NPC พ่อค้า| ShopFlow[ระบบร้านค้า NPC / Official Store]:::npcNode
    ShopFlow --> ShopView[เปิด GUI ดูรายการสินค้า & ราคา Dynamic]
    ShopView --> ShopAction{เลือกทำรายการ}
    ShopAction -->|ซื้อ Buy| ShopBuy[หัก Coins ผู้เล่น -> ได้รับไอเทมเข้ากระเป๋า<br/>*Coin Sink เผาเงินออกจากระบบ*]
    ShopAction -->|ขายขยะ/แร่ Sell| ShopSell[ตรวจโควตารับซื้อรายวัน Daily Payout Quota<br/>ป้องกันเงินเฟ้อ -> โอน Coin ให้ผู้เล่น]

    %% Market Board
    EcoChoice -->|เปิด Market Board| AHFlow[ระบบตลาดกลางประมูล / Auction House]:::ahNode
    AHFlow --> AHAction{ความต้องการของผู้เล่น}

    %% Seller
    AHAction -->|ผู้ขาย: ลงขายไอเทม| ListFlow[ตั้งขายไอเทม List Item]:::ahNode
    ListFlow --> CheckTradable{ไอเทมผูกมัดหรือไม่?<br/>Bound Check}
    CheckTradable -->|ไอเทมผูกมัดตัว| RejectList[ไม่อนุญาตให้ลงขาย]
    CheckTradable -->|ไอเทมเทรดได้| EscrowDeposit[(หักไอเทมเข้าส่วนกลาง Escrow Vault<br/>พร้อมหักค่าธรรมเนียม Listing Fee 2%)]:::txNode
    EscrowDeposit --> LiveOnMarket[ขึ้นแสดงผลบนกระดานค้นหา Market Board]

    %% Buyer
    AHAction -->|ผู้ซื้อ: ค้นหา & สั่งซื้อ| BuyFlow[เลือกดูสินค้า / Filter ตาม Rarity & Stat]:::ahNode
    BuyFlow --> ClickBuy[กดยืนยันการสั่งซื้อ Buy Instant]
    ClickBuy --> AtomicTX[(เริ่มกระบวนการ Atomic ACID Transaction)]:::txNode

    AtomicTX --> Step1[1. ตรวจสอบสถานะไอเทม: ยังเป็น ACTIVE หรือไม่?]
    Step1 --> Step2[2. ตรวจสอบยอดเงิน Coin ของผู้ซื้อ]
    Step2 --> Step3[3. หักเงินผู้ซื้อทันที: Price]
    Step3 --> Step4[4. หักภาษีตลาดเข้าระบบกลาง Market Tax 5%]
    Step4 --> Step5[5. โอนเงินสุทธิเข้ากระเป๋า/บัญชีผู้ขาย Payout Seller]
    Step5 --> Step6[6. ปลดล็อกไอเทมจาก Escrow โอนเข้าตัวผู้ซื้อ]
    Step6 --> Step7[7. ปรับสถานะเป็น SOLD บันทึกประวัติการซื้อขาย]

    Step7 --> OfflineNotify{ผู้ขายออนไลน์อยู่ไหม?}
    OfflineNotify -->|ออนไลน์| LiveMsg[แจ้งเตือนเสียงและข้อความขายสำเร็จ]
    OfflineNotify -->|ออฟไลน์| MailboxQueue[ส่งเงินเข้า Mailbox รอรับตอนล็อกอิน]
```

---

## 3. ระบบ Node เหมืองแร่มิติ (Mining Node Lifecycle)

```mermaid
flowchart TD
    classDef readyNode fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff;
    classDef reservedNode fill:#78350f,stroke:#f59e0b,stroke-width:2px,color:#fff;
    classDef depletedNode fill:#4c0519,stroke:#f43f5e,stroke-width:2px,color:#fff;
    classDef respawnNode fill:#1e1b4b,stroke:#6366f1,stroke-width:2px,color:#fff;
    classDef actionNode fill:#0f3747,stroke:#06b6d4,stroke-width:2px,color:#fff;

    Init(["โหลด Node ประจำ Tier มิติ"]) --> StateReady["สถานะ: READY (พร้อมให้ขุด)"]:::readyNode

    StateReady -->|"ผู้เล่นเริ่มโจมตี Node"| ReserveCheck{"ตรวจสิทธิ์ Tier & เครื่องมือ"}
    ReserveCheck -->|"สิทธิ์ถูกต้อง"| StateReserved["สถานะ: RESERVED (Atomic Lock ใน RAM)<br/>ล็อกคิวให้ผู้เล่นคนที่ 1 เพียงคนเดียว"]:::reservedNode
    ReserveCheck -->|"ไม่ผ่านเงื่อนไข"| CancelReserve["ยกเลิก ปฏิเสธการขุด"] --> StateReady

    StateReserved -->|"ผู้เล่นเดินหนี / โดนดาเมจขัด"| CancelMined["คืนสถานะกลับสู่ READY"] --> StateReady
    StateReserved -->|"ขุดสำเร็จตามระยะเวลา"| StateDepleted["สถานะ: DEPLETED (ขุดสำเร็จแล้ว)"]:::depletedNode

    StateDepleted --> CheckAntiAbuse{"Anti-Abuse Check:<br/>ตรวจ Reach Distance & Break Time"}
    CheckAntiAbuse -->|"สถิติปกติ"| GrantDelivery[("แจกรางวัลเข้ากระเป๋า<br/>Grant Key: mine/channel/node/gen")]:::actionNode
    CheckAntiAbuse -->|"พบค่าผิดปกติ"| FlagReview["บันทึก Log ส่งทีมงาน Flag Anti-Cheat"]

    GrantDelivery --> VisualBedrock["เปลี่ยนบล็อก Visual เป็นหิน/Bedrock ชั่วคราว"]
    VisualBedrock --> StateRespawn["สถานะ: RESPAWNING (เข้าคิว Time-Queue)"]:::respawnNode

    StateRespawn --> TimeQueue["นับถอยหลัง Cooldown ใน RAM ตามชนิดแร่"]
    TimeQueue --> GenInc["อัปเดต generation = generation + 1"]
    GenInc --> StateReady
```

---

## 4. ท่อคำนวณอุปกรณ์และสเตตัส (GearMath Pipeline)

```mermaid
flowchart LR
    classDef inputNode fill:#1e293b,stroke:#475569,stroke-width:2px,color:#fff;
    classDef calcNode fill:#4c1d95,stroke:#8b5cf6,stroke-width:2px,color:#fff;
    classDef modNode fill:#0f3747,stroke:#06b6d4,stroke-width:2px,color:#fff;
    classDef outNode fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff;

    subgraph DataInputs ["1. ข้อมูลนำเข้าของอุปกรณ์"]
        Base["Base Stat ค่าพื้นฐานไอเทม"]:::inputNode
        Adv["Advancement เลเวลชิ้นส่วน 0-100"]:::inputNode
        Growth["Growth Factor อัตราเติบโตต่อเลเวล"]:::inputNode
        Ref["Refine Tier ระดับตีบวก 0-10"]:::inputNode
    end

    subgraph CoreCalc ["2. ท่อคำนวณขั้นต้น Core Stat"]
        FormulaCore["Core_s = (Base + Adv * Growth) * (1 + RefineBonus)"]:::calcNode
    end

    subgraph Modifiers ["3. ค่าโบนัสเพิ่มเติม Additives"]
        EngraveFlat["Flat Engrave และ Flat Sockets"]:::modNode
        SetFlat["Set Effect Flat โบนัสเซ็ตตายตัว"]:::modNode
        EngravePct["Percent Engrave โบนัสเปอร์เซ็นต์"]:::modNode
        SetPct["Set Effect Percent โบนัสเซ็ตเปอร์เซ็นต์"]:::modNode
    end

    subgraph FinalPipe ["4. ท่อคำนวณสเตตัสสุทธิ Final Stat"]
        FormulaFinal["Final_s = clamp((Core_s + TotalFlat) * (1 + TotalPercent), Min, Cap)"]:::calcNode
    end

    Base --> FormulaCore
    Adv --> FormulaCore
    Growth --> FormulaCore
    Ref --> FormulaCore

    FormulaCore --> FormulaFinal
    EngraveFlat --> FormulaFinal
    SetFlat --> FormulaFinal
    EngravePct --> FormulaFinal
    SetPct --> FormulaFinal

    FormulaFinal --> OutputStat(["สเตตัสดาเมจ/พลังป้องกันสุทธิ ส่งเข้า CombatService"]):::outNode
```

---

## 5. วงจรชีวิต Instance Dungeon & Combat Engine

```mermaid
sequenceDiagram
    autonumber
    actor Party as ปาร์ตี้ผู้เล่น 1-4 คน
    participant Core as DungeonManager
    participant Pool as Warm World Pool
    participant Boss as EXeaEntity Boss
    participant DB as Neon PostgreSQL

    Party->>Core: หัวหน้าปาร์ตี้ขอยื่นกุญแจเข้า Dungeon Run
    Core->>Core: ตรวจสอบคุณสมบัติ Tier และกุญแจของสมาชิกทุกคน
    Core->>Core: สั่ง Roster Freeze ล็อกรายชื่อสมาชิกห้ามสลับตัว
    Core->>Pool: ขอจองห้องว่าง Acquire Warm World Slot
    Pool-->>Core: ส่งมอบ Instance ID และพิกัดห้อง
    Core->>DB: ตัดกุญแจ Durable Key Deduction แบบ ACID
    Core->>Party: เทเลพอร์ตปาร์ตี้เข้าสู่ห้องบอส Status ACTIVE

    Core->>Boss: เสก 3D Boss เช่น Void Overseer พร้อมเปิด AI
    
    loop วงจรการต่อสู้ Combat Loop
        Party->>Boss: โจมตีบอส ผ่านท่อ CombatService
        Boss->>Party: บอสสวนกลับด้วยทักษะ หรือสกิลกวาดวงกว้าง
    end

    alt ปาร์ตี้พ่ายแพ้ Party Wipe
        Core->>Party: ส่งผู้เล่นกลับ Hub เสีย Durability เกราะ 10%
        Core->>Pool: ส่งห้องเข้าสู่กระบวนการ CLEANING และ Reset
    else ปาร์ตี้พิชิตบอสได้สำเร็จ Victory
        Core->>DB: บันทึกของรางวัลและ Proof of Victory ลง DB ก่อนเสมอ
        DB-->>Core: ยืนยัน Commit Transaction สำเร็จ
        Core->>Boss: เล่น Death Animation 3 วินาที สลายร่าง
        Core->>Party: แสดง Victory GUI แจกรางวัลเข้าตัว และวาร์ปกลับ Hub
        Core->>Pool: คืนห้องเข้า Warm Pool รอให้ปาร์ตี้อื่นใช้งานต่อ
    end
```

---

## 6. สถาปัตยกรรม Java Core: 3D Entity & Equipment Display Engine

```mermaid
flowchart TD
    classDef inputNode fill:#1e293b,stroke:#475569,stroke-width:2px,color:#fff;
    classDef coreNode fill:#4c1d95,stroke:#8b5cf6,stroke-width:2px,color:#fff;
    classDef renderNode fill:#0f3747,stroke:#06b6d4,stroke-width:2px,color:#fff;
    classDef clientNode fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff;

    subgraph InputStage ["1. โมเดลจาก Blockbench"]
        BB["Blockbench .bbmodel"]:::inputNode --> Exporter["EXea Blockbench Plugin / Exporter"]:::inputNode
        Exporter --> BonesJSON["Bones Hierarchy JSON<br/>(Pivot, Origin, Visibility)"]:::inputNode
        Exporter --> AnimsJSON["Keyframe Animations JSON<br/>(Position, Rotation, Scale)"]:::inputNode
        Exporter --> RP["Client Resource Pack ZIP<br/>(Per-bone JSON models + Textures)"]:::inputNode
    end

    subgraph JavaCoreEngine ["2. EXeaEntityCore Engine (Java Server Layer)"]
        direction TB
        Loader["ModelLoader: โหลดโครงกระดูกและแอนิเมชัน"]:::coreNode --> Structure["ModelStructure & BoneNode Hierarchy"]:::coreNode
        
        subgraph DriverSystem ["สถาปัตยกรรมตัวขับเคลื่อน Driver Architecture"]
            Driver["LivingEntity Driver (เช่น Invisible Husk)<br/>- ควบคุม Navigation AI / Pathfinding<br/>- รับ Hurt/Attack Events & Hitbox<br/>- ถือ Plugin Chunk Ticket (กัน Unload)"]:::coreNode
        end

        subgraph RenderSystem ["ระบบแสดงผลกระดูก DisplayRenderer"]
            Bone1["ItemDisplay: Torso"]:::renderNode
            Bone2["ItemDisplay: Arm_L"]:::renderNode
            Bone3["ItemDisplay: Head_3"]:::renderNode
            Bone4["ItemDisplay: Orbiters"]:::renderNode
            SelfHeal["Self-Healing Watchdog:<br/>ตรวจสอบ isValid() ทุก Tick ถ้าหลุดจะ Auto-Respawn ทันที"]:::renderNode
        end

        subgraph MathSystem ["ท่อคำนวณคณิตศาสตร์ AnimationController"]
            TickLoop["Server Tick Loop 20 FPS"]:::coreNode --> Interpolate["Hermite / Slerp Interpolator"]:::coreNode
            Interpolate --> MatrixCalc["Forward Kinematics:<br/>ParentMatrix * LocalPivot * AnimPos * AnimRot * AnimScale"]:::coreNode
            MatrixCalc --> PacketDispatch["ส่ง Transformation Packet:<br/>setInterpolationDelay(0)<br/>setInterpolationDuration(1)<br/>setTeleportDuration(1)"]:::coreNode
        end

        Driver --> RenderSystem
        Structure --> RenderSystem
        RenderSystem --> MathSystem
    end

    subgraph ClientView ["3. ฝั่ง Client Minecraft"]
        ClientRender["Client Hardware GPU Renderer"]:::clientNode
        ClientRender --> FrameLerp["Client Frame-rate Interpolation:<br/>แสดงผลลื่นไหล 60-240 FPS ไม่มีภาพค้าง/วาป"]:::clientNode
    end

    PacketDispatch --> ClientRender
    RP --> ClientRender
```

---

## 7. สถาปัตยกรรมโมเดลอุปกรณ์ 3D (3D Equipment Display Engine Design)

สำหรับการสวมใส่หมวก 3D, ดาบใหญ่ 3D, ปีกมิติ, และอาวุธแบบกำหนดเองบนตัวผู้เล่น:

1. **Player Anchor Strategy (Passenger / Packet Tracking):**
   - ใช้วิธี Spawn `ItemDisplay` แล้ว Mount หรือ Anchor เทียบพิกัดกับ `player.getEyeLocation()`
   - ไม่ต้องส่ง Packet หมุนทุกองศา ให้ฝั่ง Client อาศัย Interpolation ของตัวผู้เล่น
2. **Lifecycle & Anti-Desync Safeguards:**
   - ดักจับ `PlayerRespawnEvent`, `PlayerChangedWorldEvent`, และ `PlayerQuitEvent`
   - เมื่อผู้เล่นตายหรือเปลี่ยนโลก ให้ทำลาย Display ทันที และ Spawn เชื่อมโยงใหม่เมื่อถึงจุดหมาย
3. **Zero-Lag Network Culling:**
   - ผู้เล่นที่อยู่ห่างเกิน 32 บล็อก จะไม่ได้รับ Packet อัปเดต Matrix ของโมเดลอุปกรณ์คนอื่น ช่วยรักษา TPS เซิร์ฟเวอร์ให้อยู่ที่ 20.0
