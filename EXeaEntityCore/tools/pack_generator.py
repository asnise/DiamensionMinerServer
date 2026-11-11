import json
import os
import shutil
import zipfile

def generate_pack():
    bb_path = r"D:\Dimensional_Miner_Technical_Starter\Entity Model\VoidOverseer\Void Overseer.bbmodel"
    texture_src = r"D:\Dimensional_Miner_Technical_Starter\Entity Model\VoidOverseer\void_overseer_texture.png"
    
    pack_base = r"D:\Dimensional_Miner_Technical_Starter\EXeaEntityCore\pack"
    
    # Clean old pack to remove any Windows uppercase leftover files
    if os.path.exists(pack_base):
        shutil.rmtree(pack_base)

    assets_dir = os.path.join(pack_base, "assets")
    exea_models_dir = os.path.join(assets_dir, "exea", "models", "item", "void_overseer")
    exea_items_dir = os.path.join(assets_dir, "exea", "items", "void_overseer")
    exea_textures_dir = os.path.join(assets_dir, "exea", "textures", "item", "void_overseer")
    mc_models_dir = os.path.join(assets_dir, "minecraft", "models", "item")
    mc_items_dir = os.path.join(assets_dir, "minecraft", "items")

    os.makedirs(exea_models_dir, exist_ok=True)
    os.makedirs(exea_items_dir, exist_ok=True)
    os.makedirs(exea_textures_dir, exist_ok=True)
    os.makedirs(mc_models_dir, exist_ok=True)
    os.makedirs(mc_items_dir, exist_ok=True)

    # 1. pack.mcmeta (compatible with all Minecraft versions 1.20 - 1.21.4+)
    pack_mcmeta = {
        "pack": {
            "pack_format": 46,
            "supported_formats": {"min_inclusive": 15, "max_inclusive": 65},
            "description": "EXeaEntityCore 3D Entity Models"
        }
    }
    with open(os.path.join(pack_base, "pack.mcmeta"), "w", encoding="utf-8") as f:
        json.dump(pack_mcmeta, f, indent=2)

    # 2. Copy Texture
    dest_tex = os.path.join(exea_textures_dir, "void_overseer.png")
    shutil.copyfile(texture_src, dest_tex)
    print(f"[PackGen] Texture copied -> {dest_tex}")

    # 3. Read .bbmodel
    with open(bb_path, "r", encoding="utf-8") as f:
        bbdata = json.load(f)

    uuid_to_group = {g["uuid"]: g for g in bbdata.get("groups", [])}
    elements_map = {el["uuid"]: el for el in bbdata.get("elements", [])}

    bones = []
    overrides_legacy = []
    dispatch_entries_modern = []
    cmd_start = 1001

    def process_group(node, parent_name=None):
        nonlocal cmd_start
        if not isinstance(node, dict):
            return
        
        gid = node.get("uuid")
        gdata = uuid_to_group.get(gid, {})
        gname = gdata.get("name", "bone")
        gname_clean = gname.lower()
        origin = gdata.get("origin", [0, 0, 0])
        rotation = gdata.get("rotation", [0, 0, 0])
        children = node.get("children", [])

        # Check if elements exceed Minecraft boundary [-16, 32]
        needs_downscale = False
        raw_elements = []
        for child in children:
            if isinstance(child, str) and child in elements_map:
                el = elements_map[child]
                efrom = [(el["from"][i] - origin[i]) + 8.0 for i in range(3)]
                eto = [(el["to"][i] - origin[i]) + 8.0 for i in range(3)]
                for val in efrom + eto:
                    if val < -16.0 or val > 32.0:
                        needs_downscale = True
                raw_elements.append((el, efrom, eto))

        scale_factor = 0.5 if needs_downscale else 1.0

        group_elements = []
        res = bbdata.get("resolution", {"width": 128, "height": 128})

        for el, efrom, eto in raw_elements:
            if needs_downscale:
                # Center around [8, 8, 8] and scale by 0.5
                sc_from = [round(8.0 + (efrom[i] - 8.0) * scale_factor, 4) for i in range(3)]
                sc_to = [round(8.0 + (eto[i] - 8.0) * scale_factor, 4) for i in range(3)]
            else:
                sc_from = [round(efrom[i], 4) for i in range(3)]
                sc_to = [round(eto[i], 4) for i in range(3)]

            # Double check bounds clamp [-16, 32]
            sc_from = [max(-16.0, min(32.0, v)) for v in sc_from]
            sc_to = [max(-16.0, min(32.0, v)) for v in sc_to]

            faces_dict = {}
            for fname, fval in el.get("faces", {}).items():
                uv = fval.get("uv", [0, 0, 16, 16])
                norm_uv = [
                    round((uv[0] / res["width"]) * 16.0, 4),
                    round((uv[1] / res["height"]) * 16.0, 4),
                    round((uv[2] / res["width"]) * 16.0, 4),
                    round((uv[3] / res["height"]) * 16.0, 4)
                ]
                faces_dict[fname] = {
                    "uv": norm_uv,
                    "texture": "#0"
                }

            group_elements.append({
                "from": sc_from,
                "to": sc_to,
                "faces": faces_dict
            })

        cmd_id = cmd_start
        cmd_start += 1

        display_scale = (2.0 if needs_downscale else 1.0)

        # Bone Model JSON
        bone_model = {
            "credit": "EXeaEntityCore Blockbench Exporter",
            "textures": {
                "0": "exea:item/void_overseer/void_overseer",
                "particle": "exea:item/void_overseer/void_overseer"
            },
            "elements": group_elements,
            "display": {
                "none": {
                    "translation": [0, 0, 0],
                    "rotation": [0, 0, 0],
                    "scale": [display_scale, display_scale, display_scale]
                },
                "head": {
                    "translation": [0, 0, 0],
                    "rotation": [0, 0, 0],
                    "scale": [display_scale, display_scale, display_scale]
                },
                "fixed": {
                    "translation": [0, 0, 0],
                    "rotation": [0, 0, 0],
                    "scale": [display_scale, display_scale, display_scale]
                }
            }
        }

        bone_file = os.path.join(exea_models_dir, f"{gname_clean}.json")
        with open(bone_file, "w", encoding="utf-8") as bf:
            json.dump(bone_model, bf, indent=2)

        # Modern 1.21.4 item_model file: assets/exea/items/void_overseer/<bone>.json
        exea_item_file = os.path.join(exea_items_dir, f"{gname_clean}.json")
        with open(exea_item_file, "w", encoding="utf-8") as eif:
            json.dump({
                "model": {
                    "type": "minecraft:model",
                    "model": f"exea:item/void_overseer/{gname_clean}"
                }
            }, eif, indent=2)

        # Legacy 1.20 - 1.21.1 overrides
        overrides_legacy.append({
            "predicate": {"custom_model_data": cmd_id},
            "model": f"exea:item/void_overseer/{gname_clean}"
        })

        # Modern 1.21.4 range_dispatch entry
        dispatch_entries_modern.append({
            "threshold": cmd_id,
            "model": {
                "type": "minecraft:model",
                "model": f"exea:item/void_overseer/{gname_clean}"
            }
        })

        bones.append({
            "name": gname_clean,
            "original_name": gname,
            "parent": parent_name,
            "origin": origin,
            "rotation": rotation,
            "custom_model_data": cmd_id,
            "element_count": len(group_elements)
        })

        for child in children:
            if isinstance(child, dict):
                process_group(child, gname_clean)

    for root_node in bbdata.get("outliner", []):
        process_group(root_node, None)

    # 4. Write Legacy Override: assets/minecraft/models/item/leather_horse_armor.json
    lha_legacy = {
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": "minecraft:item/leather_horse_armor"
        },
        "overrides": overrides_legacy
    }
    with open(os.path.join(mc_models_dir, "leather_horse_armor.json"), "w", encoding="utf-8") as lf:
        json.dump(lha_legacy, lf, indent=2)

    # 5. Write Modern 1.21.4 Item Definition: assets/minecraft/items/leather_horse_armor.json
    lha_modern = {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "fallback": {
                "type": "minecraft:model",
                "model": "minecraft:item/leather_horse_armor"
            },
            "entries": dispatch_entries_modern
        }
    }
    with open(os.path.join(mc_items_dir, "leather_horse_armor.json"), "w", encoding="utf-8") as mf:
        json.dump(lha_modern, mf, indent=2)

    # 6. Extract Animations
    anims_data = {}
    for anim in bbdata.get("animations", []):
        aname = anim.get("name", "anim")
        short_name = aname.replace("animation.void_overseer.", "")
        length = anim.get("length", 1.0)
        loop = anim.get("loop", "once")
        
        anim_entry = {
            "length": length,
            "loop": loop,
            "bones": {}
        }

        for bone_uuid, bone_anim in anim.get("animators", {}).items():
            bname = bone_anim.get("name", "").lower()
            keyframe_points = []
            
            for kf in bone_anim.get("keyframes", []):
                channel = kf.get("channel")
                time = kf.get("time")
                data_points = kf.get("data_points", [{}])[0]
                x = float(data_points.get("x", 0.0))
                y = float(data_points.get("y", 0.0))
                z = float(data_points.get("z", 0.0))

                keyframe_points.append({
                    "channel": channel,
                    "time": time,
                    "vector": [x, y, z]
                })

            keyframe_points.sort(key=lambda k: k["time"])
            anim_entry["bones"][bname] = keyframe_points

        anims_data[short_name] = anim_entry

    # Save metadata for EXeaEntityCore plugin
    models_config_dir = r"D:\Dimensional_Miner_Technical_Starter\EXeaEntityCore\models"
    server_models_dir = r"D:\Dimensional_Miner_Technical_Starter\server\plugins\EXeaEntityCore\models"
    plugin_res_models_dir = r"D:\Dimensional_Miner_Technical_Starter\EXeaEntityCore\src\main\resources\models"
    os.makedirs(models_config_dir, exist_ok=True)
    os.makedirs(server_models_dir, exist_ok=True)
    os.makedirs(plugin_res_models_dir, exist_ok=True)

    for target_dir in [models_config_dir, server_models_dir, plugin_res_models_dir]:
        with open(os.path.join(target_dir, "void_overseer_bones.json"), "w", encoding="utf-8") as bf:
            json.dump(bones, bf, indent=2)
        with open(os.path.join(target_dir, "void_overseer_anims.json"), "w", encoding="utf-8") as af:
            json.dump(anims_data, af, indent=2)

    # 7. Build zip archive strictly with forward slashes
    zip_path = os.path.join(r"D:\Dimensional_Miner_Technical_Starter\EXeaEntityCore", "exea-resourcepack.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(pack_base):
            for file in files:
                full_p = os.path.join(root, file)
                rel_p = os.path.relpath(full_p, pack_base).replace("\\", "/")
                zf.write(full_p, rel_p)

    # Copy zip to server and user's .minecraft resourcepacks folder!
    server_rp_dir = r"D:\Dimensional_Miner_Technical_Starter\server\resourcepack"
    os.makedirs(server_rp_dir, exist_ok=True)
    shutil.copyfile(zip_path, os.path.join(server_rp_dir, "exea-resourcepack.zip"))

    user_mc_rp = os.path.expandvars(r"%APPDATA%\.minecraft\resourcepacks")
    if os.path.exists(user_mc_rp):
        user_dest = os.path.join(user_mc_rp, "exea-resourcepack.zip")
        try:
            shutil.copyfile(zip_path, user_dest)
            print(f"[PackGen] Auto-copied latest pack directly to: {user_dest}")
        except Exception as e:
            print(f"[PackGen] Could not copy to client: {e}")

    print(f"[PackGen] SUCCESS!")
    print(f" - Cleaned lowercase paths and clamped boundaries [-16, 32]")
    print(f" - Built Zip -> {zip_path}")

if __name__ == "__main__":
    generate_pack()
