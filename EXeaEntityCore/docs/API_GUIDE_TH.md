# คู่มือใช้ EXeaEntityCore จากปลั๊กอินอื่น (ภาษาไทย)

คู่มือนี้สำหรับคนเขียนปลั๊กอิน Paper ที่อยากใช้โมเดล 3D ของ EXea เช่น เสกบอส สั่งท่า ยิงสกิลออกจากมือ
รู้ว่าใครฆ่าบอส หรือให้ท่าใน Blockbench ไปสั่งงานโค้ดของเรา ทุกตัวอย่างในหน้านี้ compile ผ่านจริงกับ
API jar แล้ว โค้ดเต็มอยู่ที่ [`examples/GuideExamples.java`](examples/GuideExamples.java)
(ฉบับภาษาอังกฤษแบบอ้างอิง: [`API.md`](API.md))

หลักการ 3 ข้อที่ต้องรู้ก่อน:
1. **ม็อบตัวขับ (driver)** คือ mob ล่องหน (Husk, Zombie ฯลฯ) ที่มี hitbox, เลือด, AI และการเดิน
   ส่วนโมเดลคือกระดูก `ItemDisplay` ที่ EXea วาดตามม็อบตัวนี้ เวลาจะตีหรือหาตำแหน่ง ให้ใช้ม็อบตัวขับ
   ส่วนการสั่งท่า สั่งตาย หรือหาตำแหน่งกระดูก ให้ใช้ `EXeaEntity`
2. **บอส EXea ไม่ส่ง `EntityDeathEvent` ของ vanilla** เพราะ EXea ยกเลิกดาเมจครั้งสุดท้ายไว้ เพื่อให้ม็อบยังอยู่
   ระหว่างเล่นท่าตาย ให้ฟัง `EXeaEntityDeathEvent` แทน
3. **ทุกอย่างต้องเรียกบน main thread** (ใน event handler หรือ `runTask`) ห้ามเรียกจาก async

---

## 0. ตั้งค่าโปรเจกต์

**plugin.yml** ของปลั๊กอินเรา:
```yaml
depend: [EXeaEntityCore]        # ต้องมี EXea ถึงจะรัน
# หรือ softdepend: [EXeaEntityCore]  ถ้าไม่มี EXea ก็ยังรันได้ (ต้องเช็กเองตามตัวอย่างด้านล่าง)
```

**build.gradle.kts** เลือกได้ 2 แบบ:
```kotlin
// แบบ 1: ผ่าน Maven ในเครื่อง ต้องรัน `gradlew publishToMavenLocal` ใน EXeaEntityCore/ ก่อน
repositories { mavenLocal() }
dependencies { compileOnly("com.exea:exea-entity-core-api:1.1.0") }

// แบบ 2: ชี้ไปที่ jar ของปลั๊กอินตรงๆ
dependencies { compileOnly(files("../server/plugins/EXeaEntityCore.jar")) }
```
ต้องใช้ `compileOnly` เท่านั้น ห้าม shade EXea เข้าไปใน jar ของเรา

**ตอนเปิดปลั๊กอิน** ถ้าใช้ softdepend ให้เช็กก่อน แล้วค่อยลงทะเบียนของต่างๆ:
```java
@Override
public void onEnable() {
    if (!Bukkit.getPluginManager().isPluginEnabled("EXeaEntityCore")) {
        getLogger().warning("EXeaEntityCore is not installed: 3D bosses are off");
        return;
    }
    EXeaAPI exea = EXeaAPI.getInstance();
    exea.registerBehavior("void_overseer", e -> new PhaseBossBehavior(this));   // หัวข้อ 6
    getServer().getPluginManager().registerEvents(this, this);                  // หัวข้อ 5
}
```
ถ้าใช้ softdepend และไม่มี EXea ห้ามแตะคลาส `com.exea...` ในโค้ดส่วนที่รันจริงเลย ไม่อย่างนั้นจะขึ้น
`NoClassDefFoundError` วิธีที่ปลอดภัยคือแยกโค้ด EXea ไว้ในคลาสเดียว (เช่น `ExeaBridge`) และโหลดคลาสนั้น
หลังจากเช็กแล้วว่ามี EXea

---

## 1. เสกโมเดล (spawn)

