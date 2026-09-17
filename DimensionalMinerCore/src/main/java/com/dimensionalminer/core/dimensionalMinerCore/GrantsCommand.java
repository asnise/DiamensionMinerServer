package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GrantsCommand implements CommandExecutor {

    private final DimensionalMinerCore plugin;

    public GrantsCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    public DimensionalMinerCore getPlugin() {
        return plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by players.");
            return true;
        }

        GrantsMenu.open(plugin, player);
        return true;
    }

    public void processGrants(Player player) {
        UUID playerId = player.getUniqueId();
        String query = "SELECT grant_id, payload FROM reward_grants WHERE player_id = ? AND state = 'AVAILABLE' ORDER BY created_at ASC;";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setObject(1, playerId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<GrantRecord> grants = new ArrayList<>();
                while (rs.next()) {
                    UUID grantId = (UUID) rs.getObject("grant_id");
                    String payloadJson = rs.getString("payload");
                    grants.add(new GrantRecord(grantId, payloadJson));
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (grants.isEmpty()) {
                        player.sendMessage(ChatColor.GRAY + "[Mailbox] You have no pending items to claim.");
                        return;
                    }
                    deliverGrants(player, grants);
                });
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query reward_grants: " + e.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(ChatColor.RED + "[Mailbox] Database error while checking grants.");
                }
            });
        }
    }

    private void deliverGrants(Player player, List<GrantRecord> grants) {
        List<UUID> claimedIds = new ArrayList<>();

        for (GrantRecord grant : grants) {
            List<ItemStack> itemsToGive = parseItems(grant.payload);
            if (itemsToGive.isEmpty()) continue;

            boolean hasSpace = true;
            for (ItemStack item : itemsToGive) {
                if (player.getInventory().firstEmpty() == -1) {
                    hasSpace = false;
                    break;
                }
            }

            if (!hasSpace) {
                player.sendMessage(ChatColor.RED + "[Mailbox] Your inventory is full! Make space to claim remaining items.");
                break;
            }

            for (ItemStack item : itemsToGive) {
                player.getInventory().addItem(item);
                player.sendMessage(ChatColor.GREEN + "[Mailbox] Claimed: " + ChatColor.WHITE + item.getAmount() + "x " + item.getType().name());
            }

            claimedIds.add(grant.grantId);
        }

        if (!claimedIds.isEmpty()) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> markGrantsClaimed(claimedIds));
        }
    }

    private List<ItemStack> parseItems(String json) {
        List<ItemStack> list = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("items") && obj.get("items").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("items");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject itemObj = el.getAsJsonObject();
                    String itemId = itemObj.has("item_id") ? itemObj.get("item_id").getAsString()
                            : itemObj.has("definition_id") ? itemObj.get("definition_id").getAsString()
                            : itemObj.has("id") ? itemObj.get("id").getAsString()
                            : itemObj.has("material") ? itemObj.get("material").getAsString()
                            : null;
                    int amount = itemObj.has("amount") ? itemObj.get("amount").getAsInt() : 1;
                    if (itemId != null) {
                        if (plugin.getContentRegistryManager() != null && plugin.getContentRegistryManager().hasItem(itemId)) {
                            list.add(plugin.getContentRegistryManager().createItemStack(itemId, amount));
                        } else {
                            Material mat = Material.matchMaterial(itemId.toUpperCase(java.util.Locale.ROOT));
                            if (mat != null) {
                                list.add(new ItemStack(mat, Math.max(1, amount)));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void markGrantsClaimed(List<UUID> grantIds) {
        String update = "UPDATE reward_grants SET state = 'CLAIMED', claimed_at = now() WHERE grant_id = ?;";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(update)) {
            for (UUID gid : grantIds) {
                stmt.setObject(1, gid);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to mark grants as claimed: " + e.getMessage());
        }
    }

    private record GrantRecord(UUID grantId, String payload) {}
}
