package com.dimensionalminer.core.dimensionalMinerCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mailbox GUI for {@code reward_grants}. One slot shows one grant.
 *
 * <p>Claiming is all-or-nothing and DB-first, so a grant can never pay out twice:
 * <ol>
 *   <li>Main thread: check the player has room for every stack of the grant (simulated on a copy).</li>
 *   <li>Async: {@code UPDATE ... WHERE grant_id=? AND player_id=? AND state='AVAILABLE'}. Only the
 *       request that actually flips the row gets the items. Double clicks, two open menus or a second
 *       server all lose that race.</li>
 *   <li>Main thread: give the items. If the player left in the meantime the claim is reverted; anything
 *       that no longer fits (inventory changed during the round trip) is re-mailed as a new grant.</li>
 * </ol>
 *
 * <p>While the menu is open every click and drag is cancelled, so nothing can be moved in or out of it.
 * Event handling lives in {@link MenuListener}, registered once in {@code onEnable}.
 */
public final class GrantsMenu implements InventoryHolder {

    public static final int CHEST_SIZE = 54;
    public static final int CONTENT_SLOTS = 45; // rows 0-4
    public static final int PREV_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private static final int PLAYER_STORAGE_SLOTS = 36;
    private static final int MAX_AMOUNT_PER_ENTRY = 100_000;

    /** Players with a claim round trip in flight. Blocks overlapping claims from any menu. */
    private static final Set<UUID> claimsInFlight = ConcurrentHashMap.newKeySet();

    private final DimensionalMinerCore plugin;
    private final Player player;
    private final Inventory inventory;
    private final List<GrantEntry> grants = new ArrayList<>();
    private final Map<Integer, GrantEntry> slotToGrant = new HashMap<>();
    private int currentPage = 1;

