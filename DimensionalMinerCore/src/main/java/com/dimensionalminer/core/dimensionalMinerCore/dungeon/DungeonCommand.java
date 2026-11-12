package com.dimensionalminer.core.dimensionalMinerCore.dungeon;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager;
import com.dimensionalminer.core.dimensionalMinerCore.party.PartySession;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class DungeonCommand implements CommandExecutor, TabCompleter {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final DungeonInstanceManager instanceManager;

    // Constructors
    public DungeonCommand(DimensionalMinerCore plugin, DungeonInstanceManager instanceManager) {
        this.plugin = plugin;
        this.instanceManager = instanceManager;
    }

    // Public Methods
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("exitdungeon")) {
            if (!(sender instanceof Player player)) return true;
            exitDungeon(player);
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "=== [ Dungeon System ] ===");
            sender.sendMessage(ChatColor.YELLOW + "/dungeon list " + ChatColor.GRAY + "- View available dungeons & active instances");
            sender.sendMessage(ChatColor.YELLOW + "/dungeon create <dungeon_id> " + ChatColor.GRAY + "- Create a new dungeon instance");
            sender.sendMessage(ChatColor.YELLOW + "/dungeon join <session_id> " + ChatColor.GRAY + "- Join an active dungeon instance");
            sender.sendMessage(ChatColor.YELLOW + "/dungeon enter <dungeon_id> " + ChatColor.GRAY + "- Quick create & enter directly");
            sender.sendMessage(ChatColor.YELLOW + "/exitdungeon " + ChatColor.GRAY + "- Leave current dungeon");
            if (sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "[Admin] /dungeon destroy <sessionId> " + ChatColor.GRAY + "- Force destroy instance");
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            sender.sendMessage(ChatColor.GOLD + "=== [ Available Dungeon Templates ] ===");
            for (DungeonDefinition def : plugin.getContentRegistryManager().getAllDungeons()) {
                sender.sendMessage(ChatColor.YELLOW + "- " + ChatColor.AQUA + def.getDisplayName()
                        + ChatColor.GRAY + " (ID: " + def.getDefinitionId() + ") "
                        + ChatColor.DARK_GRAY + "[Lv." + def.getRequiredLevel() + " | " + (def.getTimeLimitSeconds() / 60) + "m"
                        + " | entry: " + instanceManager.describeEntryCost(def) + "]");
            }

            Map<String, DungeonInstanceSession> sessions = instanceManager.getActiveSessions();
            sender.sendMessage(ChatColor.GOLD + "=== [ Active Instances (" + sessions.size() + ") ] ===");
            if (sessions.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No active dungeon instances running. Create one using /dungeon create <id>");
            } else {
                for (Map.Entry<String, DungeonInstanceSession> entry : sessions.entrySet()) {
                    DungeonInstanceSession sess = entry.getValue();
                    sender.sendMessage(ChatColor.GREEN + "• Session ID: " + ChatColor.WHITE + entry.getKey()
                            + ChatColor.GRAY + " [" + sess.getDefinition().getDisplayName() + "] "
                            + ChatColor.DARK_AQUA + "World: " + sess.getRuntimeWorldName() + " "
                            + ChatColor.YELLOW + "(Players: " + sess.getMembers().size() + ")");
                }
            }
            return true;
        }

        if (sub.equals("create") || sub.equals("enter")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can create or enter dungeons.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /" + label + " " + sub + " <dungeon_id>");
                return true;
            }

            String dId = args[1].toLowerCase(Locale.ROOT);
            DungeonDefinition def = plugin.getContentRegistryManager().getDungeon(dId);
            if (def == null) {
                player.sendMessage(ChatColor.RED + "Dungeon template not found: " + dId);
                return true;
            }

            if (instanceManager.getSessionByPlayer(player.getUniqueId()).isPresent()) {
                player.sendMessage(ChatColor.RED + "You are already in a dungeon. Use /exitdungeon first.");
                return true;
            }

            PartySession party;
            Optional<PartySession> partyOpt = plugin.getPartyManager().getPartyByPlayer(player.getUniqueId());
            if (partyOpt.isEmpty()) {
                // Auto-create solo party for dev / testing convenience
                plugin.getPartyManager().createParty(player);
                party = plugin.getPartyManager().getPartyByPlayer(player.getUniqueId()).orElse(null);
                if (party == null) {
                    player.sendMessage(ChatColor.RED + "Failed to initialize party session.");
                    return true;
                }
            } else {
                party = partyOpt.get();
                if (!party.getLeaderId().equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Only the party leader can initiate dungeon entry.");
                    return true;
                }
            }

            player.sendMessage(ChatColor.GREEN + "[Dungeon] Provisioning instance: " + def.getDisplayName() + "...");
            instanceManager.createDungeonInstance(def, party.getPartyId(), player, party.getMembers())
                    .thenAccept(session -> {
                        player.sendMessage(ChatColor.GREEN + "[Dungeon] Instance ready! Session ID: " + ChatColor.GOLD + session.getSessionId());
                        player.sendMessage(ChatColor.AQUA + "Others can join using: " + ChatColor.YELLOW + "/dungeon join " + session.getSessionId());
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                player.sendMessage(ChatColor.RED + "[Dungeon] Could not start: " + cause.getMessage()));
                        return null;
                    });
            return true;
        }

        if (sub.equals("join")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can join dungeons.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /" + label + " join <sessionId>");
                return true;
            }

            String targetSessionId = args[1].trim();
            instanceManager.joinInstance(player, targetSessionId);
            return true;
        }

        if (sub.equals("leave")) {
            if (!(sender instanceof Player player)) return true;
            exitDungeon(player);
            return true;
        }

        if (sub.equals("destroy")) {
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "Administrative command requires OP permissions.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + label + " destroy <sessionId>");
                return true;
            }
            instanceManager.destroyInstance(args[1]);
            sender.sendMessage(ChatColor.GREEN + "[Admin] Destruction dispatched for session: " + args[1]);
            return true;
        }

        return true;
    }

    /** /exitdungeon and /dungeon leave: leaves the run for good (no final rewards). */
    private void exitDungeon(Player player) {
        boolean inRun = instanceManager.getSessionByPlayer(player.getUniqueId()).isPresent();
        if (!inRun && !instanceManager.isInDungeon(player)) {
            player.sendMessage(ChatColor.RED + "You are not currently inside a dungeon.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "[Dungeon] Leaving instance and returning to town. You will not receive this run's final rewards.");
        instanceManager.leaveInstance(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("list", "create", "enter", "join", "leave"));
            if (sender.isOp()) subs.add("destroy");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("enter"))) {
            return plugin.getContentRegistryManager().getAllDungeons().stream()
                    .map(DungeonDefinition::getDefinitionId)
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("destroy"))) {
            return new ArrayList<>(instanceManager.getActiveSessions().keySet()).stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
