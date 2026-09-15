-- Dimensional Miner demo data (idempotent; run with psql -d dimensional_miner -f seed.sql)
BEGIN;

-- upstream fabric: nodes + sessions
INSERT INTO nodes (node_id, role, status, heartbeat_at, capacity) VALUES
 ('proxy-1',   'PROXY',     'READY', now(), '{"mode":"routing"}'),
 ('main-a',    'MAIN',      'READY', now(), '{"players":80}'),
 ('main-b',    'MAIN',      'READY', now(), '{"players":60}'),
 ('instance-1','INSTANCE',  'READY', now(), '{"slots":4}')
ON CONFLICT (node_id) DO NOTHING;

INSERT INTO player_sessions (player_id, node_id, epoch, state, lease_until, snapshot_version) VALUES
 ('550e8400-e29b-41d4-a716-446655440000', 'main-a', 7, 'ACTIVE', now() + interval '30 minutes', 12),
 ('6002c2d3-7eee-4697-86b8-15dc8426a964', 'main-b', 3, 'OFFLINE', now() - interval '1 hour', 2)
ON CONFLICT (player_id) DO NOTHING;

-- content registry
INSERT INTO content_definitions (definition_id, version, kind, payload, checksum) VALUES
 ('void_ore',        1, 'MATERIAL',   '{"display":"Void Ore","stack":64,"icon":"deepslate_redstone_ore","tier":1}', 'sha256-vore'),
 ('crystal_fragment',1, 'MATERIAL',   '{"display":"Crystal Fragment","stack":64,"icon":"amethyst_shard","tier":2}', 'sha256-crf'),
 ('mending_tablet',  1, 'MATERIAL',   '{"display":"Mending Tablet","stack":16,"icon":"honeycomb","tier":1}', 'sha256-mtab'),
 ('heal_potion',     1, 'CONSUMABLE', '{"display":"Heal Potion","stack":16,"icon":"golden_apple","tier":1}', 'sha256-hpot'),
 ('void_sword',      1, 'GEAR',       '{"display":"Void Sword","icon":"netherite_sword","tier":1}', 'sha256-vsword'),
 ('void_pickaxe',    1, 'GEAR',       '{"display":"Void Pickaxe","icon":"netherite_pickaxe","tier":1}', 'sha256-vpick')
ON CONFLICT (definition_id, version) DO NOTHING;

-- operation anchors (idempotency)
INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at) VALUES
 ('70000000-0000-0000-0000-000000000001','550e8400-e29b-41d4-a716-446655440000','seed-wallet-notch','h1','seed_wallet','SUCCEEDED','{"ok":true}', now()),
 ('70000000-0000-0000-0000-000000000002','6002c2d3-7eee-4697-86b8-15dc8426a964','seed-wallet-axnise','h2','seed_wallet','SUCCEEDED','{"ok":true}', now()),
 ('70000000-0000-0000-0000-000000000003','550e8400-e29b-41d4-a716-446655440000','seed-quest-notch','h3','seed_quest','SUCCEEDED','{"ok":true}', now()),
 ('70000000-0000-0000-0000-000000000004','6002c2d3-7eee-4697-86b8-15dc8426a964','seed-quest-axnise','h4','seed_quest','SUCCEEDED','{"ok":true}', now())
ON CONFLICT (operation_id) DO NOTHING;

-- account base: system mint + Axnise wallets (Notch wallets already exist)
INSERT INTO accounts (account_id, owner_id, system_code, currency, balance, version) VALUES
 ('e0000000-0000-0000-0000-000000000001', NULL, 'SYS_START', 'COIN',   0, 2),
 ('e0000000-0000-0000-0000-000000000002', NULL, 'SYS_START', 'ENERGY', 0, 2),
 ('a3000000-0000-0000-0000-000000000001','6002c2d3-7eee-4697-86b8-15dc8426a964', NULL, 'COIN',   3120, 1),
 ('a3000000-0000-0000-0000-000000000002','6002c2d3-7eee-4697-86b8-15dc8426a964', NULL, 'ENERGY', 240,  1)
ON CONFLICT (owner_id, currency) DO NOTHING;

