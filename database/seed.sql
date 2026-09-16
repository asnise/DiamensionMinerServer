-- Insert mockups for Content Editor

-- 1. ItemBase
INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
VALUES (
    'item_iron_sword', 
    1, 
    'ItemBase', 
    '{"id": "item_iron_sword", "name": "Iron Sword", "rarity": "COMMON", "model_path": "weapons/iron_sword", "category": "WEAPON", "base_stats": {"damage": 15, "durability": 100}}',
    'seed_hash_1'
) ON CONFLICT DO NOTHING;

-- 2. PassiveGenerator
INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
VALUES (
    'gen_coal_miner', 
    1, 
    'PassiveGenerator', 
    '{"id": "gen_coal_miner", "name": "Basic Coal Miner", "production_rate_per_hour": 50, "max_capacity": 500, "output_currency": "COIN"}',
    'seed_hash_2'
) ON CONFLICT DO NOTHING;

-- 3. UpgradeTree
INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
VALUES (
    'upg_backpack_size', 
    1, 
    'UpgradeTree', 
    '{"id": "upg_backpack_size", "name": "Backpack Expansion", "max_level": 5, "cost_scaling_formula": "level * 100 + 50"}',
    'seed_hash_3'
) ON CONFLICT DO NOTHING;
