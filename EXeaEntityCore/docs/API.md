# EXeaEntityCore — developer guide

EXeaEntityCore is a Paper plugin that puts animated Blockbench models in the game. Each model is a
tree of `ItemDisplay` bones riding an invisible driver mob. The driver gives the model its hitbox,
health, AI and movement. The plugin does not depend on any other plugin. Other plugins use it through
the API in `com.exea.entity.api`.

- Server: Paper 26.2 (Java 25). Clients need 1.21.11+ item models (the pack is sent automatically).
- One model = `<id>_bones.json` + `<id>_anims.json` (+ optional `<id>_meta.json`) + its item models and texture in the resource pack.

## 1. Install

1. Put `EXeaEntityCore.jar` in `plugins/` and start the server once. This creates `plugins/EXeaEntityCore/config.yml`.
2. Players on another machine need to download the pack, so set `resource_pack.public_url` in `config.yml`:

```yaml
resource_pack:
  enabled: true
  port: 8086                 # EXea's own small HTTP server
  public_url: "http://play.example.com:8086/exea.zip"   # empty = http://localhost:<port>/exea.zip
  required: false
  send_delay_ticks: 40       # after join; later than plugins that use setResourcePack
```

The pack is added with `addResourcePack`, so it stacks on top of other server packs. If another plugin
replaces all packs (`setResourcePack`), EXea adds its pack back.

## 2. Add a model (no rebuild)

Model in Blockbench (Generic Model format, model faces -Z / north, feet at y = 0). Then export it into
the server:

```bash
node tools/exea_exporter.js "MyBoss.bbmodel" my_boss --server /path/to/server/plugins/EXeaEntityCore
```

This writes `models/my_boss_*.json` and `pack/assets/exea/...` into that folder. In game, run
`/exea reload` to load the models, rebuild the pack and resend it to online players, then
`/exea spawn my_boss`. The rules for models (bone budget, the 24 px mesh limit, what animation features
work) are in `MODEL_STYLE_GUIDE.md` §3.

If a `my_boss_meta.json` sits next to the `.bbmodel`, the exporter copies it too. Every key is optional:

| key | default | meaning |
|---|---|---|
| `display_name`, `name_color` | from the id, `WHITE` | name tag (ChatColor name) |
| `health`, `attack_damage`, `movement_speed`, `follow_range` | 20, 3, 0.23, 32 | driver attributes |
| `hitbox_scale` | 1.0 | driver SCALE attribute (hitbox only; the model is not resized) |
| `driver` | `husk` (AI) / `armor_stand` (no AI) | any living entity type, e.g. `zombie`, `skeleton`, `iron_golem` |
| `behavior` | — | key of a behavior registered with `registerBehavior` |
| `glow_bones` | `[]` | bones drawn at full brightness |
| `hurt_sound`, `death_sound` | `entity.wither.hurt`, `entity.wither.death` | sound keys, `""` = none |
| `hurt_particle`, `death_particle` | `portal`, `warped_spore` | particle keys, `""` = none |
| `death_ticks` | 60 | length of the death sequence |

## 3. Depend on the API

`plugin.yml` of your plugin:

```yaml
depend: [EXeaEntityCore]     # or softdepend, and check Bukkit.getPluginManager().isPluginEnabled("EXeaEntityCore")
```

Gradle (`build.gradle.kts`). Publish the API to your local Maven first with
`gradlew publishToMavenLocal` in `EXeaEntityCore/`:

```kotlin
repositories { mavenLocal() }
dependencies {
    compileOnly("com.exea:exea-entity-core-api:1.1.0")
}
```

You can also skip Maven and use `compileOnly(files("libs/EXeaEntityCore.jar"))`. Never shade EXea
into your plugin.

## 4. Use it

```java
EXeaAPI exea = EXeaAPI.getInstance();

EXeaEntity boss = exea.spawn("void_overseer", location,
        SpawnOptions.create().driver(EntityType.ZOMBIE).health(2000).displayName("§5Floor 3 Overseer"));
if (boss == null) { /* unknown model id or driver type */ }

boss.playAnimation("attack_slam", 1.5f, EXeaEntity.Loop.ONCE);   // null loop = as set in Blockbench
Location hand = boss.getBoneLocation("hand_r");                     // follows the current frame
boss.kill();     // death event + death animation + removal
boss.remove();   // gone at once
```

| `EXeaAPI` | |
|---|---|
| `spawn(id, location, options)` | spawns; `options` may be null. Returns `EXeaEntity` or null |
| `get(entity)` / `getAll()` | the EXea entity of a driver / all live ones |
| `getModelIds()` / `getAnimations(id)` | what is loaded |
| `registerBehavior(key, factory)` | behavior per model id (or per meta `behavior` name) |
| `registerDamageProvider(provider)` | compute damage for hits involving EXea entities |
| `reloadModels()` | same as the model part of `/exea reload` |

