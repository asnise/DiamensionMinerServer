# EXea tools

| folder | what | published |
|---|---|---|
| `exporter/` | `exea_exporter.js`: Blockbench `.bbmodel` → EXea model files + resource pack (`node exporter/exea_exporter.js "<file>.bbmodel" <id> [--server <plugins/EXeaEntityCore>]`); `<id>_meta.json` and `<id>_moveset.json` next to the `.bbmodel` are copied too. `lib/convert.js`: the conversion itself, shared with Studio. `blockbench_plugin/`: exports from Blockbench's File menu | yes |
| `studio/` | **EXea Studio**, the desktop app with three modes: Model (preview as the plugin draws it), Effects (effect preset editor), Entity (meta + moveset editor, skill preview, export to a server). See [studio/README.md](studio/README.md) | yes |
| `dev/` | internal helpers for building models with the Blockbench MCP (`bb*.mjs`) and per-model generator/rig scripts (`models/`). Used by the `exea-model` skill | **no**, left out of the public repo |
