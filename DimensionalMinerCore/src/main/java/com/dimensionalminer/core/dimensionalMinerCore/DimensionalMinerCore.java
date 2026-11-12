package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DimensionalMinerCore extends JavaPlugin {

    private DatabaseManager databaseManager;
    private InventorySync inventorySync;
    private ContentRegistryManager contentRegistryManager;
    private com.dimensionalminer.core.dimensionalMinerCore.party.PartyManager partyManager;
    private com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelManager channelManager;
    private com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager dungeonInstanceManager;
    private com.dimensionalminer.core.dimensionalMinerCore.craft.ResourcePackManager resourcePacks;
    private com.dimensionalminer.core.dimensionalMinerCore.craft.CraftService craftService;
    private com.dimensionalminer.core.dimensionalMinerCore.worldboss.WorldBossManager worldBosses;
    private com.dimensionalminer.core.dimensionalMinerCore.combat.CombatService combatService;
    private com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems gearItems;
    private com.dimensionalminer.core.dimensionalMinerCore.craft.BlacksmithNpcs blacksmiths;
    private com.dimensionalminer.core.dimensionalMinerCore.zone.ZoneManager zoneManager;
    private com.dimensionalminer.core.dimensionalMinerCore.market.MarketManager market;
    private com.dimensionalminer.core.dimensionalMinerCore.market.TradeManager trades;
    private com.dimensionalminer.core.dimensionalMinerCore.workspace.WorkspaceManager workspaces;
    private com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager mineNodeManager;
    private com.dimensionalminer.core.dimensionalMinerCore.progression.ProgressionService progression;

    @Override
    public void onEnable() {
        getLogger().info("=======================================");
        getLogger().info(" Dimensional Miner Core is starting...");
        getLogger().info("=======================================");

        databaseManager = new DatabaseManager(this);
        try {
            databaseManager.connect();
            databaseManager.testConnection();
        } catch (Exception e) {
            // Without the DB nothing can be loaded or saved safely, so refuse to run at all.
            getLogger().severe("Could not establish database connection: " + e.getMessage());
            getLogger().severe("DimensionalMinerCore is DISABLED. Fix plugins/DimensionalMinerCore/config.yml or the database and restart.");
            databaseManager.close();
            databaseManager = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        contentRegistryManager = new ContentRegistryManager(this);
        contentRegistryManager.loadAll();

        inventorySync = new InventorySync(this);
        getServer().getPluginManager().registerEvents(inventorySync, this);
        getServer().getPluginManager().registerEvents(new GrantsMenu.MenuListener(), this);
        getServer().getPluginManager().registerEvents(new CraftingRestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(new MapProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ContentItemGuard(contentRegistryManager), this);

        // Energy from vanilla EXP + Server Level (9.3 / 9.4)
        progression = new com.dimensionalminer.core.dimensionalMinerCore.progression.ProgressionService(this);
        getServer().getPluginManager().registerEvents(progression, this);
        getCommand("level").setExecutor(progression);

        partyManager = new com.dimensionalminer.core.dimensionalMinerCore.party.PartyManager(this);
        channelManager = new com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelManager(this);
        getServer().getPluginManager().registerEvents(channelManager, this); // 1.3: join → least busy channel
        // Tiers (1.1 / 1.2) and warps (1.4)
        var warps = new com.dimensionalminer.core.dimensionalMinerCore.tier.WarpService(this);
        getServer().getPluginManager().registerEvents(warps, this);
        var tierManager = new com.dimensionalminer.core.dimensionalMinerCore.tier.TierManager(this, warps);
        getServer().getPluginManager().registerEvents(tierManager, this);
        getCommand("tier").setExecutor(tierManager);
        getCommand("tier").setTabCompleter(tierManager);
        dungeonInstanceManager = new com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager(this);

        getServer().getPluginManager().registerEvents(new com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonEventListener(this, dungeonInstanceManager), this);

        // Mining nodes: its break handler runs at LOWEST and cancels node breaks itself, so the
        // map protection (HIGHEST, ignoreCancelled) still blocks every other block.
        com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager mineNodes =
                new com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager(this);
        getServer().getPluginManager().registerEvents(mineNodes, this);
        mineNodeManager = mineNodes;
        var mineCmd = new com.dimensionalminer.core.dimensionalMinerCore.mining.MineCommand(this, mineNodes);
        getCommand("mine").setExecutor(mineCmd);
        getCommand("mine").setTabCompleter(mineCmd);

        // World Boss (7.x): scheduled bosses per Tier channel, contribution, Top 3 / Last Shot, quota
        worldBosses = new com.dimensionalminer.core.dimensionalMinerCore.worldboss.WorldBossManager(this);
        getServer().getPluginManager().registerEvents(worldBosses, this);
        getCommand("wboss").setExecutor(worldBosses);
        getCommand("wboss").setTabCompleter(worldBosses);

        // World building for admins (/dmb, blueprints in plugins/DimensionalMinerCore/blueprints)
        var buildManager = new com.dimensionalminer.core.dimensionalMinerCore.build.BuildManager(this, mineNodes);
        var buildCmd = new com.dimensionalminer.core.dimensionalMinerCore.build.BuildCommand(this, buildManager);
        // Safe / PvP zones (1.5) on the hub map, drawn from the /dmb selection.
        zoneManager = new com.dimensionalminer.core.dimensionalMinerCore.zone.ZoneManager(this, buildManager);
        getServer().getPluginManager().registerEvents(zoneManager, this);
        getCommand("zone").setExecutor(zoneManager);
        getCommand("zone").setTabCompleter(zoneManager);
        getServer().getPluginManager().registerEvents(buildCmd, this);
        getCommand("dmb").setExecutor(buildCmd);
        getCommand("dmb").setTabCompleter(buildCmd);

        // Crafting & forge (style A: textured chest menus from the UI resource pack + native dialogs)
        com.dimensionalminer.core.dimensionalMinerCore.craft.GearMath.selfTest();
        var forgeRules = new com.dimensionalminer.core.dimensionalMinerCore.craft.ForgeRules(getConfig());
        gearItems = new com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems(this, contentRegistryManager, forgeRules);
        craftService = new com.dimensionalminer.core.dimensionalMinerCore.craft.CraftService(this, gearItems);
        // Combat: one damage pipeline for player <-> mob hits (gear stats, crit, Raw x K/(K+Defense)), PvP off.
        combatService = new com.dimensionalminer.core.dimensionalMinerCore.combat.CombatService(this, gearItems,
                com.dimensionalminer.core.dimensionalMinerCore.combat.CombatConfig.from(getConfig()));
        combatService.setZones(zoneManager);
        combatService.setEffects(new com.dimensionalminer.core.dimensionalMinerCore.combat.EffectEngine(this, gearItems, contentRegistryManager));
        getServer().getPluginManager().registerEvents(combatService, this);
        getServer().getPluginManager().registerEvents(new com.dimensionalminer.core.dimensionalMinerCore.combat.DeathListener(this, gearItems), this);
        resourcePacks = new com.dimensionalminer.core.dimensionalMinerCore.craft.ResourcePackManager(this);
        getServer().getPluginManager().registerEvents(resourcePacks, this);
        resourcePacks.start(getFile());
        getServer().getPluginManager().registerEvents(new com.dimensionalminer.core.dimensionalMinerCore.craft.GuiMenu.Listen(), this);
        blacksmiths = new com.dimensionalminer.core.dimensionalMinerCore.craft.BlacksmithNpcs(this, craftService);
        getServer().getPluginManager().registerEvents(blacksmiths, this);
        blacksmiths.spawnAll(); // channel clones that load later get theirs on WorldLoadEvent
        // Economy (8.3 / 8.4 / 8.5): Auction House (/ah + Market Clerk NPC), merchant buyback, P2P trade
        market = new com.dimensionalminer.core.dimensionalMinerCore.market.MarketManager(this);
        getServer().getPluginManager().registerEvents(market, this);
        market.spawnAll();
        getCommand("ah").setExecutor(market);
        getCommand("ah").setTabCompleter(market);
        trades = new com.dimensionalminer.core.dimensionalMinerCore.market.TradeManager(this);
        getCommand("trade").setExecutor(trades);
        getCommand("trade").setTabCompleter(trades);
        // Workspaces (8.1) + offline processing jobs (8.2)
        workspaces = new com.dimensionalminer.core.dimensionalMinerCore.workspace.WorkspaceManager(this);
        getServer().getPluginManager().registerEvents(workspaces, this);
        getCommand("ws").setExecutor(workspaces);
        getCommand("ws").setTabCompleter(workspaces);
        var craftCmd = new com.dimensionalminer.core.dimensionalMinerCore.craft.CraftCommand(craftService, resourcePacks, blacksmiths);
        getServer().getPluginManager().registerEvents(craftCmd, this);
        getCommand("craft").setExecutor(craftCmd);
        getCommand("craft").setTabCompleter(craftCmd);
        getCommand("forge").setExecutor(craftCmd);
        getCommand("forge").setTabCompleter(craftCmd);

        getCommand("profile").setExecutor(new ProfileCommand(this));
        getCommand("balance").setExecutor(new BalanceCommand(this));
        getCommand("grants").setExecutor(new GrantsCommand(this));
        getCommand("shop").setExecutor(new ShopCommand(this));
        getCommand("weblogin").setExecutor(new WebLoginCommand(this));

        com.dimensionalminer.core.dimensionalMinerCore.party.PartyCommand partyCmd =
                new com.dimensionalminer.core.dimensionalMinerCore.party.PartyCommand(partyManager);
        getCommand("party").setExecutor(partyCmd);
        getCommand("party").setTabCompleter(partyCmd);

        com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelCommand channelCmd =
                new com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelCommand(channelManager);
        getCommand("channel").setExecutor(channelCmd);
        getCommand("channel").setTabCompleter(channelCmd);

        com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonCommand dungeonCmd =
                new com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonCommand(this, dungeonInstanceManager);
        getCommand("dungeon").setExecutor(dungeonCmd);
        getCommand("dungeon").setTabCompleter(dungeonCmd);
        getCommand("exitdungeon").setExecutor(dungeonCmd);

        CustomItemCommand customItemCmd = new CustomItemCommand(this);
        if (getCommand("dm") != null) {
            getCommand("dm").setExecutor(customItemCmd);
            getCommand("dm").setTabCompleter(customItemCmd);
        }

        // Ensure keepInventory is enabled and autoSave is disabled across all worlds (stateless read-only maps)
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
            world.setAutoSave(false);
        }

        // Low-frequency sync: snapshot online players every 15s; DB writes happen only
        // when slot content actually changed. Quit, channel switch, mailbox claims and shutdown also save.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                inventorySync.snapshotAndMaybeSave(p);
            }
        }, 20L * 10, 20L * 15);

        getLogger().info("Dimensional Miner Core has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (resourcePacks != null) resourcePacks.stop();
        if (blacksmiths != null) blacksmiths.despawnAll();
        if (market != null) market.despawnAll();
        if (trades != null) trades.shutdown(); // offered items go back before inventories are saved
        if (workspaces != null) workspaces.shutdown(); // stash saved on close, players out, worlds unloaded
        if (dungeonInstanceManager != null) {
            // Moves players to the hub and removes instance worlds before inventories are flushed.
            dungeonInstanceManager.shutdownAll();
        }
        if (worldBosses != null) worldBosses.shutdown(); // removes boss mobs, marks encounters ABORTED
        if (craftService != null) {
            // Forge Heat games hold crafting inputs in memory: give them back before inventories are saved.
            com.dimensionalminer.core.dimensionalMinerCore.craft.HeatMenu.refundAll(craftService);
        }
        if (inventorySync != null) {
            // Blocks until queued saves are committed; must run before the pool is closed.
            inventorySync.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Dimensional Miner Core disabled.");
    }

    public com.dimensionalminer.core.dimensionalMinerCore.progression.ProgressionService getProgression() {
        return progression;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems getGearItems() {
        return gearItems;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.combat.CombatService getCombatService() {
        return combatService;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.craft.CraftService getCraftService() {
        return craftService;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager getMineNodes() {
        return mineNodeManager;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.workspace.WorkspaceManager getWorkspaces() {
        return workspaces;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.zone.ZoneManager getZoneManager() {
        return zoneManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ContentRegistryManager getContentRegistryManager() {
        return contentRegistryManager;
    }

    public InventorySync getInventorySync() {
        return inventorySync;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.party.PartyManager getPartyManager() {
        return partyManager;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelManager getChannelManager() {
        return channelManager;
    }

    public com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager getDungeonInstanceManager() {
        return dungeonInstanceManager;
    }
}