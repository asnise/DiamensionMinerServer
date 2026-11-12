package com.dimensionalminer.core.dimensionalMinerCore.workspace;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.craft.GuiMenu;
import com.dimensionalminer.core.dimensionalMinerCore.craft.Recipe;
import com.dimensionalminer.core.dimensionalMinerCore.util.WorldFiles;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Workspaces (8.1) and processing jobs (8.2).
 * <ul>
 *   <li>Every player gets a workspace (MVP, no unlock). Its world is a copy of {@code workspace.template}
 *       (or a flat world when that folder is missing), loaded only while someone is inside, stateless
 *       (deleted on unload). What persists is in the DB: the stations placed on the pads (layout), the stash
 *       chest, the ACL and the jobs.</li>
 *   <li>Pads ({@code workspace.pads}, offsets from the world spawn) hold one station each
 *       ({@code workspace.stations}: TYPE → block). The owner places them in {@code /ws manage}.</li>
 *   <li>ACL per player: VISIT (enter), USE_STATION (start jobs), STORAGE (open the stash). The owner has all.</li>
 *   <li>Jobs (content kind ProcessingRecipe, station = TYPE): inputs escrowed, timestamp-based, so they finish
 *       while everyone is offline; a worker creates the output grant once (/grants).</li>
 * </ul>
 */
public final class WorkspaceManager implements Listener, CommandExecutor, TabCompleter {

    private final DimensionalMinerCore plugin;
    private final WorkspaceStore store;
    private final String template;
    private final long unloadAfterMs;
    private final List<int[]> pads = new ArrayList<>();
    private final int[] stashOffset;
    /** Station TYPE → block shown on the pad (insertion order = cycle order in /ws manage). */
    private final Map<String, Material> stations = new LinkedHashMap<>();

    /** A loaded workspace world. */
    private final class Loaded {
        final WorkspaceStore.Workspace ws;
        final World world;
        Map<UUID, Set<WorkspaceStore.Perm>> acl = new HashMap<>();
        long emptySince;
        UUID stashViewer;
        /** The stash as last closed (null until first opened in this load); the source of truth while loaded. */
        Map<Integer, ItemStack> stash;
        /** RUNNING jobs as last read from the DB; labels count down from their finishes_at in memory. */
        List<WorkspaceStore.Job> jobs = List.of();
        long jobsReadAt;
        boolean jobsReading;

        Loaded(WorkspaceStore.Workspace ws, World world) { this.ws = ws; this.world = world; }
    }

    private final Map<String, Loaded> loaded = new HashMap<>();
    private final Map<UUID, String> loading = new HashMap<>(); // workspace → world name being prepared

