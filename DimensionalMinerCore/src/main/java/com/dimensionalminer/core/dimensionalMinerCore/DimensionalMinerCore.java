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

        partyManager = new com.dimensionalminer.core.dimensionalMinerCore.party.PartyManager(this);
        channelManager = new com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelManager(this);
        dungeonInstanceManager = new com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager(this);

        getServer().getPluginManager().registerEvents(new com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonEventListener(this, dungeonInstanceManager), this);

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
        // when slot content actually changed. Quit + inventory close also trigger saves.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                inventorySync.snapshotAndMaybeSave(p);
            }
        }, 20L * 10, 20L * 15);

        getLogger().info("Dimensional Miner Core has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (dungeonInstanceManager != null) {
            // Moves players to the hub and removes instance worlds before inventories are flushed.
            dungeonInstanceManager.shutdownAll();
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