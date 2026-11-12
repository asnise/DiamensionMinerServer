package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.google.gson.JsonObject;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forge window (style A): Advancement (always succeeds), Refine (chance + visible pity, a
 * failure never lowers the level) and Engrave (up to 3 fragments). Layout (row, col): help (0,8)
 * · gear (1,1) · stats (1,3) · engrave slots (2,3-5) · Advancement bar (3,3-7) · Refine pips (4,3-7)
 * · ADVANCE (5,1-3) · REFINE (5,5-7).
 * The player picks the gear by clicking it in their own inventory; clicking a fragment there
 * engraves it, clicking a filled engrave slot removes it. Refine and Engrave ask for confirmation
 * in a native dialog.
 */
public final class ForgeMenu extends GuiMenu {

    private static final int SLOT_HELP = slot(0, 8), SLOT_GEAR = slot(1, 1), SLOT_STATS = slot(1, 3);
    private static final int[] SLOT_ADV = {slot(5, 1), slot(5, 2), slot(5, 3)};
    private static final int[] SLOT_REF = {slot(5, 5), slot(5, 6), slot(5, 7)};
    private static final int[] SLOT_ENGRAVE = {slot(2, 3), slot(2, 4), slot(2, 5)};
    private static final int[] SLOT_INHERIT = {slot(1, 6), slot(1, 7), slot(1, 8)};
    private static final int[] SLOT_REPAIR = {slot(0, 5), slot(0, 6), slot(0, 7)};

    private final ResourcePackManager packs;
    private UUID selected;
    private long energy = -1;

    private ForgeMenu(CraftService service, ResourcePackManager packs, Player player) {
        super(service, player, packs.hasPack(player));
        this.packs = packs;
    }

    /** Opens the forge; selects {@code gearId} or, if null, the gear in the main hand. */
    public static void open(CraftService service, ResourcePackManager packs, Player player, UUID gearId) {
        ForgeMenu menu = new ForgeMenu(service, packs, player);
        menu.inventory = Bukkit.createInventory(menu, ROWS * 9, menu.title('', "Forge"));
        if (gearId != null) menu.selected = gearId;
        else {
            GearItems.GearData d = service.gear().readOrAssign(player.getInventory().getItemInMainHand());
            if (d != null) menu.selected = d.id();
        }
        menu.render();
        player.openInventory(menu.inventory);
        service.loadEnergy(player, e -> { menu.energy = e; if (player.getOpenInventory().getTopInventory() == menu.inventory) menu.render(); });
    }

    private record Target(int slot, ItemStack stack, ItemDefinition def, GearItems.GearData data) {}

    private Target target() {
        if (selected == null) return null;
        int s = service.gear().findSlot(player, selected);
        if (s < 0) return null;
        ItemStack stack = player.getInventory().getItem(s);
        ItemDefinition def = service.gear().gearDefinition(stack);
        GearItems.GearData data = service.gear().read(stack);
        return def == null || data == null ? null : new Target(s, stack, def, data);
    }

    private String materialOf(ItemDefinition def) {
        return def.getUpgradeMaterial() != null ? def.getUpgradeMaterial() : service.rules().defaultUpgradeMaterial();
    }

    // ------------------------------------------------------------------ render

