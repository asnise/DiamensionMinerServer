"""Lists the groups (bones) and animations of a Blockbench file.

Usage: python inspect_model.py "<path>.bbmodel"
"""
import json
import sys

if len(sys.argv) != 2:
    sys.exit(__doc__)
bb_path = sys.argv[1]
with open(bb_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"=== Groups in {data.get('name', bb_path)} ===")
for g in data.get('groups', []):
    name = g.get('name')
    origin = g.get('origin')
    uuid = g.get('uuid')
    print(f"Group: {name:20} Origin: {str(origin):18} UUID: {uuid}")

print("\n=== Animations ===")
for a in data.get('animations', []):
    name = a.get('name')
    length = a.get('length')
    loop = a.get('loop')
    animators = list(a.get('animators', {}).keys())
    print(f"Anim: {name:35} Length: {length}s Loop: {loop} Bones Animated: {len(animators)}")
