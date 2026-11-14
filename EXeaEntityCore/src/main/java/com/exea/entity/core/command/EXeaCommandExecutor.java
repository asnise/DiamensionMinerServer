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
            case "fx" -> handleFx(sender, args);
            case "skill" -> handleSkill(sender, args);
            case "reload" -> {
                var plugin = com.exea.entity.EXeaEntityPlugin.getInstance();
                EntityManager.getInstance().reload(plugin);
                sender.sendMessage(ChatColor.GREEN + "[EXea] Models reloaded: " + String.join(", ", EntityManager.getInstance().getTemplateIds()));
                com.exea.entity.core.effect.EffectManager.get().reload();
                com.exea.entity.core.skill.SkillManager.get().reload();
                sender.sendMessage(ChatColor.GREEN + "[EXea] Effects reloaded: " + com.exea.entity.core.effect.EffectManager.get().ids().size()
                        + ChatColor.GRAY + " (problems are in the server log)");
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
            List<String> subs = Arrays.asList("spawn", "kill", "anim", "bone", "fx", "skill", "stats", "list", "pack", "reload");
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("skill") && sender instanceof Player p) {
            List<String> opts = new ArrayList<>(List.of("list", "auto", "manual", "cancel"));
            EXActiveEntity near = findNearest(p);
            if (near != null) opts.addAll(near.getSkills());
            for (String s : opts) if (s.startsWith(args[1].toLowerCase())) completions.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fx")) {
            List<String> opts = new ArrayList<>(List.of("list", "stop"));
            opts.addAll(com.exea.entity.core.effect.EffectManager.get().ids());
            for (String s : opts) if (s.startsWith(args[1].toLowerCase())) completions.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("fx") && !args[1].equalsIgnoreCase("list")) {
            completions.addAll(Arrays.asList("0.5", "1", "1.5", "2"));
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
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea fx <id> [scale] | list | stop" + ChatColor.GRAY + " - Plays an effect preset at you (facing your way)");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "/exea skill <id> | list | auto | manual | cancel" + ChatColor.GRAY + " - Moveset skills of the nearest entity (you are the target)");
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

    /** /exea fx &lt;id&gt; [scale] plays at the player, facing their way; list; stop (within 32 blocks). */
    private void handleFx(CommandSender sender, String[] args) {
        var fx = com.exea.entity.core.effect.EffectManager.get();
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "=== EXea effects (" + fx.ids().size() + ") ===");
            sender.sendMessage(ChatColor.GRAY + String.join(", ", fx.ids()));
            sender.sendMessage(ChatColor.GRAY + "Playing: " + fx.activeCount() + "  display entities: " + fx.entityCount());
            return;
        }
        if (!(sender instanceof Player player)) {
            // Console: /exea fx <id> [scale] [world] plays at the world's spawn point (testing without a player).
            org.bukkit.World w = args.length >= 4 ? org.bukkit.Bukkit.getWorld(args[3]) : org.bukkit.Bukkit.getWorlds().get(0);
            if (w == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return;
            }
            double s = 1;
            try {
                if (args.length >= 3) s = Double.parseDouble(args[2]);
            } catch (NumberFormatException ignored) {
                // keep 1
            }
            var h = fx.play(args[1].toLowerCase(), w.getSpawnLocation(), com.exea.entity.api.effect.EffectOptions.create().scale(s));
            sender.sendMessage(h == null ? "[EXea] Unknown effect: " + args[1]
                    : "[EXea] Playing " + args[1] + " at the spawn of " + w.getName() + "; playing now: " + fx.activeCount());
            return;
        }
        if (args[1].equalsIgnoreCase("stop")) {
            sender.sendMessage(ChatColor.YELLOW + "[EXea] Stopped " + fx.stopNear(player.getLocation(), 32) + " effect(s) near you.");
            return;
        }
        double scale = 1;
        if (args.length >= 3) {
            try {
                scale = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Scale must be a number.");
                return;
            }
        }
        // Played 2 blocks in front of the player, facing the same way, so it is easy to watch.
        org.bukkit.Location at = player.getLocation();
        at.add(at.getDirection().setY(0).normalize().multiply(2));
        at.setPitch(0);
        var opts = com.exea.entity.api.effect.EffectOptions.create().scale(scale).yaw(player.getLocation().getYaw()).target(player);
        if (fx.play(args[1].toLowerCase(), at, opts) == null) {
            sender.sendMessage(ChatColor.RED + "[EXea] Unknown effect: " + args[1] + ChatColor.GRAY + " (/exea fx list)");
        } else {
            sender.sendMessage(ChatColor.GREEN + "[EXea] Playing " + args[1] + (scale != 1 ? " ×" + scale : ""));
        }
    }

    /** /exea skill: cast a skill of the nearest entity at you, list its skills, switch its AI, cancel. */
    private void handleSkill(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            // Console: /exea skill <id> casts it on every live entity that has it (testing without a player).
            if (args.length >= 2) {
                int n = 0;
                for (EXActiveEntity e : EntityManager.getInstance().getActiveEntities().values()) {
                    if (e.isValid() && e.getSkills().contains(args[1].toLowerCase())) {
                        e.setCooldown(args[1].toLowerCase(), 0);
                        String running = e.getCurrentSkill();
                        if (e.castSkill(args[1].toLowerCase())) n++;
                        else sender.sendMessage("[EXea]   not cast on " + e.getModelId() + " in " + e.getDriver().getWorld().getName()
                                + (running != null ? ": " + running + " is running" : e.isDying() ? ": dying" : !e.getDriver().isValid() ? ": driver not loaded" : ""));
                    }
                }
                sender.sendMessage("[EXea] Cast " + args[1] + " on " + n + " entit" + (n == 1 ? "y" : "ies"));
                return;
            }
            sender.sendMessage(ChatColor.GRAY + "[EXea] Movesets: " + String.join(", ", com.exea.entity.core.skill.SkillManager.get().ids()));
            for (EXActiveEntity e : EntityManager.getInstance().getActiveEntities().values()) {
                if (!e.isValid() || !e.hasMoveset()) continue;
                sender.sendMessage("[EXea]   " + e.getModelId() + " in " + e.getDriver().getWorld().getName() + ": hp "
                        + (int) e.getDriver().getHealth() + ", phase " + e.getPhase() + ", running " + e.getCurrentSkill()
                        + (e.getDriver().isInvulnerable() ? ", invulnerable" : "") + (e.isAutoSkills() ? ", auto" : ", manual"));
            }
            return;
        }
        EXActiveEntity e = findNearest(player);
        if (e == null || !e.hasMoveset()) {
            player.sendMessage(ChatColor.RED + "[EXea] No EXea entity with a moveset within 20 blocks.");
            return;
        }
        String sub = args.length < 2 ? "list" : args[1].toLowerCase();
        switch (sub) {
            case "list" -> {
                player.sendMessage(ChatColor.DARK_PURPLE + "=== " + e.getModelId() + " skills"
                        + (e.getPhase() != null ? " (phase " + e.getPhase() + ")" : "") + (e.isAutoSkills() ? " [auto]" : " [manual]") + " ===");
                for (String s : e.getSkills()) {
                    double cd = e.getCooldown(s);
                    player.sendMessage(ChatColor.LIGHT_PURPLE + s + ChatColor.GRAY + (cd > 0 ? "  cooldown " + String.format("%.1f", cd) + " s" : "  ready")
                            + (s.equals(e.getCurrentSkill()) ? ChatColor.GOLD + "  (running)" : ""));
                }
            }
            case "auto", "manual" -> {
                e.setAutoSkills(sub.equals("auto"));
                player.sendMessage(ChatColor.GREEN + "[EXea] " + e.getModelId() + " skills: " + sub);
            }
            case "cancel" -> {
                e.cancelSkill();
                player.sendMessage(ChatColor.YELLOW + "[EXea] Skill cancelled.");
            }
            default -> {
                if (!e.getSkills().contains(sub)) {
                    player.sendMessage(ChatColor.RED + "[EXea] Unknown skill " + sub + ChatColor.GRAY + " (/exea skill list)");
                    return;
                }
                e.setCooldown(sub, 0);
                boolean ok = e.castSkill(sub, player);
                player.sendMessage(ok ? ChatColor.GREEN + "[EXea] Casting " + sub : ChatColor.RED + "[EXea] Could not cast " + sub + " (another skill is running, or a plugin cancelled it)");
            }
        }
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
