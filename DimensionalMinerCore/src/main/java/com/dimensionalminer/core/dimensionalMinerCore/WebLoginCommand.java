package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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

        plugin.getDatabaseManager().upsertPlayer(player);
        plugin.getDatabaseManager().ensureAccounts(player.getUniqueId());

        DatabaseManager.WebLoginKeyResult res = plugin.getDatabaseManager().getOrCreateWebLoginKey(player.getUniqueId());
        if (res == null) {
            player.sendMessage(Component.text("[DimensionalMiner] Failed to generate login key. Try again later.").color(NamedTextColor.RED));
            return true;
        }

        String webUrl = "http://localhost:3000/login?code=" + res.key();

        long mins = res.remainingSeconds() / 60;
        long secs = res.remainingSeconds() % 60;
        String timeStr = mins > 0 ? (mins + "m " + secs + "s") : (secs + "s");

        player.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text("[DimensionalMiner] Web Login Key").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.text("Key: ").color(NamedTextColor.GRAY)
                .append(Component.text(res.key()).color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" (" + (res.isNew() ? "Valid for 10m" : "Cooldown: " + timeStr + " remaining") + ")").color(NamedTextColor.DARK_GRAY)));

        Component linkComp = Component.text("[Click here to Login automatically]")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text("Click to open web console and log in automatically").color(NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.openUrl(webUrl));

        player.sendMessage(linkComp);
        player.sendMessage(Component.text("Manual URL: ").color(NamedTextColor.GRAY)
                .append(Component.text(webUrl).color(NamedTextColor.DARK_AQUA).clickEvent(ClickEvent.openUrl(webUrl))));
        player.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.DARK_GRAY));

        return true;
    }
}