| `EXeaEntity` | |
|---|---|
| `getModelId()`, `getDriver()`, `getUniqueId()` | the driver's UUID is the entity's id |
| `isValid()`, `isDying()` | removed? in the death sequence? |
| `playAnimation(name[, speed, loop])`, `stopAnimation()`, `getCurrentAnimation()`, `getAnimations()` | animation names are lower case |
| `getBones()`, `getBoneLocation(bone)` | world position of a bone pivot |
| `getDamageByAttacker()` | UUID → HP removed by that attacker |
| `getBehavior()`, `kill()`, `remove()` | |

`SpawnOptions`: `driver`, `ai`, `health`, `attackDamage`, `movementSpeed`, `hitboxScale`, `followRange`,
`displayName`, `nameVisible`, `behavior`. Anything you leave unset comes from the meta file.

All calls must run on the main thread.

## 5. Behaviors (AI, skills)

The engine always handles drawing, the death sequence and the events. The behavior decides what the
entity does. Each entity gets its own behavior instance. `DefaultBehavior` plays `idle` / `move` by
movement and a random `attack_*` when the driver lands a melee hit. Extend it to change only part of
that:

```java
exea.registerBehavior("void_overseer", e -> new DefaultBehavior() {
    @Override
    protected String pickAttack(EXeaEntity entity, LivingEntity target) {
        return "attack_slam";
    }
});
```

`EXeaBehavior` hooks (all optional, main thread): `onSpawn`, `onTick` (each tick while alive),
`onDamaged(entity, attacker, damage)`, `onAttack(entity, target)`, `onAnimationEnd(entity, name)`
(ONCE animations), `onDeath(entity, killer)`, `onRemove(entity, reason)`. If a hook throws, the
exception is logged and the entity keeps running.

The behavior is chosen in this order: `SpawnOptions.behavior`, then the meta `behavior` key, then
the model id key, then `DefaultBehavior`.

## 6. Events

| event | when |
|---|---|
| `EXeaEntitySpawnEvent` | after a spawn (`getEntity()`, `getTemplateId()`) |
| `EXeaEntityDamageEvent` (cancellable) | a hit by or on an EXea entity. `getSkillId()` is `melee`, or the animation name for animation hits; `setDamage` changes it |
| `EXeaEntityDeathEvent` | once, when the death sequence starts. `getKiller()` is the last hit, a projectile counts for its shooter. `getDamageByAttacker()` |
| `EXeaEntityRemoveEvent` | once, when it is removed. `getReason()` is one of `DIED`, `REMOVED`, `DRIVER_LOST`, `PLUGIN_DISABLE` |
| `EXeaAnimationEventTriggerEvent` | an effect keyframe fired. `getEventTag()`, `getAnimationName()`, `getTriggerLocation()` |

EXea cancels the lethal hit so the driver stays alive for the death animation. The vanilla
`EntityDeathEvent` therefore does not fire for EXea entities. Listen to `EXeaEntityDeathEvent` instead.

## 7. Animation events from Blockbench

Use the **Effects** row of an animation. The exporter turns it into an `events` list in `_anims.json`:

- **Sound**: put a Java sound key in the effect field (`entity.wither.shoot`).
- **Particle**: put a particle key in the effect field (`flame`). The locator field takes a locator or
  bone name and sets where the particle appears; without one it appears at the entity.
- **Timeline**: one instruction per line.

```
hit radius=3.5 damage=20 cone=120 bone=hand_r knockback=0.5
sound entity.generic.explode volume=2 pitch=0.8 bone=core
particle explosion count=3 bone=hand_r
shockwave
```

`hit` damages living entities within `radius` blocks of the bone. With `cone`, only those inside that
angle in front of the body are hit. It does not hit the entity itself, other EXea entities, armor
stands, or players in creative or spectator. The damage goes through the normal damage events with
the driver as attacker and the animation name as skill id.

Every line, including `hit`, fires `EXeaAnimationEventTriggerEvent` with the line as its tag, so your
plugin can react to lines it defines itself (`shockwave` above).

## 8. Commands (`exea.admin`, op)

| command | |
|---|---|
| `/exea spawn <id> [ai\|noai]` | spawn at you; `noai` = statue |
| `/exea anim play <name>` / `stop` | nearest entity (20 blocks) |
| `/exea bone <bone>` | marks a bone's position with particles for 5 s |
| `/exea kill` | removes all EXea entities |
| `/exea list`, `/exea pack`, `/exea reload` | count, resend pack to you, reload models + pack |

## 9. Limits

- Each bone that has cubes is one entity. Updates are sent only for bones that move. Keep about 30 such bones for a mob and about 50 for a boss.
- Keyframes cannot use Molang (it reads as 0). Bezier plays as linear. Global rotation plays as local. Particles that need extra data (dust, block) cannot be set from keyframes or meta.
- The driver's hitbox is a box. The model can be bigger than its hitbox.