    private void render() {
        inventory.clear();
        ForgeRules rules = service.rules();
        inventory.setItem(SLOT_HELP, sprite("blank", Material.BOOK, text("How to forge", NamedTextColor.GOLD), lines(
                text("Click a weapon/armor/tool in your inventory to select it.", NamedTextColor.GRAY),
                text("ADVANCE: +1 level (0-100), always succeeds.", NamedTextColor.GRAY),
                text("REFINE: +1 (0-10) with a success chance.", NamedTextColor.GRAY),
                text("A failed Refine keeps the level and adds pity;", NamedTextColor.GRAY),
                text("when pity is full the next Refine always succeeds.", NamedTextColor.GRAY),
                text("ENGRAVE: click a Fragment in your inventory (max " + GearItems.MAX_ENGRAVES + ").", NamedTextColor.LIGHT_PURPLE),
                text("Click an engraved slot to remove it with " + service.displayName(rules.removerItem()) + ";", NamedTextColor.LIGHT_PURPLE),
                text("the fragment comes back.", NamedTextColor.LIGHT_PURPLE))));

        ItemStack inherit = button(true, Material.ENDER_EYE, text("INHERIT", NamedTextColor.GOLD), lines(
                text("Move Advancement, Refine or Engrave", NamedTextColor.GRAY),
                text("from one piece to another (source destroyed)", NamedTextColor.DARK_GRAY)));
        for (int s : SLOT_INHERIT) inventory.setItem(s, inherit);

        Target t = target();
        if (t == null) {
            // Keep `selected`: during an action the gear is out of the inventory and comes back after.
            inventory.setItem(SLOT_GEAR, sprite("blank", Material.ITEM_FRAME, text("No gear selected", NamedTextColor.GRAY),
                    lines(text("Click gear in your inventory.", NamedTextColor.DARK_GRAY))));
            for (int i = 0; i < 5; i++) {
                inventory.setItem(slot(3, 3 + i), sprite("bar0", Material.GRAY_STAINED_GLASS_PANE, text("Advancement", NamedTextColor.GRAY), null));
                inventory.setItem(slot(4, 3 + i), sprite("pip0", Material.GRAY_STAINED_GLASS_PANE, text("Refine", NamedTextColor.GRAY), null));
            }
            for (int s : SLOT_ENGRAVE) inventory.setItem(s, sprite("blank", Material.GRAY_STAINED_GLASS_PANE,
                    text("Engrave slot", NamedTextColor.GRAY), lines(text("Select gear first", NamedTextColor.DARK_GRAY))));
            ItemStack off = button(false, Material.GRAY_STAINED_GLASS_PANE, text("Select gear first", NamedTextColor.GRAY), null);
            for (int s : SLOT_ADV) inventory.setItem(s, off);
            for (int s : SLOT_REPAIR) inventory.setItem(s, off);
            for (int s : SLOT_REF) inventory.setItem(s, off);
            fillEmpty();
            return;
        }

        GearItems.GearData d = t.data();
        inventory.setItem(SLOT_GEAR, t.stack().clone());
        inventory.setItem(SLOT_STATS, statsIcon(t));
        renderEngraves(t);

        Component advName = text("Advancement " + d.advancement() + "/" + GearMath.MAX_ADVANCEMENT, NamedTextColor.YELLOW);
        for (int i = 0; i < 5; i++) {
            int fill = Math.clamp((d.advancement() - i * 20) / 5, 0, 4);
            inventory.setItem(slot(3, 3 + i), sprite("bar" + fill, fill > 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, advName, null));
        }
        int chance = rules.effectiveRefineChanceBp(d.refine(), d.pity());
        int pityMax = rules.refinePityMax(d.refine());
        List<Component> refLore = d.refine() >= GearMath.MAX_REFINE ? lines(text("Max refine reached", NamedTextColor.GOLD))
                : lines(text("Next: " + ForgeRules.percent(chance) + " success", NamedTextColor.GRAY),
                        text(pityMax > 0 ? "Pity " + d.pity() + "/" + pityMax + " (full = guaranteed)" : "No pity at this level", NamedTextColor.GRAY));
        Component refName = text("Refine +" + d.refine() + "/+" + GearMath.MAX_REFINE, NamedTextColor.GOLD);
        for (int i = 0; i < 5; i++) {
            int pips = Math.clamp(d.refine() - i * 2, 0, 2);
            inventory.setItem(slot(4, 3 + i), sprite("pip" + pips, pips > 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, refName, refLore));
        }

        boolean busy = service.isBusy(player.getUniqueId());
        String mat = materialOf(t.def());
        int have = service.count(player, mat);

        // ADVANCE
        String advBlock = null;
        ForgeRules.Cost ac = rules.advancementCost(d.advancement());
        if (d.advancement() >= GearMath.MAX_ADVANCEMENT) advBlock = "Max Advancement reached";
        else if (have < ac.material()) advBlock = "Need " + ac.material() + "x " + service.displayName(mat) + " (have " + have + ")";
        else if (energy >= 0 && energy < ac.energy()) advBlock = "Need " + ac.energy() + " Energy (have " + energy + ")";
        if (busy) advBlock = "Working...";
        List<Component> advLore = lines(
                text("Advancement " + d.advancement() + " → " + Math.min(GearMath.MAX_ADVANCEMENT, d.advancement() + 1) + " (always succeeds)", NamedTextColor.GRAY),
                text("Cost: " + ac.material() + "x " + service.displayName(mat) + ", " + ac.energy() + " Energy", NamedTextColor.GRAY));
        if (advBlock != null) advLore.add(text(advBlock, NamedTextColor.RED));
        ItemStack adv = button(advBlock == null, Material.ORANGE_STAINED_GLASS_PANE, text("ADVANCE", advBlock == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), advLore);
        for (int s : SLOT_ADV) inventory.setItem(s, adv);

        // REPAIR (self, Energy)
        int maxDur = service.gear().maxDurability(t.def()), curDur = service.gear().durability(t.def(), d);
        long repairCost = rules.repairEnergy(maxDur - curDur);
        String repBlock = null;
        if (curDur >= maxDur) repBlock = "Durability is full";
        else if (energy >= 0 && energy < repairCost) repBlock = "Need " + repairCost + " Energy (have " + energy + ")";
        if (busy) repBlock = "Working...";
        List<Component> repLore = lines(
                text("Durability " + curDur + " → " + maxDur, NamedTextColor.GRAY),
                text("Cost: " + repairCost + " Energy (Blacksmith NPCs repair for COIN)", NamedTextColor.GRAY));
        if (repBlock != null) repLore.add(text(repBlock, NamedTextColor.RED));
        ItemStack rep = button(repBlock == null, Material.ANVIL, text("REPAIR", repBlock == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), repLore);
        for (int s : SLOT_REPAIR) inventory.setItem(s, rep);

        // REFINE
        String refBlock = null;
        ForgeRules.Cost rc = rules.refineCost(d.refine());
        if (d.refine() >= GearMath.MAX_REFINE) refBlock = "Max Refine reached";
        else if (have < rc.material()) refBlock = "Need " + rc.material() + "x " + service.displayName(mat) + " (have " + have + ")";
        else if (energy >= 0 && energy < rc.energy()) refBlock = "Need " + rc.energy() + " Energy (have " + energy + ")";
        if (busy) refBlock = "Working...";
        List<Component> refBtnLore = lines(
                text("Refine +" + d.refine() + " → +" + Math.min(GearMath.MAX_REFINE, d.refine() + 1) + " · " + ForgeRules.percent(chance), NamedTextColor.GRAY),
                text("Cost: " + rc.material() + "x " + service.displayName(mat) + ", " + rc.energy() + " Energy", NamedTextColor.GRAY),
                text("Fail = level stays, pity +1", NamedTextColor.DARK_GRAY));
        if (refBlock != null) refBtnLore.add(text(refBlock, NamedTextColor.RED));
        ItemStack ref = button(refBlock == null, Material.RED_STAINED_GLASS_PANE, text("REFINE", refBlock == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), refBtnLore);
        for (int s : SLOT_REF) inventory.setItem(s, ref);
        fillEmpty();
    }