    public WorkspaceManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.store = new WorkspaceStore(plugin);
        this.asyncPool = r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
        var c = plugin.getConfig();
        this.template = c.getString("workspace.template", "ws_template");
        this.unloadAfterMs = Math.max(10, c.getLong("workspace.unload_after_seconds", 60)) * 1000L;
        for (String s : c.getStringList("workspace.pads")) {
            int[] v = parse(s);
            if (v != null) pads.add(v);
        }
        int[] st = parse(c.getString("workspace.stash_offset", "-3,0,0"));
        this.stashOffset = st != null ? st : new int[]{-3, 0, 0};
        ConfigurationSection sec = c.getConfigurationSection("workspace.stations");
        if (sec != null) for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(sec.getString(k, "FURNACE"));
            stations.put(k.toUpperCase(Locale.ROOT), m != null && m.isBlock() ? m : Material.FURNACE);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 100L, 100L);
        // Station labels: redrawn every second from memory (no DB), so the countdown moves smoothly.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickLabels, 20L, 20L);
        // Job worker: outputs of finished jobs → mailbox (runs whether or not anyone is online).
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            List<String[]> done = store.completeDue();
            if (done.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String[] d : done) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        UUID acc = accountOf(p);
                        if (acc != null && acc.toString().equals(d[0])) {
                            var r = plugin.getContentRegistryManager().getProcessingRecipe(d[1]);
                            p.sendMessage(Component.text("[Workspace] " + (r != null ? r.name() : d[1]) + " is done. Collect it in /grants.", NamedTextColor.GREEN));
                        }
                    }
                }
            });
        }, 200L, 200L);
    }

    private static int[] parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID accountOf(Player p) {
        try {
            return plugin.getDatabaseManager().getAccountId(p.getUniqueId());
        } catch (SQLException e) {
            return null;
        }
    }

    private static String worldName(UUID ws) {
        return "ws_" + ws.toString().replace("-", "").substring(0, 12);
    }

    public boolean isWorkspaceWorld(World w) {
        return w != null && loaded.containsKey(w.getName());
    }

    // ------------------------------------------------------------------ world lifecycle (main thread)

    private void enter(Player p, WorkspaceStore.Workspace ws) {
        if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(p)) {
            p.sendMessage(Component.text("[Workspace] Leave the dungeon first.", NamedTextColor.RED));
            return;
        }
        String name = worldName(ws.id());
        Loaded l = loaded.get(name);
        if (l != null) { go(p, l); return; }
        if (loading.containsKey(ws.id())) { p.sendMessage(Component.text("[Workspace] Opening, one moment...", NamedTextColor.GRAY)); return; }
        loading.put(ws.id(), name);
        p.sendMessage(Component.text("[Workspace] Opening " + (ws.owner().equals(accountOf(p)) ? "your workspace" : ws.ownerName() + "'s workspace") + "...", NamedTextColor.GRAY));
        File templateDir = new File(Bukkit.getWorldContainer(), template);
        File target = new File(new File(Bukkit.getWorldContainer(), "overworld_t0"), "dimensions/minecraft/" + name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Set<WorkspaceStore.Perm>> acl;
            try {
                WorldFiles.deleteTree(target.toPath()); // a leftover from a crash
                if (templateDir.isDirectory()) WorldFiles.copyWorld(templateDir.toPath(), target.toPath());
                acl = store.acl(ws.id());
            } catch (Exception e) {
                plugin.getLogger().warning("[Workspace] Could not prepare " + name + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading.remove(ws.id());
                    p.sendMessage(Component.text("[Workspace] Could not open the workspace.", NamedTextColor.RED));
                });
                return;
            }
            Map<UUID, Set<WorkspaceStore.Perm>> fAcl = acl;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(ws.id());
                WorldCreator wc = new WorldCreator(name);
                if (!templateDir.isDirectory()) wc.type(WorldType.FLAT).generateStructures(false);
                World w = wc.createWorld();
                if (w == null) { p.sendMessage(Component.text("[Workspace] Could not load the world.", NamedTextColor.RED)); return; }
                w.setAutoSave(false);
                w.setGameRule(GameRule.KEEP_INVENTORY, true);
                w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                w.setTime(6000);
                Loaded nl = new Loaded(ws, w);
                nl.acl = fAcl;
                loaded.put(name, nl);
                render(nl);
                if (p.isOnline()) go(p, nl);
            });
        });
    }

    /** world → standing block at its spawn, fixed at first use so placed stations never move the grid. */
    private final Map<String, Location> origins = new HashMap<>();

    private Location origin(World w) {
        return origins.computeIfAbsent(w.getName(), k -> {
            Location s = w.getSpawnLocation();
            int y = w.getHighestBlockYAt(s.getBlockX(), s.getBlockZ()) + 1;
            return new Location(w, s.getBlockX(), y, s.getBlockZ());
        }).clone();
    }

    private Location padLoc(World w, int[] off) {
        return origin(w).add(off[0], off[1], off[2]);
    }

    /** Places the station blocks (and the stash chest) from the layout. */
    private void render(Loaded l) {
        JsonObject placed = l.ws.layout().has("pads") ? l.ws.layout().getAsJsonObject("pads") : new JsonObject();
        for (int i = 0; i < pads.size(); i++) {
            Block b = padLoc(l.world, pads.get(i)).getBlock();
            String type = placed.has(String.valueOf(i)) ? placed.get(String.valueOf(i)).getAsString() : null;
            Material m = type != null ? stations.get(type) : null;
            b.setType(m != null ? m : Material.AIR, false);
            b.getRelative(0, -1, 0).setType(Material.POLISHED_ANDESITE, false); // pad marker
        }
        padLoc(l.world, stashOffset).getBlock().setType(Material.CHEST, false);
    }

    private void go(Player p, Loaded l) {
        plugin.getInventorySync().snapshotAndMaybeSave(p);
        Location to = origin(l.world).add(0.5, 0, 0.5);
        p.teleportAsync(to).thenAccept(ok -> {
            if (ok) p.sendMessage(Component.text("[Workspace] Welcome to " + l.ws.ownerName() + "'s workspace. /ws leave to go back.", NamedTextColor.AQUA));
        });
    }

    private void leave(Player p) {
        World hub = Bukkit.getWorld("overworld_t0");
        if (hub != null) p.teleportAsync(hub.getSpawnLocation());
    }

    private void upkeep() {
        long now = System.currentTimeMillis();
        for (Loaded l : List.copyOf(loaded.values())) {
            if (!l.world.getPlayers().isEmpty()) { l.emptySince = 0; continue; }
            if (l.emptySince == 0) { l.emptySince = now; continue; }
            if (now - l.emptySince < unloadAfterMs || l.stashViewer != null) continue;
            unload(l);
        }
    }

    /** Floating text over each station: what it is doing, so inputs never seem to just vanish. */
    private final Map<String, org.bukkit.entity.TextDisplay> labels = new HashMap<>();

    /** DB re-read of the jobs as a safety net (start / cancel / finish already trigger one). */
    private static final long JOBS_RECHECK_MS = 60_000;
    /** The job worker runs every 10 s; read again this long after a job's finish time to see it gone. */
    private static final long AFTER_FINISH_MS = 12_000;

    private void tickLabels() {
        long now = System.currentTimeMillis();
        for (Loaded l : loaded.values()) {
            if (l.world.getPlayers().isEmpty()) continue; // nobody to see it
            boolean finishedSinceRead = l.jobs.stream().anyMatch(j -> now >= j.finishesAtMillis() + AFTER_FINISH_MS
                    && l.jobsReadAt < j.finishesAtMillis() + AFTER_FINISH_MS);
            if (now - l.jobsReadAt > JOBS_RECHECK_MS || finishedSinceRead) refreshLabels(l);
            drawLabels(l, l.jobs);
        }
    }

    /** Reads the RUNNING jobs once (async) into the cache, then redraws. */
    private void refreshLabels(Loaded l) {
        if (l.jobsReading) return;
        l.jobsReading = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<WorkspaceStore.Job> jobs;
            try {
                jobs = store.jobs(l.ws.id());
            } catch (SQLException e) {
                jobs = null;
            }
            List<WorkspaceStore.Job> fj = jobs;
            Bukkit.getScheduler().runTask(plugin, () -> {
                l.jobsReading = false;
                l.jobsReadAt = System.currentTimeMillis();
                if (fj == null) return; // keep the old cache, retry at the next recheck
                l.jobs = List.copyOf(fj);
                drawLabels(l, l.jobs);
            });
        });
    }

    private void drawLabels(Loaded l, List<WorkspaceStore.Job> jobs) {
        if (loaded.get(l.world.getName()) != l) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < pads.size(); i++) {
            String key = l.world.getName() + "#" + i;
            String type = stationAt(l, i);
            org.bukkit.entity.TextDisplay d = labels.get(key);
            if (type == null) {
                if (d != null) { d.remove(); labels.remove(key); }
                continue;
            }
            int pad = i;
            WorkspaceStore.Job job = jobs.stream().filter(j -> j.pad() == pad).findFirst().orElse(null);
            Component text;
            if (job != null) {
                var r = plugin.getContentRegistryManager().getProcessingRecipe(job.recipeId());
                long left = Math.max(0, job.finishesAtMillis() - now) / 1000;
                text = Component.text(pretty(type), NamedTextColor.GOLD)
                        .append(Component.newline())
                        .append(Component.text(r != null ? r.name() : job.recipeId(), NamedTextColor.WHITE))
                        .append(Component.newline())
                        .append(Component.text(left > 0 ? "⏳ " + fmt(left) : "✔ Done → /grants", left > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
            } else {
                text = Component.text(pretty(type), NamedTextColor.GOLD)
                        .append(Component.newline())
                        .append(Component.text("Idle · right-click", NamedTextColor.GRAY));
            }
            if (d == null || !d.isValid()) {
                Location at = padLoc(l.world, pads.get(i)).add(0.5, 1.4, 0.5);
                d = l.world.spawn(at, org.bukkit.entity.TextDisplay.class, e -> {
                    e.setPersistent(false);
                    e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    e.setShadowed(true);
                });
                labels.put(key, d);
            }
            if (!text.equals(d.text())) d.text(text); // unchanged text (idle pads) sends no packet
        }
    }

    private void unload(Loaded l) {
        labels.keySet().removeIf(k -> k.startsWith(l.world.getName() + "#"));
        for (Player p : List.copyOf(l.world.getPlayers())) {
            World hub = Bukkit.getWorld("overworld_t0");
            if (hub != null) p.teleport(hub.getSpawnLocation());
        }
        String name = l.world.getName();
        if (!Bukkit.unloadWorld(l.world, false)) return; // retried next upkeep
        loaded.remove(name);
        origins.remove(name);
        File dir = new File(new File(Bukkit.getWorldContainer(), "overworld_t0"), "dimensions/minecraft/" + name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WorldFiles.deleteTree(dir.toPath());
            } catch (Exception e) {
                plugin.getLogger().warning("[Workspace] Could not delete " + dir + ": " + e.getMessage());
            }
        });
    }

    /** onDisable: everyone out, worlds unloaded (folders are removed on the next open). */
    public void shutdown() {
        for (Loaded l : List.copyOf(loaded.values())) {
            for (Player p : List.copyOf(l.world.getPlayers())) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof StashHolder) p.closeInventory(); // saves the stash
                World hub = Bukkit.getWorld("overworld_t0");
                if (hub != null) p.teleport(hub.getSpawnLocation());
            }
            Bukkit.unloadWorld(l.world, false);
        }
        loaded.clear();
    }

    // ------------------------------------------------------------------ permissions

    private boolean has(Loaded l, Player p, WorkspaceStore.Perm perm) {
        UUID acc = accountOf(p);
        if (acc == null) return false;
        if (acc.equals(l.ws.owner())) return true;
        return l.acl.getOrDefault(acc, Set.of()).contains(perm);
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Loaded l = loaded.get(event.getPlayer().getWorld().getName());
        if (l == null) return;
        Location at = event.getClickedBlock().getLocation();
        Player p = event.getPlayer();
        if (at.equals(padLoc(l.world, stashOffset))) {
            event.setCancelled(true);
            if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
            if (!has(l, p, WorkspaceStore.Perm.STORAGE)) { p.sendMessage(Component.text("[Workspace] You may not open this stash.", NamedTextColor.RED)); return; }
            openStash(p, l);
            return;
        }
        for (int i = 0; i < pads.size(); i++) {
            if (!at.equals(padLoc(l.world, pads.get(i)))) continue;
            event.setCancelled(true);
            if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
            String type = stationAt(l, i);
            if (type == null) return;
            if (!has(l, p, WorkspaceStore.Perm.USE_STATION)) { p.sendMessage(Component.text("[Workspace] You may not use these stations.", NamedTextColor.RED)); return; }
            openStation(p, l, i, type);
            return;
        }
    }

    private String stationAt(Loaded l, int pad) {
        JsonObject placed = l.ws.layout().has("pads") ? l.ws.layout().getAsJsonObject("pads") : null;
        return placed != null && placed.has(String.valueOf(pad)) ? placed.get(String.valueOf(pad)).getAsString() : null;
    }

    // ------------------------------------------------------------------ stash

    private static final class StashHolder implements InventoryHolder {
        final Loaded l;
        Inventory inv;
        StashHolder(Loaded l) { this.l = l; }
        @Override public Inventory getInventory() { return inv; }
    }

    /**
     * Stash saves per workspace, run one after another. A load waits for the saves queued before it,
     * so reopening right after closing can never read an older copy and save it over the new one.
     */
    private final Map<UUID, java.util.concurrent.CompletableFuture<Void>> stashSaves = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.Executor asyncPool;

    private java.util.concurrent.CompletableFuture<Void> pendingSaves(UUID ws) {
        return stashSaves.getOrDefault(ws, java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private void openStash(Player p, Loaded l) {
        if (l.stashViewer != null) {
            if (!l.stashViewer.equals(p.getUniqueId()))
                p.sendMessage(Component.text("[Workspace] Someone else is using the stash.", NamedTextColor.YELLOW));
            return;
        }
        l.stashViewer = p.getUniqueId();
        if (l.stash != null) { showStash(p, l); return; }
        pendingSaves(l.ws.id()).thenRunAsync(() -> {
            Map<Integer, ItemStack> items;
            try {
                items = store.loadStash(l.ws);
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    l.stashViewer = null;
                    p.sendMessage(Component.text("[Workspace] The stash is unavailable (database).", NamedTextColor.RED));
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (l.stash == null) l.stash = items;
                showStash(p, l);
            });
        }, asyncPool);
    }

    private void showStash(Player p, Loaded l) {
        if (!p.isOnline() || !p.getWorld().equals(l.world)) { l.stashViewer = null; return; }
        StashHolder h = new StashHolder(l);
        h.inv = Bukkit.createInventory(h, 27, Component.text(l.ws.ownerName() + "'s stash"));
        l.stash.forEach((slot, it) -> { if (slot >= 0 && slot < 27) h.inv.setItem(slot, it.clone()); });
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onStashClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof StashHolder h)) return;
        Map<Integer, ItemStack> items = new HashMap<>();
        Map<Integer, String> ids = new HashMap<>();
        for (int i = 0; i < 27; i++) {
            ItemStack it = event.getInventory().getItem(i);
            if (it == null || it.isEmpty()) continue;
            items.put(i, it.clone());
            ids.put(i, itemId(it));
        }
        Loaded l = h.l;
        l.stash = items;           // the next open shows exactly this, without a DB round trip
        l.stashViewer = null;
        Player p = (Player) event.getPlayer();
        Map<Integer, ItemStack> snapshot = new HashMap<>();
        items.forEach((k, v) -> snapshot.put(k, v.clone()));
        if (!plugin.isEnabled()) {
            // Shutdown: schedulers no longer accept tasks. Let queued saves finish, then save here.
            try {
                pendingSaves(l.ws.id()).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            saveNow(l, snapshot, ids);
            return;
        }
        plugin.getInventorySync().snapshotAndMaybeSave(p);
        stashSaves.compute(l.ws.id(), (k, prev) -> (prev == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : prev)
                .thenRunAsync(() -> saveNow(l, snapshot, ids), asyncPool));
    }

    private void saveNow(Loaded l, Map<Integer, ItemStack> items, Map<Integer, String> ids) {
        try {
            store.saveStash(l.ws, items, ids);
        } catch (SQLException e) {
            plugin.getLogger().severe("[Workspace] Stash save of " + l.ws.id() + " failed: " + e.getMessage()
                    + " (" + items.size() + " stack(s) kept in memory; saved again on the next close)");
        }
    }

    private String itemId(ItemStack s) {
        String id = plugin.getGearItems() != null ? plugin.getGearItems().definitionId(s) : null;
        return id != null ? id.toLowerCase(Locale.ROOT) : s.getType().name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ station menu (jobs)

    private void openStation(Player p, Loaded l, int pad, String type) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<WorkspaceStore.Job> jobs;
            try {
                jobs = store.jobs(l.ws.id());
            } catch (SQLException e) {
                jobs = null;
            }
            List<WorkspaceStore.Job> fj = jobs;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (fj == null) { p.sendMessage(Component.text("[Workspace] Unavailable (database).", NamedTextColor.RED)); return; }
                WorkspaceStore.Job running = fj.stream().filter(j -> j.pad() == pad).findFirst().orElse(null);
                if (p.isOnline()) new StationMenu(p, l, pad, type, running).open();
            });
        });
    }

    private final class StationMenu extends GuiMenu {
        private final Loaded l;
        private final int pad;
        private final String type;
        private final WorkspaceStore.Job running;
        private final List<Recipe> recipes = new ArrayList<>();

        StationMenu(Player p, Loaded l, int pad, String type, WorkspaceStore.Job running) {
            super(null, p, false);
            this.l = l;
            this.pad = pad;
            this.type = type;
            this.running = running;
            for (Recipe r : plugin.getContentRegistryManager().getAllProcessingRecipes())
                if (r.station().equalsIgnoreCase(type)) recipes.add(r);
        }

        void open() {
            inventory = Bukkit.createInventory(this, ROWS * 9, Component.text(pretty(type) + " · Pad " + (pad + 1)));
            var craft = plugin.getCraftService();
            if (running != null) {
                Recipe r = plugin.getContentRegistryManager().getProcessingRecipe(running.recipeId());
                long left = Math.max(0, running.finishesAtMillis() - System.currentTimeMillis()) / 1000;
                inventory.setItem(4, named(Material.CLOCK, "Working: " + (r != null ? r.name() : running.recipeId()), NamedTextColor.GOLD,
                        List.of("Started by " + running.playerName(), "Done in " + fmt(left) + " (keeps going while offline)",
                                "Output goes to the starter's /grants", "Shift-click: cancel (inputs back to /grants)")));
            } else {
                inventory.setItem(4, named(Material.LIME_STAINED_GLASS_PANE, "Idle: pick a recipe below", NamedTextColor.GREEN, List.of()));
            }
            for (int i = 0; i < recipes.size() && i < 36; i++) {
                Recipe r = recipes.get(i);
                ItemStack icon = craft.displayStack(r.mainOutput().item(), r.mainOutput().qty());
                ItemMeta m = icon.getItemMeta();
                m.displayName(text(r.name(), NamedTextColor.AQUA));
                List<Component> lore = new ArrayList<>();
                for (Recipe.Stack in : r.inputs()) {
                    int have = craft.count(player, in.item());
                    lore.add(text((have >= in.qty() ? "✔ " : "✘ ") + in.qty() + "x " + craft.displayName(in.item()) + " (" + have + ")",
                            have >= in.qty() ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                for (Recipe.Output o : r.outputs())
                    lore.add(text("→ " + o.qty() + "x " + craft.displayName(o.item()) + (o.chance() < 100 ? " (" + (int) o.chance() + "%)" : ""), NamedTextColor.GRAY));
                lore.add(text("Time: " + fmt(r.durationSeconds()), NamedTextColor.YELLOW));
                lore.add(text(running == null ? "Click to start" : "Pad is busy", running == null ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));
                m.lore(lore);
                icon.setItemMeta(m);
                inventory.setItem(18 + i, icon);
            }
            if (recipes.isEmpty()) inventory.setItem(22, named(Material.PAPER, "No recipes for " + pretty(type) + " yet", NamedTextColor.GRAY, List.of()));
            fillEmpty();
            player.openInventory(inventory);
        }

        @Override
        protected void clickTop(int slot, boolean shift) {
            if (slot == 4 && running != null && shift) {
                UUID acc = accountOf(player);
                player.closeInventory();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean ok = acc != null && store.cancel(running.id(), acc, l.ws.owner());
                    Bukkit.getScheduler().runTask(plugin, () -> refreshLabels(l));
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ok
                            ? Component.text("[Workspace] Job cancelled. The inputs are in the starter's /grants.", NamedTextColor.YELLOW)
                            : Component.text("[Workspace] Only the starter or the owner can cancel (or it already finished).", NamedTextColor.RED)));
                });
                return;
            }
            int i = slot - 18;
            if (i < 0 || i >= recipes.size() || running != null) return;
            start(player, l, pad, recipes.get(i));
        }
    }

    private void start(Player p, Loaded l, int pad, Recipe r) {
        var craft = plugin.getCraftService();
        for (Recipe.Stack in : r.inputs()) {
            if (craft.count(p, in.item()) < in.qty()) {
                p.sendMessage(Component.text("[Workspace] Missing " + in.qty() + "x " + craft.displayName(in.item()) + ".", NamedTextColor.RED));
                return;
            }
        }
        UUID acc = accountOf(p);
        if (acc == null || !craft.tryBegin(p.getUniqueId())) return;
        List<ItemStack> taken = craft.take(p, com.dimensionalminer.core.dimensionalMinerCore.craft.CraftService.amounts(r.inputs()));
        craft.persist(p);
        Map<ItemStack, String> ids = new HashMap<>();
        for (ItemStack s : taken) ids.put(s, itemId(s));
        p.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            WorkspaceStore.Start st = store.start(l.ws.id(), acc, r, pad, taken, ids);
            Bukkit.getScheduler().runTask(plugin, () -> {
                craft.end(p.getUniqueId());
                if (st == WorkspaceStore.Start.OK) {
                    refreshLabels(l);
                    p.sendMessage(Component.text("[Workspace] Started " + r.name() + ": ready in " + fmt(r.durationSeconds())
                            + ". It keeps going while you are offline; the result arrives in /grants.", NamedTextColor.GREEN));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 1f, 1f);
                    return;
                }
                if (p.isOnline()) { craft.give(p, taken); craft.persist(p); } else craft.returnOffline(p.getUniqueId(), taken, "PROCESSING_RETURN");
                p.sendMessage(Component.text(st == WorkspaceStore.Start.PAD_BUSY
                        ? "[Workspace] This pad already has a job. Your items were given back."
                        : "[Workspace] Could not start (database). Your items were given back.", NamedTextColor.RED));
            });
        });
    }

    // ------------------------------------------------------------------ manage menu (owner)

    private final class ManageMenu extends GuiMenu {
        private final Loaded l;
        private final Set<Integer> busyPads;

        ManageMenu(Player p, Loaded l, Set<Integer> busyPads) {
            super(null, p, false);
            this.l = l;
            this.busyPads = busyPads;
        }

        void open() {
            inventory = Bukkit.createInventory(this, 27, Component.text("Workspace · Stations"));
            for (int i = 0; i < pads.size() && i < 18; i++) {
                String type = stationAt(l, i);
                Material m = type != null ? stations.getOrDefault(type, Material.FURNACE) : Material.LIGHT_GRAY_STAINED_GLASS_PANE;
                List<String> lore = new ArrayList<>();
                lore.add(type != null ? "Station: " + pretty(type) : "Empty pad");
                if (busyPads.contains(i)) lore.add("A job is running here: cannot change");
                else {
                    lore.add("Click: next station type");
                    lore.add("Shift-click: clear the pad");
                }
                inventory.setItem(i, named(m.isItem() ? m : Material.FURNACE, "Pad " + (i + 1), NamedTextColor.AQUA, lore));
            }
            inventory.setItem(22, named(Material.CHEST, "Stash (right-click the chest)", NamedTextColor.GOLD,
                    List.of("Access: /ws allow <player> <visit|use|storage|all>")));
            fillEmpty();
            player.openInventory(inventory);
        }

        @Override
        protected void clickTop(int slot, boolean shift) {
            if (slot < 0 || slot >= pads.size() || slot >= 18 || busyPads.contains(slot)) return;
            JsonObject layout = l.ws.layout();
            JsonObject placed = layout.has("pads") ? layout.getAsJsonObject("pads") : new JsonObject();
            String key = String.valueOf(slot);
            String cur = placed.has(key) ? placed.get(key).getAsString() : null;
            if (shift) placed.remove(key);
            else {
                List<String> order = new ArrayList<>(stations.keySet());
                if (order.isEmpty()) return;
                int idx = cur == null ? 0 : (order.indexOf(cur) + 1) % order.size();
                placed.addProperty(key, order.get(idx));
            }
            layout.add("pads", placed);
            render(l);
            refreshLabels(l);
            open();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    store.saveLayout(l.ws.id(), layout);
                } catch (SQLException e) {
                    plugin.getLogger().warning("[Workspace] Layout save failed: " + e.getMessage());
                }
            });
        }
    }

    // ------------------------------------------------------------------ /ws

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        String sub = args.length == 0 ? "home" : args[0].toLowerCase(Locale.ROOT);
        UUID acc = accountOf(p);
        if (acc == null) { p.sendMessage(Component.text("[Workspace] Your account is still loading.", NamedTextColor.RED)); return true; }
        switch (sub) {
            case "home" -> async(p, () -> store.ensure(acc), ws -> enter(p, ws));
            case "leave" -> leave(p);
            case "visit" -> {
                if (args.length < 2) { p.sendMessage(Component.text("/" + label + " visit <player>", NamedTextColor.RED)); return true; }
                async(p, () -> {
                    WorkspaceStore.Workspace ws = store.byOwnerName(args[1]);
                    if (ws == null) return null;
                    if (!ws.owner().equals(acc) && store.acl(ws.id()).getOrDefault(acc, Set.of()).isEmpty()) return new WorkspaceStore.Workspace(null, null, ws.ownerName(), null);
                    return ws;
                }, ws -> {
                    if (ws == null) p.sendMessage(Component.text("[Workspace] " + args[1] + " has no workspace yet.", NamedTextColor.RED));
                    else if (ws.id() == null) p.sendMessage(Component.text("[Workspace] " + ws.ownerName() + " has not given you access.", NamedTextColor.RED));
                    else enter(p, ws);
                });
            }
            case "manage" -> async(p, () -> store.ensure(acc), ws -> {
                Loaded l = loaded.get(worldName(ws.id()));
                if (l == null || !p.getWorld().equals(l.world)) { p.sendMessage(Component.text("[Workspace] Go to your workspace first (/ws).", NamedTextColor.YELLOW)); return; }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Set<Integer> busy = new java.util.HashSet<>();
                    try {
                        for (WorkspaceStore.Job j : store.jobs(ws.id())) busy.add(j.pad());
                    } catch (SQLException ignored) {
                        for (int i = 0; i < pads.size(); i++) busy.add(i); // unknown: lock everything
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> new ManageMenu(p, l, busy).open());
                });
            });
            case "allow", "deny" -> {
                if (args.length < 2) { p.sendMessage(Component.text("/" + label + " " + sub + " <player> [visit|use|storage|all]", NamedTextColor.RED)); return true; }
                Set<WorkspaceStore.Perm> perms = perms(args.length >= 3 ? args[2] : (sub.equals("allow") ? "visit" : "all"));
                if (perms == null) { p.sendMessage(Component.text("[Workspace] Permission: visit, use, storage or all.", NamedTextColor.RED)); return true; }
                if (sub.equals("allow") && !perms.isEmpty()) perms.add(WorkspaceStore.Perm.VISIT); // using needs entering
                boolean allow = sub.equals("allow");
                async(p, () -> {
                    Object[] who = store.accountByName(args[1]);
                    if (who == null) return null;
                    WorkspaceStore.Workspace ws = store.ensure(acc);
                    store.setAcl(ws.id(), (UUID) who[0], allow ? perms : Set.of(), allow ? Set.of() : perms);
                    Loaded l = loaded.get(worldName(ws.id()));
                    if (l != null) l.acl = store.acl(ws.id());
                    return (String) who[1];
                }, name -> p.sendMessage(name == null
                        ? Component.text("[Workspace] No player named " + args[1] + ".", NamedTextColor.RED)
                        : Component.text("[Workspace] " + (allow ? "Gave " : "Removed ") + name + ": " + perms, NamedTextColor.GREEN)));
            }
            case "access" -> async(p, () -> {
                WorkspaceStore.Workspace ws = store.ensure(acc);
                Map<UUID, Set<WorkspaceStore.Perm>> acl = store.acl(ws.id());
                Map<UUID, String> names = store.names(acl.keySet());
                List<String> out = new ArrayList<>();
                acl.forEach((k, v) -> out.add(names.getOrDefault(k, k.toString().substring(0, 8)) + ": " + v));
                return out;
            }, list -> {
                p.sendMessage(Component.text("[Workspace] Access to your workspace (" + list.size() + "):", NamedTextColor.GOLD));
                for (String s : list) p.sendMessage(Component.text("  " + s, NamedTextColor.GRAY));
            });
            case "jobs" -> async(p, () -> store.jobsOf(acc), list -> {
                p.sendMessage(Component.text("[Workspace] Your jobs:", NamedTextColor.GOLD));
                if (list.isEmpty()) p.sendMessage(Component.text("  none", NamedTextColor.GRAY));
                long now = System.currentTimeMillis();
                for (WorkspaceStore.Job j : list) {
                    var r = plugin.getContentRegistryManager().getProcessingRecipe(j.recipeId());
                    String name = r != null ? r.name() : j.recipeId();
                    p.sendMessage(Component.text("  " + name + " · pad " + (j.pad() + 1) + " · " + (j.state().equals("RUNNING")
                            ? "done in " + fmt(Math.max(0, j.finishesAtMillis() - now) / 1000) : j.state()), NamedTextColor.GRAY));
                }
            });
            default -> p.sendMessage(Component.text("/" + label + " [home] | visit <player> | leave | manage | allow <player> [visit|use|storage|all] | deny <player> [perm] | access | jobs", NamedTextColor.YELLOW));
        }
        return true;
    }

    private static Set<WorkspaceStore.Perm> perms(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "visit" -> EnumSet.of(WorkspaceStore.Perm.VISIT);
            case "use", "use_station", "station" -> EnumSet.of(WorkspaceStore.Perm.USE_STATION);
            case "storage", "stash" -> EnumSet.of(WorkspaceStore.Perm.STORAGE);
            case "all" -> EnumSet.allOf(WorkspaceStore.Perm.class);
            default -> null;
        };
    }

    private interface Db<T> { T get() throws SQLException; }

    private <T> void async(Player p, Db<T> work, java.util.function.Consumer<T> then) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T v;
            try {
                v = work.get();
            } catch (SQLException e) {
                plugin.getLogger().warning("[Workspace] " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Component.text("[Workspace] Unavailable (database).", NamedTextColor.RED)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) then.accept(v); });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("home", "visit", "leave", "manage", "allow", "deny", "access", "jobs"), args[0]);
        if (args.length == 2 && List.of("visit", "allow", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && List.of("allow", "deny").contains(args[0].toLowerCase(Locale.ROOT)))
            return filter(List.of("visit", "use", "storage", "all"), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> l, String p) {
        return l.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack named(Material m, String name, NamedTextColor c, List<String> lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.displayName(Component.text(name, c).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(s -> (Component) Component.text(s, NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        i.setItemMeta(meta);
        return i;
    }

    private static String pretty(String type) {
        String s = type.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String fmt(long seconds) {
        if (seconds >= 3600) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        if (seconds >= 60) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds + "s";
    }
}
