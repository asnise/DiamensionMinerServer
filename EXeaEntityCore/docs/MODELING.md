# Making models for EXeaEntityCore

EXea draws each Blockbench bone that has cubes as one `ItemDisplay` entity, and it animates the bones
with display transforms. These rules keep a model correct and cheap to run.

## Setup

- Blockbench format: **Generic Model**.
- The model faces **−Z (north)**. EXea turns it to face where the driver mob looks.
- The feet are at **y = 0**. A model can be several blocks tall. The hitbox comes from the driver mob,
  not from the model, so the model can be larger than the hitbox.
- One texture per model, any size (128–512 px is typical), with per-face UV. The exporter uses only
  the first texture.

## Bones

| Topic | Rule | Why |
|---|---|---|
| Bones with cubes | About 30 or fewer for a mob, about 50 or fewer for a boss | Each one is an entity, and every change it makes is a packet. Bones are expensive, cubes are cheap: all cubes of a bone become one item model. |
| Cubes per bone | No practical limit | Put as much detail as you want in one bone. |
| Distance from the pivot | Keep the mesh within 24 px of its bone pivot in every direction | Item models only accept coordinates −16..32. The exporter shrinks larger meshes (`mesh_scale`) and EXea scales them back up, so they cost some texture sharpness. |
| Long parts (wings, tails, big weapons) | Split into a chain of bones | They stay inside the limit and can bend like a wave. |
| Pivot | Put it on the real joint (shoulder, elbow, tail root) | Animations rotate around the pivot. |
| Bones without cubes | Allowed, for grouping | They create no entity. |
| Bone names | Unique, lower case with underscores (`wing_l_2`) | The loader uses the lower-case name as the key. |

## Cubes

- Any rotation on any axis works. Multi-axis rotations are exported in the 1.21.11+ `rotation {x,y,z}` form.
- Inflate works: the exporter grows the cube for you.
- Planes work, but a face is drawn on one side only. Texture both faces, or only the side that will be seen.
- Translucent colours can sort wrongly where they overlap.
- To draw a bone at full brightness, list it in the meta file's `glow_bones`.

## Animations

- Channels: position, rotation, scale.
- Interpolation: linear, Catmull-Rom, step.
- **No Molang.** An expression in a keyframe value reads as 0.
- Bezier keyframes play as linear. Global rotation plays as local.
- Avoid more than 180° between two keyframes.
- The default behavior uses these names:
  - `idle` and `move` (looping)
  - `attack_*`: a random one plays when the driver lands a hit
  - `death`: held until the entity is removed

### Effect keyframes

Use the animation's **Effects** row:

- **Sound.** The effect field holds a Java sound key (`entity.wither.shoot`).
- **Particle.** The effect field holds a particle key (`flame`). The locator field holds a locator or
  bone name where the particle appears. Particles that need extra data (dust colour, block) are not
  supported.
- **Timeline.** One instruction per line:

```
hit radius=3.5 damage=20 cone=120 bone=hand_r knockback=0.5
sound entity.generic.explode volume=2 pitch=0.8 bone=core
particle explosion count=3 spread=0.5 speed=0.1 bone=hand_r
shockwave
```

`hit` damages living things within `radius` of the bone. With `cone`, only targets inside that angle
in front of the body are hit. Every line, including `hit`, fires `EXeaAnimationEventTriggerEvent`, so
a plugin can handle its own lines (`shockwave` above). Without effect keyframes, an animation makes no
sound and deals no damage.

## Checklist

1. **Silhouette first.** Block out the shape with 10–20 large cubes. Check it from the front, the
   side and the top: it should be recognisable as a black shape.
2. **Bones on the joints.** Count the bones with cubes against the budget. Give every moving part
   its own bone: head, jaw, each arm segment, 2–3 wing segments, 3–6 tail segments.
3. **Detail.** Add tapering, planes for fur and wings, and teeth, claws and spikes. Rotate cubes to
   follow the form.
4. **UV at even density.** Use 1 px = 1 texel on a 256² texture, or 512² for a large boss.
5. **Texture.**
   1. Base colours.
   2. Shadow at seams and undersides.
   3. Highlights on top edges.
   4. Patterns.
   5. Accent and glowing spots.
6. **Animations.**
   - `idle`: breathing, with small motion on floating parts.
   - `move`.
   - 2–3 `attack_*` animations.
   - `death`.

   Use Catmull-Rom for smooth motion.
7. **Export and test.**
   ```bash
   node tools/exporter/exea_exporter.js "MyBoss.bbmodel" my_boss --server <server>/plugins/EXeaEntityCore
   ```
   Read any `[WARN]` or `[SCALE]` lines. Then, in game, run `/exea reload` and `/exea spawn my_boss`.
