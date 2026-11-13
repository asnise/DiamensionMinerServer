# EXeaEntityCore

**Animated Blockbench models for Paper servers, with no client mods and no extra plugins.**

EXeaEntityCore draws a Blockbench model in the game as a tree of `ItemDisplay` bones that ride an
invisible "driver" mob. The driver provides the hitbox, health, AI and movement. EXea draws the model
and plays its animations. Players get the model's resource pack automatically when they join.

Other plugins use EXea through a small Java API. They can spawn a model, play animations, find where
a bone (a hand, a weapon tip) is, react to deaths, and write their own AI.

| | |
|---|---|
| Server | Paper **26.2**, Java **25** |
| Client | Vanilla Java Edition with 1.21.11+ item models (the pack is sent automatically) |
| Dependencies | None at runtime (JOML ships with Paper) |
| Version | 1.1.0 · see [CHANGELOG](CHANGELOG.md) |

---

## Features

- **Blockbench → game, 1:1.** Euler order, Catmull-Rom/linear/step keyframes, nested bones, bone
  scale, multi-axis cube rotation and meshes larger than the vanilla 48 px box all match what
  Blockbench shows.
- **Any mob as the driver.** Husk, zombie, iron golem, or an armor stand for a statue. Health,
  damage, speed, follow range and hitbox size are set per model or per spawn.
- **Cheap to run.**
  - All bones ride one carrier, so moving a model costs one teleport.
  - Turning goes into the bone transforms, not teleports.
  - Only bones that changed are sent.
  - When no player is near, poses are not computed at all.
  - `/exea stats` shows the numbers.
- **Effect keyframes.** The Blockbench *Effects* row plays sounds and particles at a bone, and deals
  area damage (`hit radius=3 damage=20 cone=120 bone=hand_r`). Any other line in a timeline script
  becomes an event your plugin can listen for.
- **Lifecycle events.** Spawn, damage (cancellable, with a skill id), death (once, with the killer
  and damage per attacker) and remove (with a reason).
- **Behaviors.** Each entity gets its own AI object. Extend `DefaultBehavior` to change only part of
  it, or write one from scratch.
- **Built-in pack server.** EXea serves its own resource pack and stacks it on top of other server
  packs with `addResourcePack`.
- **Hot reload.** Export a new model into the plugin folder and run `/exea reload`. There is no jar
  rebuild and no restart.

## Installation