    private GrantsMenu(DimensionalMinerCore plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, CHEST_SIZE,
                Component.text("Reward Grants & Mailbox", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(DimensionalMinerCore plugin, Player player) {
        new GrantsMenu(plugin, player).reload(true);
    }

    // ---------- loading ----------

    /** Re-reads AVAILABLE grants from the DB. Payloads are parsed on the main thread (ItemStack creation). */
    private void reload(boolean openAfterLoad) {
        UUID minecraftId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RawGrant> raw = new ArrayList<>();
            try {
                UUID accountId = plugin.getDatabaseManager().getAccountId(minecraftId);
                String sql = "SELECT grant_id, payload FROM reward_grants"
                        + " WHERE player_id = ? AND state = 'AVAILABLE' ORDER BY created_at ASC, grant_id ASC";
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setObject(1, accountId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            raw.add(new RawGrant((UUID) rs.getObject("grant_id"), rs.getString("payload")));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load grants for " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Component.text("[Mailbox] Database error while loading grants.", NamedTextColor.RED));
                    }
                });
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                grants.clear();
                for (RawGrant g : raw) {
                    grants.add(new GrantEntry(g.grantId(), buildStacks(g.payload())));
                }
                int totalPages = totalPages();
                if (currentPage > totalPages) currentPage = totalPages;
                renderPage();
                if (openAfterLoad) {
                    player.openInventory(inventory);
                }
            });
        });
    }

    // ---------- clicks ----------

    private void handleClick(int rawSlot) {
        if (rawSlot == PREV_SLOT) {
            if (currentPage > 1) {
                currentPage--;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                renderPage();
            }
            return;
        }
        if (rawSlot == NEXT_SLOT) {
            if (currentPage < totalPages()) {
                currentPage++;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                renderPage();
            }
            return;
        }
        if (rawSlot < 0 || rawSlot >= CONTENT_SLOTS) return;

        GrantEntry entry = slotToGrant.get(rawSlot);
        if (entry == null) return;
        claim(entry);
    }

    private void claim(GrantEntry entry) {
        if (entry.stacks() == null) {
            player.sendMessage(Component.text("[Mailbox] This reward could not be read. Please contact an admin (grant "
                    + entry.grantId().toString().substring(0, 8) + ").", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        UUID minecraftId = player.getUniqueId();
        if (!claimsInFlight.add(minecraftId)) return; // previous claim still running

        if (!fitsInInventory(entry.stacks())) {
            claimsInFlight.remove(minecraftId);
            player.sendMessage(Component.text("[Mailbox] Not enough inventory space for this reward ("
                    + entry.stacks().size() + " stack(s)). Make space first.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        // Hide it right away so the player sees the click land; the DB decides whether it really counts.
        grants.remove(entry);
        renderPage();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean claimed;
            boolean dbError = false;
            UUID accountId = null;
            try {
                accountId = plugin.getDatabaseManager().getAccountId(minecraftId);
                claimed = markClaimed(entry.grantId(), accountId);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to claim grant " + entry.grantId() + ": " + e.getMessage());
                claimed = false;
                dbError = true;
            }

            final boolean wasClaimed = claimed;
            final boolean failedOnDb = dbError;
            final UUID ownerId = accountId;
            Bukkit.getScheduler().runTask(plugin, () -> {
                claimsInFlight.remove(minecraftId);
                if (!wasClaimed) {
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(failedOnDb
                                ? "[Mailbox] Database error, nothing was claimed. Please try again."
                                : "[Mailbox] That reward was already claimed.", NamedTextColor.RED));
                        reload(false);
                    }
                    return;
                }
                if (!player.isOnline()) {
                    // Nobody to hand the items to: put the grant back.
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> revertClaim(entry.grantId()));
                    return;
                }
                deliver(entry, ownerId);
            });
        });
    }

    /** Main thread. The grant is already CLAIMED in the DB. */
    private void deliver(GrantEntry entry, UUID ownerId) {
        ItemStack[] toGive = entry.stacks().stream().map(ItemStack::clone).toArray(ItemStack[]::new);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toGive);

        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.4f);
        player.sendMessage(Component.text("[Mailbox] Claimed reward (" + entry.stacks().size() + " stack(s)).", NamedTextColor.GREEN));

        if (!leftovers.isEmpty()) {
            String payload = payloadFor(leftovers.values());
            UUID originalId = entry.grantId();
            player.sendMessage(Component.text("[Mailbox] Some items did not fit and were sent back to your mailbox.", NamedTextColor.YELLOW));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> remail(ownerId, originalId, payload));
        }

        // Persist the new inventory now rather than waiting for the next periodic snapshot.
        plugin.getInventorySync().snapshotAndMaybeSave(player);
    }

    // ---------- inventory helpers (main thread) ----------

    private boolean fitsInInventory(List<ItemStack> stacks) {
        Inventory sim = Bukkit.createInventory(null, PLAYER_STORAGE_SLOTS);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && i < PLAYER_STORAGE_SLOTS; i++) {
            if (storage[i] != null) sim.setItem(i, storage[i].clone());
        }
        for (ItemStack s : stacks) {
            if (!sim.addItem(s.clone()).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Turns a grant payload into the exact stacks to hand out, split by max stack size.
     * Returns {@code null} when any entry is unreadable, so a broken grant is never half-delivered.
     */
    private List<ItemStack> buildStacks(String payloadJson) {
        try {
            JsonObject obj = JsonParser.parseString(payloadJson).getAsJsonObject();
            if (!obj.has("items") || !obj.get("items").isJsonArray()) return null;
            JsonArray arr = obj.getAsJsonArray("items");
            if (arr.isEmpty()) return null;

            List<ItemStack> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) return null;
                JsonObject it = el.getAsJsonObject();
                // Exact item (market purchase, trade return): the full serialized ItemStack, so gear keeps
                // its Advancement / Refine / Engraves / durability.
                if (it.has("item_data") && !it.get("item_data").isJsonNull()) {
                    ItemStack exact = ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(it.get("item_data").getAsString()));
                    if (exact == null || exact.isEmpty()) return null;
                    out.add(exact);
                    continue;
                }
                String itemId = firstString(it, "item_id", "item", "definition_id", "id", "material");
                int amount = firstInt(it, 1, "amount", "qty", "quantity");
                if (itemId == null || amount <= 0 || amount > MAX_AMOUNT_PER_ENTRY) return null;

                ItemStack proto = prototype(itemId);
                if (proto == null) return null;
                int perStack = Math.max(1, proto.getAmount());
                while (amount > 0) {
                    int n = Math.min(perStack, amount);
                    ItemStack s = proto.clone();
                    s.setAmount(n);
                    out.add(s);
                    amount -= n;
                }
            }
            return out;
        } catch (Exception e) {
            plugin.getLogger().warning("Unreadable grant payload: " + e.getMessage());
            return null;
        }
    }

    /** A full stack of the item (amount = max stack size), or null if the id is unknown. */
    private ItemStack prototype(String itemId) {
        ContentRegistryManager registry = plugin.getContentRegistryManager();
        if (registry != null && registry.hasItem(itemId)) {
            // createItemStack clamps the amount to the definition's stack size.
            return registry.createItemStack(itemId, Integer.MAX_VALUE);
        }
        Material mat = Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isItem() || mat.isAir()) return null;
        return new ItemStack(mat, mat.getMaxStackSize());
    }

    private String payloadFor(Collection<ItemStack> stacks) {
        Map<String, Integer> amounts = new HashMap<>();
        for (ItemStack s : stacks) {
            amounts.merge(itemIdOf(s), s.getAmount(), Integer::sum);
        }
        JsonArray items = new JsonArray();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) {
            JsonObject it = new JsonObject();
            it.addProperty("item_id", e.getKey());
            it.addProperty("amount", e.getValue());
            items.add(it);
        }
        JsonObject payload = new JsonObject();
        payload.add("items", items);
        return payload.toString();
    }

    private String itemIdOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        ContentRegistryManager registry = plugin.getContentRegistryManager();
        if (meta != null && registry != null) {
            String defId = meta.getPersistentDataContainer().get(registry.getDefIdKey(), PersistentDataType.STRING);
            if (defId != null && !defId.isBlank()) return defId;
        }
        return stack.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
        }
        return null;
    }

    private static int firstInt(JsonObject o, int fallback, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsInt();
        }
        return fallback;
    }

    // ---------- database (async threads only) ----------

    /** @return true only for the one caller that moved the grant from AVAILABLE to CLAIMED. */
    private boolean markClaimed(UUID grantId, UUID accountId) throws SQLException {
        String sql = "UPDATE reward_grants SET state = 'CLAIMED', claimed_at = now()"
                + " WHERE grant_id = ? AND player_id = ? AND state = 'AVAILABLE'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, grantId);
            stmt.setObject(2, accountId);
            return stmt.executeUpdate() == 1;
        }
    }

    private void revertClaim(UUID grantId) {
        String sql = "UPDATE reward_grants SET state = 'AVAILABLE', claimed_at = NULL WHERE grant_id = ? AND state = 'CLAIMED'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, grantId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to revert claim of grant " + grantId + " (player left before delivery): " + e.getMessage());
        }
    }

    private void remail(UUID ownerId, UUID originalGrantId, String payload) {
        String sql = "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state)"
                + " VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')";
        UUID newId = UUID.randomUUID();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, newId);
            stmt.setObject(2, ownerId);
            stmt.setString(3, "mailbox_overflow:" + originalGrantId + ":" + newId);
            stmt.setString(4, payload);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to re-mail overflow of grant " + originalGrantId
                    + " for " + ownerId + ", payload=" + payload + ": " + e.getMessage());
        }
    }

    // ---------- rendering ----------

    private int totalPages() {
        return Math.max(1, (int) Math.ceil((double) grants.size() / CONTENT_SLOTS));
    }

    private void renderPage() {
        inventory.clear();
        slotToGrant.clear();

        int start = (currentPage - 1) * CONTENT_SLOTS;
        int end = Math.min(start + CONTENT_SLOTS, grants.size());
        for (int i = start; i < end; i++) {
            GrantEntry entry = grants.get(i);
            int slot = i - start;
            slotToGrant.put(slot, entry);
            inventory.setItem(slot, iconFor(entry));
        }
        renderNavigationControls();
    }

    private ItemStack iconFor(GrantEntry entry) {
        if (entry.stacks() == null) {
            ItemStack broken = new ItemStack(Material.BARRIER);
            ItemMeta meta = broken.getItemMeta();
            meta.displayName(Component.text("Unreadable reward", NamedTextColor.RED, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Contact an admin: " + entry.grantId().toString().substring(0, 8), NamedTextColor.GRAY)));
            broken.setItemMeta(meta);
            return broken;
        }

        ItemStack icon = entry.stacks().get(0).clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        List<Component> lore = new ArrayList<>();
        Map<String, Integer> totals = new HashMap<>();
        for (ItemStack s : entry.stacks()) totals.merge(nameOf(s), s.getAmount(), Integer::sum);
        lore.add(Component.text("Contains:", NamedTextColor.GRAY));
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            lore.add(Component.text(" - " + e.getValue() + "x " + e.getKey(), NamedTextColor.WHITE));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to claim everything", NamedTextColor.GREEN));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static String nameOf(ItemStack s) {
        ItemMeta meta = s.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return s.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void renderNavigationControls() {
        int totalPages = totalPages();
        ItemStack filler = createFillerPane();
        for (int slot = CONTENT_SLOTS; slot < CHEST_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        if (currentPage > 1) {
            inventory.setItem(PREV_SLOT, navButton("<- Previous Page (" + (currentPage - 1) + ")"));
        }

        ItemStack info = new ItemStack(Material.CHEST);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("Page " + currentPage + " / " + totalPages, NamedTextColor.GOLD, TextDecoration.BOLD));
        infoMeta.lore(List.of(
                Component.text("Pending rewards: " + grants.size(), NamedTextColor.GRAY),
                Component.text("Click a reward to claim all of it.", NamedTextColor.GREEN),
                Component.text("Claimed items cannot be put back.", NamedTextColor.RED)));
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        if (currentPage < totalPages) {
            inventory.setItem(NEXT_SLOT, navButton("Next Page (" + (currentPage + 1) + ") ->"));
        }
    }

    private static ItemStack navButton(String label) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta meta = arrow.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.YELLOW, TextDecoration.BOLD));
        arrow.setItemMeta(meta);
        return arrow;
    }

    private static ItemStack createFillerPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        return pane;
    }

    private record RawGrant(UUID grantId, String payload) {}

    /** {@code stacks == null} means the payload could not be parsed; the grant is shown but not claimable. */
    private record GrantEntry(UUID grantId, List<ItemStack> stacks) {}

    // ---------- events ----------

    /** Registered once in {@code onEnable}; routes events to whichever menu is open. */
    public static final class MenuListener implements org.bukkit.event.Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof GrantsMenu menu)) return;

            // Nothing may move while the mailbox is open: covers shift-click, number keys,
            // double-click collect, drops and clicks in the player's own inventory.
            event.setCancelled(true);

            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
            if (!(event.getWhoClicked() instanceof Player clicker) || !clicker.equals(menu.player)) return;

            menu.handleClick(event.getRawSlot());
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof GrantsMenu) {
                event.setCancelled(true);
            }
        }
    }
}
