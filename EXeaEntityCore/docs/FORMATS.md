# EXea data formats: effects and movesets

Status: **EXeaEntityCore 1.2.0**. Both parts are implemented: effects (§1) and movesets (§2). The runtime is built against this file. EXea Studio
reads and writes the same files and validates them with the JSON Schemas in [`../schemas/`](../schemas).

EXea 1.1 already reads three files per model: `<id>_bones.json`, `<id>_anims.json` and an optional
`<id>_meta.json` (see [API.md](API.md#2-add-a-model-no-rebuild)). This spec adds two kinds of files:

| file | where | what |
|---|---|---|
| `effects/<effect_id>.json` | jar `effects/`, then `plugins/EXeaEntityCore/effects/` (the data folder wins on the same id) | one **effect preset**: layers of particles, smoke, flying blocks, ground waves, warnings, sounds |
| `models/<id>_moveset.json` | next to the other model files | the **moveset** of one model: skills, when to use them, what each skill does over time, HP phases |

Both are loaded at startup and by `/exea reload`.

## 0. Shared rules

- **Units.**
  - Time is in **seconds**, floats allowed. The runtime rounds to ticks (0.05 s).
  - Distance is in **blocks**.
  - Angles are in **degrees**.
- **Local space.** Offsets are `[right, up, forward]` relative to where the entity's body faces. If there
  is no entity, the yaw given to the play call is used. So `[0, 1, 2]` means 1 block up and 2 blocks in front.
- **Ids.** Effect ids and skill ids are lower case `a-z 0-9 _ - /`. A slash makes a folder:
  `effects/fire/burst.json` has the id `fire/burst`.
- **`format`.** Every file starts with `"format": 1`. If a file has a higher format, EXea skips it and
  logs a warning. If a file has no `format`, EXea reads it as 1.
- **Unknown keys** are ignored, and each one logs a warning once per file. Studio keeps unknown keys when
  it saves, so a newer file round-trips through an older Studio.
- **Ranges.** A value written as `[min, max]` is rolled uniformly, once per particle, piece or projectile.
  A single number means the same value every time.
- **Colours** are `"#rrggbb"` or `"#aarrggbb"`.
- **Particles.** `particle` is any Paper particle key (`flame`, `minecraft:campfire_cosy_smoke`).
  Particles that need extra data read it from these fields:
  - `color` and `size` for `dust`.
  - `color`, `to_color` and `size` for `dust_color_transition`.
  - `block` for `block`, `falling_dust`, `dust_pillar` and `block_marker`.
  - `item` for `item`.
  - `color` for `entity_effect`.
- **Blocks.** A `block` is a block key (`stone`, `minecraft:deepslate`). It can also be one of:
  - `"ground"`: the real block under the anchor, with air skipped downward up to 4 blocks.
  - A list of block keys: one is picked at random for each piece.
  Effects never change the world. Blocks are drawn with `BlockDisplay`.
- **Checks the schemas can't express.** EXea and Studio also check these when a file loads. Each
  problem logs a warning that names the file and the JSON path, and the bad part is skipped:
  - Every effect id referenced by `preset`, `fx`, `trail`, `fx_in` and `fx_out` exists.
  - Every skill id referenced by `phases[].skills`, `on_enter`, `followup` and `preset_skill` exists.
  - Every `animation` and `bone` exists on the model.
  - `preset` nesting has no loops.

---

## 1. Effect presets

An effect is a named list of **layers** that run on their own timers. You play an effect *at* something:
a bone of an EXea entity, an entity, or a plain location. That place, plus the facing yaw, is the
effect's **origin**.

```json
{
  "format": 1,
  "id": "ground_slam",
  "name": "Ground slam",
  "description": "Rocks burst out of the ground with a dust ring.",
  "tags": ["impact", "ground"],
  "duration": 2.0,
  "layers": [
    { "type": "debris", "anchor": { "ground": true }, "count": 14, "block": "ground",
      "size": [0.25, 0.55], "speed": [0.35, 0.7], "angle": 70, "gravity": 0.08, "lifetime": [1.0, 1.6] },
    { "type": "particle", "anchor": { "ground": true }, "particle": "block", "block": "ground",
      "shape": { "type": "ring", "radius": 0.5, "radius_end": 4 }, "count": 40, "duration": 0.4 },
    { "type": "ground_wave", "start": 0.05, "radius": [1, 5], "duration": 0.5, "height": 0.6 },
    { "type": "sound", "sound": "entity.generic.explode", "volume": 1.5, "pitch": [0.7, 0.9] }
  ]
}
```

| key | default | meaning |
|---|---|---|
| `id` | from the file path | must match the path if given |
| `name`, `description`, `tags` | — | shown in Studio and `/exea fx list`. Not used by the runtime |
| `duration` | end of the last layer | the effect is removed after this time, however its layers are set |
| `loop` | `false` | restart after `duration` until stopped. Used for auras. A looping effect needs `follow` or an explicit stop |
| `layers` | required | see below |

### 1.1 Keys every layer has

| key | default | meaning |
|---|---|---|
| `type` | required | `particle`, `debris`, `ground_wave`, `decal`, `telegraph`, `model`, `sound`, `preset`, or a type registered by a plugin |
| `start` | 0 | seconds after the effect starts |
| `duration` | 0 | 0 = one burst at `start`. More than 0 = the layer runs for that long and repeats every `interval` |
| `interval` | 0.05 | seconds between repeats while `duration` runs |
| `anchor` | origin | where the layer happens, see below |
| `chance` | 1 | 0–1, rolled once when the layer starts |
| `when` | — | optional tag. The layer runs only when the play call passes this tag in its context. Example: `"phase2"` |

`anchor`:

| key | default | meaning |
|---|---|---|
| `offset` | `[0,0,0]` | `[right, up, forward]` from the origin |
| `ground` | `false` | after the offset, snap down to the top of the first solid block, searching down up to 8 blocks |
| `follow` | `false` | track the origin bone or entity every tick while the layer runs. A dash trail sets this to `true` |
| `at` | `"origin"` | `"origin"` or `"target"`. `"target"` uses the target in the play context, such as a skill's target, and falls back to the origin |
| `yaw` | 0 | extra yaw in degrees added to the facing, for shapes and directions |

All layers scale with the play call's `scale`. It multiplies sizes, radii, distances and speeds, but not
counts or times.

### 1.2 `particle`

Spawns particles in a shape. With `duration` this covers fog, smoke columns, auras and trails.

| key | default | meaning |
|---|---|---|
| `particle` | required | particle key (+ `color`, `to_color`, `size`, `block`, `item` when it needs them) |
| `count` | 10 | particles per burst (per repeat) |
| `shape` | point | see the shape table |
| `motion` | `"none"` | `none`, `outward` (away from the shape centre), `up`, `forward`, `inward`, `random` |
| `speed` | 0 | speed along `motion` (blocks/tick, as Minecraft particles use it) |
| `spread` | 0 | random jitter in blocks added to each position |
| `force` | `false` | send to players up to 512 blocks away (`force` particles). Otherwise the client limit of 32 blocks applies |

**Shapes** (also used by `telegraph` and by skill `hit` areas):

| `shape.type` | keys | |
|---|---|---|
| `point` | — | the anchor |
| `sphere` | `radius`, `hollow` | in or on a sphere |
| `circle` | `radius` | filled flat disc on the ground plane |
| `ring` | `radius`, `width` (0.2) | flat ring outline |
| `cone` | `radius`, `angle` | flat sector in front, `angle` = full opening |
| `line` | `length`, `width` (0), `to` | from the anchor forward. With `to: "target"`, the line runs to the target and becomes a beam |
| `box` | `size` `[x,y,z]` | box centred on the anchor, rotated with the facing |
| `column` | `radius`, `height` | cylinder going up |
| `spiral` | `radius`, `height`, `turns`, `arms` (1) | helix going up |

`radius`, `length` and `height` can have an `_end` variant (`radius_end`, `length_end`, `height_end`). The
value then goes from the start value to the end value over the layer's `duration`. This makes an
expanding ring or a growing cloud.

### 1.3 `debris`: flying blocks

Small block pieces thrown out of the anchor. They fall with gravity, rest on the ground, and then shrink
away.

| key | default | meaning |
|---|---|---|
| `block` | `"ground"` | block key, list, or `"ground"` |
| `count` | 8 | pieces |
| `size` | `[0.2, 0.45]` | cube edge in blocks |
| `radius` | 0.5 | pieces start spread over a disc of this radius |
| `speed` | `[0.3, 0.6]` | launch speed in blocks/tick |
| `angle` | 60 | launch angle up from the ground plane, randomised ±15. 90 = straight up |
| `direction` | `"outward"` | `outward`, `forward` (a cone of `cone` degrees around the facing), or `up` |
| `cone` | 60 | used with `direction: "forward"` |
| `gravity` | 0.08 | blocks/tick² |
| `bounce` | 0.3 | speed kept when a piece hits the ground. 0 = it stops |
| `spin` | 20 | max degrees per tick |
| `lifetime` | `[1.0, 1.5]` | seconds before it shrinks away (shrinking takes 0.25 s) |
| `glow` | — | optional colour, makes the pieces glow |

Pieces collide with solid blocks only. They do not hit entities. Pieces are client-side movement over
interpolated displays, so they cost one entity each. See the budget in §1.10.

### 1.4 `ground_wave`

A ring of ground blocks pops up and settles as it travels outward, like a shockwave through the floor.

| key | default | meaning |
|---|---|---|
| `radius` | `[1, 5]` | start and end radius. The wave travels between them over `duration` |
| `duration` | 0.5 | required more than 0 |
| `height` | 0.5 | how high a block pops |
| `block` | `"ground"` | |
| `density` | 1.0 | blocks per block of circumference |
| `angle` | 360 | limit the wave to a sector in front (a cone wave) |

### 1.5 `decal`: marks on the ground

Flat thin block plates that stay on the ground for a while, such as cracks, scorch marks or a magic
circle.

| key | default | meaning |
|---|---|---|
| `block` | `"black_concrete"` | |
| `pattern` | `"crack"` | `crack` (random branches), `scorch` (filled blob), `circle` (ring of plates) |
| `radius` | 2 | |
| `pieces` | 12 | |
| `lifetime` | 4 | seconds, then the plates shrink away |
| `glow` | — | colour |

### 1.6 `telegraph`: warning area

Shows where a hit will land. It fills from the centre (or from the base, for a line or cone) over
`duration`. The area is full at the moment the hit happens.

| key | default | meaning |
|---|---|---|
| `shape` | required | `circle`, `ring`, `cone`, `line`, `box` (same keys as §1.2) |
| `duration` | required | warning time |
| `style` | `"particles"` | `particles` (dust outline + fill) or `plates` (glowing thin block plates, more visible, more entities) |
| `color` | `"#ff3030"` | |
| `fill` | `true` | show the growing fill. `false` shows the outline only |

### 1.7 `model`

An `ItemDisplay` with any item model. Use it for slash arcs, magic circles or a spinning weapon. The
model can come from the EXea pack.

| key | default | meaning |
|---|---|---|
| `item_model` | required | e.g. `exea:fx/slash_arc` |
| `lifetime` | 0.5 | |
| `scale` | 1 | number, `[x,y,z]`, or `{ "from": …, "to": … }` over the lifetime |
| `rotation` | `[0,0,0]` | pitch/yaw/roll in local space. Can also be `{from, to}` |
| `billboard` | `"fixed"` | `fixed`, `vertical`, `horizontal`, `center` |
| `glow` | `false` | full brightness |

### 1.8 `sound`

| key | default | meaning |
|---|---|---|
| `sound` | required | sound key |
| `volume` | 1 | |
| `pitch` | 1 | number or `[min, max]` |
| `category` | `"hostile"` | Bukkit `SoundCategory` name, lower case |

### 1.9 `preset`

Plays another effect as a layer. Its `start`, `anchor` and `scale` (default 1, multiplied) apply to
the child. Loops in the nesting are rejected when the files load.

```json
{ "type": "preset", "id": "impact_spark", "start": 0.1, "scale": 0.5 }
```

### 1.10 Budget and cleanup

These are set in `config.yml` under `effects:`. The runtime enforces them, not the files:
- `max_entities_per_effect` (default 48): debris + wave + decal + plate + model entities alive at once for one effect.
- `max_entities` (default 600): all effects on the server.
- `max_particles_per_tick` (default 4000): bursts over the limit are thinned evenly.
- `view_distance` (default 48): players further away get no particles.

When a limit is hit, the layer spawns fewer pieces. It never fails. Every effect entity is removed when
its effect ends, when its chunk unloads, and when the plugin disables. Effect entities are not saved to
the world.

### 1.11 Playing an effect

- **Animation keyframe** (Blockbench Timeline effect), one line:
  ```
  fx ground_slam bone=hand_r scale=1.5 follow=false
  ```
  The arguments are `bone`, `scale`, `follow` and `yaw`. The line also fires
  `EXeaAnimationEventTriggerEvent`, like every timeline line.
- **Command:** `/exea fx <id> [scale]` plays it at your position. `/exea fx list` lists the loaded ids.
  `/exea fx stop` stops looping effects near you.
- **Moveset:** the `fx` action, §2.4.
- **API (Java):**
  ```java
  EffectHandle h = EXeaAPI.getInstance().playEffect("ground_slam", location,
          EffectOptions.create().scale(1.5).yaw(player.getYaw()).target(player));
  entity.playEffect("dash_trail", "hips");   // on a bone of an EXea entity, follows it
  h.stop();
  EXeaAPI.getInstance().registerEffectLayer("my_layer", (ctx, json) -> { ... });   // new layer type
  ```

### 1.12 Built-in presets (shipped in the jar)

| id | what |
|---|---|
| `fog` | low, slow, wide cloud that lingers (loop) |
| `smoke_puff` | short burst of smoke |
| `smoke_trail` | smoke that follows the origin (loop, `follow`) |
| `dash_trail` | streak + dust behind a moving bone |
| `ground_slam` | flying rocks + dust ring + ground wave + explosion sound |
| `shockwave` | expanding ring of particles at ground level |
| `impact_spark` | small crit / spark hit flash |
| `fire_burst` | flame sphere burst + smoke |
| `void_burst` | dark purple implosion then burst |
| `beam` | line to the target, for a channelled laser |
| `aura_loop` | slow spiral around the body (loop) |
| `telegraph_circle`, `telegraph_cone`, `telegraph_line` | warning areas, sized by `scale` or by the skill |
| `summon_circle` | ground decal circle + rising particles |
| `projectile_trail` | trail used by projectiles that set no trail |

---

## 2. Movesets

A moveset gives a model **skills**: named attacks with an animation, conditions, a cooldown and
**actions** at set times. It can also give **phases**, which are HP stages that change which skills
are used. The file is `models/<model_id>_moveset.json`. EXea finds it by name. The meta key
`"moveset": "<other_id>"` lets several models share one moveset.

```json
{
  "format": 1,
  "ai": { "mode": "auto", "global_cooldown": 0.8, "chase_range": 24, "keep_distance": 0 },
  "phases": [
    { "id": "one", "hp_above": 0.5, "skills": ["slash", "slam", "orb"] },
    { "id": "two", "hp_above": 0, "skills": ["slash", "slam", "orb", "leap_slam"],
      "speed": 1.2, "on_enter": "roar" }
  ],
  "skills": {
    "slash": {
      "animation": "attack_slash", "cooldown": 3, "weight": 10,
      "conditions": { "range": [0, 4] },
      "actions": [
        { "at": 0.35, "type": "hit", "shape": { "type": "cone", "radius": 4, "angle": 120 },
          "damage": 12, "knockback": 0.6 },
        { "at": 0.35, "type": "fx", "id": "impact_spark", "bone": "hand_r" }
      ]
    },
    "slam": {
      "animation": "attack_slam", "cooldown": 8, "weight": 6,
      "conditions": { "range": [0, 6] }, "root": true,
      "actions": [
        { "at": 0.0, "type": "telegraph", "shape": { "type": "circle", "radius": 5 }, "duration": 0.9 },
        { "at": 0.9, "type": "hit", "shape": { "type": "circle", "radius": 5 }, "damage": 20, "knockup": 0.5 },
        { "at": 0.9, "type": "fx", "id": "ground_slam", "anchor": { "ground": true, "offset": [0, 0, 1.5] } }
      ]
    },
    "orb": {
      "animation": "attack_singularity", "cooldown": 6,
      "conditions": { "range": [6, 20], "line_of_sight": true },
      "actions": [
        { "at": 0.6, "type": "projectile", "bone": "core", "speed": 0.6, "homing": 0.15, "lifetime": 4,
          "radius": 0.6, "trail": "projectile_trail",
          "on_hit": [ { "type": "hit", "shape": { "type": "sphere", "radius": 2.5 }, "damage": 14 },
                      { "type": "fx", "id": "void_burst" } ] }
      ]
    },
    "leap_slam": {
      "animation": "attack_slam", "cooldown": 12, "conditions": { "range": [6, 16] },
      "actions": [
        { "at": 0.1, "type": "move", "mode": "leap", "to": "target", "height": 4, "duration": 0.7 },
        { "at": 0.85, "type": "preset_skill", "skill": "slam", "from": 0.9 }
      ]
    },
    "roar": {
      "animation": "roar", "internal": true,
      "actions": [ { "at": 0.2, "type": "fx", "id": "shockwave", "scale": 2 },
                   { "at": 0.2, "type": "self", "invulnerable": 1.5 } ]
    }
  }
}
```

### 2.1 `ai`

| key | default | meaning |
|---|---|---|
| `mode` | `"auto"` | `auto`: EXea picks and casts skills. `manual`: skills run only when a plugin calls `castSkill`. `SpawnOptions.autoSkills(false)` forces `manual` for one entity |
| `think_interval` | 0.25 | how often the AI looks for a skill |
| `global_cooldown` | 1.0 | minimum gap between the end of one skill and the start of the next |
| `target` | `"current"` | default target selection, see §2.3 `target` |
| `chase_range` | `follow_range` from meta | targets further than this are dropped |
| `keep_distance` | 0 | blocks. More than 0 makes the AI back away when a target comes closer. Use it for casters |
| `idle_animation`, `move_animation` | `idle`, `move` | played between skills, as `DefaultBehavior` does |
| `melee` | `true` | the driver's vanilla melee still hits when no skill is ready. `false` = damage only from skills |

### 2.2 `phases`

The first phase in the list whose `hp_above` the current HP fraction is greater than is the active one.
List phases from high HP to low. If there are no phases, every non-`internal` skill can be used.

| key | default | meaning |
|---|---|---|
| `id` | required | |
| `hp_above` | 0 | 0–1 |
| `skills` | all | skill ids usable in this phase |
| `speed` | 1 | multiplies movement speed and skill animation speed |
| `on_enter` | — | skill id cast at once when the phase starts. It ignores cooldowns and conditions and can't be cancelled by the AI |
| `invulnerable_on_enter` | 0 | seconds of no damage when the phase starts |

Phases only go forward. Healing back above a threshold does not return to an earlier phase.

### 2.3 Skill

| key | default | meaning |
|---|---|---|
| `name` | from the id | display name, sent in events |
| `animation` | — | animation to play. A skill without one is instant (actions only) |
| `speed` | 1 | animation and action-time speed |
| `duration` | animation length | when the skill ends |
| `cooldown` | 0 | seconds, per entity |
| `weight` | 1 | relative chance among the skills that pass their conditions |
| `priority` | 0 | skills with a higher priority that pass are always chosen before lower ones |
| `internal` | `false` | never chosen by the AI. It can still be cast by `on_enter`, `preset_skill`, `followup` or the API |
| `conditions` | — | see below |
| `target` | from `ai.target` | `current` (the driver's target), `nearest`, `farthest`, `random`, `lowest_hp` (players within `chase_range`) |
| `face` | `"start"` | `start`: turn to the target once. `track`: keep turning during the skill. `none` |
| `root` | `false` | the driver does not move during the skill |
| `interruptible` | `false` | when `true`, being hit by more than `interrupt_damage` cancels the skill |
| `interrupt_damage` | 0 | |
| `actions` | `[]` | see §2.4 |
| `followup` | — | `{ "skill": "slash_2", "chance": 0.5 }` is cast right after this skill ends, ignoring the global cooldown |

`conditions` (all must pass):

| key | meaning |
|---|---|
| `range` | `[min, max]` distance to the target. Required for the skill to be picked when there is a target |
| `hp` | `[min, max]` own HP fraction |
| `targets_min` | at least this many players within `targets_radius` (default 6) |
| `line_of_sight` | the target must be visible |
| `chance` | 0–1 extra roll |
| `target_airborne` | `true` / `false` |

### 2.4 Actions

Every action has these keys:

| key | default | meaning |
|---|---|---|
| `at` | 0 | seconds into the skill, multiplied by 1/`speed` |
| `type` | required | see below, or a type registered by a plugin |
| `repeat` | — | `{ "count": 3, "interval": 0.2 }` runs the action `count` times |
| `bone` | — | bone to use as the origin. Without it, the origin is the driver's feet + `anchor` |
| `anchor` | — | same keys as §1.1 (`offset`, `ground`, `at: "target"`, `yaw`) |

| `type` | keys | what |
|---|---|---|
| `hit` | `shape`, `damage`, `knockback`, `knockup`, `max_targets`, `potions`, `tag`, `once`, `height` | Damages living entities in the shape. It never hits itself, other EXea entities, armor stands, or creative/spectator players. Flat shapes count bodies within `height` blocks (default 3) above or below the place. The damage goes through `EXeaEntityDamageEvent` with skill id `<skill>`, or `<skill>:<tag>` when `tag` is set. `potions`: `[{ "type": "slowness", "seconds": 2, "amplifier": 1 }]`. `once: true` with `repeat` hits each entity only once per cast |
| `telegraph` | `shape`, `duration`, `color`, `style` | plays a telegraph layer (§1.6) with this shape |
| `fx` | `id`, `scale`, `follow` | plays an effect preset |
| `sound` | `sound`, `volume`, `pitch` | |
| `projectile` | see §2.5 | |
| `move` | `mode`, … see §2.6 | moves the driver or the targets |
| `summon` | `model`, `count`, `radius`, `max_alive`, `options` | spawns other EXea models around. `options` = `SpawnOptions` keys. Summons die when the summoner is removed if `bound: true` |
| `self` | `heal` (fraction), `invulnerable` (s), `speed` (multiplier, `seconds`) | effects on the caster |
| `animation` | `name`, `speed` | plays another animation now (the skill clock keeps running) |
| `preset_skill` | `skill`, `from` | runs the actions of another skill from time `from`, without its animation or cooldown |
| `event` | `tag` | fires `EXeaSkillEvent` with the tag. Use it for logic that lives in your plugin |

### 2.5 `projectile`

| key | default | meaning |
|---|---|---|
| `speed` | 0.8 | blocks/tick |
| `gravity` | 0 | blocks/tick² |
| `lifetime` | 3 | seconds |
| `radius` | 0.5 | hit radius |
| `aim` | `"target"` | `target`, `forward`, `target_ground` (lands where the target stands, arcs with `gravity`) |
| `homing` | 0 | 0–1 turn strength per tick toward the target |
| `count`, `spread` | 1, 0 | several projectiles fanned over `spread` degrees |
| `pierce` | 0 | entities passed through before it stops |
| `item_model` | — | draws the projectile as an `ItemDisplay`. Without it, the projectile is only its trail |
| `trail` | `"projectile_trail"` | effect id played while flying (follows) |
| `on_hit` | `[]` | actions run where it hits an entity or a block (`at` ignored). `hit` shapes are centred on the impact |
| `damage` | 0 | direct damage to the entity hit, before `on_hit` |

### 2.6 `move`

| `mode` | keys | what |
|---|---|---|
| `dash` | `distance`, `duration`, `direction` (`forward`/`target`/`back`), `through` (false) | slides the driver. It stops at walls unless `through` |
| `leap` | `to` (`target`/`forward`), `distance`, `height`, `duration` | arcs the driver onto the target's position at cast time |
| `teleport` | `to` (`behind_target`/`target`/`random`), `distance`, `fx_out`, `fx_in` | instant move with optional effects |
| `pull` | `radius`, `strength`, `shape` | pulls targets in the shape toward the caster |
| `push` | `radius`, `strength`, `shape` | pushes them away |

### 2.7 Using movesets from a plugin

```java
EXeaEntity boss = exea.spawn("void_overseer", loc, SpawnOptions.create().autoSkills(false));
boss.castSkill("slam", target);            // false if unknown, on cooldown, or another skill is running
boss.getSkills();                          // ids
boss.getCooldown("slam");                  // seconds left
boss.getPhase();                           // current phase id or null
boss.cancelSkill();

exea.registerSkillAction("chain_lightning", (ctx, json) -> { ... });   // new action type
```

| event | when |
|---|---|
| `EXeaSkillCastEvent` (cancellable) | before a skill starts. `getSkillId()`, `getTarget()`, `isAutomatic()` |
| `EXeaSkillEndEvent` | when it ends. `getReason()`: `FINISHED`, `CANCELLED`, `INTERRUPTED`, `DIED` |
| `EXeaSkillEvent` | an `event` action. `getTag()` |
| `EXeaPhaseChangeEvent` | a phase starts. `getFrom()`, `getTo()` |

Damage from `hit` and `projectile` actions also fires `EXeaEntityDamageEvent`, with `getSkillId()` =
the skill id.

Animation keyframe events (`events` in `_anims.json`) still fire when a skill plays that animation.
Don't put the same `hit` in both places, or it lands twice.

### 2.8 Behavior choice

1. `SpawnOptions.behavior`
2. meta `behavior`
3. a behavior registered under the model id
4. **`MovesetBehavior` if the model has a moveset**
5. `DefaultBehavior`

`MovesetBehavior` is a public class. Extend it to change how it picks skills (`pickSkill`) and keep the
rest.