-- balanced ledger (sum of deltas per currency = 0)
INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason, created_at) VALUES
 ('f0000001-0000-0000-0000-000000000001','70000000-0000-0000-0000-000000000001','e0000000-0000-0000-0000-000000000001', -15420, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000002','70000000-0000-0000-0000-000000000001','a10e8400-e29b-41d4-a716-446655440001',  15420, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000003','70000000-0000-0000-0000-000000000001','e0000000-0000-0000-0000-000000000002',   -850, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000004','70000000-0000-0000-0000-000000000001','a20e8400-e29b-41d4-a716-446655440002',    850, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000005','70000000-0000-0000-0000-000000000002','e0000000-0000-0000-0000-000000000001',  -3120, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000006','70000000-0000-0000-0000-000000000002','a3000000-0000-0000-0000-000000000001',   3120, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000007','70000000-0000-0000-0000-000000000002','e0000000-0000-0000-0000-000000000002',   -240, 'INITIAL_MINT', now() - interval '15 days'),
 ('f0000001-0000-0000-0000-000000000008','70000000-0000-0000-0000-000000000002','a3000000-0000-0000-0000-000000000002',    240, 'INITIAL_MINT', now() - interval '15 days')
ON CONFLICT (entry_id) DO NOTHING;

-- Notch inventory: gear + stacks
INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot, version) VALUES
 ('30000000-0000-0000-0000-000000000001','550e8400-e29b-41d4-a716-446655440000','INVENTORY','ITEM',  'inv',   0, 1),
 ('30000000-0000-0000-0000-000000000002','550e8400-e29b-41d4-a716-446655440000','INVENTORY','STACK', 'inv',   2, 1),
 ('30000000-0000-0000-0000-000000000003','550e8400-e29b-41d4-a716-446655440000','INVENTORY','STACK', 'inv',   3, 1),
 ('30000000-0000-0000-0000-000000000004','550e8400-e29b-41d4-a716-446655440000','INVENTORY','STACK', 'inv',   4, 1),
 ('30000000-0000-0000-0000-000000000005','550e8400-e29b-41d4-a716-446655440000','INVENTORY','STACK', 'inv',   5, 1),
 ('30000000-0000-0000-0000-000000000006','550e8400-e29b-41d4-a716-446655440000','STASH',   'STACK', 'stash', 0, 1),
 ('30000000-0000-0000-0000-000000000007','550e8400-e29b-41d4-a716-446655440000','MARKET_ESCROW','ITEM', 'auction', NULL, 1)
ON CONFLICT (location_id) DO NOTHING;

INSERT INTO items (item_id, location_id, definition_id, definition_version, rarity, advancement, refine, engraves, sockets, binding, durability, version) VALUES
 ('31000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','void_sword',1,'RARE',  12, 0, '[]', '[]', 'UNBOUND', 100, 1),
 ('31000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000007','void_pickaxe',1,'EPIC', 45, 6, '[{"affix":"void_power","group":"offense","pct":8}]', '[]', 'UNBOUND', 88, 1)
ON CONFLICT (item_id) DO NOTHING;

INSERT INTO material_stacks (stack_id, location_id, definition_id, definition_version, quantity, binding) VALUES
 ('32000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000002','void_ore',1,320,'UNBOUND'),
 ('32000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000003','crystal_fragment',1,45,'UNBOUND'),
 ('32000000-0000-0000-0000-000000000003','30000000-0000-0000-0000-000000000004','heal_potion',1,16,'UNBOUND'),
 ('32000000-0000-0000-0000-000000000004','30000000-0000-0000-0000-000000000005','mending_tablet',1,8,'UNBOUND'),
 ('32000000-0000-0000-0000-000000000005','30000000-0000-0000-0000-000000000006','void_ore',1,256,'UNBOUND')
ON CONFLICT (stack_id) DO NOTHING;

-- Axnise inventory
INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot, version) VALUES
 ('30000001-0000-0000-0000-000000000001','6002c2d3-7eee-4697-86b8-15dc8426a964','INVENTORY','STACK','inv',0,1),
 ('30000001-0000-0000-0000-000000000002','6002c2d3-7eee-4697-86b8-15dc8426a964','INVENTORY','ITEM', 'inv',1,1)
ON CONFLICT (location_id) DO NOTHING;

INSERT INTO items (item_id, location_id, definition_id, definition_version, rarity, advancement, refine, engraves, sockets, binding, durability, version) VALUES
 ('31000001-0000-0000-0000-000000000001','30000001-0000-0000-0000-000000000002','void_pickaxe',1,'COMMON',5,0,'[]','[]','UNBOUND',100,1)
ON CONFLICT (item_id) DO NOTHING;