```java
EXeaEntity boss = EXeaAPI.getInstance().spawn("void_overseer", where,
        SpawnOptions.create()
                .driver(EntityType.ZOMBIE)      // ม็อบตัวขับ (ไม่ใส่ = ค่าใน _meta.json หรือ HUSK)
                .health(2000)
                .attackDamage(15)
                .movementSpeed(0.3)
                .displayName("§5Floor 3 Overseer"));
if (boss == null) {
    // ไม่มีโมเดล id นี้ หรือม็อบชนิดนั้น spawn ไม่ได้
}
```
- ใส่ `null` แทน `SpawnOptions` ได้ จะใช้ค่าทั้งหมดจาก `<id>_meta.json`
- รูปปั้น (ไม่เดิน ไม่ตี): `SpawnOptions.create().ai(false)` จะได้ ArmorStand เป็นตัวขับ
- ตัวเลือกทั้งหมดของ `SpawnOptions`: `driver`, `ai`, `health`, `attackDamage`, `movementSpeed`,
  `hitboxScale`, `followRange`, `displayName`, `nameVisible`, `behavior`
- ดูว่ามีโมเดลอะไรโหลดอยู่บ้าง: `EXeaAPI.getInstance().getModelIds()`, ดูท่าของโมเดล: `getAnimations("void_overseer")`

`EXeaEntity` ที่ได้มาเก็บไว้ใช้ต่อได้ แต่ก่อนใช้ทุกครั้งให้เช็ก `isValid()` ด้วย
(เมื่อโมเดลถูกลบไปแล้ว ค่านี้จะเป็น `false`)

## 2. หา EXea entity จาก entity ที่มีอยู่

ใน event ทั่วไปของ Bukkit เราจะได้ `Entity` มา ให้แปลงด้วย `get()`:
```java
EXeaEntity e = EXeaAPI.getInstance().get(mob);   // null = ไม่ใช่โมเดล EXea
if (e == null || e.isDying()) return;
```
ถ้าแค่อยากรู้ว่าเป็นโมเดลไหม ใช้ `EXeaAPI.getInstance().isEXeaEntity(entity)` ถ้าอยากได้ทุกตัวที่ยังอยู่ใช้ `getAll()`

## 3. สั่งท่า

```java
if (e.getAnimations().contains("attack_slam")) {
    e.playAnimation("attack_slam", 1.2f, EXeaEntity.Loop.ONCE);  // ความเร็ว 1.2 เท่า เล่นครั้งเดียว
}
e.playAnimation("idle");               // เล่นแบบที่ตั้งไว้ใน Blockbench
e.stopAnimation();                     // กลับท่าพัก
String now = e.getCurrentAnimation();  // ท่าที่กำลังเล่น หรือ null
```
`Loop` มี 3 แบบ: `ONCE` (เล่นจบแล้ว behavior จะเลือกท่าต่อ ปกติคือ idle/move), `LOOP` (วนไปเรื่อยๆ),
`HOLD` (ค้างเฟรมสุดท้าย) ถ้าใส่ `null` จะใช้แบบที่ตั้งไว้ใน Blockbench

## 4. ปล่อยของออกจากกระดูก (มือ ปาก ปลายดาบ)

`getBoneLocation("ชื่อ bone")` คืนตำแหน่งจริงในโลกของจุดหมุนกระดูก ตามท่าที่เล่นอยู่ ณ tick นั้น:
```java
Location hand = e.getBoneLocation("hand_r");
if (hand == null) return;                        // โมเดลไม่มี bone ชื่อนี้
Vector dir = target.getEyeLocation().toVector().subtract(hand.toVector()).normalize();
SmallFireball ball = hand.getWorld().spawn(hand, SmallFireball.class);
ball.setShooter(e.getDriver());                  // ให้นับว่าบอสเป็นคนยิง
ball.setDirection(dir);
hand.getWorld().spawnParticle(Particle.FLAME, hand, 20, 0.2, 0.2, 0.2, 0.02);
```
- ดูรายชื่อ bone ได้ด้วย `e.getBones()` หรือพิมพ์ `/exea bone <ชื่อ>` ในเกม จะมีอนุภาคขึ้นตรงกระดูกนั้น 5 วินาที
- จุดที่ได้คือจุดหมุน (pivot) ของ bone ถ้าอยากได้ปลายมือ ให้ทำ bone เล็กๆ ไว้ที่ปลายมือใน Blockbench
  (bone ที่ไม่มี cube ไม่เปลืองอะไร)

## 5. ฟัง event (ให้ปลั๊กอินเรารู้ว่าเกิดอะไรขึ้น)

ทุก event อยู่ใน `com.exea.entity.api.event` ใช้แบบ `@EventHandler` ปกติ

