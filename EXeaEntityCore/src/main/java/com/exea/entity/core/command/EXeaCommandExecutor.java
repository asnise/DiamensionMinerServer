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
            case "bone" -> handleBone(sender, args);
            case "stats" -> handleStats(sender);
            case "reload" -> {
                var plugin = com.exea.entity.EXeaEntityPlugin.getInstance();
                EntityManager.getInstance().reload(plugin);
                sender.sendMessage(ChatColor.GREEN + "[EXea] Models reloaded: " + String.join(", ", EntityManager.getInstance().getTemplateIds()));
                if (plugin.getPackServer() != null) {
                    sender.sendMessage(ChatColor.GREEN + "[EXea] Resource pack: " + plugin.getPackServer().rebuild());
                }
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = Arrays.asList("spawn", "kill", "anim", "bone", "stats", "list", "pack", "reload");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            for (String id : EntityManager.getInstance().getTemplateIds()) {
                if (id.startsWith(args[1].toLowerCase())) completions.add(id);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) {
            completions.addAll(Arrays.asList("ai", "noai"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bone") && sender instanceof Player p) {
            EXActiveEntity near = findNearest(p);
            if (near != null) {
                for (String b : near.getBones()) {
                    if (b.startsWith(args[1].toLowerCase())) completions.add(b);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
            completions.addAll(Arrays.asList("play", "stop"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("anim") && args[1].equalsIgnoreCase("play")) {
            java.util.Set<String> anims = new java.util.TreeSet<>();
            if (sender instanceof Player p) {
                EXActiveEntity near = findNearest(p);
                if (near != null) anims.addAll(EntityManager.getInstance().getAnimationNames(near.getTemplateId()));
            }
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
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea spawn <id> [ai|noai]" + ChatColor.GRAY + " - Spawns a custom entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea kill [all]" + ChatColor.GRAY + " - Cleans up active models");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea anim play <anim>" + ChatColor.GRAY + " - Plays animation on nearest entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea anim stop" + ChatColor.GRAY + " - Stops animation on nearest entity");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea bone <bone>" + ChatColor.GRAY + " - Marks a bone of the nearest entity with particles for 5 s");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea stats" + ChatColor.GRAY + " - Work per second since the last /exea stats");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea list" + ChatColor.GRAY + " - Shows active custom entities");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea pack" + ChatColor.GRAY + " - Sends the model pack to you again");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea reload" + ChatColor.GRAY + " - Reloads templates and configs");
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only in-game players can spawn entities directly.");
            return;
        }

        if (args.length < 2) {
            List<String> ids = EntityManager.getInstance().getTemplateIds();
            sender.sendMessage(ChatColor.RED + "Usage: /exea spawn <id> [ai|noai]");
            sender.sendMessage(ChatColor.GRAY + "Models: " + (ids.isEmpty() ? "(none loaded)" : String.join(", ", ids)));
            return;
        }
        String templateId = args[1];
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

    /** Debug for EXeaEntity#getBoneLocation: a particle on the bone pivot every tick for 5 seconds. */
    private void handleBone(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Must be a player to target entities.");
            return;
        }
        EXActiveEntity nearest = findNearest(player);
        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "[EXea] No active EXea entity nearby.");
            return;
        }
        if (args.length < 2 || nearest.getBoneLocation(args[1]) == null) {
            player.sendMessage(ChatColor.RED + "Usage: /exea bone <bone>  Bones: " + String.join(", ", nearest.getBones()));
            return;
        }
        String bone = args[1];
        int[] ticks = {100};
        org.bukkit.Bukkit.getScheduler().runTaskTimer(com.exea.entity.EXeaEntityPlugin.getInstance(), task -> {
            org.bukkit.Location loc = nearest.isValid() ? nearest.getBoneLocation(bone) : null;
            if (loc == null || --ticks[0] < 0) {
                task.cancel();
                return;
            }
            loc.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, loc, 1, 0, 0, 0, 0);
        }, 0L, 1L);
        player.sendMessage(ChatColor.GREEN + "[EXea] Marking bone " + bone + " for 5 s.");
    }

    private long statsAt = System.nanoTime();
    private final long[] statsLast = new long[6];

    /** Per-second rates of EXeaStats since the previous call (first call: since the plugin started). */
    private void handleStats(CommandSender sender) {
        long now = System.nanoTime();
        double secs = Math.max(0.05, (now - statsAt) / 1e9);
        long[] cur = {
                com.exea.entity.core.EXeaStats.boneUpdates, com.exea.entity.core.EXeaStats.carrierMoves,
                com.exea.entity.core.EXeaStats.poses, com.exea.entity.core.EXeaStats.hiddenTicks,
                com.exea.entity.core.EXeaStats.entityTicks, com.exea.entity.core.EXeaStats.respawns};
        long[] d = new long[cur.length];
        for (int i = 0; i < cur.length; i++) d[i] = cur[i] - statsLast[i];
        System.arraycopy(cur, 0, statsLast, 0, cur.length);
        statsAt = now;

        int entities = 0, bones = 0;
        for (EXActiveEntity e : EntityManager.getInstance().getActiveEntities().values()) {
            if (e.isRemoved()) continue;
            entities++;
            bones += e.getRenderer().getSpawnedDisplays().size();
        }
        sender.sendMessage(ChatColor.DARK_PURPLE + "=== EXea stats (last " + String.format("%.1f", secs) + " s) ===");
        sender.sendMessage(ChatColor.GRAY + "Entities: " + ChatColor.WHITE + entities + ChatColor.GRAY + "  bone displays: " + ChatColor.WHITE + bones);
        sender.sendMessage(ChatColor.GRAY + "Bone transform updates/s: " + ChatColor.WHITE + String.format("%.1f", d[0] / secs)
                + ChatColor.GRAY + "  (1 packet per viewer each)");
        sender.sendMessage(ChatColor.GRAY + "Carrier moves/s: " + ChatColor.WHITE + String.format("%.1f", d[1] / secs)
                + ChatColor.GRAY + "  (was 1 teleport per bone before)");
        sender.sendMessage(ChatColor.GRAY + "Poses computed/s: " + ChatColor.WHITE + String.format("%.1f", d[2] / secs)
                + ChatColor.GRAY + "  hidden (nobody near): " + ChatColor.WHITE
                + (d[4] == 0 ? "-" : Math.round(100.0 * d[3] / d[4]) + "%") + ChatColor.GRAY + " of entity ticks");
        if (d[5] > 0) sender.sendMessage(ChatColor.YELLOW + "Bone sets respawned: " + d[5]);
    }

    private void handlePack(CommandSender sender, String[] args) {
        var pack = com.exea.entity.EXeaEntityPlugin.getInstance().getPackServer();
        if (!(sender instanceof Player player) || pack == null) {
            sender.sendMessage(ChatColor.RED + "[EXea] Only players can be sent the model pack.");
            return;
        }
        pack.send(player);
        sender.sendMessage(ChatColor.GREEN + "[EXea] Model pack sent again.");
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