    private ItemStack statsIcon(Target t) {
        GearItems.GearData d = t.data();
        Map<String, Double> now = service.gear().stats(t.def(), d);
        Map<String, Double> nextAdv = service.gear().stats(t.def(), d.withAdvancement(Math.min(GearMath.MAX_ADVANCEMENT, d.advancement() + 1)));
        Map<String, Double> nextRef = service.gear().stats(t.def(), d.withRefine(Math.min(GearMath.MAX_REFINE, d.refine() + 1), 0));
        List<Component> lore = new ArrayList<>();
        lore.add(text("now  /  next Advance  /  next Refine", NamedTextColor.DARK_GRAY));
        for (Map.Entry<String, Double> e : now.entrySet()) {
            if (e.getValue() == 0 && nextAdv.get(e.getKey()) == 0) continue;
            lore.add(text(e.getKey().replace('_', ' ') + ": ", NamedTextColor.GRAY)
                    .append(text(GearItems.fmt(e.getValue()), NamedTextColor.WHITE))
                    .append(text("  /  " + GearItems.fmt(nextAdv.get(e.getKey())), NamedTextColor.YELLOW))
                    .append(text("  /  " + GearItems.fmt(nextRef.get(e.getKey())), NamedTextColor.GOLD)));
        }
        lore.add(Component.empty());
        lore.add(text("Energy: " + (energy < 0 ? "loading..." : energy), NamedTextColor.AQUA));
        return sprite("blank", Material.PAPER, text("Stats", NamedTextColor.GOLD), lore);
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected void clickBottom(int slot) {
        ItemStack s = player.getInventory().getItem(slot);
        ItemDefinition frag = service.plugin().getContentRegistryManager().getItem(service.gear().definitionId(s));
        if (frag != null && frag.isFragment()) { askEngrave(frag); return; }
        GearItems.GearData d = service.gear().readOrAssign(s);
        if (d == null) {
            if (s != null && !s.isEmpty()) msg("Only weapons, armor and tools can be forged.", NamedTextColor.GRAY);
            return;
        }
        selected = d.id();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        render();
    }

    @Override
    protected void clickTop(int slot, boolean shift) {
        for (int s : SLOT_ADV) if (s == slot) { advance(); return; }
        for (int s : SLOT_REF) if (s == slot) { askRefine(); return; }
        for (int s : SLOT_INHERIT) if (s == slot) { InheritMenu.open(service, packs, player); return; }
        for (int s : SLOT_REPAIR) if (s == slot) { repair(); return; }
        for (int i = 0; i < SLOT_ENGRAVE.length; i++) if (SLOT_ENGRAVE[i] == slot) { askRemove(i); return; }
    }

    private void advance() {
        Target t = target();
        if (t == null || service.isBusy(player.getUniqueId())) return;
        GearItems.GearData d = t.data();
        if (d.advancement() >= GearMath.MAX_ADVANCEMENT) return;
        ForgeRules.Cost cost = service.rules().advancementCost(d.advancement());
        GearItems.GearData after = d.withAdvancement(d.advancement() + 1);
        JsonObject meta = baseMeta(t, cost);
        meta.addProperty("from", d.advancement());
        meta.addProperty("to", after.advancement());
        run(t, cost.energy(), mats(materialOf(t.def()), cost.material()), "ADVANCE", after, meta, List.of(), () -> {
            msg("Advancement " + d.advancement() + " → " + after.advancement() + ".", NamedTextColor.GREEN);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        });
    }

    private void repair() {
        Target t = target();
        if (t == null || service.isBusy(player.getUniqueId())) return;
        GearItems.GearData d = t.data();
        int max = service.gear().maxDurability(t.def()), cur = service.gear().durability(t.def(), d);
        if (cur >= max) return;
        long cost = service.rules().repairEnergy(max - cur);
        JsonObject meta = new JsonObject();
        meta.addProperty("gear_id", d.id().toString());
        meta.addProperty("from", cur);
        meta.addProperty("to", max);
        meta.addProperty("energy", cost);
        meta.addProperty("by", "FORGE");
        run(t, cost, Map.of(), "REPAIR", d.withDurability(max), meta, List.of(), () -> {
            msg("Repaired " + t.def().getDisplay() + ": durability " + cur + " → " + max + ".", NamedTextColor.GREEN);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
        });
    }

    /** Closes the menu and shows the native confirmation dialog for one Refine attempt. */
    private void askRefine() {
        Target t = target();
        if (t == null || service.isBusy(player.getUniqueId())) return;
        GearItems.GearData d = t.data();
        if (d.refine() >= GearMath.MAX_REFINE) return;
        ForgeRules rules = service.rules();
        ForgeRules.Cost cost = rules.refineCost(d.refine());
        String mat = materialOf(t.def());
        if (service.count(player, mat) < cost.material() || (energy >= 0 && energy < cost.energy())) {
            msg("Not enough materials or Energy for Refine.", NamedTextColor.RED);
            return;
        }
        int chance = rules.effectiveRefineChanceBp(d.refine(), d.pity());
        int pityMax = rules.refinePityMax(d.refine());
        UUID gearId = d.id();
        int quotedRefine = d.refine(), quotedPity = d.pity();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Refine +" + d.refine() + " → +" + (d.refine() + 1) + " ?", NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.item(t.stack().clone()).showTooltip(true).build(),
                                DialogBody.plainMessage(Component.text("Success chance: " + ForgeRules.percent(chance)
                                        + (chance >= GearMath.BP && pityMax > 0 && d.pity() >= pityMax ? " (pity full)" : ""), NamedTextColor.GREEN)),
                                DialogBody.plainMessage(Component.text(pityMax > 0
                                        ? "Pity: " + d.pity() + "/" + pityMax + " — when full, the next Refine always succeeds"
                                        : "No pity at this level", NamedTextColor.GRAY)),
                                DialogBody.plainMessage(Component.text("Cost: " + cost.material() + "x " + service.displayName(mat)
                                        + ", " + cost.energy() + " Energy", NamedTextColor.YELLOW)),
                                DialogBody.plainMessage(Component.text("On failure the level stays at +" + d.refine()
                                        + " and pity goes up by 1. The gear is never destroyed.", NamedTextColor.GRAY))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Refine", NamedTextColor.GOLD))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(),
                                        () -> confirmRefine(gearId, quotedRefine, quotedPity)), once)).build(),
                        ActionButton.builder(Component.text("Cancel"))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(),
                                        () -> reopen(gearId)), once)).build())));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void reopen(UUID gearId) {
        if (player.isOnline()) ForgeMenu.open(service, packs, player, gearId);
    }

    /** Runs the confirmed attempt; the gear must still be in the quoted state. */
    private void confirmRefine(UUID gearId, int quotedRefine, int quotedPity) {
        if (!player.isOnline()) return;
        // The menu was closed for the dialog: open a fresh one so results render somewhere.
        ForgeMenu.open(service, packs, player, gearId);
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ForgeMenu menu)) return;
        Target t = menu.target();
        if (t == null || t.data().refine() != quotedRefine || t.data().pity() != quotedPity) {
            msg("The gear changed since you confirmed. Check it and try again.", NamedTextColor.YELLOW);
            return;
        }
        menu.energy = energy;
        menu.refine(t);
    }

    private void refine(Target t) {
        if (service.isBusy(player.getUniqueId())) return;
        GearItems.GearData d = t.data();
        ForgeRules rules = service.rules();
        ForgeRules.Cost cost = rules.refineCost(d.refine());
        int chance = rules.effectiveRefineChanceBp(d.refine(), d.pity());
        int roll = ThreadLocalRandom.current().nextInt(GearMath.BP);
        boolean success = chance >= GearMath.BP || roll < chance;
        GearItems.GearData after = success ? d.withRefine(d.refine() + 1, 0) : d.withRefine(d.refine(), d.pity() + 1);
        JsonObject meta = baseMeta(t, cost);
        meta.addProperty("from", d.refine());
        meta.addProperty("to", after.refine());
        meta.addProperty("chance_bp", chance);
        meta.addProperty("roll", roll);
        meta.addProperty("pity_before", d.pity());
        meta.addProperty("pity_after", after.pity());
        meta.addProperty("success", success);
        run(t, cost.energy(), mats(materialOf(t.def()), cost.material()), "REFINE", after, meta, List.of(), () -> {
            if (success) {
                msg("Refine succeeded! +" + d.refine() + " → +" + after.refine(), NamedTextColor.GOLD);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            } else {
                msg("Refine failed (" + ForgeRules.percent(chance) + "). Level stays +" + d.refine()
                        + ", pity " + after.pity() + "/" + rules.refinePityMax(d.refine()) + ".", NamedTextColor.RED);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f);
            }
        });
    }

    private JsonObject baseMeta(Target t, ForgeRules.Cost cost) {
        JsonObject meta = new JsonObject();
        meta.addProperty("gear_id", t.data().id().toString());
        meta.addProperty("material", materialOf(t.def()));
        meta.addProperty("material_qty", cost.material());
        meta.addProperty("energy", cost.energy());
        return meta;
    }

    /**
     * Shared Advance/Refine execution: take materials and the gear out of the inventory, commit,
     * then put the gear back with the new state (or unchanged + refund on failure).
     */
    private static Map<String, Integer> mats(String item, int qty) {
        return qty > 0 ? Map.of(item, qty) : Map.of();
    }

    private void run(Target t, long energyCost, Map<String, Integer> materials, String action, GearItems.GearData after,
                     JsonObject meta, List<ItemStack> giveOnSuccess, Runnable onSuccess) {
        UUID pid = player.getUniqueId();
        for (Map.Entry<String, Integer> m : materials.entrySet()) {
            if (service.count(player, m.getKey()) < m.getValue()) { msg("Missing materials.", NamedTextColor.RED); render(); return; }
        }
        if (energy >= 0 && energy < energyCost) { msg("Not enough Energy.", NamedTextColor.RED); return; }
        if (!service.tryBegin(pid)) return;

        PlayerInventory inv = player.getInventory();
        ItemStack gearStack = t.stack().clone();
        inv.setItem(t.slot(), null);
        List<ItemStack> taken = materials.isEmpty() ? List.of() : service.take(player, materials);
        render();

        JsonObject response = meta.deepCopy();
        service.commit(new CraftService.Commit(pid, action.toLowerCase() + "/" + UUID.randomUUID(), t.data().id() + ":" + action,
                action, energyCost, List.of(new CraftService.ItemTx(t.def().getId(), action, meta)), response), result -> {
            service.end(pid);
            if (result.ok()) service.gear().write(gearStack, t.def(), after);
            if (!player.isOnline()) {
                List<ItemStack> back = new ArrayList<>(List.of(gearStack));
                if (result.ok()) back.addAll(giveOnSuccess); else back.addAll(taken);
                service.returnOffline(pid, back, action + (result.ok() ? "_RESULT" : "_REFUND"));
                return;
            }
            if (inv.getItem(t.slot()) == null) inv.setItem(t.slot(), gearStack); else service.give(player, List.of(gearStack));
            if (result.ok()) {
                energy = result.energyAfter();
                if (!giveOnSuccess.isEmpty()) service.give(player, giveOnSuccess);
                onSuccess.run();
            } else {
                service.give(player, taken);
                if (result.status() == CraftService.Status.NO_ENERGY) energy = result.energyAfter();
                msg(action + " failed (" + (result.status() == CraftService.Status.NO_ENERGY ? "not enough Energy" : result.status().name())
                        + "). Nothing was used.", NamedTextColor.RED);
            }
            service.persist(player);
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        });
    }

    // ------------------------------------------------------------------ engrave

    private void renderEngraves(Target t) {
        List<String> ids = t.data().engraves();
        ForgeRules rules = service.rules();
        String remover = rules.removerItem();
        boolean removerKnown = service.plugin().getContentRegistryManager().getItem(remover) != null;
        for (int i = 0; i < SLOT_ENGRAVE.length; i++) {
            if (i >= ids.size()) {
                inventory.setItem(SLOT_ENGRAVE[i], sprite("blank", Material.GRAY_STAINED_GLASS_PANE,
                        text("Empty engrave slot", NamedTextColor.GRAY), lines(
                                text("Click a Fragment in your inventory", NamedTextColor.DARK_GRAY),
                                text("Cost: the fragment + " + rules.engraveEnergy() + " Energy", NamedTextColor.DARK_GRAY))));
                continue;
            }
            ItemDefinition f = service.plugin().getContentRegistryManager().getItem(ids.get(i));
            ItemStack icon = f != null ? service.displayStack(f.getId(), 1) : new ItemStack(Material.BARRIER);
            org.bukkit.inventory.meta.ItemMeta m = icon.getItemMeta();
            m.displayName(text(f != null ? f.getDisplay() : ids.get(i) + " (unknown)", NamedTextColor.LIGHT_PURPLE));
            List<Component> lore = new ArrayList<>();
            if (f != null && f.isFragment()) {
                lore.add(text(GearItems.describe(f.getEngrave()), NamedTextColor.GRAY));
                if (f.getEngrave().exclusive()) lore.add(text("Exclusive affix: " + f.getEngrave().affix(), NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            lore.add(text(removerKnown ? "Click to remove: 1x " + service.displayName(remover) + " + " + rules.removeEnergy() + " Energy"
                    : "Removing is not available (no " + remover + " item)", removerKnown ? NamedTextColor.YELLOW : NamedTextColor.RED));
            if (removerKnown) lore.add(text("The fragment is returned to you.", NamedTextColor.DARK_GRAY));
            m.lore(lore);
            m.setEnchantmentGlintOverride(true);
            icon.setItemMeta(m);
            inventory.setItem(SLOT_ENGRAVE[i], icon);
        }
    }

    /** Why this fragment can't go onto the selected gear now, or null. */
    private String engraveBlock(Target t, ItemDefinition frag) {
        if (t == null) return "Select a gear piece first (click it in your inventory)";
        ItemDefinition.Engrave e = frag.getEngrave();
        if (!e.fits(t.def().getCategory())) return frag.getDisplay() + " can't be engraved on " + t.def().getCategory();
        if (t.data().engraves().size() >= GearItems.MAX_ENGRAVES) return "All " + GearItems.MAX_ENGRAVES + " engrave slots are used";
        for (ItemDefinition.Engrave other : service.gear().engraveSpecs(t.data())) {
            if (other.affix().equals(e.affix()) && (other.exclusive() || e.exclusive()))
                return "This piece already has the " + e.affix() + " affix (exclusive)";
        }
        if (service.count(player, frag.getId()) < 1) return "You have no " + frag.getDisplay();
        long cost = service.rules().engraveEnergy();
        if (energy >= 0 && energy < cost) return "Need " + cost + " Energy (have " + energy + ")";
        if (service.isBusy(player.getUniqueId())) return "Please wait, the last action is still running";
        return null;
    }

    private void askEngrave(ItemDefinition frag) {
        Target t = target();
        String block = engraveBlock(t, frag);
        if (block != null) { msg(block + ".", NamedTextColor.RED); player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f); return; }
        GearItems.GearData before = t.data();
        List<String> after = new ArrayList<>(before.engraves());
        after.add(frag.getId());
        Map<String, Double> now = service.gear().stats(t.def(), before);
        Map<String, Double> next = service.gear().stats(t.def(), before.withEngraves(after));
        List<Component> body = new ArrayList<>();
        body.add(Component.text(frag.getDisplay() + ": " + GearItems.describe(frag.getEngrave()), NamedTextColor.LIGHT_PURPLE));
        for (Map.Entry<String, Double> e : next.entrySet()) {
            double was = now.getOrDefault(e.getKey(), 0.0);
            if (Math.abs(e.getValue() - was) < 0.005) continue;
            body.add(Component.text(GearItems.statLabel(e.getKey()) + ": " + GearItems.fmt(was) + " → " + GearItems.fmt(e.getValue()), NamedTextColor.GREEN));
        }
        body.add(Component.text("Slot " + after.size() + "/" + GearItems.MAX_ENGRAVES + " · Cost: 1x " + frag.getDisplay()
                + ", " + service.rules().engraveEnergy() + " Energy", NamedTextColor.YELLOW));
        body.add(Component.text("You can take it out later with " + service.displayName(service.rules().removerItem())
                + "; the fragment is returned.", NamedTextColor.GRAY));
        UUID gearId = before.id();
        List<String> quoted = before.engraves();
        confirm("Engrave " + frag.getDisplay() + "?", t.stack(), body, "Engrave", gearId, menu -> {
            Target now2 = menu.target();
            if (now2 == null || !now2.data().engraves().equals(quoted)) { menu.msg("The gear changed since you confirmed. Check it and try again.", NamedTextColor.YELLOW); return; }
            String b2 = menu.engraveBlock(now2, frag);
            if (b2 != null) { menu.msg(b2 + ".", NamedTextColor.RED); return; }
            menu.engrave(now2, frag);
        });
    }

    private void engrave(Target t, ItemDefinition frag) {
        GearItems.GearData d = t.data();
        List<String> list = new ArrayList<>(d.engraves());
        list.add(frag.getId());
        GearItems.GearData after = d.withEngraves(list);
        long cost = service.rules().engraveEnergy();
        JsonObject meta = new JsonObject();
        meta.addProperty("gear_id", d.id().toString());
        meta.addProperty("fragment", frag.getId());
        meta.addProperty("slot", list.size());
        meta.addProperty("energy", cost);
        meta.add("engraves_before", toJson(d.engraves()));
        meta.add("engraves_after", toJson(list));
        run(t, cost, Map.of(frag.getId(), 1), "ENGRAVE", after, meta, List.of(), () -> {
            msg("Engraved " + frag.getDisplay() + " (slot " + list.size() + "/" + GearItems.MAX_ENGRAVES + ").", NamedTextColor.LIGHT_PURPLE);
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.1f);
        });
    }

    private void askRemove(int index) {
        Target t = target();
        if (t == null || index >= t.data().engraves().size()) return;
        ForgeRules rules = service.rules();
        String remover = rules.removerItem();
        String fragId = t.data().engraves().get(index);
        ItemDefinition frag = service.plugin().getContentRegistryManager().getItem(fragId);
        String block = null;
        if (service.plugin().getContentRegistryManager().getItem(remover) == null) block = "Removing is not available on this server (no " + remover + " item)";
        else if (frag == null) block = "Unknown fragment " + fragId + "; ask an admin";
        else if (service.count(player, remover) < 1) block = "You need 1x " + service.displayName(remover);
        else if (energy >= 0 && energy < rules.removeEnergy()) block = "Need " + rules.removeEnergy() + " Energy (have " + energy + ")";
        else if (service.isBusy(player.getUniqueId())) block = "Please wait, the last action is still running";
        if (block != null) { msg(block + ".", NamedTextColor.RED); player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f); return; }
        List<Component> body = List.of(
                Component.text("Take " + frag.getDisplay() + " out of slot " + (index + 1) + "?", NamedTextColor.LIGHT_PURPLE),
                Component.text("Removes: " + GearItems.describe(frag.getEngrave()), NamedTextColor.GRAY),
                Component.text("Cost: 1x " + service.displayName(remover) + ", " + rules.removeEnergy() + " Energy", NamedTextColor.YELLOW),
                Component.text("The fragment goes back to your inventory (mailbox if full).", NamedTextColor.GRAY));
        UUID gearId = t.data().id();
        List<String> quoted = t.data().engraves();
        confirm("Remove " + frag.getDisplay() + "?", t.stack(), body, "Remove", gearId, menu -> {
            Target now = menu.target();
            if (now == null || !now.data().engraves().equals(quoted)) { menu.msg("The gear changed since you confirmed. Check it and try again.", NamedTextColor.YELLOW); return; }
            menu.removeEngrave(now, index, frag);
        });
    }

    private void removeEngrave(Target t, int index, ItemDefinition frag) {
        GearItems.GearData d = t.data();
        List<String> list = new ArrayList<>(d.engraves());
        list.remove(index);
        GearItems.GearData after = d.withEngraves(list);
        ForgeRules rules = service.rules();
        JsonObject meta = new JsonObject();
        meta.addProperty("gear_id", d.id().toString());
        meta.addProperty("fragment", frag.getId());
        meta.addProperty("slot", index + 1);
        meta.addProperty("remover", rules.removerItem());
        meta.addProperty("energy", rules.removeEnergy());
        meta.add("engraves_before", toJson(d.engraves()));
        meta.add("engraves_after", toJson(list));
        List<ItemStack> back = com.dimensionalminer.core.dimensionalMinerCore.mining.RewardItems.build(
                service.plugin().getContentRegistryManager(), Map.of(frag.getId(), 1));
        run(t, rules.removeEnergy(), Map.of(rules.removerItem(), 1), "UNENGRAVE", after, meta, back, () -> {
            msg("Removed " + frag.getDisplay() + "; it is back in your inventory.", NamedTextColor.LIGHT_PURPLE);
            player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.7f, 1f);
        });
    }

    private static com.google.gson.JsonArray toJson(List<String> ids) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        ids.forEach(a::add);
        return a;
    }

    /** Native confirmation dialog; on yes the forge reopens and {@code onYes} runs on the new menu. */
    private void confirm(String title, ItemStack shown, List<Component> body, String yes, UUID gearId,
                         java.util.function.Consumer<ForgeMenu> onYes) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        List<DialogBody> parts = new ArrayList<>();
        parts.add(DialogBody.item(shown.clone()).showTooltip(true).build());
        for (Component c : body) parts.add(DialogBody.plainMessage(c));
        long knownEnergy = energy;
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.LIGHT_PURPLE)).canCloseWithEscape(true).body(parts).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text(yes, NamedTextColor.LIGHT_PURPLE))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(), () -> {
                                    if (!player.isOnline()) return;
                                    ForgeMenu.open(service, packs, player, gearId);
                                    if (player.getOpenInventory().getTopInventory().getHolder() instanceof ForgeMenu menu) {
                                        menu.energy = knownEnergy;
                                        onYes.accept(menu);
                                    }
                                }), once)).build(),
                        ActionButton.builder(Component.text("Cancel"))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(),
                                        () -> reopen(gearId)), once)).build())));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void msg(String s, NamedTextColor c) {
        player.sendMessage(Component.text("[Forge] " + s, c));
    }
}