| Event | เกิดตอน | ได้อะไรมา |
|---|---|---|
| `EXeaEntitySpawnEvent` | spawn เสร็จ | `getEntity()` (ม็อบตัวขับ), `getTemplateId()` |
| `EXeaEntityDamageEvent` | มีการตีที่เกี่ยวกับโมเดล (โมเดลตีหรือโดนตี) ยกเลิกได้ | `getAttacker()`, `getVictim()`, `getDamage()`/`setDamage()`, `getSkillId()` |
| `EXeaEntityDeathEvent` | เริ่มฉากตาย (ครั้งเดียว) | `getKiller()`, `getDamageByAttacker()`, `getEntityTemplateId()` |
| `EXeaEntityRemoveEvent` | ถูกลบจริง (ครั้งเดียว) | `getReason()` = `DIED` / `REMOVED` / `DRIVER_LOST` / `PLUGIN_DISABLE` |
| `EXeaAnimationEventTriggerEvent` | effect keyframe ในท่าทำงาน | `getEventTag()`, `getAnimationName()`, `getTriggerLocation()` |

**ตัวอย่าง: แจกรางวัล Top 3 ตามดาเมจ + คนตีครั้งสุดท้าย**
```java
@EventHandler
public void onDeath(EXeaEntityDeathEvent event) {
    Map<UUID, Double> damage = event.getDamageByAttacker();     // UUID -> HP ที่ทำได้
    List<Map.Entry<UUID, Double>> top = damage.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(3).toList();
    int rank = 1;
    for (Map.Entry<UUID, Double> entry : top) {
        Player p = Bukkit.getPlayer(entry.getKey());
        if (p != null) p.sendMessage("Rank " + rank + ": " + Math.round(entry.getValue()) + " damage");
        rank++;
    }
    if (event.getKiller() instanceof Player p) p.sendMessage("Last hit on " + event.getEntityTemplateId() + "!");
}
```
ตอน event นี้ทำงาน ม็อบตัวขับยังอยู่ ใช้ `getEntity().getLocation()` หาจุดดรอปของได้

**ตัวอย่าง: ปรับดาเมจ**
```java
@EventHandler(ignoreCancelled = true)
public void onDamage(EXeaEntityDamageEvent event) {
    boolean victimIsModel = EXeaAPI.getInstance().isEXeaEntity(event.getVictim());
    if (victimIsModel && event.getAttacker() instanceof Player p && p.isSneaking()) {
        event.setDamage(event.getDamage() * 0.5);        // ย่อตีบอส = ดาเมจครึ่งเดียว
    }
    if (event.getSkillId().equals("attack_singularity") && event.getVictim() instanceof Player p) {
        p.setFreezeTicks(100);                           // โดนท่านี้แล้วตัวแข็ง
    }
}
```
`getSkillId()` จะเป็น `"melee"` ถ้าเป็นการตีปกติ หรือเป็นชื่อท่า ถ้าดาเมจมาจากบรรทัด `hit` ในท่า (หัวข้อ 7)
`event.setCancelled(true)` ใช้ยกเลิกการตีครั้งนั้น

**ตัวอย่าง: ทำความสะอาดเมื่อบอสหายไป**
```java
@EventHandler
public void onRemove(EXeaEntityRemoveEvent event) {
    if (event.getReason() == EXeaEntityRemoveEvent.Reason.DRIVER_LOST) {
        getLogger().warning(event.getTemplateId() + " vanished without dying");
    }
    // ลบ BossBar / หยุด task ของบอสตัวนี้ตรงนี้
}
```

**ถ้าอยากคุมสูตรดาเมจเองทั้งหมด** ให้ลงทะเบียน `IDamageProvider` ได้ทีละ 1 ตัว:
```java
exea.registerDamageProvider((attacker, victim, base, skillId) ->
        skillId.equals("attack_slam") ? base * 1.5 : base);
```

## 6. เขียน AI / สกิลเอง (Behavior)

behavior เป็นตัวตัดสินว่าโมเดลจะเล่นท่าไหนเมื่อไร และทำอะไรเมื่อโดนตี ตีโดน หรือตาย
ส่วนเรื่องการวาด ฉากตาย และ event นั้น EXea ทำให้เองเสมอ แต่ละโมเดลที่เกิดจะได้ behavior ของตัวเอง 1 ตัว
จึงเก็บสถานะในตัวได้ เช่น cooldown หรือ phase

จุดที่เกาะได้ (เลือก override เฉพาะที่ใช้):

