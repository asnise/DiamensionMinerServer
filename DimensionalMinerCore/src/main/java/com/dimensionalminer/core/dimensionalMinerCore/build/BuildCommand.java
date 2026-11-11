package com.dimensionalminer.core.dimensionalMinerCore.build;

import com.dimensionalminer.core.dimensionalMinerCore.CustomItemCommand;
import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager;
import com.dimensionalminer.core.dimensionalMinerCore.mining.OreType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /dmb — admin world building (Dimensional Miner Build). See tools/blueprints/README.md.
 * <pre>
 * /dmb wand | pos1 | pos2 | sel        selection (wand: left-click = pos1, right-click = pos2)
 * /dmb fill &lt;block&gt;                   fill the selection
 * /dmb replace &lt;from&gt; &lt;to&gt;           replace one block type in the selection
 * /dmb ores &lt;ore_type&gt; &lt;percent&gt; [all] turn exposed (or all) stone in the selection into mining nodes
 * /dmb list | preview &lt;name&gt; | run &lt;name&gt;   blueprints in plugins/DimensionalMinerCore/blueprints
 * /dmb undo | save
 * </pre>
 */
public class BuildCommand implements CommandExecutor, TabCompleter, Listener {

    private static final Set<Material> STONE_LIKE = Set.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE,
            Material.DIORITE, Material.GRANITE, Material.TUFF, Material.COBBLESTONE);

    private final DimensionalMinerCore plugin;
    private final BuildManager build;
    private final NamespacedKey wandKey;
    private final File blueprintDir;

    public BuildCommand(DimensionalMinerCore plugin, BuildManager build) {
        this.plugin = plugin;
        this.build = build;
        this.wandKey = new NamespacedKey(plugin, "dmb_wand");
        this.blueprintDir = new File(plugin.getDataFolder(), "blueprints");
        installExamples();
    }

    /** Copies the bundled example blueprints into the data folder (never overwrites edits). */
    private void installExamples() {
        if (!blueprintDir.isDirectory() && !blueprintDir.mkdirs()) return;
        for (String name : List.of("tutorial_mine_t1.json")) {
            if (!new File(blueprintDir, name).exists()) {
                plugin.saveResource("blueprints/" + name, false);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(CustomItemCommand.ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        if (build.isBusy(p) && !sub.equals("sel") && !sub.equals("list") && !sub.equals("help")) {
            p.sendMessage(ChatColor.RED + "[Build] Your previous operation is still running.");
            return true;
        }
        try {
            switch (sub) {
                case "wand" -> giveWand(p);
                case "pos1", "pos2" -> setPos(p, sub.equals("pos1"), targetOrFeet(p));
                case "sel" -> showSelection(p);
                case "fill" -> fill(p, args);
                case "replace" -> replace(p, args);
                case "ores" -> ores(p, args);
                case "list" -> listBlueprints(p);
                case "preview" -> blueprint(p, args, false);
                case "run" -> blueprint(p, args, true);
                case "undo" -> build.undo(p);
                case "save" -> {
                    List<String> worlds = build.familyOf(p.getWorld().getName());
                    build.saveWorlds(worlds);
                    p.sendMessage(ChatColor.GREEN + "[Build] Saved " + worlds);
                }
                default -> help(p, label);
            }
        } catch (IllegalArgumentException e) {
            p.sendMessage(ChatColor.RED + "[Build] " + e.getMessage());
        }
        return true;
    }

    // ---------- selection ----------

    private void giveWand(Player p) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("DMB Wand", NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("Left-click block: pos1", NamedTextColor.GRAY),
                Component.text("Right-click block: pos2", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        p.getInventory().addItem(wand);
        p.sendMessage(ChatColor.GREEN + "[Build] Wand given. Left-click = pos1, right-click = pos2.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()
                || !item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        Player p = e.getPlayer();
        if (!p.hasPermission(CustomItemCommand.ADMIN_PERMISSION)) return;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) setPos(p, true, e.getClickedBlock());
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) setPos(p, false, e.getClickedBlock());
    }

    private void setPos(Player p, boolean first, Block b) {
        BuildManager.Selection s = build.selection(p);
        if (s.world != null && !s.world.equals(b.getWorld().getName())) {
            s.pos1 = null;
            s.pos2 = null;
        }
        s.world = b.getWorld().getName();
        int[] pos = {b.getX(), b.getY(), b.getZ()};
        if (first) s.pos1 = pos; else s.pos2 = pos;
        p.sendMessage(ChatColor.LIGHT_PURPLE + "[Build] pos" + (first ? 1 : 2) + " = " + pos[0] + " " + pos[1] + " " + pos[2]
                + (s.complete() ? ChatColor.GRAY + " (" + s.volume() + " blocks)" : ""));
    }

    private void showSelection(Player p) {
        BuildManager.Selection s = build.selection(p);
        if (!s.complete()) {
            p.sendMessage(ChatColor.GRAY + "[Build] No complete selection. Use /dmb wand or /dmb pos1 + pos2.");
            return;
        }
        int[] a = s.min(), b = s.max();
        p.sendMessage(ChatColor.LIGHT_PURPLE + "[Build] " + s.world + " " + Arrays.toString(a) + " -> " + Arrays.toString(b)
                + " = " + s.volume() + " blocks · mirrored to " + build.familyOf(s.world));
    }

    private BuildManager.Selection requireSelection(Player p) {
        BuildManager.Selection s = build.selection(p);
        if (!s.complete()) throw new IllegalArgumentException("Select an area first: /dmb wand (or /dmb pos1 + pos2).");
        if (!s.world.equals(p.getWorld().getName())) throw new IllegalArgumentException("Your selection is in " + s.world + "; go there first.");
        if (s.volume() > BuildManager.MAX_BLOCKS) throw new IllegalArgumentException("Selection too big (" + s.volume() + " > " + BuildManager.MAX_BLOCKS + ").");
        return s;
    }

    // ---------- selection operations ----------

    private void fill(Player p, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /dmb fill <block>");
        BuildManager.Selection s = requireSelection(p);
        BlockData data = blockData(args[1]);
        List<BuildManager.Change> changes = new ArrayList<>();
        forEach(s, (x, y, z) -> changes.add(new BuildManager.Change(x, y, z, data)));
        build.apply(p, s.world, new BuildManager.Plan("fill " + args[1], changes, Map.of(), Map.of()));
    }

    private void replace(Player p, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /dmb replace <from> <to>");
        BuildManager.Selection s = requireSelection(p);
        Material from = material(args[1]);
        BlockData to = blockData(args[2]);
        World w = p.getWorld();
        List<BuildManager.Change> changes = new ArrayList<>();
        forEach(s, (x, y, z) -> {
            if (w.getBlockAt(x, y, z).getType() == from) changes.add(new BuildManager.Change(x, y, z, to));
        });
        build.apply(p, s.world, new BuildManager.Plan("replace " + args[1] + " -> " + args[2], changes, Map.of(), Map.of()));
    }

    /** Turns a percentage of stone-like blocks in the selection into mining nodes of one ore type. */
    private void ores(Player p, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /dmb ores <ore_type> <percent> [all]");
        BuildManager.Selection s = requireSelection(p);
        OreType ore = plugin.getContentRegistryManager().getOreType(args[1]);
        if (ore == null) throw new IllegalArgumentException("Unknown ore type '" + args[1] + "' (see /mine types).");
        double percent;
        try { percent = Double.parseDouble(args[2]); } catch (NumberFormatException e) { throw new IllegalArgumentException("percent must be a number"); }
        if (percent <= 0 || percent > 100) throw new IllegalArgumentException("percent must be 0 < p <= 100");
        boolean exposedOnly = !(args.length > 3 && args[3].equalsIgnoreCase("all"));
        World w = p.getWorld();
        Random rnd = new Random();
        BlockData oreBlock = ore.block().createBlockData();
        List<BuildManager.Change> changes = new ArrayList<>();
        List<MineNodeManager.Pos> positions = new ArrayList<>();
        forEach(s, (x, y, z) -> {
            if (!STONE_LIKE.contains(w.getBlockAt(x, y, z).getType())) return;
            if (exposedOnly && !touchesAir(w, x, y, z)) return;
            if (rnd.nextDouble() * 100 >= percent) return;
            changes.add(new BuildManager.Change(x, y, z, oreBlock));
            positions.add(new MineNodeManager.Pos(x, y, z));
        });
        Map<String, List<MineNodeManager.Pos>> nodes = new LinkedHashMap<>();
        if (!positions.isEmpty()) nodes.put(ore.id(), positions);
        build.apply(p, s.world, new BuildManager.Plan("ores " + ore.id() + " " + percent + "%", changes, nodes, Map.of()));
    }

    // ---------- blueprints ----------

    private void listBlueprints(Player p) {
        File[] files = blueprintDir.listFiles((d, n) -> n.endsWith(".json"));
        p.sendMessage(ChatColor.GOLD + "=== Blueprints (" + (files == null ? 0 : files.length) + ") in " + blueprintDir.getPath() + " ===");
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().replace(".json", "");
            String desc = "";
            try { desc = load(name).description(); } catch (Exception ignored) { desc = ChatColor.RED + "(invalid JSON)"; }
            p.sendMessage(ChatColor.AQUA + name + ChatColor.GRAY + " - " + desc);
        }
    }

    private void blueprint(Player p, String[] args, boolean run) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /dmb " + (run ? "run" : "preview") + " <name>");
        Blueprint bp = load(args[1]);
        Block origin = p.getLocation().getBlock();
        BlockFace facing = p.getFacing();
        BuildManager.Plan plan = bp.plan(p.getWorld(), origin.getX(), origin.getY(), origin.getZ(), facing, plugin.getContentRegistryManager());
        if (!run) {
            p.sendMessage(ChatColor.GOLD + "[Build] Preview of " + bp.name() + " facing " + facing + " from " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
            p.sendMessage(ChatColor.YELLOW + "  blocks: " + plan.changes().size() + " · nodes: " + plan.nodeCount()
                    + " " + plan.nodesByOre().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).collect(Collectors.joining(", "))
                    + " · worlds: " + build.familyOf(p.getWorld().getName()));
            plan.markers().forEach((k, v) -> p.sendMessage(ChatColor.AQUA + "  marker " + k + " = " + v[0] + " " + v[1] + " " + v[2]));
            p.sendMessage(ChatColor.GRAY + "  Stand in the same spot, face the same way, and /dmb run " + bp.name() + " to build it.");
            return;
        }
        build.apply(p, p.getWorld().getName(), plan);
    }

    private Blueprint load(String name) {
        if (!name.matches("[A-Za-z0-9_\\-]{1,64}")) throw new IllegalArgumentException("Bad blueprint name '" + name + "'.");
        File f = new File(blueprintDir, name + ".json");
        if (!f.isFile()) throw new IllegalArgumentException("Blueprint '" + name + "' not found in " + blueprintDir.getPath() + " (see /dmb list).");
        try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
            return new Blueprint(name, json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Blueprint '" + name + "' is not valid JSON: " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private interface XYZ { void accept(int x, int y, int z); }

    private static void forEach(BuildManager.Selection s, XYZ fn) {
        int[] a = s.min(), b = s.max();
        for (int x = a[0]; x <= b[0]; x++)
            for (int y = a[1]; y <= b[1]; y++)
                for (int z = a[2]; z <= b[2]; z++) fn.accept(x, y, z);
    }

    private static boolean touchesAir(World w, int x, int y, int z) {
        return w.getBlockAt(x + 1, y, z).getType().isAir() || w.getBlockAt(x - 1, y, z).getType().isAir()
                || w.getBlockAt(x, y + 1, z).getType().isAir() || w.getBlockAt(x, y - 1, z).getType().isAir()
                || w.getBlockAt(x, y, z + 1).getType().isAir() || w.getBlockAt(x, y, z - 1).getType().isAir();
    }

    private static Block targetOrFeet(Player p) {
        Block t = p.getTargetBlockExact(8);
        return t != null && !t.getType().isAir() ? t : p.getLocation().getBlock();
    }

    private static BlockData blockData(String spec) {
        try {
            return Bukkit.createBlockData(spec.contains(":") ? spec : "minecraft:" + spec.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown block '" + spec + "'.");
        }
    }

    private static Material material(String name) {
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (m == null || !m.isBlock()) throw new IllegalArgumentException("Unknown block '" + name + "'.");
        return m;
    }

    private void help(Player p, String label) {
        String l = "/" + label;
        p.sendMessage(ChatColor.GOLD + "=== " + l + " (world building, admin) ===");
        p.sendMessage(ChatColor.YELLOW + l + " wand" + ChatColor.GRAY + " - selection wand (left = pos1, right = pos2)");
        p.sendMessage(ChatColor.YELLOW + l + " pos1|pos2|sel" + ChatColor.GRAY + " - set / show selection");
        p.sendMessage(ChatColor.YELLOW + l + " fill <block>" + ChatColor.GRAY + " · " + ChatColor.YELLOW + l + " replace <from> <to>");
        p.sendMessage(ChatColor.YELLOW + l + " ores <ore_type> <percent> [all]" + ChatColor.GRAY + " - stone in selection -> mining nodes");
        p.sendMessage(ChatColor.YELLOW + l + " list | preview <name> | run <name>" + ChatColor.GRAY + " - blueprints");
        p.sendMessage(ChatColor.YELLOW + l + " undo | save" + ChatColor.GRAY + " - edits auto-save; worlds otherwise never save");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(CustomItemCommand.ADMIN_PERMISSION)) return Collections.emptyList();
        if (args.length == 1) {
            return filter(List.of("wand", "pos1", "pos2", "sel", "fill", "replace", "ores", "list", "preview", "run", "undo", "save"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("preview") || sub.equals("run"))) {
            File[] files = blueprintDir.listFiles((d, n) -> n.endsWith(".json"));
            List<String> names = new ArrayList<>();
            if (files != null) for (File f : files) names.add(f.getName().replace(".json", ""));
            return filter(names, args[1]);
        }
        if (args.length == 2 && sub.equals("ores")) {
            List<String> ids = new ArrayList<>();
            for (OreType t : plugin.getContentRegistryManager().getAllOreTypes()) ids.add(t.id());
            return filter(ids, args[1]);
        }
        if ((args.length == 2 && (sub.equals("fill") || sub.equals("replace"))) || (args.length == 3 && sub.equals("replace"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Material.values()).filter(Material::isBlock).map(m -> m.name().toLowerCase(Locale.ROOT))
                    .filter(n -> n.startsWith(prefix)).limit(40).collect(Collectors.toList());
        }
        if (args.length == 4 && sub.equals("ores")) return filter(List.of("all"), args[3]);
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }
}
