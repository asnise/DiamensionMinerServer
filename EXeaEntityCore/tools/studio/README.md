# EXea Studio

Desktop app for EXeaEntityCore models. Version 0.1 previews a model and its animations exactly as the
plugin draws them in game. It uses the same pose math, ported line by line from `KeyframeTimeline` and
`AnimationController`.

## Run

Needs Node.js 18+.

```bash
npm install
npm start                      # opens the app
npm start -- "path/to/Model.bbmodel"
npm run dist                   # builds dist/EXeaStudio-<version>.exe (portable, no install)
```

If drive C: is short on space, point the Electron caches somewhere else before installing:
`electron_config_cache`, `ELECTRON_BUILDER_CACHE`, `TEMP`.

## What it does

- **Open** a Blockbench `.bbmodel`, or the exported `<id>_bones.json`. The exported item models and
  texture are found in the pack next to it (`../pack`, or `EXeaEntityCore/pack` for
  `src/main/resources/models`). You can also drop a file on the window or pass it on the command line.
- **Layers**: the bone tree with cube counts and per-bone hide, plus a chip on bones whose mesh is scaled down.
- **Viewport**:
  - The model is posed for a mob with the chosen body yaw. At yaw 0 it faces +Z.
  - The driver hitbox comes from `_meta.json` (`driver`, `hitbox_scale`). Glow bones are drawn unlit.
  - Click a bone to select it.
- **Timeline**:
  - Rows show position, rotation and scale keys per bone, plus animation events.
  - Scrub, play or pause, set the speed, override the loop mode, and step one tick with ← →.
  - Events flash in the viewport as they play. A `hit` line shows its radius and cone.
- **Design panel**: model stats with the entity budget (about 30 for a mob, 50 for a boss), driver
  settings, the current animation, and for a bone its pivot, rest rotation, mesh reach and live position.
- **Checks**: everything that will differ in game:
  - meshes over 24 px
  - Molang, bezier and global rotation
  - over 180° between rotation keys
  - duplicate bone names, missing item models, more than one texture
- **Hot reload**: saving the `.bbmodel` (or re-exporting) reloads the view and keeps the animation,
  time and selection.

Panels: drag the edge between a panel and the viewport to resize the left panel, the right panel or the
timeline; double-click the edge to reset it. Sizes are remembered (**View → Reset Panel Sizes**).

## Projects and where files are saved

A project is one folder that keeps everything together:

```
<project>/exea-project.json   name and server folder
<project>/models/             .bbmodel (or exported <id>_bones.json), <id>_meta.json, <id>_moveset.json
<project>/effects/            your effects, <id>.json
```

- **File → New Project…** picks (or creates) a folder. **Open Project…** opens one; a folder without
  `exea-project.json` can be turned into a project. The last project reopens with its last model.
- **Import Model into Project…** (or drop a `.bbmodel` on the window) copies the model, and its exported
  files and meta / moveset if they sit next to it, into `models/`.
- The **Project** tab (Model mode, left) lists the models, the folders and the server folder.
- **Save** (Ctrl+S) saves what the current mode edits:
  - Effects mode: `<project>/effects/<id>.json` (the first save asks for the id).
  - Entity mode: `<id>_meta.json` and `<id>_moveset.json` next to the model.
  - Model mode only previews; edit the model itself in Blockbench.
- Built-in effects live in the plugin jar. Saving one with the same id makes your copy replace it.
- **Export to Server** (Ctrl+E) copies the model, meta, moveset and used effects (or the current effect)
  into the server folder (`server/plugins/EXeaEntityCore`); then `/exea reload`. **Send to Live Server**
  (Ctrl+Enter) does the same through the Live link.
- Without a project, Studio works on loose files: effects go to the folder picked with **Folder…**.
- **Help → Where are my files?** shows the real paths, with links that open them.

## Menus and Toolbox

The menu bar has **File** (projects, models, effects, save, export, live, Explorer), **Edit** (undo / redo,
duplicate, delete, add layer / action / skill), **View** (grid, hitbox, pivots, wireframe, theme, panel
sizes), **Window** (modes, Live link, dev tools) and **Help**.

**Toolbox** (left tab in Effects and Entity mode): drag an item onto the timeline to place it at that time,
or onto the model to attach it to the bone under the cursor. Clicking adds it at the playhead.

- Effects: ready-made layers (smoke burst, rocks, ground wave…), plain layer types, and effects (as a nested
  preset).
- Entity: skill templates (slash, ground slam, fireball, dash strike…), single actions and effects. A
  template dropped with no skill picked becomes a new skill.

Text fields with known values (particles, sounds, blocks and items from your Minecraft jar, bones,
animations, skills, effects) have a **▾** list: click it to see every value, or type to filter.

Shortcuts:

| key | action |
|---|---|
| Ctrl+Shift+N / Ctrl+Shift+O | new / open project |
| Ctrl+O / Ctrl+R | open / reload model |
| Ctrl+S / Ctrl+Shift+S / Ctrl+Alt+S | save / save as / save all |
| Ctrl+E / Ctrl+Enter | export to server / send to live server |
| Ctrl+Z / Ctrl+Y | undo / redo |
| Ctrl+D / Del | duplicate / delete the selection |
| Ctrl+1 / 2 / 3 | Model / Effects / Entity |
| Space | play / pause |
| ← → | step one tick (Shift: 0.25 s) |
| Home | to start |
| F | frame the model |
| G H P W | grid, hitbox, pivots, wireframe |
| Esc | deselect |
| F12 | dev tools |

## Layout

