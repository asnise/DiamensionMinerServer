package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.mining.RewardItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Craft window (style A). Layout (row, col):
 * recipes (0-4, 0-1) + page arrows (5,0)/(5,1) · result (0,3) · inputs (2,3-8) · have/need marks (3,3-8)
 * · energy (4,3) · CRAFT button (5,5-7).
 * The result tooltip is the quote: output, rules, energy and time. A quote expires after
 * forge.quote_seconds and is re-checked (recipe version, materials, energy in the DB) on confirm.
 */
public final class CraftMenu extends GuiMenu {

    private static final int PER_PAGE = 10;
    private static final int SLOT_RESULT = slot(0, 3), SLOT_ENERGY = slot(4, 3);
    private static final int SLOT_PREV = slot(5, 0), SLOT_NEXT = slot(5, 1);
    private static final int[] SLOT_CRAFT = {slot(5, 5), slot(5, 6), slot(5, 7)};
    private static final int[] SLOT_GAME = {slot(5, 3), slot(5, 4)};
    private static final int MAX_INPUTS = 6;

    private record Quote(UUID id, long expiresAt, String recipeId, int recipeVersion) {}

    private final String station;
    private ResourcePackManager packs;
    private List<Recipe> recipes = List.of();
    private int page;
    private Recipe selected;
    private Quote quote;
    private long energy = -1;

    private CraftMenu(CraftService service, Player player, boolean pack, String station) {
        super(service, player, pack);
        this.station = station;
    }

    /** station null = every recipe (/craft); otherwise only recipes of that station. */
    public static void open(CraftService service, ResourcePackManager packs, Player player, String station) {
        CraftMenu menu = new CraftMenu(service, player, packs.hasPack(player), station);
        menu.packs = packs;
        menu.inventory = Bukkit.createInventory(menu, ROWS * 9, menu.title('', "Crafting"));
        menu.recipes = new ArrayList<>(service.plugin().getContentRegistryManager().getAllRecipes().stream()
                .filter(r -> station == null || station.equalsIgnoreCase(r.station())).toList());
        if (!menu.recipes.isEmpty()) menu.select(menu.recipes.getFirst());
        menu.render();
        player.openInventory(menu.inventory);
        menu.refreshEnergy();
    }

    private void refreshEnergy() {
        service.loadEnergy(player, e -> { energy = e; if (player.getOpenInventory().getTopInventory() == inventory) render(); });
    }

    private void select(Recipe r) {
        selected = r;
        quote = new Quote(UUID.randomUUID(), System.currentTimeMillis() + service.rules().quoteSeconds() * 1000L, r.id(), r.version());
    }

    // ------------------------------------------------------------------ render

