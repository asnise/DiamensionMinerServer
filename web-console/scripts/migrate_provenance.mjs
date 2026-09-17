import { gamePool } from '../lib/db.js';

async function migrate() {
  await gamePool.query(`
    -- 1. Add origin columns to items if not exist
    ALTER TABLE items ADD COLUMN IF NOT EXISTS origin_source TEXT NOT NULL DEFAULT 'GENESIS';
    ALTER TABLE items ADD COLUMN IF NOT EXISTS origin_actor_id UUID REFERENCES user_accounts(account_id);
    ALTER TABLE items ADD COLUMN IF NOT EXISTS origin_context JSONB NOT NULL DEFAULT '{}';
    ALTER TABLE items ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

    -- 2. Create item_transactions table
    CREATE TABLE IF NOT EXISTS item_transactions (
        transaction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        operation_id UUID REFERENCES operations(operation_id),
        item_id UUID REFERENCES items(item_id) ON DELETE CASCADE,
        definition_id TEXT NOT NULL,
        from_actor_id UUID REFERENCES user_accounts(account_id),
        to_actor_id UUID REFERENCES user_accounts(account_id),
        source_action TEXT NOT NULL,
        metadata JSONB NOT NULL DEFAULT '{}',
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX IF NOT EXISTS idx_item_tx_item ON item_transactions(item_id, created_at DESC);
    CREATE INDEX IF NOT EXISTS idx_item_tx_actors ON item_transactions(from_actor_id, to_actor_id, created_at DESC);
  `);

  console.log('Item provenance & item_transactions tables migrated successfully!');
  process.exit(0);
}

migrate().catch(err => {
  console.error('Migration failed:', err);
  process.exit(1);
});
