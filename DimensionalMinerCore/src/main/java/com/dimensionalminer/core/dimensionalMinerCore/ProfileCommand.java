package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /profile: sends the web profile link. No DB work here; the account is created on join.
 * The URL must stay http://localhost:3000/profile?id=<uuid> (see CLAUDE.md "Profile URL coupling").
 */
public class ProfileCommand implements CommandExecutor {

    public ProfileCommand(DimensionalMinerCore plugin) {
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        String webUrl = "http://localhost:3000/profile?id=" + player.getUniqueId();

        player.sendMessage(Component.text("[DimensionalMiner] ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Click here to view your profile: ").color(NamedTextColor.GRAY))
                .append(Component.text(webUrl)
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(webUrl))));
        return true;
    }
}
