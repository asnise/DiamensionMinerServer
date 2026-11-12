package com.dimensionalminer.core.dimensionalMinerCore.craft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Locale;

/**
 * /craft [pack] — craft window (all recipes) · /forge [energy &lt;amount&gt; [player]] — forge window.
 * Right-clicking a crafting table opens the craft window (CRAFTING_TABLE recipes); a smithing
 * table opens the forge. Creative ops sneaking keep the vanilla block screens for building.
 */
public final class CraftCommand implements TabExecutor, Listener {

    private final CraftService service;
    private final ResourcePackManager packs;

    private final BlacksmithNpcs npcs;

    public CraftCommand(CraftService service, ResourcePackManager packs, BlacksmithNpcs npcs) {
        this.service = service;
        this.packs = packs;
        this.npcs = npcs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean forge = command.getName().equalsIgnoreCase("forge");
        if (forge && args.length >= 1 && args[0].equalsIgnoreCase("energy")) return grantEnergy(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!forge && args.length >= 1 && args[0].equalsIgnoreCase("pack")) {
            if (!packs.isEnabled()) player.sendMessage(Component.text("[Craft] The UI pack is disabled on this server.", NamedTextColor.GRAY));
            else {
                packs.send(player);
                player.sendMessage(Component.text("[Craft] UI pack sent. Reopen the menu after it loads.", NamedTextColor.GRAY));
            }
            return true;
        }
        if (forge && args.length >= 1 && args[0].equalsIgnoreCase("npc")) {
            if (!player.hasPermission("dimensionalminer.admin")) { player.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }
            String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            switch (sub) {
                case "add" -> player.sendMessage(Component.text("[Blacksmith] " + npcs.add(player), NamedTextColor.GREEN));
                case "remove" -> player.sendMessage(Component.text("[Blacksmith] " + npcs.remove(player), NamedTextColor.YELLOW));
                case "list" -> player.sendMessage(Component.text("[Blacksmith] " + (npcs.list().isEmpty() ? "none" : String.join(" | ", npcs.list())), NamedTextColor.GRAY));
                default -> player.sendMessage(Component.text("Usage: /forge npc <add|remove|list>", NamedTextColor.YELLOW));
            }
            return true;
        }
        if (forge && args.length >= 1 && args[0].equalsIgnoreCase("inherit")) InheritMenu.open(service, packs, player);
        else if (forge) ForgeMenu.open(service, packs, player, null);
        else CraftMenu.open(service, packs, player, null);
        return true;
    }

    private boolean grantEnergy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dimensionalminer.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args.length > 1 ? args[1] : "");
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Usage: /forge energy <amount> [player]", NamedTextColor.YELLOW));
            return true;
        }
        if (amount <= 0 || amount > 1_000_000) {
            sender.sendMessage(Component.text("Amount must be 1-1000000.", NamedTextColor.RED));
            return true;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) {
            sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
            return true;
        }
        var id = target.getUniqueId();
        String by = sender.getName();
        Bukkit.getScheduler().runTaskAsynchronously(service.plugin(), () -> {
            long balance = service.grantEnergyNow(id, amount, by);
            Bukkit.getScheduler().runTask(service.plugin(), () -> sender.sendMessage(balance < 0
                    ? Component.text("Energy grant failed (see console).", NamedTextColor.RED)
                    : Component.text("Gave " + amount + " Energy to " + target.getName() + " (now " + balance + ").", NamedTextColor.GREEN)));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = command.getName().equalsIgnoreCase("forge")
                    ? (sender.hasPermission("dimensionalminer.admin") ? List.of("inherit", "energy", "npc") : List.of("inherit"))
                    : List.of("pack");
            return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && command.getName().equalsIgnoreCase("forge") && args[0].equalsIgnoreCase("npc")
                && sender.hasPermission("dimensionalminer.admin"))
            return List.of("add", "remove", "list").stream().filter(o -> o.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    /** Stations: crafting table → craft window, smithing table → forge. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Player p = event.getPlayer();
        if (p.isSneaking() && p.isOp() && p.getGameMode() == GameMode.CREATIVE) return;
        Material type = event.getClickedBlock().getType();
        if (type == Material.CRAFTING_TABLE) {
            event.setCancelled(true);
            CraftMenu.open(service, packs, p, "CRAFTING_TABLE");
        } else if (type == Material.SMITHING_TABLE) {
            event.setCancelled(true);
            ForgeMenu.open(service, packs, p, null);
        }
    }
}
