# Changelog

All notable changes to EXeaEntityCore. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/) for the public API (`com.exea.entity.api`).

## [Unreleased]

### Added
- `docs/FORMATS.md` and `schemas/`: file formats for effect presets and movesets.
- Movesets: `models/<id>_moveset.json`.
  - Skills have conditions, cooldown, weight, priority and followups.
  - Actions: `hit` in shapes, `telegraph`, `fx`, `sound`, projectiles, dash / leap / teleport / pull / push,
    `summon`, `self`, `animation`, `preset_skill`, `event`.
  - HP phases with entry skills.
  - `MovesetBehavior` picks skills by itself; manual mode lets a plugin cast them.
- New API: `EXeaEntity.castSkill`, `cancelSkill`, `getSkills`, `getCooldown` / `setCooldown`, `getPhase`,
  `setAutoSkills`; `SpawnOptions.autoSkills`; `EXeaAPI.registerSkillAction`, `getMovesetIds`.
- New events: `EXeaSkillCastEvent`, `EXeaSkillEndEvent`, `EXeaSkillEvent`, `EXeaPhaseChangeEvent`.
- `/exea skill <id> | list | auto | manual | cancel`. The meta key `moveset` shares a moveset between models.
- Example moveset: `docs/examples/void_overseer_moveset.json`.
- Live link (`live_link:` in config, off by default):
  - an HTTP API on the pack port: upload model / moveset / effect files, reload, spawn, cast a skill,
    play an effect or animation
  - token required, this machine only unless `allow_remote`, allowed paths only
- EXea Studio (`tools/studio`):
  - Model, Effects and Entity modes
  - export to a server folder or over the live link
- The exporter's conversion is now `tools/exporter/lib/convert.js`, shared by the CLI and Studio.
  `<id>_moveset.json` next to the `.bbmodel` is exported too.
- Effect presets: `effects/*.json` (jar and data folder), with these layer types:
  - particle shapes, flying blocks from the ground, ground waves, decals
  - warning areas, item-model displays, sounds, nested presets
  - layer types that other plugins register
- 16 bundled presets: fog, smoke, dash trail, ground slam, shockwave, beams, auras, warning areas and more.
- The timeline line `fx <id> [bone=] [scale=] [follow=] [yaw=]`, and `/exea fx <id> [scale] | list | stop`.
- New API: `EXeaAPI.playEffect`, `getEffectIds`, `registerEffectLayer`, `EXeaEntity.playEffect`,
  `EffectOptions`, `EffectHandle`.
- The `effects:` budget in `config.yml`.

### Changed
- `gradlew jar` writes to `build/libs` unless `-PoutDir` is given or a `../server/plugins` folder exists.
- The exporter moved to `tools/exporter/exea_exporter.js` and the Blockbench plugin to `tools/exporter/blockbench_plugin/`.

### Removed
- `tools/pack_generator.py` (replaced by `tools/exporter/exea_exporter.js`).
- `tools/inspect_model.py` (internal helper).

## [1.1.0] - 2026-11-13

First public release.

### Added
- Public API `com.exea.entity.api`:
  - `EXeaAPI.spawn(id, location, SpawnOptions)` returns an `EXeaEntity` handle.
  - The handle plays animations, gives bone world positions, damage per attacker, `kill` and `remove`.
- `SpawnOptions`: driver type, AI, health, attack damage, movement speed, hitbox scale, follow range,
  display name, behavior.
- Behaviors: `EXeaBehavior` hooks and `DefaultBehavior`, registered per model id or per meta name.
- Events:
  - `EXeaEntitySpawnEvent`
  - `EXeaEntityDamageEvent` (skill id: `melee` or the animation name)
  - `EXeaEntityDeathEvent` (killer, damage per attacker)
  - `EXeaEntityRemoveEvent` (reason)
  - `EXeaAnimationEventTriggerEvent`
- Blockbench effect keyframes:
  - Sound and particle at a bone.
  - Timeline scripts `hit`, `sound` and `particle`.
  - Any other script line fires a custom event.
- Built-in resource pack server. It merges the jar pack with `plugins/EXeaEntityCore/pack/` and resends
  the pack on `/exea reload`.
- `exea_exporter.js --server`: export a model straight into a server's plugin folder, no rebuild.
- Per-model `<id>_meta.json`: driver, stats, glowing bones, sounds, particles, death length.
- Commands `/exea bone` and `/exea stats`.
- API-only Maven artifact `com.exea:exea-entity-core-api` (`publishToMavenLocal`).
- Documentation: `docs/API.md`, Thai guide `docs/API_GUIDE_TH.md` with compiled examples.

### Changed
- Rendering cost per tick:
  - Bones ride one carrier display.
  - Turning is part of the bone transforms.
  - Unchanged bones are not resent.
  - Poses are skipped while no client tracks the model.
- Animation playback matches Blockbench:
  - ZYX Euler order and Catmull-Rom interpolation.
  - Full-matrix bone transforms.
  - Meshes larger than 48 px.
  - Multi-axis cube rotation.
