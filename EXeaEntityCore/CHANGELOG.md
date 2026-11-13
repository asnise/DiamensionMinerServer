# Changelog

All notable changes to EXeaEntityCore. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/) for the public API (`com.exea.entity.api`).

## [Unreleased]

### Changed
- `gradlew jar` writes to `build/libs` unless `-PoutDir` is given or a `../server/plugins` folder exists.

### Removed
- `tools/pack_generator.py` (replaced by `tools/exea_exporter.js`).

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
