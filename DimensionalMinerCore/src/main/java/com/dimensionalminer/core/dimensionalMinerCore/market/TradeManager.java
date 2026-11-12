package com.dimensionalminer.core.dimensionalMinerCore.market;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.craft.GuiMenu;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * P2P Trade (8.5). Both players must stand in a Safe Zone, in the same world, within
 * {@code economy.trade.max_distance} blocks. Each side offers up to 16 stacks and some COIN; any change
 * clears both "ready" flags; when both are ready a 3 s countdown runs, then one transaction moves the COIN
 * (and logs every item in item_transactions) and the items are swapped. No tax (user decision).
 * Account-bound items cannot be offered. Offered items are held by the trade (out of the inventory) and
 * go back to their owner on cancel, quit, walking away, or plugin disable.
 */
public final class TradeManager implements CommandExecutor, TabCompleter {

    private static final int OFFER_SLOTS = 16;
    private static final long REQUEST_MS = 60_000;

    private final DimensionalMinerCore plugin;
    private final double maxDistance;
    /** target → (requester → time) */
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public TradeManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.maxDistance = Math.max(2, plugin.getConfig().getDouble("economy.trade.max_distance", 8));
        Bukkit.getScheduler().runTaskTimer(plugin, this::watch, 20L, 20L);
    }

    // ------------------------------------------------------------------ rules

    /** null if the two may trade right now, else why not. */
    private String blocked(Player a, Player b) {
        if (a.equals(b)) return "You cannot trade with yourself.";
        if (!a.getWorld().equals(b.getWorld()) || a.getLocation().distance(b.getLocation()) > maxDistance)
            return "You must be within " + (int) maxDistance + " blocks of " + b.getName() + ".";
        var zones = plugin.getZoneManager();
        if (zones == null || !zones.isSafe(a.getLocation()) || !zones.isSafe(b.getLocation()))
            return "Trading only works inside a Safe Zone (both players).";
        var dungeons = plugin.getDungeonInstanceManager();
        if (dungeons != null && (dungeons.isInDungeon(a) || dungeons.isInDungeon(b))) return "Not inside a dungeon.";
        return null;
    }

    private void watch() {
        for (Session s : List.copyOf(sessions.values())) {
            if (s.state != State.OPEN && s.state != State.COUNTDOWN) continue;
            Player a = Bukkit.getPlayer(s.a.id), b = Bukkit.getPlayer(s.b.id);
            if (a == null || b == null) { s.cancel("a player left"); continue; }
            String why = blocked(a, b);
            if (why != null) s.cancel(why);
        }
        long now = System.currentTimeMillis();
        requests.values().forEach(m -> m.values().removeIf(t -> now - t > REQUEST_MS));
    }

    // ------------------------------------------------------------------ /trade

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) {
            p.sendMessage(Component.text("/" + label + " <player> · /" + label + " accept <player> · /" + label + " deny <player>", NamedTextColor.YELLOW));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("accept") || sub.equals("deny")) && args.length >= 2) {
            Player from = Bukkit.getPlayerExact(args[1]);
            Map<UUID, Long> mine = requests.getOrDefault(p.getUniqueId(), Map.of());
            if (from == null || !mine.containsKey(from.getUniqueId())) {
                p.sendMessage(Component.text("[Trade] No trade request from " + args[1] + ".", NamedTextColor.RED));
                return true;
            }
            requests.get(p.getUniqueId()).remove(from.getUniqueId());
            if (sub.equals("deny")) {
                from.sendMessage(Component.text("[Trade] " + p.getName() + " declined your trade.", NamedTextColor.YELLOW));
                p.sendMessage(Component.text("[Trade] Declined.", NamedTextColor.GRAY));
                return true;
            }
            start(from, p);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { p.sendMessage(Component.text("[Trade] " + args[0] + " is not online.", NamedTextColor.RED)); return true; }
        if (sessions.containsKey(p.getUniqueId())) { p.sendMessage(Component.text("[Trade] You are already trading.", NamedTextColor.RED)); return true; }
        String why = blocked(p, target);
        if (why != null) { p.sendMessage(Component.text("[Trade] " + why, NamedTextColor.RED)); return true; }
        // Both asked each other: open at once.
        if (requests.getOrDefault(p.getUniqueId(), Map.of()).containsKey(target.getUniqueId())) {
            requests.get(p.getUniqueId()).remove(target.getUniqueId());
            start(target, p);
            return true;
        }
        requests.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(Component.text("[Trade] Request sent to " + target.getName() + " (60 s).", NamedTextColor.GREEN));
        target.sendMessage(Component.text("[Trade] " + p.getName() + " wants to trade with you. ", NamedTextColor.GOLD)
                .append(Component.text("[Accept]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/trade accept " + p.getName())))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/trade deny " + p.getName()))));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> names = new ArrayList<>();
        if (args.length == 1) {
            names.add("accept");
            names.add("deny");
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(sender)) names.add(o.getName());
            return names.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) for (Player o : Bukkit.getOnlinePlayers()) if (o.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) names.add(o.getName());
        return names;
    }

    private void start(Player a, Player b) {
        String why = blocked(a, b);
        if (why != null) { a.sendMessage(Component.text("[Trade] " + why, NamedTextColor.RED)); b.sendMessage(Component.text("[Trade] " + why, NamedTextColor.RED)); return; }
        if (sessions.containsKey(a.getUniqueId()) || sessions.containsKey(b.getUniqueId())) {
            b.sendMessage(Component.text("[Trade] One of you is already trading.", NamedTextColor.RED));
            return;
        }
        Session s = new Session(a, b);
        sessions.put(a.getUniqueId(), s);
        sessions.put(b.getUniqueId(), s);
        // COIN balances for the offer limits (re-checked in the transaction).
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long ba = balance(a.getUniqueId()), bb = balance(b.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> { s.a.balance = ba; s.b.balance = bb; s.refresh(); });
        });
        s.a.menu.openFor();
        s.b.menu.openFor();
    }

    private long balance(UUID mc) {
        try {
            return plugin.getDatabaseManager().getBalances(plugin.getDatabaseManager().getAccountId(mc)).getOrDefault("COIN", 0L);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Plugin disable: every open trade gives the offers back (players are still online here). */
    public void shutdown() {
        for (Session s : List.copyOf(sessions.values())) s.cancel("the server is stopping");
    }

    // ------------------------------------------------------------------ session

    private enum State { OPEN, COUNTDOWN, EXECUTING, DONE, CANCELLED }

    private final class Side {
        final UUID id;
        final String name;
        final List<ItemStack> items = new ArrayList<>();
        long coin;
        long balance = -1;
        boolean ready;
        TradeMenu menu;

        Side(Player p) { this.id = p.getUniqueId(); this.name = p.getName(); }
    }

    private final class Session {
        final UUID tradeId = UUID.randomUUID();
        final Side a, b;
        State state = State.OPEN;
        BukkitTask countdown;

        Session(Player pa, Player pb) {
            a = new Side(pa);
            b = new Side(pb);
            a.menu = new TradeMenu(this, pa, a, b);
            b.menu = new TradeMenu(this, pb, b, a);
        }

        Side sideOf(UUID id) { return a.id.equals(id) ? a : b; }

        void changed() {
            a.ready = b.ready = false;
            if (countdown != null) { countdown.cancel(); countdown = null; }
            if (state == State.COUNTDOWN) state = State.OPEN;
            refresh();
        }

        void refresh() { a.menu.draw(); b.menu.draw(); }

        void toggleReady(Side s) {
            if (state != State.OPEN && state != State.COUNTDOWN) return;
            s.ready = !s.ready;
            if (a.ready && b.ready) {
                state = State.COUNTDOWN;
                int[] left = {3};
                countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (left[0] > 0) {
                        for (Side x : new Side[]{a, b}) {
                            Player p = Bukkit.getPlayer(x.id);
                            if (p != null) {
                                p.sendActionBar(Component.text("Trading in " + left[0] + "...", NamedTextColor.GOLD));
                                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
                            }
                        }
                        left[0]--;
                        return;
                    }
                    countdown.cancel();
                    countdown = null;
                    execute();
                }, 0L, 20L);
            } else if (countdown != null) {
                countdown.cancel();
                countdown = null;
                state = State.OPEN;
            }
            refresh();
        }

        void execute() {
            state = State.EXECUTING;
            refresh();
            List<ItemStack> fromA = List.copyOf(a.items), fromB = List.copyOf(b.items);
            long coinA = a.coin, coinB = b.coin;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String err = commit(fromA, fromB, coinA, coinB);
                Bukkit.getScheduler().runTask(plugin, () -> finish(err, fromA, fromB));
            });
        }

        /** Async. One transaction: operation, COIN both ways (no tax), item_transactions for every stack. */
        private String commit(List<ItemStack> fromA, List<ItemStack> fromB, long coinA, long coinB) {
            try (Connection c = plugin.getDatabaseManager().getConnection()) {
                UUID accA = plugin.getDatabaseManager().getAccountId(a.id), accB = plugin.getDatabaseManager().getAccountId(b.id);
                c.setAutoCommit(false);
                try {
                    UUID op = UUID.randomUUID();
                    String body = "{\"with\":\"" + accB + "\",\"coin_a\":" + coinA + ",\"coin_b\":" + coinB
                            + ",\"items_a\":" + fromA.size() + ",\"items_b\":" + fromB.size() + "}";
                    Ledger.insertOperation(c, op, accA, "trade/" + tradeId, a.name + "<->" + b.name, "P2P_TRADE", body);
                    if (coinA > 0 || coinB > 0) {
                        boolean aFirst = accA.toString().compareTo(accB.toString()) < 0;
                        Ledger.Wallet wa, wb;
                        if (aFirst) { wa = Ledger.lockWallet(c, accA, null, "COIN"); wb = Ledger.lockWallet(c, accB, null, "COIN"); }
                        else { wb = Ledger.lockWallet(c, accB, null, "COIN"); wa = Ledger.lockWallet(c, accA, null, "COIN"); }
                        if (wa.balance() < coinA) { c.rollback(); return a.name + " does not have " + coinA + " COIN any more."; }
                        if (wb.balance() < coinB) { c.rollback(); return b.name + " does not have " + coinB + " COIN any more."; }
                        // One ledger entry per wallet per operation: move only the net amount.
                        long net = coinA - coinB;
                        if (net > 0) Ledger.transfer(c, op, wa.id(), wb.id(), net, "P2P_TRADE");
                        else if (net < 0) Ledger.transfer(c, op, wb.id(), wa.id(), -net, "P2P_TRADE");
                    }
                    for (ItemStack s : fromA) MarketService.itemTx(c, op, itemId(s), accA, accB, "P2P_TRADE", s.getAmount(), tradeId);
                    for (ItemStack s : fromB) MarketService.itemTx(c, op, itemId(s), accB, accA, "P2P_TRADE", s.getAmount(), tradeId);
                    c.commit();
                    return null;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("[Trade] " + a.name + " <-> " + b.name + " failed: " + e.getMessage());
                return "database error";
            }
        }

        void finish(String err, List<ItemStack> fromA, List<ItemStack> fromB) {
            if (err != null) {
                for (Side x : new Side[]{a, b}) {
                    Player p = Bukkit.getPlayer(x.id);
                    if (p != null) p.sendMessage(Component.text("[Trade] Trade failed: " + err + " Nothing changed hands.", NamedTextColor.RED));
                }
                state = State.OPEN; // offers stay; cancel() hands them back
                cancel(null);
                return;
            }
            state = State.DONE;
            a.items.clear();
            b.items.clear();
            end();
            deliver(b.id, fromA, "TRADE");
            deliver(a.id, fromB, "TRADE");
            for (Side x : new Side[]{a, b}) {
                Player p = Bukkit.getPlayer(x.id);
                if (p == null) continue;
                p.sendMessage(Component.text("[Trade] Trade complete.", NamedTextColor.GREEN));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
        }

        /** Gives back what is on the table and closes both windows. */
        void cancel(String why) {
            if (state == State.DONE || state == State.CANCELLED || state == State.EXECUTING) return;
            state = State.CANCELLED;
            if (countdown != null) countdown.cancel();
            List<ItemStack> ia = List.copyOf(a.items), ib = List.copyOf(b.items);
            a.items.clear();
            b.items.clear();
            end();
            deliver(a.id, ia, "TRADE_RETURN");
            deliver(b.id, ib, "TRADE_RETURN");
            if (why != null) for (Side x : new Side[]{a, b}) {
                Player p = Bukkit.getPlayer(x.id);
                if (p != null) p.sendMessage(Component.text("[Trade] Cancelled: " + why + ". Your items were returned.", NamedTextColor.YELLOW));
            }
        }

        private void end() {
            sessions.remove(a.id, this);
            sessions.remove(b.id, this);
            for (Side x : new Side[]{a, b}) {
                Player p = Bukkit.getPlayer(x.id);
                if (p != null && p.getOpenInventory().getTopInventory().getHolder() == x.menu) p.closeInventory();
            }
        }
    }

    /** Main thread. Into the inventory; what does not fit (or an offline player) goes to the mailbox, exact. */
    private void deliver(UUID to, List<ItemStack> stacks, String type) {
        if (stacks.isEmpty()) return;
        Player p = Bukkit.getPlayer(to);
        List<ItemStack> rest = new ArrayList<>();
        if (p != null && p.isOnline()) {
            rest.addAll(p.getInventory().addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).values());
            plugin.getCraftService().persist(p);
        } else {
            rest.addAll(stacks);
        }
        if (rest.isEmpty()) return;
        if (p != null) p.sendMessage(Component.text("[Trade] " + rest.size() + " stack(s) did not fit and went to /grants.", NamedTextColor.YELLOW));
        List<ItemStack> mail = List.copyOf(rest);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = plugin.getDatabaseManager().getConnection()) {
                UUID acc = plugin.getDatabaseManager().getAccountId(to);
                for (ItemStack s : mail) {
                    MarketService.mailExact(c, acc, type.toLowerCase(Locale.ROOT) + ":" + UUID.randomUUID(), type, itemId(s), s.getAmount(), s.serializeAsBytes());
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("[Trade] Could not mail " + mail.size() + " stack(s) to " + to + ": " + e.getMessage());
            }
        });
    }

    private String itemId(ItemStack s) {
        String id = plugin.getGearItems() != null ? plugin.getGearItems().definitionId(s) : null;
        return id != null ? id.toLowerCase(Locale.ROOT) : s.getType().name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ window

    private static final int[] COIN_STEPS = {10, 100, 1000};
    private static final int COIN_BTN0 = 36, MY_COIN = 39, THEIR_COIN = 41, READY = 45, CANCEL = 49, THEIR_READY = 53;

    private static int mySlot(int i) { return (i / 4) * 9 + i % 4; }
    private static int theirSlot(int i) { return (i / 4) * 9 + 5 + i % 4; }

    private final class TradeMenu extends GuiMenu {
        private final Session s;
        private final Side me, them;

        TradeMenu(Session s, Player p, Side me, Side them) {
            super(null, p, false);
            this.s = s;
            this.me = me;
            this.them = them;
            inventory = Bukkit.createInventory(this, ROWS * 9, Component.text("Trade with " + them.name));
        }

        void openFor() {
            draw();
            player.openInventory(inventory);
        }

        void draw() {
            inventory.clear();
            for (int i = 0; i < me.items.size(); i++) inventory.setItem(mySlot(i), me.items.get(i));
            for (int i = 0; i < them.items.size(); i++) inventory.setItem(theirSlot(i), them.items.get(i));
            ItemStack divider = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int r = 0; r < 6; r++) inventory.setItem(r * 9 + 4, divider);
            for (int k = 0; k < COIN_STEPS.length; k++)
                inventory.setItem(COIN_BTN0 + k, named(Material.GOLD_NUGGET, "COIN ±" + COIN_STEPS[k], NamedTextColor.GOLD,
                        "Click: +" + COIN_STEPS[k] + " · Shift-click: -" + COIN_STEPS[k]));
            inventory.setItem(MY_COIN, named(Material.GOLD_INGOT, "Your COIN: " + me.coin, NamedTextColor.YELLOW,
                    me.balance >= 0 ? "You have " + me.balance : "Loading balance..."));
            inventory.setItem(THEIR_COIN, named(Material.GOLD_INGOT, them.name + "'s COIN: " + them.coin, NamedTextColor.YELLOW, null));
            String st = s.state == State.EXECUTING ? "Processing..." : s.state == State.COUNTDOWN ? "Locked in, trading..." : null;
            inventory.setItem(READY, named(me.ready ? Material.LIME_DYE : Material.RED_DYE, me.ready ? "Ready ✔ (click to undo)" : "Click when ready",
                    me.ready ? NamedTextColor.GREEN : NamedTextColor.RED, st != null ? st : "Any change resets both sides."));
            inventory.setItem(CANCEL, named(Material.BARRIER, "Cancel trade", NamedTextColor.RED, null));
            inventory.setItem(THEIR_READY, named(them.ready ? Material.LIME_DYE : Material.GRAY_DYE, them.name + (them.ready ? " is ready" : " is not ready"),
                    them.ready ? NamedTextColor.GREEN : NamedTextColor.GRAY, null));
            ItemStack hint = named(Material.PAPER, "Your offer (left) · Their offer (right)", NamedTextColor.AQUA,
                    "Click items in your inventory to offer them. Click your offer to take an item back.");
            inventory.setItem(47, hint);
        }

        private ItemStack pane(Material m, String name) {
            ItemStack p = new ItemStack(m);
            ItemMeta meta = p.getItemMeta();
            meta.displayName(Component.text(name));
            meta.setHideTooltip(true);
            p.setItemMeta(meta);
            return p;
        }

        private ItemStack named(Material m, String name, NamedTextColor c, String lore) {
            ItemStack i = new ItemStack(m);
            ItemMeta meta = i.getItemMeta();
            meta.displayName(text(name, c));
            if (lore != null) meta.lore(List.of(text(lore, NamedTextColor.GRAY)));
            i.setItemMeta(meta);
            return i;
        }

        private boolean locked() { return s.state != State.OPEN && s.state != State.COUNTDOWN; }

        @Override
        protected void clickTop(int slot, boolean shift) {
            if (locked()) return;
            if (slot == CANCEL) { s.cancel(me.name + " cancelled"); return; }
            if (slot == READY) { s.toggleReady(me); return; }
            if (slot >= COIN_BTN0 && slot < COIN_BTN0 + COIN_STEPS.length) {
                long step = COIN_STEPS[slot - COIN_BTN0];
                long max = Math.max(0, me.balance);
                long next = Math.clamp(me.coin + (shift ? -step : step), 0, max);
                if (next != me.coin) { me.coin = next; s.changed(); }
                return;
            }
            for (int i = 0; i < me.items.size(); i++) {
                if (mySlot(i) == slot) {
                    ItemStack back = me.items.remove(i);
                    plugin.getCraftService().give(player, List.of(back));
                    plugin.getCraftService().persist(player);
                    s.changed();
                    return;
                }
            }
        }

        @Override
        protected void clickBottom(int slot) {
            if (locked() || slot < 0 || slot > 35) return;
            ItemStack it = player.getInventory().getItem(slot);
            if (it == null || it.isEmpty()) return;
            ItemDefinition def = plugin.getContentRegistryManager().getItem(itemId(it));
            if (def != null && "ACCOUNT_BOUND".equalsIgnoreCase(def.getBinding())) {
                player.sendMessage(Component.text("[Trade] Account-bound items cannot be traded.", NamedTextColor.RED));
                return;
            }
            if (me.items.size() >= OFFER_SLOTS) {
                player.sendMessage(Component.text("[Trade] Your offer is full (" + OFFER_SLOTS + " stacks).", NamedTextColor.RED));
                return;
            }
            me.items.add(it.clone());
            player.getInventory().setItem(slot, null);
            plugin.getCraftService().persist(player);
            s.changed();
        }

        @Override
        protected void closed() {
            // Closing the window (Esc, quit) ends the trade; closes done by end() find the state already final.
            if (!locked()) Bukkit.getScheduler().runTask(plugin, () -> s.cancel(me.name + " closed the trade window"));
        }
    }
}