| method | ถูกเรียกตอน |
|---|---|
| `onSpawn(e)` | เกิดเสร็จ (EXea เล่น `idle` ให้แล้วถ้ามี) |
| `onTick(e)` | ทุก tick ตอนยังไม่ตาย |
| `onDamaged(e, attacker, damage)` | โดนตีแต่ยังไม่ตาย |
| `onAttack(e, target)` | ม็อบตัวขับตีโดนเป้าหมาย (melee) |
| `onAnimationEnd(e, name)` | ท่าแบบ ONCE เล่นจบ |
| `onDeath(e, killer)` | เริ่มฉากตาย |
| `onRemove(e, reason)` | กำลังจะถูกลบ |

ถ้าโค้ดใน behavior error จะเขียนลง log เท่านั้น บอสไม่ค้าง

**แบบที่ 1: ต่อยอดจาก `DefaultBehavior`** (ยังได้ idle/move/สุ่มท่าโจมตีเหมือนเดิม แล้วเพิ่ม phase 2 กับสกิลตามเวลา)
```java
static final class PhaseBossBehavior extends DefaultBehavior {
    private final JavaPlugin plugin;
    private boolean phaseTwo;
    private int skillCooldown = 200;                       // tick (20 = 1 วินาที)

    PhaseBossBehavior(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public void onTick(EXeaEntity entity) {
        super.onTick(entity);                              // อย่าลืม: ให้สลับ idle/move ต่อ
        if (--skillCooldown > 0 || isAttacking()) return;
        skillCooldown = phaseTwo ? 100 : 200;
        entity.playAnimation("attack_singularity", 1f, EXeaEntity.Loop.ONCE);
    }

    @Override
    public void onDamaged(EXeaEntity entity, LivingEntity attacker, double damage) {
        LivingEntity d = entity.getDriver();
        if (!phaseTwo && d.getHealth() < d.getAttribute(Attribute.MAX_HEALTH).getValue() / 2) {
            phaseTwo = true;
            d.getWorld().playSound(d.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2f, 0.7f);
        }
    }

    @Override
    protected String pickAttack(EXeaEntity entity, LivingEntity target) {   // ท่าโจมตีตอนตีโดน
        return phaseTwo && entity.getAnimations().contains("attack_slam") ? "attack_slam" : super.pickAttack(entity, target);
    }
}
```

**แบบที่ 2: เขียนเองทั้งหมด** (`implements EXeaBehavior`) เช่น ยืนอยู่กับที่ ยิงลูกไฟใส่ผู้เล่นที่ใกล้สุดทุก 3 วินาที
```java
static final class FireballBehavior implements EXeaBehavior {
    private int cooldown = 60;

    @Override public void onSpawn(EXeaEntity entity) { entity.playAnimation("idle"); }

    @Override
    public void onTick(EXeaEntity entity) {
        if (--cooldown > 0) return;
        cooldown = 60;
        Player target = nearestPlayer(entity.getDriver().getLocation(), 16);
        if (target == null) return;
        entity.playAnimation("attack_cast", 1f, EXeaEntity.Loop.ONCE);
        Location hand = entity.getBoneLocation("hand_r");
        if (hand == null) hand = entity.getDriver().getEyeLocation();
        SmallFireball ball = hand.getWorld().spawn(hand, SmallFireball.class);
        ball.setShooter(entity.getDriver());
        ball.setDirection(target.getEyeLocation().toVector().subtract(hand.toVector()));
    }

    @Override public void onAnimationEnd(EXeaEntity entity, String animation) { entity.playAnimation("idle"); }
}
```

**ผูก behavior กับโมเดล** มี 3 ทาง (EXea ไล่ตามลำดับนี้):
```java
// 1) เฉพาะตัวที่ spawn ครั้งนี้
EXeaAPI.getInstance().spawn("vaelith", where, SpawnOptions.create().behavior(new FireballBehavior()));
// 2) ตามชื่อที่ meta ของโมเดลอ้างถึง: ใน vaelith_meta.json ใส่ "behavior": "fireball_caster"
exea.registerBehavior("fireball_caster", e -> new FireballBehavior());
// 3) ทุกตัวของโมเดล id นี้ (รวมตัวที่เสกด้วย /exea spawn และตัวที่ปลั๊กอินอื่นเสก)
exea.registerBehavior("void_overseer", e -> new PhaseBossBehavior(this));
```
ถ้าไม่ตรงกับทางไหนเลยจะใช้ `DefaultBehavior` ส่วนการลงทะเบียน (ทาง 2 และ 3) ควรทำใน `onEnable`
ก่อนที่จะมีการ spawn

## 7. ให้ท่าใน Blockbench ส่งสัญญาณมาที่โค้ดเรา

