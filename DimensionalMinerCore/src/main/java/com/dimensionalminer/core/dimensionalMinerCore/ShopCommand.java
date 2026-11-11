package com.dimensionalminer.core.dimensionalMinerCore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /shop: sends the web shop link. No DB work here: the account and wallets are created on join
 * (InventorySync -> DatabaseManager.upsertPlayer), and the web shop knows the buyer from its session.
 */
public class ShopCommand implements CommandExecutor {

    private static final String SHOP_URL = "http://localhost:3000/shop";

    public ShopCommand(DimensionalMinerCore plugin) {
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        player.sendMessage(Component.text("[DimensionalMiner] ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Click here to open Web Shop: ").color(NamedTextColor.GRAY))
                .append(Component.text(SHOP_URL)
                        .color(NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.openUrl(SHOP_URL))));
        player.sendMessage(Component.text("Log in there with ").color(NamedTextColor.GRAY)
                .append(Component.text("/weblogin").color(NamedTextColor.AQUA))
                .append(Component.text(" to buy with your COIN.").color(NamedTextColor.GRAY)));
        return true;
    }
}