INSERT INTO material_stacks (stack_id, location_id, definition_id, definition_version, quantity, binding) VALUES
 ('32000001-0000-0000-0000-000000000001','30000001-0000-0000-0000-000000000001','void_ore',1,64,'UNBOUND')
ON CONFLICT (stack_id) DO NOTHING;

-- mailbox / grants
INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state, created_at, claimed_at, claim_operation_id) VALUES
 ('40000000-0000-0000-0000-000000000001','550e8400-e29b-41d4-a716-446655440000','mine/main-a/node-42/gen-12',
  '{"kind":"STACK","definition_id":"void_ore","quantity":128}', 'AVAILABLE', now() - interval '2 hours', NULL, NULL),
 ('40000000-0000-0000-0000-000000000002','550e8400-e29b-41d4-a716-446655440000','worldboss/elder_void/window-2026-09-16',
  '{"kind":"ITEM","definition_id":"void_pickaxe"}', 'AVAILABLE', now() - interval '1 hour', NULL, NULL),
 ('40000000-0000-0000-0000-000000000003','6002c2d3-7eee-4697-86b8-15dc8426a964','craft/mending_tablet/job-77',
  '{"kind":"STACK","definition_id":"mending_tablet","quantity":4}', 'CLAIMED', now() - interval '3 hours', now() - interval '2 hours', '70000000-0000-0000-0000-000000000004')
ON CONFLICT (grant_id) DO NOTHING;

-- quests + unlocks
INSERT INTO quest_progress (player_id, quest_id, definition_version, state, progress, version) VALUES
 ('550e8400-e29b-41d4-a716-446655440000','q_tier1_kit', 1, 'ACTIVE',  '{"smelted":12,"target":30}', 2),
 ('550e8400-e29b-41d4-a716-446655440000','q_tier1_boss',1, 'COMPLETE','{"bosses":1}', 1),
 ('6002c2d3-7eee-4697-86b8-15dc8426a964','q_tier1_kit', 1, 'ACTIVE',  '{"smelted":2,"target":30}', 1)
ON CONFLICT (player_id, quest_id) DO NOTHING;

INSERT INTO player_unlocks (player_id, unlock_id, granted_at, source_operation_id) VALUES
 ('550e8400-e29b-41d4-a716-446655440000','unlock_tier2',    now() - interval '5 days', '70000000-0000-0000-0000-000000000003'),
 ('550e8400-e29b-41d4-a716-446655440000','unlock_workspace', now() - interval '5 days', '70000000-0000-0000-0000-000000000003'),
 ('6002c2d3-7eee-4697-86b8-15dc8426a964','unlock_tier2',     now() - interval '2 days', '70000000-0000-0000-0000-000000000004')
ON CONFLICT (player_id, unlock_id) DO NOTHING;

-- mining world sample
INSERT INTO mine_nodes (channel_id, node_id, dimension_id, block_x, block_y, block_z, generation, state, respawn_at, ore_definition_id, ore_version) VALUES
 ('main-a','mn-101','dim_level_1', 12,  64,   8,  3, 'READY',    NULL,                  'void_ore', 1),
 ('main-a','mn-102','dim_level_1', 44,  40, -12,  2, 'DEPLETED', now() + interval '5 minutes', 'void_ore', 1),
 ('main-b','mn-103','dim_level_1', -8,  32,  20,  1, 'READY',    NULL,                  'void_ore', 1)
ON CONFLICT (channel_id, node_id) DO NOTHING;

-- world boss encounter sample
INSERT INTO world_encounters (encounter_id, channel_id, boss_type, schedule_window, config_version, state, last_hit_player_id, outcome) VALUES
 ('50000000-0000-0000-0000-000000000001','main-a','elder_void','2026-09-16T14:00',1,'FINISHED','550e8400-e29b-41d4-a716-446655440000',
  '{"hp_lost":2000000,"reward_window":"2026-09-16T14:00"}')
ON CONFLICT (encounter_id) DO NOTHING;

INSERT INTO encounter_contributions (encounter_id, player_id, damage_units, support_units, active_ms, tie_break_at) VALUES
 ('50000000-0000-0000-0000-000000000001','550e8400-e29b-41d4-a716-446655440000', 1200000000, 0,      3600000, now() - interval '7 hours'),
 ('50000000-0000-0000-0000-000000000001','6002c2d3-7eee-4697-86b8-15dc8426a964',  800000000, 15000,  3600000, now() - interval '7 hours')
ON CONFLICT (encounter_id, player_id) DO NOTHING;

COMMIT;