ใน Blockbench ไปที่ท่านั้น แล้วใส่ keyframe ในแถว **Effects** ช่อง **Timeline** โดยเขียนคำสั่งทีละบรรทัด
บรรทัดที่ EXea รู้จัก EXea จะทำให้เอง ส่วนบรรทัดอื่นๆ จะถูกส่งเป็น `EXeaAnimationEventTriggerEvent`
ให้ปลั๊กอินเรามาจัดการ

```
hit radius=3.5 damage=20 cone=120 bone=hand_r knockback=0.5     ← EXea ทำดาเมจรอบมือให้เอง
sound entity.generic.explode volume=2 pitch=0.8 bone=core      ← EXea เล่นเสียงให้
particle explosion count=3 bone=hand_r                         ← EXea ปล่อยอนุภาคให้
summon zombie                                                  ← EXea ไม่รู้จัก → ส่ง event ให้เรา
```

ฝั่งโค้ดของเรา:
```java
@EventHandler
public void onAnimationEvent(EXeaAnimationEventTriggerEvent event) {
    if (!event.getEventTag().startsWith("summon ")) return;          // tag = ข้อความทั้งบรรทัด
    String type = event.getEventTag().substring("summon ".length()).trim();
    EntityType t = EntityType.fromName(type);
    if (t != null) event.getTriggerLocation().getWorld().spawnEntity(event.getTriggerLocation(), t);
}
```
- `getTriggerLocation()` คือตำแหน่งของ bone ที่ใส่ใน `bone=` ถ้าไม่ใส่จะอยู่สูงจากตัวม็อบ 1 บล็อก
- บรรทัด `hit`/`sound`/`particle` ก็ส่ง event นี้ด้วย (tag = ข้อความทั้งบรรทัด)
- ดาเมจจาก `hit` วิ่งผ่าน `EXeaEntityDamageEvent` โดย `getSkillId()` = ชื่อท่า (หัวข้อ 5) จึงปรับหรือยกเลิกได้
- แก้ท่าแล้วต้อง export ใหม่ (`update-exea.bat` หรือ `exea_exporter.js ... --server` แล้วพิมพ์ `/exea reload`)

## 8. ฆ่า / ลบ / ย้าย

```java
e.kill();      // ตายแบบปกติ: DeathEvent → ท่าตาย → RemoveEvent(DIED)
e.remove();    // หายทันที ไม่มีท่าตาย ไม่นับว่าตาย: RemoveEvent(REMOVED)
for (EXeaEntity x : EXeaAPI.getInstance().getAll()) {
    if (x.getModelId().equals("void_overseer")) x.remove();
}
```
- **ห้ามลบม็อบตัวขับเองด้วย `getDriver().remove()`** ให้ใช้ `e.remove()` แทน
  (ถ้าลบตัวขับตรงๆ EXea ก็เก็บกวาดกระดูกให้ แต่ RemoveEvent จะเป็น `DRIVER_LOST`)
- ย้ายบอส: เรียก `e.getDriver().teleport(loc)` ได้ตามปกติ ข้ามโลกก็ได้ เพราะกระดูกไม่ได้เกาะอยู่บนตัวขับ
- `/kill` ใส่ตัวขับก็ยังส่ง `EXeaEntityDeathEvent` ให้ (killer = คนที่ตีล่าสุด)

## 9. ข้อควรระวัง

- **ไม่มี `EntityDeathEvent`** สำหรับโมเดล EXea ให้ใช้ `EXeaEntityDeathEvent` (นับเควสต์ นับ kill แจกรางวัล)
- อย่าเก็บ `EXeaEntity` ไว้นานโดยไม่เช็ก `isValid()`
- โมเดลไม่ถูกบันทึกลงโลก ปิดเซิร์ฟหรือ chunk ถูก unload ก็หายไป ถ้าบอสต้องกลับมาหลังรีสตาร์ต ปลั๊กอินเราต้อง spawn ใหม่เอง
- `EXeaEntityDamageEvent` เกิดเมื่อ entity ตี entity โดยฝั่งใดฝั่งหนึ่งเป็นโมเดล ถ้าโดนธนูหรือกระสุน
  `getAttacker()` จะเป็น `null` (คนยิงหาได้จาก `EntityDamageByEntityEvent` ปกติ ส่วนใน `getDamageByAttacker()`
  ดาเมจจะนับให้คนยิง) ไฟหรือตกที่สูงไม่ทำให้ event นี้เกิด
- ทุกการเรียกต้องอยู่บน main thread
- ทดสอบได้ด้วยคำสั่ง: `/exea spawn <id>`, `/exea anim play <ท่า>`, `/exea bone <bone>`, `/exea stats`, `/exea kill`