```
main.js         Electron main: window, dialogs, file read/write/watch, schema validation (no other Node access for the page)
mcjar.js        reads textures from the user's Minecraft jar in memory
app/fx/         effects mode: sim.js (preview, port of core/effect), shape.js, form.js (schema forms), textures.js, editor.js
preload.js      the bridge exposed as window.studio
app/core/       model loading (.bbmodel and exported), pose math, mesh building — no UI
app/*.js        viewport (three.js), timeline, panels
```

## Effects mode

Switch with **Model | Effects** at the top left.

- **Library**:
  - the 16 presets bundled with the plugin, and your own effects folder (**Folder…**, e.g. `plugins/EXeaEntityCore/effects`)
  - search by name or tag
  - **New**, and **Open…** for any effect `.json`
- **Layers**: every layer of the effect. Add one from the type list. Move up or down, duplicate, delete
  (Ctrl+D, Del).
- **Design**:
  - The form is built from `schemas/effect.schema.json`, so it shows exactly the keys the plugin reads.
  - An empty field means the plugin default, which is shown as the placeholder.
  - Nested anchor and shape sections show the keys of the chosen shape.
- **Preview**:
  - Runs the effect the way the plugin does: 20 ticks/s, layer timing, anchors, shapes, flying-block
    physics, ground waves, decals, warning fills, nested presets.
  - Plays on the ground or on a bone of the model open in Model mode (it follows the animation).
  - Set the scale and tags, and turn on a target dummy 6 blocks ahead for beams and `"at": "target"`.
  - Sounds and item models are listed in the event log.
- **Timing**:
  - Each layer has **Start**, **Duration** and **Every**. Duration 0 means one burst; above 0 the layer
    keeps spawning, once every *Every* seconds.
  - The effect has **Length**: *Auto* ends when the last layer ends, *Fixed* cuts (or holds) it at a set
    time. **Loop** repeats it.
- **Timeline**: one bar per layer, with the lifetime of flying blocks and decals shown fainter.
  - Drag a bar to move its start, and its right edge to change the duration. Dragging the right side of
    an instant (diamond) mark stretches it into a bar.
  - Drag the **end** line to set a fixed effect length. It is dashed while the length is Auto.
  - Click or drag the ruler to scrub.
- **Checks**: validation with the JSON Schema, plus preset references that are missing.
- **Save** (Ctrl+S) / **Save as…** (Ctrl+Shift+S) writes `<id>.json`. Then run `/exea reload` in game.
  Built-in presets are read-only: Save as… into your folder, and a file with the same id replaces the
  built-in one.

**Textures.** The preview draws real particle sprites and block faces. They are read **from the Minecraft
client jar installed on this computer**: `.minecraft/versions/26.2/26.2.jar`, or the newest release; use
**Jar…** to pick another. They are read into memory only. Nothing from Mojang is copied into Studio, the
repo or the exe. Without a jar, the preview uses flat colours.

## Entity mode

For the model open in Model mode. Its files sit next to it: `<id>_meta.json` and `<id>_moveset.json`.

- **Skills** (left):
  - **Entity settings** edits the meta file: name, HP, damage, driver mob, hitbox, glow bones, sounds…
  - Then come the skills: add, rename (references follow), duplicate, delete.
  - The first **+ Skill** on a model without a moveset creates one, with a starter skill per `attack_*`
    animation.
- **Phases & AI**: the `ai` block and the HP phases (high HP first).
- **Design** (right): forms from `schemas/moveset.schema.json` and `meta.schema.json`. A skill's actions
  are listed there. **+ Add action** puts one at the playhead.
- **Skill timeline**:
  - The animation bar, and one row per action. Repeats show as smaller marks; telegraph and move
    durations as bars.
  - Drag a mark to change `at`. Scrub the ruler.
- **Preview**:
  - Plays the skill on the model against a green **target dummy**; *Target* sets its distance.
  - Hits flash their shape: red when they would hit the dummy, orange when they miss.
  - Telegraphs and fx run through the effect preview.
  - Projectiles fly at the dummy and run `on_hit`.
  - Dash, leap and teleport move the model. The selected action's shape is drawn in blue.
- **Checks**:
  - schema errors
  - unknown skills, animations, bones and effects
  - skills without a range condition
- **Save** (Ctrl+S) writes the meta and moveset files next to the model.
- **Export to server**:
  - Writes the model (from the `.bbmodel`, with the same converter as `exporter/exea_exporter.js`), its
    pack folders, meta, moveset, and the effects it uses from your effects folder.
  - The target is a server's `plugins/EXeaEntityCore` folder (**Server…**).
  - Then run `/exea reload` in game.
  - From the command line: `"EXea Studio.exe" Model.bbmodel --export-to=<server>/plugins/EXeaEntityCore`.

## Live link

**Live** (top right) connects Studio to a running server, so you see changes in game without copying
files or typing commands.

1. On the server, in `plugins/EXeaEntityCore/config.yml`, set `live_link.enabled: true` and a `token` of
   16+ characters. Restart the server.
2. In Studio, click **Live**. Enter the server URL (`http://localhost:8086` on the same machine) and the
   token, or click **From folder…** to read it from the server's config. Pick a player, then **Connect**.
   The dot turns green.
3. Use it:
   - **Entity mode**:
     - **Send & reload** uploads the model, pack, meta, moveset and your effects, then reloads.
     - **Spawn** puts the model in front of the player.
     - **Cast <skill>** makes the nearest one cast the selected skill at the player.
     - **Remove all** clears the test entities.
   - **Effects mode**: **▶ Play in game** uploads the effect as it is now (saved or not), reloads, and plays it
     in front of the player.

The server only accepts tools on its own machine unless `live_link.allow_remote: true`. Keep the token
secret. Scripts can use `--live-url=… --live-token=… --send-live <model.bbmodel>`.
