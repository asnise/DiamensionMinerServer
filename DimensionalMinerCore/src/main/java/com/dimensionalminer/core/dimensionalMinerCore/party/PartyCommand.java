package com.dimensionalminer.core.dimensionalMinerCore.party;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class PartyCommand implements CommandExecutor, TabCompleter {

    // Variables/Fields
    private final PartyManager partyManager;

    // Constructors
    public PartyCommand(PartyManager partyManager) {
        this.partyManager = partyManager;
    }

    // Public Methods
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("create")) {
            partyManager.createParty(player);
            return true;
        }

        if (sub.equals("invite")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /" + label + " invite <player>");
                return true;
            }
            Player target = org.bukkit.Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }
            if (target.equals(player)) {
                player.sendMessage(ChatColor.RED + "You cannot invite yourself.");
                return true;
            }
            partyManager.invitePlayer(player, target);
            return true;
        }

        if (sub.equals("accept")) {
            partyManager.acceptInvite(player);
            return true;
        }

        if (sub.equals("leave")) {
            partyManager.leaveParty(player);
            return true;
        }

        if (sub.equals("disband")) {
            partyManager.getPartyByPlayer(player.getUniqueId()).ifPresentOrElse(session -> {
                if (!session.getLeaderId().equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Only the party leader can disband the party.");
                } else if (partyManager.isLockedByDungeon(session.getPartyId())) {
                    player.sendMessage(ChatColor.RED + "[Party Lock] You cannot disband your party during a dungeon run.");
                } else {
                    partyManager.disbandParty(session.getPartyId());
                }
            }, () -> player.sendMessage(ChatColor.RED + "You are not in a party."));
            return true;
        }

        if (sub.equals("info") || sub.equals("list")) {
            partyManager.getPartyByPlayer(player.getUniqueId()).ifPresentOrElse(session -> {
                player.sendMessage(ChatColor.GOLD + "=== [ Party Info ] ===");
                Player leader = org.bukkit.Bukkit.getPlayer(session.getLeaderId());
                player.sendMessage(ChatColor.YELLOW + "Leader: " + ChatColor.AQUA + (leader != null ? leader.getName() : "Offline"));
                player.sendMessage(ChatColor.YELLOW + "Members (" + session.getMembers().size() + "/4):");
                for (UUID mId : session.getMembers()) {
                    Player m = org.bukkit.Bukkit.getPlayer(mId);
                    player.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + (m != null ? m.getName() : mId.toString()));
                }
            }, () -> player.sendMessage(ChatColor.RED + "You are not in a party. Use /" + label + " create to make one."));
            return true;
        }

        sendHelp(player, label);
        return true;
    }

    private void sendHelp(Player player, String label) {
        player.sendMessage(ChatColor.GOLD + "=== [ Party Commands ] ===");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " create " + ChatColor.GRAY + "- Create a new party");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " invite <player> " + ChatColor.GRAY + "- Invite a player");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " accept " + ChatColor.GRAY + "- Accept pending party invite");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " leave " + ChatColor.GRAY + "- Leave your party");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " info " + ChatColor.GRAY + "- View party members");
        player.sendMessage(ChatColor.YELLOW + "/" + label + " disband " + ChatColor.GRAY + "- Disband party (Leader only)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("create", "invite", "accept", "leave", "info", "disband").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            return null; // Player names
        }
        return Collections.emptyList();
    }
}
