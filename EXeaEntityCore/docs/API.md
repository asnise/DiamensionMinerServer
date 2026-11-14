# EXeaEntityCore — developer guide

EXeaEntityCore is a Paper plugin that puts animated Blockbench models in the game. Each model is a
tree of `ItemDisplay` bones riding an invisible driver mob. The driver gives the model its hitbox,
health, AI and movement. The plugin does not depend on any other plugin. Other plugins use it through
the API in `com.exea.entity.api`.

Thai step-by-step guide with code recipes: [`API_GUIDE_TH.md`](API_GUIDE_TH.md)
(all snippets compiled in [`examples/GuideExamples.java`](examples/GuideExamples.java)).

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
node tools/exporter/exea_exporter.js "MyBoss.bbmodel" my_boss --server /path/to/server/plugins/EXeaEntityCore
```

This writes `models/my_boss_*.json` and `pack/assets/exea/...` into that folder. In game, run
`/exea reload` to load the models, rebuild the pack and resend it to online players, then
`/exea spawn my_boss`. The rules for models (bone budget, the 24 px mesh limit, what animation features
work) are in [`MODELING.md`](MODELING.md).

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
fx ground_slam bone=hand_r scale=1.5
shockwave
```

`fx <id>` plays an effect preset (§8) at the bone, or at the driver without `bone`. It takes `scale`,
`follow=false` (stay where it started) and `yaw` (extra turn). The driver's current target becomes the
effect's target, which beams use.

`hit` damages living entities within `radius` blocks of the bone. With `cone`, only those inside that
angle in front of the body are hit. It does not hit the entity itself, other EXea entities, armor
stands, or players in creative or spectator. The damage goes through the normal damage events with
the driver as attacker and the animation name as skill id.

Every line, including `hit`, fires `EXeaAnimationEventTriggerEvent` with the line as its tag, so your
plugin can react to lines it defines itself (`shockwave` above).

## 8. Effect presets

An effect preset is a JSON file of layers:
- particles in shapes (fog, smoke, auras, beams)
- flying blocks taken from the ground
- ground waves
- cracks and decals
- warning areas
- item-model displays
- sounds
- other presets

