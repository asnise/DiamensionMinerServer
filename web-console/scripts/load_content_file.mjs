// Loads one content definition from a JSON file into:
//   1. dimensional_content.content_definitions (so the admin editor shows it)
//   2. database/content_registry.json and server/plugins/DimensionalMinerCore/content_registry.json
//      (what the Paper plugin reads; apply in-game with /dm reload)
//
// File format: { "definition_id": "...", "kind": "Dungeon" | "ItemBase" | ..., "payload": { ... } }
//
// Usage (from web-console/):
//   node --env-file=.env.local scripts/load_content_file.mjs ../database/test-content/dungeon_test_training.json
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { contentPool } from '../lib/db.js';

const KINDS = new Set(['ItemBase', 'PassiveGenerator', 'UpgradeTree', 'Recipe', 'LootTable', 'Dungeon', 'OreType', 'GearSet']);

const file = process.argv[2];
if (!file) {
  console.error('Usage: node --env-file=.env.local scripts/load_content_file.mjs <definition.json>');
  process.exit(1);
}

const def = JSON.parse(fs.readFileSync(file, 'utf8'));
const { definition_id: id, kind, payload } = def;
if (!/^[a-z0-9_]{1,64}$/.test(id || '')) throw new Error(`Bad definition_id: ${id}`);
if (!KINDS.has(kind)) throw new Error(`Unknown kind: ${kind}`);
if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('payload must be an object');

// 1. Content DB (same upsert as lib/content.js upsertDefinition)
const checksum = crypto.createHash('sha256').update(JSON.stringify(payload)).digest('hex');
await contentPool.query(
  `INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
   VALUES ($1, 1, $2, $3, $4)
   ON CONFLICT (definition_id, version) DO UPDATE
   SET kind = EXCLUDED.kind, payload = EXCLUDED.payload, checksum = EXCLUDED.checksum, published_at = now()`,
  [id, kind, payload, checksum]
);
console.log(`[db] upserted ${kind}/${id}`);
await contentPool.end();

// 2. Registry JSON files the plugin loads
const repoRoot = path.resolve(process.cwd(), '..');
const registries = [
  path.join(repoRoot, 'database', 'content_registry.json'),
  path.join(repoRoot, 'server', 'plugins', 'DimensionalMinerCore', 'content_registry.json'),
];
for (const reg of registries) {
  if (!fs.existsSync(reg)) {
    console.log(`[registry] skipped (not found): ${reg}`);
    continue;
  }
  const data = JSON.parse(fs.readFileSync(reg, 'utf8'));
  data[kind] = (data[kind] || []).filter((d) => d.definition_id !== id);
  data[kind].push({ definition_id: id, version: 1, kind, payload });
  data[kind].sort((a, b) => a.definition_id.localeCompare(b.definition_id));
  fs.writeFileSync(reg, JSON.stringify(data, null, 2) + '\n');
  console.log(`[registry] wrote ${kind}/${id} -> ${reg}`);
}
console.log('Done. In game run /dm reload, then /dungeon list.');
