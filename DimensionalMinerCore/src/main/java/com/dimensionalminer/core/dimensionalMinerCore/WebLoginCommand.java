package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;

/** /weblogin: creates a one-time key for the web console. DB work runs off the main thread. */
public class WebLoginCommand implements CommandExecutor {

    private final DimensionalMinerCore plugin;

    public WebLoginCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        UUID minecraftId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.WebLoginKeyResult res;
            try {
                // The key belongs to the account, not the Minecraft UUID (web_login_keys.player_id -> user_accounts).
                UUID accountId = plugin.getDatabaseManager().getAccountId(minecraftId);
                res = plugin.getDatabaseManager().createWebLoginKey(accountId);
            } catch (SQLException e) {
                plugin.getLogger().warning("/weblogin failed for " + player.getName() + ": " + e.getMessage());
                res = null;
            }

            final DatabaseManager.WebLoginKeyResult result = res;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (result == null) {
                    player.sendMessage(Component.text("[DimensionalMiner] Failed to generate login key. Try again later.").color(NamedTextColor.RED));
                    return;
                }
                sendKey(player, result);
            });
        });
        return true;
    }

    private static void sendKey(Player player, DatabaseManager.WebLoginKeyResult res) {
        String webUrl = "http://localhost:3000/login?code=" + res.key();

        player.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text("[DimensionalMiner] Web Login Key").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.text("Key: ").color(NamedTextColor.GRAY)
                .append(Component.text(res.key()).color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" (one-time, valid for " + (res.validSeconds() / 60) + " minutes)").color(NamedTextColor.DARK_GRAY)));

        Component linkComp = Component.text("[Click here to Login automatically]")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text("Click to open web console and log in automatically").color(NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.openUrl(webUrl));

        player.sendMessage(linkComp);
        player.sendMessage(Component.text("Manual URL: ").color(NamedTextColor.GRAY)
                .append(Component.text(webUrl).color(NamedTextColor.DARK_AQUA).clickEvent(ClickEvent.openUrl(webUrl))));
        player.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.DARK_GRAY));
    }
}
