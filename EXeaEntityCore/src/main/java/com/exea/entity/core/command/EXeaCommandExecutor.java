package com.exea.entity.core.command;

import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EXeaCommandExecutor implements CommandExecutor, TabCompleter {

    // #region [Public Methods]
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("exea.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "spawn" -> handleSpawn(sender, args);
            case "kill" -> handleKill(sender, args);
            case "anim" -> handleAnim(sender, args);
            case "list" -> handleList(sender);
            case "pack" -> handlePack(sender, args);
            case "reload" -> {
                EntityManager.getInstance().reload(com.exea.entity.EXeaEntityPlugin.getInstance());
                sender.sendMessage(ChatColor.GREEN + "[EXeaEntityCore] Configuration, models, and animations reloaded!");
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = Arrays.asList("spawn", "kill", "anim", "list", "pack", "reload");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            completions.add("void_overseer");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) {
            completions.addAll(Arrays.asList("ai", "noai"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
            completions.addAll(Arrays.asList("play", "stop"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("anim") && args[1].equalsIgnoreCase("play")) {
            List<String> anims = Arrays.asList("idle", "move", "hover_ascend", "attack_slash", "attack_double_slash", "attack_slam", "attack_singularity", "death");
            for (String a : anims) {
                if (a.startsWith(args[2].toLowerCase())) {
                    completions.add(a);
                }
            }
        }
        return completions;
    }
    // #endregion

    // #region [Private Methods]
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_PURPLE + "=== EXeaEntityCore Commands ===");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea spawn <id>" + ChatColor.GRAY + " - Spawns a custom entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea kill [all]" + ChatColor.GRAY + " - Cleans up active models");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea anim play <anim>" + ChatColor.GRAY + " - Plays animation on nearest entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea anim stop" + ChatColor.GRAY + " - Stops animation on nearest entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea list" + ChatColor.GRAY + " - Shows active custom entities");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea pack generate" + ChatColor.GRAY + " - Generates resource pack zip");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea reload" + ChatColor.GRAY + " - Reloads templates and configs");
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only in-game players can spawn entities directly.");
            return;
        }

        String templateId = args.length > 1 ? args[1] : "void_overseer";
        boolean enableAI = args.length <= 2 || !args[2].equalsIgnoreCase("noai");
        LivingEntity spawned = EntityManager.getInstance().spawn(templateId, player.getLocation(), enableAI);
        if (spawned != null) {
            String mode = enableAI ? ChatColor.GOLD + "[Combat AI Enabled]" : ChatColor.GRAY + "[Statue/NoAI Mode]";
            player.sendMessage(ChatColor.GREEN + "[EXea] Successfully spawned: " + templateId + " " + mode);
        } else {
            player.sendMessage(ChatColor.RED + "[EXea] Unknown entity template: " + templateId);
        }
    }

    private void handleKill(CommandSender sender, String[] args) {
        EntityManager.getInstance().killAll();
        sender.sendMessage(ChatColor.YELLOW + "[EXea] All active EXea entities have been removed.");
    }

    private void handleList(CommandSender sender) {
        int count = EntityManager.getInstance().getActiveEntities().size();
        sender.sendMessage(ChatColor.AQUA + "[EXea] Active entities: " + count);
    }

    private void handleAnim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Must be a player to target entities.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /exea anim play <anim_name> OR /exea anim stop");
            return;
        }

        EXActiveEntity nearest = findNearest(player);
        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "[EXea] No active EXea entity nearby.");
            return;
        }

        if (args[1].equalsIgnoreCase("play") && args.length >= 3) {
            String anim = args[2];
            nearest.playAnimation(anim);
            player.sendMessage(ChatColor.GREEN + "[EXea] Playing animation: " + anim);
        } else if (args[1].equalsIgnoreCase("stop")) {
            nearest.stopAnimation();
            player.sendMessage(ChatColor.YELLOW + "[EXea] Stopped animation.");
        }
    }

    private void handlePack(CommandSender sender, String[] args) {
        sender.sendMessage(ChatColor.GREEN + "[EXea] Generating client resource pack in /plugins/EXeaEntityCore/pack/ ...");
    }

    private EXActiveEntity findNearest(Player player) {
        double closestDistSq = Double.MAX_VALUE;
        EXActiveEntity closest = null;
        for (EXActiveEntity entity : EntityManager.getInstance().getActiveEntities().values()) {
            if (entity.getRootEntity().isValid()) {
                double distSq = entity.getRootEntity().getLocation().distanceSquared(player.getLocation());
                if (distSq < closestDistSq && distSq < 400.0) {
                    closestDistSq = distSq;
                    closest = entity;
                }
            }
        }
        return closest;
    }
    // #endregion
}
