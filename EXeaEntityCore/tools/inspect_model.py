import json

bb_path = r'D:\Dimensional_Miner_Technical_Starter\Entity Model\VoidOverseer\Void Overseer.bbmodel'
with open(bb_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

print("=== Groups in Void Overseer ===")
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
