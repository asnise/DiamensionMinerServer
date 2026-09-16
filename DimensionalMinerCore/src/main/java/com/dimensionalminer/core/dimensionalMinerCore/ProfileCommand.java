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

        String webUrl = "http://localhost:3000/profile?id=" + player.getUniqueId();

        Component message = Component.text("[DimensionalMiner] ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Click here to view your profile: ").color(NamedTextColor.GRAY))
                .append(Component.text(webUrl)
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(webUrl)));

        player.sendMessage(message);
        return true;
    }
}