1. Download `EXeaEntityCore.jar` (or [build it](#building)), put it in `plugins/`, and start the
   server once.
2. If players connect from other machines, set a reachable pack URL in
   `plugins/EXeaEntityCore/config.yml`:

   ```yaml
   resource_pack:
     enabled: true
     port: 8086                                           # EXea's own HTTP server
     public_url: "http://play.example.com:8086/exea.zip"  # empty = http://localhost:<port>/exea.zip
     required: false
     send_delay_ticks: 40
   ```

3. Open the port (TCP 8086 by default) in your firewall, then restart.
4. In game: `/exea spawn void_overseer`. The bundled sample model should walk toward you and attack.

## Adding your own model

Model in Blockbench using the *Generic Model* format. The model faces −Z (north) and its feet are at
y = 0. Name the animations `idle`, `move`, `attack_*` and `death` for the default behavior. Then
export the model with Node.js 18+:

```bash
node tools/exea_exporter.js "MyBoss.bbmodel" my_boss --server /path/to/server/plugins/EXeaEntityCore
```

Run `/exea reload` in game, then `/exea spawn my_boss`.

An optional `my_boss_meta.json` next to the `.bbmodel` sets the name, health, damage, driver type,
glowing bones, sounds and particles. The full key list is in [docs/API.md](docs/API.md#2-add-a-model-no-rebuild).

There is also a Blockbench plugin that exports from the File menu:
[`tools/blockbench_plugin/exea_entity_exporter.js`](tools/blockbench_plugin/exea_entity_exporter.js).
Load it with *File → Plugins → Load Plugin from File*.

Model rules (bone budget, the mesh size limit, unsupported animation features):
[docs/MODELING.md](docs/MODELING.md).

## Using the API

`plugin.yml`:

```yaml
depend: [EXeaEntityCore]   # or softdepend, and check that it is enabled
```

`build.gradle.kts`:

```kotlin
dependencies {
    compileOnly(files("libs/EXeaEntityCore.jar"))
    // or, after `gradlew publishToMavenLocal` in this project:
    // compileOnly("com.exea:exea-entity-core-api:1.1.0")   with repositories { mavenLocal() }
}
```

Never shade EXea into your plugin.

```java
EXeaAPI exea = EXeaAPI.getInstance();

EXeaEntity boss = exea.spawn("void_overseer", location,
        SpawnOptions.create().driver(EntityType.ZOMBIE).health(2000).displayName("§5Floor 3 Overseer"));

boss.playAnimation("attack_slam", 1.5f, EXeaEntity.Loop.ONCE);
Location hand = boss.getBoneLocation("hand_r");   // follows the current animation frame
```

```java
@EventHandler
public void onDeath(EXeaEntityDeathEvent event) {
    Map<UUID, Double> damage = event.getDamageByAttacker();   // reward the top damage dealers
}
```

The full reference is in [docs/API.md](docs/API.md). A step-by-step guide in Thai with recipes for
skills, events and behaviors is in [docs/API_GUIDE_TH.md](docs/API_GUIDE_TH.md). Every snippet in the
guide compiles; they are collected in [docs/examples/GuideExamples.java](docs/examples/GuideExamples.java).

## Commands and permissions

All commands need `exea.admin` (default: op). `/ex` is an alias.

| Command | |
|---|---|
| `/exea spawn <id> [ai\|noai]` | Spawn a model at you (`noai` = statue) |
| `/exea anim play <name>` / `stop` | Control the nearest model (20 blocks) |
| `/exea bone <bone>` | Mark a bone's position with particles for 5 s |
| `/exea stats` | Work per second: bone updates, carrier moves, poses, hidden ticks |
| `/exea list` | Live models |
| `/exea kill` | Remove all models |
| `/exea pack` | Send the model pack to you again |
| `/exea reload` | Reload models, rebuild the pack, resend it to online players |

## Building

You need JDK 25. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew jar                    # → build/libs/EXeaEntityCore.jar
./gradlew jar -PoutDir=<folder>  # straight into a server's plugins folder
./gradlew publishToMavenLocal    # API-only jar: com.exea:exea-entity-core-api
```

If a folder `../server/plugins` exists next to the project, `jar` writes there by default.

## Project layout

```
src/main/java/com/exea/entity/
  api/            public API: EXeaAPI, EXeaEntity, SpawnOptions, behavior/, event/
  core/           engine (not API): model loading, animation, rendering, pack server, commands
src/main/resources/models/   models bundled in the jar (<id>_bones.json, <id>_anims.json)
pack/            resource pack bundled in the jar (served by the pack server)
tools/           exea_exporter.js, Blockbench plugin, model inspectors
docs/            API reference, Thai guide, modeling rules
```

Only `com.exea.entity.api` is a stable API. Classes in `core` can change in any release.

## Limits

- Each bone with cubes is one display entity. Aim for about 30 such bones for a mob and about 50 for
  a boss.
- Keyframes cannot use Molang expressions (they read as 0). Bezier keyframes play as linear. Global
  rotation plays as local.
- The hitbox is the driver's box. A model can be larger than its hitbox.

## Support

Report bugs and ask for features in [GitHub Issues](https://github.com/asnise/EXeaEntityCore/issues).
For security problems, see [SECURITY.md](SECURITY.md).

## License

Copyright © 2026 asnise. **All rights reserved.** The source is published for reference only.
Using, copying, modifying or distributing it, including running it on a server, needs written
permission from the copyright holder. See [LICENSE](LICENSE).
