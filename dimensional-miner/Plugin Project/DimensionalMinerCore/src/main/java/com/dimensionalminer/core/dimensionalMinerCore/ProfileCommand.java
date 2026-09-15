package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class ProfileCommand implements CommandExecutor {

    private final DimensionalMinerCore plugin;

    public ProfileCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        plugin.getDatabaseManager().upsertPlayer(player);

        UUID playerId = player.getUniqueId();
        String webUrl = "http://localhost:3001/profile?id=" + playerId;

        Component message = Component.text("[DimensionalMiner] Click here to open profile dashboard: ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(webUrl)
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(webUrl)));

        player.sendMessage(message);
        return true;
    }
}