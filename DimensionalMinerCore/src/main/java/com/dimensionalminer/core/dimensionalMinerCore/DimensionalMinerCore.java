package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DimensionalMinerCore extends JavaPlugin {

    private DatabaseManager databaseManager;
    private InventorySync inventorySync;

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
            getLogger().severe("Could not establish database connection!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        inventorySync = new InventorySync(this);
        getServer().getPluginManager().registerEvents(inventorySync, this);

        getCommand("profile").setExecutor(new ProfileCommand(this));
        getCommand("balance").setExecutor(new BalanceCommand(this));
        getCommand("grants").setExecutor(new GrantsCommand(this));
        getCommand("shop").setExecutor(new ShopCommand(this));
        getCommand("weblogin").setExecutor(new WebLoginCommand(this));

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
        if (inventorySync != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                inventorySync.snapshotAndMaybeSave(p);
            }
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Dimensional Miner Core disabled.");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}