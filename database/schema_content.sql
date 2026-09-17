-- =============================================================================
-- Dimensional Miner • Content Master Database Schema (dimensional_content)
-- =============================================================================
-- This database is authoritative for Game Design content definitions:
-- Items, Weapons, Armor, Tools, Materials, Crafting Recipes, Loot Tables,
-- Passive Currency Generators, and Skill Upgrades.
--
-- RUNTIME PLAYER INVENTORY OR TRANSACTION DATA MUST NEVER BE STORED HERE.
-- =============================================================================

CREATE TABLE IF NOT EXISTS content_definitions (
    definition_id TEXT NOT NULL,
    version INTEGER NOT NULL CHECK(version > 0),
    kind TEXT NOT NULL,
    payload JSONB NOT NULL,
    checksum TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(definition_id, version)
);

CREATE INDEX IF NOT EXISTS idx_content_kind ON content_definitions(kind);
CREATE INDEX IF NOT EXISTS idx_content_published ON content_definitions(published_at DESC);

CREATE TABLE IF NOT EXISTS content_releases (
    release_id UUID PRIMARY KEY,
    tag TEXT NOT NULL UNIQUE,
    manifest JSONB NOT NULL DEFAULT '{}',
    published_by TEXT NOT NULL DEFAULT 'SYSTEM',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