    private void render() {
        inventory.clear();
        int pages = Math.max(1, (recipes.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= recipes.size()) break;
            inventory.setItem(slot(i / 2, i % 2), recipeIcon(recipes.get(idx)));
        }
        inventory.setItem(SLOT_PREV, sprite("blank", Material.ARROW, text("Previous page", NamedTextColor.YELLOW),
                lines(text("Page " + (page + 1) + "/" + pages, NamedTextColor.GRAY))));
        inventory.setItem(SLOT_NEXT, sprite("blank", Material.ARROW, text("Next page", NamedTextColor.YELLOW),
                lines(text("Page " + (page + 1) + "/" + pages, NamedTextColor.GRAY))));

        if (selected == null) {
            inventory.setItem(SLOT_RESULT, sprite("blank", Material.BOOK, text("No recipes", NamedTextColor.GRAY),
                    lines(text(station == null ? "No Recipe content is loaded." : "Nothing can be crafted at this station.", NamedTextColor.DARK_GRAY))));
            fillEmpty();
            return;
        }

        String blocker = null;
        // inputs + marks
        List<Recipe.Stack> inputs = selected.inputs();
        for (int i = 0; i < Math.min(MAX_INPUTS, inputs.size()); i++) {
            Recipe.Stack in = inputs.get(i);
            int have = service.count(player, in.item());
            boolean ok = have >= in.qty();
            if (!ok && blocker == null) blocker = "Missing " + (in.qty() - have) + "x " + service.displayName(in.item());
            ItemStack icon = service.displayStack(in.item(), in.qty());
            ItemMeta m = icon.getItemMeta();
            m.displayName(text(in.qty() + "x " + service.displayName(in.item()), ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            m.lore(lines(text("You have " + have + " / need " + in.qty(), ok ? NamedTextColor.GREEN : NamedTextColor.RED)));
            icon.setItemMeta(m);
            inventory.setItem(slot(2, 3 + i), icon);
            inventory.setItem(slot(3, 3 + i), sprite(ok ? "check" : "cross", ok ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    text(ok ? "Ready" : "Not enough", ok ? NamedTextColor.GREEN : NamedTextColor.RED), null));
        }

        long cost = selected.energy(service.rules());
        boolean energyOk = energy < 0 || energy >= cost;
        if (!energyOk && blocker == null) blocker = "Not enough Energy (" + energy + "/" + cost + ")";
        inventory.setItem(SLOT_ENERGY, sprite("blank", Material.EXPERIENCE_BOTTLE, text("Energy cost: " + cost, NamedTextColor.YELLOW),
                lines(text(energy < 0 ? "Your Energy: loading..." : "Your Energy: " + energy, energyOk ? NamedTextColor.GRAY : NamedTextColor.RED))));

        inventory.setItem(SLOT_RESULT, resultIcon(cost));

        if (service.isBusy(player.getUniqueId())) blocker = "Working...";
        List<Component> lore = blocker == null
                ? lines(text("Craft " + service.displayName(selected.mainOutput().item()), NamedTextColor.GRAY),
                        text("Uses the materials and " + cost + " Energy", NamedTextColor.DARK_GRAY))
                : lines(text(blocker, NamedTextColor.RED));
        ItemStack craft = button(blocker == null, Material.LIME_STAINED_GLASS_PANE,
                text("CRAFT", blocker == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), lore);
        for (int s : SLOT_CRAFT) inventory.setItem(s, craft);

        HeatGame.Rules hr = service.heatRules();
        String gameBlocker = blocker != null ? blocker
                : !hr.enabled() ? "The mini-game is turned off on this server"
                : !isGear(selected.mainOutput().item()) ? "Only gear has a grade" : null;
        List<Component> gameLore = gameBlocker == null
                ? lines(text("Forge Heat mini-game", NamedTextColor.GRAY),
                        text("Same materials and Energy as CRAFT.", NamedTextColor.DARK_GRAY),
                        text("Quality " + hr.fineAt() + "+ = Fine, " + hr.masterworkAt() + "+ = Masterwork", NamedTextColor.DARK_GRAY),
                        text("Failing or closing still crafts it at Normal.", NamedTextColor.DARK_GRAY))
                : lines(text(gameBlocker, NamedTextColor.RED));
        ItemStack game = button(gameBlocker == null, Material.ORANGE_STAINED_GLASS_PANE,
                text("FORGE (mini-game)", gameBlocker == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), gameLore);
        for (int s : SLOT_GAME) inventory.setItem(s, game);
        fillEmpty();
    }

    private ItemStack recipeIcon(Recipe r) {
        Recipe.Output out = r.mainOutput();
        ItemStack icon = service.displayStack(out.item(), out.qty());
        ItemMeta m = icon.getItemMeta();
        m.displayName(text(r.name(), r == selected ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        m.lore(lines(text("→ " + out.qty() + "x " + service.displayName(out.item()), NamedTextColor.GRAY),
                text(r == selected ? "Selected" : "Click to select", r == selected ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY)));
        if (r == selected) m.setEnchantmentGlintOverride(true);
        icon.setItemMeta(m);
        return icon;
    }

    /** The quote: result preview + every rule the player agrees to by pressing CRAFT. */
    private ItemStack resultIcon(long cost) {
        Recipe.Output out = selected.mainOutput();
        ItemDefinition def = service.plugin().getContentRegistryManager().getItem(out.item());
        ItemStack icon;
        List<Component> lore = new ArrayList<>();
        if (def != null && def.isGear()) {
            GearItems.GearData preview = new GearItems.GearData(UUID.randomUUID(), 0, 0, 0, "NORMAL");
            icon = service.gear().create(def, preview);
            lore.addAll(service.gear().lore(def, preview));
            lore.removeLast(); // "Gear #..." of the throwaway preview id
        } else {
            icon = service.displayStack(out.item(), out.qty());
            if (icon.getItemMeta().lore() != null) lore.addAll(icon.getItemMeta().lore());
        }
        lore.add(Component.empty());
        lore.add(text("Craft rules", NamedTextColor.GOLD));
        lore.add(text(" Output: " + out.qty() + "x " + service.displayName(out.item())
                + (out.chance() < 100 ? " (" + GearItems.fmt(out.chance()) + "% chance)" : ""), NamedTextColor.GRAY));
        for (int i = 1; i < selected.outputs().size(); i++) {
            Recipe.Output extra = selected.outputs().get(i);
            lore.add(text(" Bonus: " + extra.qty() + "x " + service.displayName(extra.item())
                    + (extra.chance() < 100 ? " (" + GearItems.fmt(extra.chance()) + "%)" : ""), NamedTextColor.GRAY));
        }
        lore.add(text(" Energy: " + cost, NamedTextColor.GRAY));
        if (selected.durationSeconds() > 0) lore.add(text(" Time: " + selected.durationSeconds() + "s", NamedTextColor.GRAY));
        if (def != null) lore.add(text(" Binding: " + ("UNBOUND".equalsIgnoreCase(def.getBinding()) ? "tradeable" : def.getBinding()), NamedTextColor.GRAY));
        if (def != null && def.isGear()) lore.add(text(" Grade: Normal with CRAFT, up to Masterwork with FORGE", NamedTextColor.DARK_GRAY));
        long secs = Math.max(0, (quote.expiresAt() - System.currentTimeMillis()) / 1000);
        lore.add(text(" Quote valid " + secs + "s", NamedTextColor.DARK_GRAY));
        ItemMeta m = icon.getItemMeta();
        m.lore(lore);
        icon.setItemMeta(m);
        return icon;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected void clickTop(int slot, boolean shift) {
        int row = slot / 9, col = slot % 9;
        if (col < 2 && row < 5) {
            int idx = page * PER_PAGE + row * 2 + col;
            if (idx < recipes.size()) {
                select(recipes.get(idx));
                click();
                render();
            }
        } else if (slot == SLOT_PREV) { page--; click(); render(); }
        else if (slot == SLOT_NEXT) { page++; click(); render(); }
        else if (slot == SLOT_CRAFT[0] || slot == SLOT_CRAFT[1] || slot == SLOT_CRAFT[2]) craft(false);
        else if (slot == SLOT_GAME[0] || slot == SLOT_GAME[1]) craft(true);
    }

    private void click() {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    private boolean isGear(String itemId) {
        ItemDefinition def = service.plugin().getContentRegistryManager().getItem(itemId);
        return def != null && def.isGear();
    }

    /** What was checked and taken from the player at the start of a craft. */
    record Pending(Recipe recipe, Quote quote, long cost, List<ItemStack> taken) {}

    /** Checks the quote, takes the inputs, then crafts now (Normal) or starts the mini-game. */
    private void craft(boolean game) {
        if (selected == null) return;
        UUID pid = player.getUniqueId();
        Recipe current = service.plugin().getContentRegistryManager().getRecipe(quote.recipeId());
        if (current == null || current.version() != quote.recipeVersion()) {
            msg("This recipe was changed by an update. Check it again.", NamedTextColor.YELLOW);
            recipes = new ArrayList<>(service.plugin().getContentRegistryManager().getAllRecipes().stream()
                    .filter(r -> station == null || station.equalsIgnoreCase(r.station())).toList());
            if (current != null) select(current); else selected = null;
            render();
            return;
        }
        if (System.currentTimeMillis() > quote.expiresAt()) {
            select(current);
            msg("The quote expired and was refreshed. Check it and press the button again.", NamedTextColor.YELLOW);
            render();
            return;
        }
        if (game && (!service.heatRules().enabled() || !isGear(current.mainOutput().item()))) return;
        Map<String, Integer> need = CraftService.amounts(current.inputs());
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (service.count(player, e.getKey()) < e.getValue()) { msg("Missing materials.", NamedTextColor.RED); render(); return; }
        }
        long cost = current.energy(service.rules());
        if (energy >= 0 && energy < cost) { msg("Not enough Energy (" + energy + "/" + cost + ").", NamedTextColor.RED); return; }
        // Every output at full quantity must fit (be strict: ignore the space the inputs free up).
        if (!service.fits(player, buildOutputs(current, "NORMAL", null, false).results())) {
            msg("Not enough inventory space for the result.", NamedTextColor.RED);
            return;
        }
        if (!service.tryBegin(pid)) { msg("Please wait, the last action is still running.", NamedTextColor.YELLOW); return; }

        // The server holds the inputs from here on; finish() commits them or gives them back.
        Pending pending = new Pending(current, quote, cost, service.take(player, need));
        if (game) {
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                if (!player.isOnline()) { finish(pending, null); return; }
                HeatMenu.open(service, this, pending);
            });
        } else {
            render();
            finish(pending, null);
        }
    }

    private record Outputs(List<ItemStack> results, List<CraftService.ItemTx> txs, JsonArray rolled) {}

    /** Builds the output items (rolling chances when roll=true); gear gets the given grade. base null = no transactions. */
    private Outputs buildOutputs(Recipe current, String grade, JsonObject base, boolean roll) {
        List<ItemStack> results = new ArrayList<>();
        JsonArray rolled = new JsonArray();
        List<CraftService.ItemTx> txs = new ArrayList<>();
        for (Recipe.Output out : current.outputs()) {
            boolean hit = !roll || out.chance() >= 100 || ThreadLocalRandom.current().nextDouble(100) < out.chance();
            JsonObject r = new JsonObject();
            r.addProperty("item", out.item());
            r.addProperty("qty", out.qty());
            r.addProperty("chance", out.chance());
            r.addProperty("hit", hit);
            rolled.add(r);
            if (!hit) continue;
            ItemDefinition def = service.plugin().getContentRegistryManager().getItem(out.item());
            if (def != null && def.isGear()) {
                for (int i = 0; i < out.qty(); i++) {
                    GearItems.GearData data = new GearItems.GearData(UUID.randomUUID(), 0, 0, 0, grade);
                    results.add(service.gear().create(def, data));
                    if (base == null) continue;
                    JsonObject meta = base.deepCopy();
                    meta.addProperty("gear_id", data.id().toString());
                    meta.addProperty("quality", data.quality());
                    txs.add(new CraftService.ItemTx(def.getId(), "CRAFT", meta));
                }
            } else {
                results.addAll(RewardItems.build(service.plugin().getContentRegistryManager(), Map.of(out.item(), out.qty())));
                if (base == null) continue;
                JsonObject meta = base.deepCopy();
                meta.addProperty("qty", out.qty());
                txs.add(new CraftService.ItemTx(out.item(), "CRAFT", meta));
            }
        }
        return new Outputs(results, txs, rolled);
    }

    /**
     * Commits a craft whose inputs are already taken. game = the finished mini-game (null for CRAFT).
     * On any failure the inputs go back; if the player has left, everything goes to the mailbox.
     */
    void finish(Pending p, HeatGame game) {
        UUID pid = player.getUniqueId();
        Recipe current = p.recipe();
        String grade = game == null ? "NORMAL" : game.grade();
        JsonObject base = new JsonObject();
        base.addProperty("recipe", current.id());
        base.addProperty("recipe_version", current.version());
        base.addProperty("quote", p.quote().id().toString());
        base.add("inputs", inputsJson(current));
        base.addProperty("energy", p.cost());
        if (game != null) base.add("minigame", game.toJson());
        Outputs o = buildOutputs(current, grade, base, true);
        List<CraftService.ItemTx> txs = new ArrayList<>(o.txs());
        if (txs.isEmpty()) {
            JsonObject meta = base.deepCopy();
            meta.addProperty("result", "NO_OUTPUT_ROLLED");
            txs.add(new CraftService.ItemTx(current.mainOutput().item(), "CRAFT", meta));
        }
        JsonObject response = new JsonObject();
        response.add("outputs", o.rolled());
        response.addProperty("grade", grade);
        List<ItemStack> results = o.results();
        service.commit(new CraftService.Commit(pid, "craft/" + p.quote().id(), current.id() + ":" + current.version(), "CRAFT",
                p.cost(), txs, response), result -> {
            service.end(pid);
            if (!player.isOnline()) {
                service.returnOffline(pid, result.ok() ? results : p.taken(), result.ok() ? "CRAFT_RESULT" : "CRAFT_REFUND");
                return;
            }
            if (result.ok()) {
                service.give(player, results);
                energy = result.energyAfter();
                if (results.isEmpty()) {
                    msg("The craft failed its chance roll. Materials and Energy were used.", NamedTextColor.RED);
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 0.6f, 1f);
                } else {
                    String g = isGear(current.mainOutput().item()) ? " [" + GearItems.qualityName(grade) + "]" : "";
                    msg("Crafted " + RewardItems.describe(service.plugin().getContentRegistryManager(), countIds(results)) + g + ".", NamedTextColor.GREEN);
                    player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.1f);
                }
            } else {
                service.give(player, p.taken());
                if (result.status() == CraftService.Status.NO_ENERGY) {
                    energy = result.energyAfter();
                    msg("Not enough Energy. Your materials were returned.", NamedTextColor.RED);
                } else {
                    msg("Crafting failed (" + result.status() + "). Your materials were returned.", NamedTextColor.RED);
                }
            }
            service.persist(player);
            select(current);
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        });
    }

    /** Back to this window after the mini-game (fresh quote, same page). */
    void reopen() {
        if (!player.isOnline()) return;
        if (selected != null) {
            Recipe r = service.plugin().getContentRegistryManager().getRecipe(selected.id());
            if (r != null) select(r);
        }
        render();
        player.openInventory(inventory);
        refreshEnergy();
    }

    ResourcePackManager packs() { return packs; }

    Player player() { return player; }

    private Map<String, Integer> countIds(List<ItemStack> stacks) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (ItemStack s : stacks) {
            String id = service.gear().definitionId(s);
            m.merge(id != null ? id : s.getType().name().toLowerCase(java.util.Locale.ROOT), s.getAmount(), Integer::sum);
        }
        return m;
    }

    private static JsonArray inputsJson(Recipe r) {
        JsonArray a = new JsonArray();
        for (Recipe.Stack s : r.inputs()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", s.item());
            o.addProperty("qty", s.qty());
            a.add(o);
        }
        return a;
    }

    private void msg(String s, NamedTextColor c) {
        player.sendMessage(Component.text("[Craft] " + s, c));
    }
}
