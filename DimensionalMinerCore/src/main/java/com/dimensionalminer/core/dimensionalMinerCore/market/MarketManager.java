package com.dimensionalminer.core.dimensionalMinerCore.market;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Market NPCs, /ah and the merchant buyback (8.3 / 8.4).
 * <ul>
 *   <li>{@code /ah} browses and buys anywhere outside dungeons; listing an item needs a Market Clerk NPC.</li>
 *   <li>The Market Clerk (right-click): browse, list the item in hand at a price, sell materials to the
 *       merchant for COIN (fixed prices, daily COIN cap per account = the capped coin source of the GDD).</li>
 *   <li>Admin: {@code /ah npc add|remove|list}, {@code /ah stats} (COIN sources/sinks of the last 24 h).</li>
 * </ul>
 * NPC positions live in market_npcs.yml and are spawned in every hub channel (like the Blacksmith).
 */
public final class MarketManager implements Listener, CommandExecutor, TabCompleter {

    private record Pos(double x, double y, double z, float yaw) {}

    private final DimensionalMinerCore plugin;
    private final MarketService service;
    private final NamespacedKey tag;
    private final File file;
    private final List<Pos> positions = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final Map<String, Long> buybackPrices = new HashMap<>();
    private final long buybackCap;
    private final java.util.Set<UUID> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MarketManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.service = new MarketService(plugin);
        this.tag = new NamespacedKey(plugin, "market_npc");
        this.file = new File(plugin.getDataFolder(), "market_npcs.yml");
        this.buybackCap = Math.max(0, plugin.getConfig().getLong("economy.buyback.daily_coin_cap", 500));
        ConfigurationSection prices = plugin.getConfig().getConfigurationSection("economy.buyback.prices");
        if (prices != null) for (String k : prices.getKeys(false)) {
            long v = prices.getLong(k);
            if (v > 0) buybackPrices.put(k.toLowerCase(Locale.ROOT), v);
        }
        load();
        // Expired listings go back to their sellers.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            int n = service.expireDue();
            if (n > 0) plugin.getLogger().info("[Market] " + n + " listing(s) expired and went back to their sellers.");
        }, 20L * 30, 20L * 60);
    }

    public MarketService service() { return service; }

    // ------------------------------------------------------------------ NPC positions

    private void load() {
        positions.clear();
        for (String s : YamlConfiguration.loadConfiguration(file).getStringList("npcs")) {
            String[] p = s.split(",");
            try {
                positions.add(new Pos(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        p.length > 3 ? Float.parseFloat(p[3]) : 0f));
            } catch (Exception e) {
                plugin.getLogger().warning("[Market] Bad position in market_npcs.yml: " + s);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("npcs", positions.stream().map(p -> String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.1f", p.x(), p.y(), p.z(), p.yaw())).toList());
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[Market] Could not save market_npcs.yml: " + e.getMessage());
        }
    }

    private List<String> worlds() {
        return plugin.getChannelManager() != null ? plugin.getChannelManager().getChannelWorldNames() : List.of("overworld_t0");
    }

    public void spawnAll() {
        despawnAll();
        for (String w : worlds()) {
            World world = Bukkit.getWorld(w);
            if (world != null) spawnIn(world);
        }
    }

    private void spawnIn(World world) {
        for (Pos p : positions) {
            Location loc = new Location(world, p.x(), p.y(), p.z(), p.yaw(), 0);
            world.addPluginChunkTicket(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, plugin);
            spawned.add(world.spawn(loc, Villager.class, e -> {
                e.setPersistent(false);
                e.setAI(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setCollidable(false);
                e.setProfession(Villager.Profession.LIBRARIAN);
                e.customName(Component.text("Market Clerk", NamedTextColor.AQUA));
                e.setCustomNameVisible(true);
                e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            }));
        }
    }

    public void despawnAll() {
        for (Entity e : spawned) if (e.isValid()) e.remove();
        spawned.clear();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (worlds().contains(event.getWorld().getName())) spawnIn(event.getWorld());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(tag)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ NPC menu

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(3)).build();

    private ActionButton button(String label, NamedTextColor color, Runnable onMain) {
        return ActionButton.builder(Component.text(label, color)).width(220)
                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, onMain), ONCE)).build();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(tag)) return;
        event.setCancelled(true);
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String handId = hand.isEmpty() ? null : itemId(hand);
        Long unit = handId == null ? null : buybackPrices.get(handId);

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Browse the market", NamedTextColor.AQUA, () -> openBrowse(p, false)));
        buttons.add(button("My listings", NamedTextColor.YELLOW, () -> openBrowse(p, true)));
        if (!hand.isEmpty()) buttons.add(button("List \"" + display(hand) + "\" x" + hand.getAmount(), NamedTextColor.GOLD, () -> askPrice(p, hand.clone())));
        if (unit != null) buttons.add(button("Sell to merchant (" + unit + " COIN each)", NamedTextColor.GREEN, () -> buyback(p, hand.clone(), unit)));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Listings stay " + service.listingHours + " h. Tax on sale: "
                + service.taxBasisPoints / 100.0 + "% (paid from the price).", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text("The merchant buys some materials for COIN, up to " + buybackCap + " COIN per day.", NamedTextColor.DARK_GRAY)));
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Market Clerk", NamedTextColor.AQUA)).canCloseWithEscape(true).body(body).build())
                .type(DialogType.multiAction(buttons, ActionButton.builder(Component.text("Close")).build(), 1))));
    }

    /** Main thread. Hand item → price dialog → list. */
    private void askPrice(Player p, ItemStack quoted) {
        String why = cannotList(quoted);
        if (why != null) { p.sendMessage(Component.text("[Market] " + why, NamedTextColor.RED)); return; }
        List<DialogBody> body = List.of(DialogBody.plainMessage(Component.text("Selling: " + display(quoted) + " x" + quoted.getAmount()
                + "\nYou receive the price minus " + service.taxBasisPoints / 100.0 + "% tax when it sells.", NamedTextColor.GRAY)));
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("List an item", NamedTextColor.GOLD)).canCloseWithEscape(true).body(body)
                        .inputs(List.of(DialogInput.text("price", Component.text("Price in COIN")).maxLength(10).build())).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("List it", NamedTextColor.GOLD)).action(DialogAction.customClick((view, audience) -> {
                            String raw = view.getText("price");
                            Bukkit.getScheduler().runTask(plugin, () -> list(p, quoted, raw));
                        }, ONCE)).build(),
                        ActionButton.builder(Component.text("Cancel")).build()))));
    }

    private String cannotList(ItemStack s) {
        if (s == null || s.isEmpty()) return "Hold the item you want to sell.";
        String id = itemId(s);
        ItemDefinition def = plugin.getContentRegistryManager().getItem(id);
        if (def != null && "ACCOUNT_BOUND".equalsIgnoreCase(def.getBinding())) return "Account-bound items cannot be sold.";
        return null;
    }

    private void list(Player p, ItemStack quoted, String rawPrice) {
        if (!p.isOnline()) return;
        long price;
        try {
            price = Long.parseLong(rawPrice == null ? "" : rawPrice.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            p.sendMessage(Component.text("[Market] \"" + rawPrice + "\" is not a number.", NamedTextColor.RED));
            return;
        }
        if (price < service.minPrice || price > service.maxPrice) {
            p.sendMessage(Component.text("[Market] Price must be " + service.minPrice + " - " + service.maxPrice + " COIN.", NamedTextColor.RED));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!hand.isSimilar(quoted) || hand.getAmount() != quoted.getAmount()) {
            p.sendMessage(Component.text("[Market] The item in your hand changed. Talk to the clerk again.", NamedTextColor.YELLOW));
            return;
        }
        if (!busy.add(p.getUniqueId())) return;
        ItemStack taken = hand.clone();
        p.getInventory().setItemInMainHand(null);
        plugin.getCraftService().persist(p);
        String id = itemId(taken);
        ItemDefinition def = plugin.getContentRegistryManager().getItem(id);
        String binding = def != null ? def.getBinding() : "UNBOUND";
        UUID pid = p.getUniqueId();
        long fprice = price;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketService.Status st = service.list(pid, taken, id, binding, fprice);
            Bukkit.getScheduler().runTask(plugin, () -> {
                busy.remove(pid);
                if (st == MarketService.Status.OK) {
                    p.sendMessage(Component.text("[Market] Listed " + display(taken) + " x" + taken.getAmount() + " for " + fprice
                            + " COIN (you get " + (fprice - service.tax(fprice)) + " after tax).", NamedTextColor.GREEN));
                    return;
                }
                giveBack(p, taken);
                p.sendMessage(Component.text(st == MarketService.Status.LIMIT
                        ? "[Market] You already have " + service.maxListings + " active listings."
                        : "[Market] Listing failed (database). Your item was given back.", NamedTextColor.RED));
            });
        });
    }

    private void buyback(Player p, ItemStack quoted, long unit) {
        if (!p.isOnline() || !busy.add(p.getUniqueId())) return;
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long today;
            try {
                today = service.buybackToday(pid);
            } catch (SQLException e) {
                today = -1;
            }
            long left = today < 0 ? -1 : buybackCap - today;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (left < 0) { busy.remove(pid); p.sendMessage(Component.text("[Market] The merchant is unavailable (database).", NamedTextColor.RED)); return; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!hand.isSimilar(quoted)) { busy.remove(pid); p.sendMessage(Component.text("[Market] The item in your hand changed.", NamedTextColor.YELLOW)); return; }
                int units = (int) Math.min(hand.getAmount(), left / unit);
                if (units <= 0) {
                    busy.remove(pid);
                    p.sendMessage(Component.text("[Market] The merchant has paid you " + buybackCap + " COIN today already. Come back tomorrow.", NamedTextColor.YELLOW));
                    return;
                }
                ItemStack taken = hand.clone();
                taken.setAmount(units);
                if (units == hand.getAmount()) p.getInventory().setItemInMainHand(null); else hand.setAmount(hand.getAmount() - units);
                plugin.getCraftService().persist(p);
                String id = itemId(taken);
                long coin = units * unit;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean ok = service.buyback(pid, id, units, coin, buybackCap);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        busy.remove(pid);
                        if (ok) {
                            p.sendMessage(Component.text("[Market] Sold " + units + "x " + display(taken) + " to the merchant for " + coin
                                    + " COIN (" + (left - coin) + " COIN left today).", NamedTextColor.GREEN));
                        } else {
                            giveBack(p, taken);
                            p.sendMessage(Component.text("[Market] The sale failed. Your items were given back.", NamedTextColor.RED));
                        }
                    });
                });
            });
        });
    }

    private void giveBack(Player p, ItemStack s) {
        if (p.isOnline()) {
            plugin.getCraftService().give(p, List.of(s));
            plugin.getCraftService().persist(p);
        } else {
            plugin.getCraftService().returnOffline(p.getUniqueId(), List.of(s), "MARKET_RETURN");
        }
    }

    // ------------------------------------------------------------------ browse / buy / cancel

    void openBrowse(Player p, boolean mine) {
        openBrowse(p, mine, 0);
    }

    void openBrowse(Player p, boolean mine, int page) {
        if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(p)) {
            p.sendMessage(Component.text("[Market] Not available inside a dungeon.", NamedTextColor.RED));
            return;
        }
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketService.Listing> rows;
            try {
                rows = service.browse(mine ? pid : null, null, page * MarketMenu.PER_PAGE, MarketMenu.PER_PAGE + 1);
            } catch (SQLException e) {
                plugin.getLogger().warning("[Market] Browse failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Component.text("[Market] The market is unavailable (database).", NamedTextColor.RED)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) new MarketMenu(this, p, mine, page, rows).open();
            });
        });
    }

    void confirmBuy(Player p, MarketService.Listing l, boolean mine, int page) {
        if (l.sellerName() != null && l.sellerName().equals(p.getName()) || mine) {
            confirmCancel(p, l, page);
            return;
        }
        List<DialogBody> body = List.of(
                DialogBody.item(safeItem(l)).build(),
                DialogBody.plainMessage(Component.text(display(safeItem(l)) + " x" + l.quantity() + " from " + l.sellerName(), NamedTextColor.GRAY)),
                DialogBody.plainMessage(Component.text("Price: " + l.price() + " COIN. It goes to your /grants mailbox.", NamedTextColor.YELLOW)));
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Buy this listing?", NamedTextColor.GOLD)).canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(
                        button("Buy for " + l.price() + " COIN", NamedTextColor.GOLD, () -> buy(p, l, page)),
                        ActionButton.builder(Component.text("Back")).action(DialogAction.customClick((v, a) ->
                                Bukkit.getScheduler().runTask(plugin, () -> openBrowse(p, false, page)), ONCE)).build()))));
    }

    private void buy(Player p, MarketService.Listing l, int page) {
        if (!busy.add(p.getUniqueId())) return;
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketService.BuyResult r = service.buy(pid, l.id(), l.price());
            Bukkit.getScheduler().runTask(plugin, () -> {
                busy.remove(pid);
                if (!p.isOnline()) return;
                switch (r.status()) {
                    case OK -> {
                        p.sendMessage(Component.text("[Market] Bought " + display(safeItem(l)) + " x" + l.quantity() + " for " + l.price()
                                + " COIN. Claim it with /grants.", NamedTextColor.GREEN));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                        Player seller = Bukkit.getPlayerExact(l.sellerName());
                        if (seller != null) seller.sendMessage(Component.text("[Market] " + p.getName() + " bought your " + display(safeItem(l))
                                + " x" + l.quantity() + ": +" + (l.price() - service.tax(l.price())) + " COIN.", NamedTextColor.GREEN));
                    }
                    case NO_FUNDS -> p.sendMessage(Component.text("[Market] Not enough COIN (" + r.balance() + "/" + l.price() + ").", NamedTextColor.RED));
                    case OWN_LISTING -> p.sendMessage(Component.text("[Market] That is your own listing.", NamedTextColor.RED));
                    case NOT_FOUND, DUPLICATE -> p.sendMessage(Component.text("[Market] That listing is gone (sold, cancelled or expired).", NamedTextColor.YELLOW));
                    default -> p.sendMessage(Component.text("[Market] Purchase failed (database). Nothing was charged.", NamedTextColor.RED));
                }
                openBrowse(p, false, page);
            });
        });
    }

    private void confirmCancel(Player p, MarketService.Listing l, int page) {
        List<DialogBody> body = List.of(
                DialogBody.item(safeItem(l)).build(),
                DialogBody.plainMessage(Component.text("Take this listing down? The item goes back to your /grants mailbox.", NamedTextColor.GRAY)));
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Cancel listing", NamedTextColor.YELLOW)).canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(
                        button("Take it down", NamedTextColor.YELLOW, () -> cancel(p, l, page)),
                        ActionButton.builder(Component.text("Keep it")).action(DialogAction.customClick((v, a) ->
                                Bukkit.getScheduler().runTask(plugin, () -> openBrowse(p, true, page)), ONCE)).build()))));
    }

    private void cancel(Player p, MarketService.Listing l, int page) {
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketService.Status st = service.cancel(pid, l.id());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                p.sendMessage(st == MarketService.Status.OK
                        ? Component.text("[Market] Listing cancelled. The item is in /grants.", NamedTextColor.GREEN)
                        : Component.text("[Market] Could not cancel (already sold or gone).", NamedTextColor.RED));
                openBrowse(p, true, page);
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    static ItemStack safeItem(MarketService.Listing l) {
        try {
            ItemStack s = l.item();
            if (s != null && !s.isEmpty()) return s;
        } catch (Exception ignored) {
        }
        return new ItemStack(Material.BARRIER);
    }

    String itemId(ItemStack s) {
        String id = plugin.getGearItems() != null ? plugin.getGearItems().definitionId(s) : null;
        return id != null ? id.toLowerCase(Locale.ROOT) : s.getType().name().toLowerCase(Locale.ROOT);
    }

    static String display(ItemStack s) {
        var meta = s.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        String n = s.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ------------------------------------------------------------------ /ah

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.isOp() || sender.hasPermission("dimensionalminer.admin");
        if (sub.equals("npc") && admin && sender instanceof Player p) {
            String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
            switch (op) {
                case "add" -> {
                    Location l = p.getLocation();
                    positions.add(new Pos(l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, l.getYaw() + 180));
                    save();
                    spawnAll();
                    p.sendMessage(Component.text("[Market] Market Clerk added here in every hub channel.", NamedTextColor.GREEN));
                }
                case "remove" -> {
                    Location l = p.getLocation();
                    Pos best = null;
                    double bestD = 9;
                    for (Pos q : positions) {
                        double d = Math.pow(q.x() - l.getX(), 2) + Math.pow(q.y() - l.getY(), 2) + Math.pow(q.z() - l.getZ(), 2);
                        if (d < bestD) { bestD = d; best = q; }
                    }
                    if (best == null) { p.sendMessage(Component.text("[Market] No Market Clerk within 3 blocks.", NamedTextColor.RED)); return true; }
                    positions.remove(best);
                    save();
                    spawnAll();
                    p.sendMessage(Component.text("[Market] Market Clerk removed.", NamedTextColor.GREEN));
                }
                default -> {
                    p.sendMessage(Component.text("[Market] " + positions.size() + " Market Clerk position(s):", NamedTextColor.GOLD));
                    for (Pos q : positions) p.sendMessage(Component.text(String.format(Locale.ROOT, "  %.0f %.0f %.0f", q.x(), q.y(), q.z()), NamedTextColor.GRAY));
                }
            }
            return true;
        }
        if (sub.equals("stats") && admin) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    long[] t = service.totals();
                    Map<String, Long> flows = service.coinFlows(24);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(Component.text("=== Economy (COIN) ===", NamedTextColor.GOLD));
                        sender.sendMessage(Component.text("Held by players: " + t[0] + " in " + t[1] + " wallet(s) · active listings: " + t[2], NamedTextColor.YELLOW));
                        sender.sendMessage(Component.text("Last 24 h, net to players by reason (+ source / - sink):", NamedTextColor.GRAY));
                        if (flows.isEmpty()) sender.sendMessage(Component.text("  (no COIN movement)", NamedTextColor.DARK_GRAY));
                        flows.forEach((k, v) -> sender.sendMessage(Component.text("  " + k + ": " + (v > 0 ? "+" : "") + v,
                                v >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED)));
                    });
                } catch (SQLException e) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("[Market] Stats failed: " + e.getMessage(), NamedTextColor.RED)));
                }
            });
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (sub.equals("sell")) {
            p.sendMessage(Component.text("[Market] Listing items is done at a Market Clerk in the hub.", NamedTextColor.YELLOW));
            return true;
        }
        openBrowse(p, sub.equals("mine") || sub.equals("my"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.isOp() || sender.hasPermission("dimensionalminer.admin");
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("mine"));
            if (admin) subs.addAll(List.of("npc", "stats"));
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc") && admin)
            return List.of("add", "remove", "list").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