The format is in [`FORMATS.md`](FORMATS.md#1-effect-presets). The jar ships 16 presets: `fog`, `smoke_puff`,
`smoke_trail`, `dash_trail`, `ground_slam`, `shockwave`, `impact_spark`, `fire_burst`, `void_burst`,
`beam`, `aura_loop`, `telegraph_circle`, `telegraph_cone`, `telegraph_line`, `summon_circle` and
`projectile_trail`. Your own presets go in `plugins/EXeaEntityCore/effects/<id>.json`. Sub-folders make ids
like `fire/burst`, and a file with the same id as a bundled preset replaces it. `/exea reload` loads
them again, and any problem is logged with its JSON path.

To play one:
- **From an animation:** add a Timeline line `fx ground_slam bone=hand_r scale=1.5`.
- **In game:** `/exea fx ground_slam 1.5` plays it 2 blocks in front of you.
- **From code:**

```java
EffectHandle h = exea.playEffect("ground_slam", location, EffectOptions.create().scale(1.5).yaw(player.getYaw()));
boss.playEffect("aura_loop", null);                       // on the driver, follows it
boss.playEffect("smoke_trail", "hand_r");                 // on a bone, follows the animation
exea.playEffect("beam", boss.getDriver(), EffectOptions.create().target(player));
h.stop();                                                 // looping effects run until stopped

exea.registerEffectLayer("lightning", ctx ->              // a new layer type: {"type": "lightning", ...}
        ctx.getLocation().getWorld().strikeLightningEffect(ctx.getLocation()));
```

Effects never change the world. Flying blocks and plates are non-persistent display entities, and they
are removed when the effect ends or the plugin stops. `config.yml` `effects:` sets the budget
(entities per effect and in total, particles per tick, view distance). When a limit is reached, effects
spawn less instead of failing.

## 9. Movesets (skills, AI, phases)

`models/<id>_moveset.json` gives a model skills. Each skill has:
- an animation
- conditions: range, HP, number of players, line of sight
- a cooldown, a weight and a priority
- actions at set times:
  - `hit` in a circle, cone, line, box or ring
  - `telegraph`
  - `fx`, `sound`
  - `projectile`: homing, fan, pierce, `on_hit`
  - `move`: dash, leap, teleport behind, pull, push
  - `summon`, `self` (heal, invulnerable, speed)
  - `animation`, `preset_skill`, `event`

HP **phases** change the skill pool and speed, and can cast an entry skill. The format is in
[`FORMATS.md`](FORMATS.md#2-movesets). A complete example is
[`examples/void_overseer_moveset.json`](examples/void_overseer_moveset.json).

A model with a moveset gets `MovesetBehavior`. Every `think_interval` it picks a target and a ready
skill whose conditions pass, and casts it. Between skills it plays idle and move, and keeps
`keep_distance`. To let your plugin decide instead, set `"ai": {"mode": "manual"}`,
`SpawnOptions.autoSkills(false)` or `entity.setAutoSkills(false)`:

```java
EXeaEntity boss = exea.spawn("void_overseer", loc, SpawnOptions.create().autoSkills(false));
boss.castSkill("slam", player);              // false: unknown, on cooldown, another skill running, or cancelled
boss.getSkills(); boss.getCooldown("slam"); boss.setCooldown("slam", 0);
boss.getCurrentSkill(); boss.cancelSkill(); boss.getPhase();

exea.registerSkillAction("chain_lightning", ctx -> { /* ctx.getCaster(), getTarget(), getLocation(), getJson() */ });
```

| event | when |
|---|---|
| `EXeaSkillCastEvent` (cancellable) | before a skill starts. `getSkillId()`, `getTarget()`, `isAutomatic()` |
| `EXeaSkillEndEvent` | it ended: `FINISHED`, `CANCELLED`, `INTERRUPTED`, `DIED` |
| `EXeaSkillEvent` | an `event` action fired. `getTag()`, `getLocation()` |
| `EXeaPhaseChangeEvent` | a new HP phase. `getFrom()`, `getTo()` |

Skill damage goes through `EXeaEntityDamageEvent`, with `getSkillId()` = the skill id, or `<skill>:<tag>`
when the hit has a `tag`. Extend `MovesetBehavior` and override `pickSkill` to choose skills your way.
To share one moveset between models, put `"moveset": "<other id>"` in their meta.

## 10. Live link (EXea Studio ↔ a running server)

The live link lets EXea Studio upload a model, its moveset and its effects to a running server, reload,
and run test commands: spawn, cast a skill, play an effect, play an animation. It runs on the same HTTP
port as the model pack. It is **off by default**:

```yaml
live_link:
  enabled: true
  token: "a-long-random-secret-16+"   # every request needs it; treat it like a password
  allow_remote: false                 # true = accept tools on other machines (open the port, keep the token secret)
  max_upload_mb: 16
```

| request | does |
|---|---|
| `GET /api/status` | version, models, effects, movesets, online players, live entities |
| `PUT /api/file?path=…` | writes one file into `plugins/EXeaEntityCore`. Only these paths are accepted: `models/<id>_{bones,anims,meta,moveset}.json`, `effects/**.json`, and the model's `pack/assets/exea/...` files |
| `POST /api/clear?model=<id>` | removes that model's folders from the data-folder pack before an upload |
| `POST /api/reload` | same as `/exea reload`, and the pack is resent |
| `POST /api/spawn` `{model, player?, ai?}` | spawns it 3 blocks in front of the player |
| `POST /api/skill` `{skill, model?, player?}` | the nearest entity with that skill casts it at the player |
| `POST /api/fx` `{id, player?, scale?}` | plays an effect in front of the player |
| `POST /api/anim` `{name, model?, player?}` / `POST /api/kill` `{model?}` | plays an animation / removes live entities |

Send the header `Authorization: Bearer <token>`. Without `allow_remote`, only requests from the server
machine itself are answered. Each command is logged to the console as `[Live] ...`.

## 11. Commands (`exea.admin`, op)

| command | |
|---|---|
| `/exea spawn <id> [ai\|noai]` | spawn at you; `noai` = statue |
| `/exea anim play <name>` / `stop` | nearest entity (20 blocks) |
| `/exea bone <bone>` | marks a bone's position with particles for 5 s |
| `/exea fx <id> [scale]` / `fx list` / `fx stop` | play an effect preset in front of you, list them, stop the ones near you |
| `/exea skill <id>` / `skill list` / `auto` / `manual` / `cancel` | cast a skill of the nearest entity at you, list skills and cooldowns, switch its AI, stop the running skill |
| `/exea stats` | work per second since the last call (bone updates, carrier moves, poses, hidden ticks) |
| `/exea kill` | removes all EXea entities |
| `/exea list`, `/exea pack`, `/exea reload` | count, resend pack to you, reload models + pack |

## 12. Limits

- Each bone that has cubes is one entity. Updates are sent only for bones that move. Keep about 30 such bones for a mob and about 50 for a boss.
- Cost per tick: all bones ride one invisible carrier display, so a moving model is one teleport. Turning goes into the bone transforms. While no client tracks a model, its pose is not computed or sent (the animation clock and events still run).
- Keyframes cannot use Molang (it reads as 0). Bezier plays as linear. Global rotation plays as local. Particles that need extra data (dust, block) cannot be set from keyframes or meta.
- The driver's hitbox is a box. The model can be bigger than its hitbox.
