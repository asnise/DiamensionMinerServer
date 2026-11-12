package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.mining.RewardItems;
import com.google.gson.JsonArray;
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
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Inheritance (GDD 5): move ONE of Advancement / Refine / Engrave from a source gear piece onto a
 * target piece of the same category. The source is destroyed; the target's value is replaced (not
 * added). For Engrave the target's old fragments are returned to the player.
 * Layout (row, col): source (1,1) · result preview (1,4) · target (1,7) · choices
 * ADVANCEMENT (3,0-2) / REFINE (3,3-5) / ENGRAVE (3,6-8) · TRANSFER (5,3-5) · back (5,0) · help (0,8).
 * Pieces are picked by clicking them in the player's own inventory (first = source, then target).
 */
public final class InheritMenu extends GuiMenu {

    public enum Kind { ADVANCEMENT, REFINE, ENGRAVE }

    private static final int SLOT_SOURCE = slot(1, 1), SLOT_RESULT = slot(1, 4), SLOT_TARGET = slot(1, 7);
    private static final int SLOT_HELP = slot(0, 8), SLOT_BACK = slot(5, 0);
    private static final int[] SLOT_GO = {slot(5, 3), slot(5, 4), slot(5, 5)};
    private static final int[][] SLOT_KIND = {{slot(3, 0), slot(3, 1), slot(3, 2)}, {slot(3, 3), slot(3, 4), slot(3, 5)},
            {slot(3, 6), slot(3, 7), slot(3, 8)}};
    /** Armor (36-39) and off-hand (40) count as equipped. */
    private static final int FIRST_EQUIP_SLOT = 36;

    private final ResourcePackManager packs;
    private UUID source, target;
    private Kind kind = Kind.ADVANCEMENT;
    private long energy = -1;

    private InheritMenu(CraftService service, ResourcePackManager packs, Player player) {
        super(service, player, packs.hasPack(player));
        this.packs = packs;
    }

    public static void open(CraftService service, ResourcePackManager packs, Player player) {
        open(service, packs, player, null, null, Kind.ADVANCEMENT, -1);
    }

    private static InheritMenu open(CraftService service, ResourcePackManager packs, Player player,
                                    UUID source, UUID target, Kind kind, long energy) {
        InheritMenu m = new InheritMenu(service, packs, player);
        m.inventory = Bukkit.createInventory(m, ROWS * 9, m.title('', "Inheritance"));
        m.source = source;
        m.target = target;
        m.kind = kind;
        m.energy = energy;
        m.render();
        player.openInventory(m.inventory);
        service.loadEnergy(player, e -> { m.energy = e; if (player.getOpenInventory().getTopInventory() == m.inventory) m.render(); });
        return m;
    }

    private record Piece(int slot, ItemStack stack, ItemDefinition def, GearItems.GearData data) {}

    private Piece piece(UUID id) {
        if (id == null) return null;
        int s = service.gear().findSlot(player, id);
        if (s < 0) return null;
        ItemStack stack = player.getInventory().getItem(s);
        ItemDefinition def = service.gear().gearDefinition(stack);
        GearItems.GearData d = service.gear().read(stack);
        return def == null || d == null ? null : new Piece(s, stack, def, d);
    }

    private static String kindName(Kind k) {
        return switch (k) { case ADVANCEMENT -> "Advancement"; case REFINE -> "Refine"; case ENGRAVE -> "Engrave"; };
    }

    private static String valueOf(Kind k, GearItems.GearData d) {
        return switch (k) {
            case ADVANCEMENT -> d.advancement() + "/" + GearMath.MAX_ADVANCEMENT;
            case REFINE -> "+" + d.refine();
            case ENGRAVE -> d.engraves().size() + "/" + GearItems.MAX_ENGRAVES + " fragments";
        };
    }

    /** The target's data after the transfer. */
    private static GearItems.GearData apply(Kind k, GearItems.GearData src, GearItems.GearData dst) {
        return switch (k) {
            case ADVANCEMENT -> dst.withAdvancement(src.advancement());
            // Pity belongs to the refine level, so it moves with it.
            case REFINE -> dst.withRefine(src.refine(), src.pity());
            case ENGRAVE -> dst.withEngraves(src.engraves());
        };
    }

    /** Lower = the target ends up with less of this value than it has now. */
    private static boolean lowers(Kind k, GearItems.GearData src, GearItems.GearData dst) {
        return switch (k) {
            case ADVANCEMENT -> src.advancement() < dst.advancement();
            case REFINE -> src.refine() < dst.refine();
            case ENGRAVE -> src.engraves().size() < dst.engraves().size();
        };
    }

    /** Why the transfer can't run now, or null. */
    private String block(Piece s, Piece t) {
        if (s == null) return "Click the SOURCE piece in your inventory";
        if (t == null) return "Click the TARGET piece in your inventory";
        if (s.data().id().equals(t.data().id())) return "Source and target must be different pieces";
        if (s.slot() >= FIRST_EQUIP_SLOT || t.slot() >= FIRST_EQUIP_SLOT) return "Take the pieces off first (armor / off-hand)";
        if (!s.def().getCategory().equalsIgnoreCase(t.def().getCategory()))
            return "Different categories (" + s.def().getCategory() + " → " + t.def().getCategory() + ")";
        boolean empty = switch (kind) {
            case ADVANCEMENT -> s.data().advancement() == 0;
            case REFINE -> s.data().refine() == 0;
            case ENGRAVE -> s.data().engraves().isEmpty();
        };
        if (empty) return "The source has no " + kindName(kind) + " to transfer";
        if (kind == Kind.ENGRAVE) {
            for (ItemDefinition.Engrave e : service.gear().engraveSpecs(s.data()))
                if (!e.fits(t.def().getCategory())) return "A fragment on the source can't go on " + t.def().getCategory();
        }
        long cost = service.rules().inheritEnergy();
        if (energy >= 0 && energy < cost) return "Need " + cost + " Energy (have " + energy + ")";
        if (service.isBusy(player.getUniqueId())) return "Working...";
        return null;
    }

    // ------------------------------------------------------------------ render

    private void render() {
        inventory.clear();
        Piece s = piece(source), t = piece(target);
        inventory.setItem(SLOT_HELP, sprite("blank", Material.BOOK, text("Inheritance", NamedTextColor.GOLD), lines(
                text("1. Click the SOURCE piece in your inventory", NamedTextColor.GRAY),
                text("2. Click the TARGET piece (same category)", NamedTextColor.GRAY),
                text("3. Pick ONE value to move, then TRANSFER", NamedTextColor.GRAY),
                text("The source is destroyed.", NamedTextColor.RED),
                text("The target's value is replaced, not added.", NamedTextColor.YELLOW),
                text("Engrave: the target's old fragments come back to you.", NamedTextColor.DARK_GRAY),
                text("Click a picked piece above to unpick it.", NamedTextColor.DARK_GRAY))));
        inventory.setItem(SLOT_BACK, sprite("blank", Material.ARROW, text("Back to the forge", NamedTextColor.YELLOW), null));

        inventory.setItem(SLOT_SOURCE, s != null ? labelled(s.stack(), "SOURCE (destroyed)", NamedTextColor.RED)
                : sprite("blank", Material.ITEM_FRAME, text("Source: none", NamedTextColor.GRAY), lines(text("Click gear in your inventory", NamedTextColor.DARK_GRAY))));
        inventory.setItem(SLOT_TARGET, t != null ? labelled(t.stack(), "TARGET (keeps the rest)", NamedTextColor.GREEN)
                : sprite("blank", Material.ITEM_FRAME, text("Target: none", NamedTextColor.GRAY),
                        lines(text(s == null ? "Pick the source first" : "Click gear of the same category", NamedTextColor.DARK_GRAY))));

        for (Kind k : Kind.values()) {
            List<Component> lore = lines(text(k == kind ? "Selected" : "Click to choose", k == kind ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));
            if (s != null) lore.add(text("Source: " + valueOf(k, s.data()), NamedTextColor.GRAY));
            if (t != null) lore.add(text("Target now: " + valueOf(k, t.data()), NamedTextColor.GRAY));
            ItemStack b = button(k == kind, Material.LIME_STAINED_GLASS_PANE,
                    text(kindName(k).toUpperCase(Locale.ROOT), k == kind ? NamedTextColor.GOLD : NamedTextColor.GRAY), lore);
            for (int slot : SLOT_KIND[k.ordinal()]) inventory.setItem(slot, b);
        }

        String blocker = block(s, t);
        if (s != null && t != null && !s.data().id().equals(t.data().id())) {
            GearItems.GearData after = apply(kind, s.data(), t.data());
            ItemStack preview = t.stack().clone();
            service.gear().write(preview, t.def(), after);
            inventory.setItem(SLOT_RESULT, labelled(preview, "Target after transfer", NamedTextColor.AQUA));
        } else {
            inventory.setItem(SLOT_RESULT, sprite("blank", Material.GRAY_STAINED_GLASS_PANE, text("Result preview", NamedTextColor.GRAY), null));
        }

        List<Component> goLore = blocker == null
                ? lines(text("Move " + kindName(kind) + " " + valueOf(kind, s.data()) + " onto the target", NamedTextColor.GRAY),
                        text("Cost: " + service.rules().inheritEnergy() + " Energy · the source is destroyed", NamedTextColor.YELLOW))
                : lines(text(blocker, NamedTextColor.RED));
        ItemStack go = button(blocker == null, Material.ORANGE_STAINED_GLASS_PANE,
                text("TRANSFER", blocker == null ? NamedTextColor.GOLD : NamedTextColor.GRAY), goLore);
        for (int slot : SLOT_GO) inventory.setItem(slot, go);
        fillEmpty();
    }

    private static ItemStack labelled(ItemStack stack, String label, NamedTextColor color) {
        ItemStack c = stack.clone();
        ItemMeta m = c.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(text(label, color));
        if (m.lore() != null) lore.addAll(m.lore());
        m.lore(lore);
        c.setItemMeta(m);
        return c;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected void clickBottom(int slot) {
        GearItems.GearData d = service.gear().readOrAssign(player.getInventory().getItem(slot));
        if (d == null) return;
        if (source == null || (target != null && d.id().equals(target))) source = d.id();
        else if (!d.id().equals(source)) target = d.id();
        if (source != null && source.equals(target)) target = null;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        render();
    }

    @Override
    protected void clickTop(int slot, boolean shift) {
        if (slot == SLOT_SOURCE) { source = null; render(); return; }
        if (slot == SLOT_TARGET) { target = null; render(); return; }
        if (slot == SLOT_BACK) { ForgeMenu.open(service, packs, player, target != null ? target : source); return; }
        for (Kind k : Kind.values())
            for (int s : SLOT_KIND[k.ordinal()]) if (s == slot) { kind = k; player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f); render(); return; }
        for (int s : SLOT_GO) if (s == slot) { ask(); return; }
    }

    /** Native dialog with the three rules (source destroyed, value replaced, compatibility). */
    private void ask() {
        Piece s = piece(source), t = piece(target);
        String blocker = block(s, t);
        if (blocker != null) { msg(blocker + ".", NamedTextColor.RED); return; }
        GearItems.GearData after = apply(kind, s.data(), t.data());
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(s.stack().clone()).showTooltip(true).build());
        body.add(DialogBody.plainMessage(Component.text("This piece is DESTROYED: " + s.def().getDisplay()
                + " (Gear #" + s.data().id().toString().substring(0, 8) + ")", NamedTextColor.RED)));
        body.add(DialogBody.item(t.stack().clone()).showTooltip(true).build());
        body.add(DialogBody.plainMessage(Component.text(kindName(kind) + " on " + t.def().getDisplay() + ": "
                + valueOf(kind, t.data()) + " → " + valueOf(kind, after) + " (replaced, not added)", NamedTextColor.YELLOW)));
        if (lowers(kind, s.data(), t.data()))
            body.add(DialogBody.plainMessage(Component.text("Warning: the target ends up LOWER than it is now.", NamedTextColor.GOLD)));
        if (kind == Kind.ENGRAVE && !t.data().engraves().isEmpty())
            body.add(DialogBody.plainMessage(Component.text("The target's " + t.data().engraves().size()
                    + " old fragment(s) come back to your inventory.", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text("Both are " + t.def().getCategory() + " · Cost: "
                + service.rules().inheritEnergy() + " Energy · everything else on the target stays", NamedTextColor.GRAY)));

        UUID src = s.data().id(), dst = t.data().id();
        GearItems.GearData qs = s.data(), qt = t.data();
        Kind k = kind;
        long e = energy;
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Transfer " + kindName(k) + "?", NamedTextColor.GOLD)).canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Transfer and destroy source", NamedTextColor.RED))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(), () -> {
                                    if (!player.isOnline()) return;
                                    InheritMenu m = open(service, packs, player, src, dst, k, e);
                                    Piece ns = m.piece(src), nt = m.piece(dst);
                                    if (ns == null || nt == null || !ns.data().equals(qs) || !nt.data().equals(qt)) {
                                        m.msg("A piece changed since you confirmed. Check it and try again.", NamedTextColor.YELLOW);
                                        return;
                                    }
                                    String b2 = m.block(ns, nt);
                                    if (b2 != null) { m.msg(b2 + ".", NamedTextColor.RED); return; }
                                    m.transfer(ns, nt);
                                }), once)).build(),
                        ActionButton.builder(Component.text("Cancel"))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(service.plugin(), () -> {
                                    if (player.isOnline()) open(service, packs, player, src, dst, k, e);
                                }), once)).build())));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void transfer(Piece s, Piece t) {
        UUID pid = player.getUniqueId();
        if (!service.tryBegin(pid)) return;
        Kind k = kind;
        GearItems.GearData after = apply(k, s.data(), t.data());
        long cost = service.rules().inheritEnergy();

        // Old fragments of the target come back when Engrave is replaced.
        Map<String, Integer> returned = new LinkedHashMap<>();
        if (k == Kind.ENGRAVE) for (String f : t.data().engraves()) returned.merge(f, 1, Integer::sum);
        List<ItemStack> back = returned.isEmpty() ? List.of() : RewardItems.build(service.plugin().getContentRegistryManager(), returned);

        PlayerInventory inv = player.getInventory();
        ItemStack srcStack = s.stack().clone(), dstStack = t.stack().clone();
        inv.setItem(s.slot(), null);
        inv.setItem(t.slot(), null);
        render();

        JsonObject meta = new JsonObject();
        meta.addProperty("kind", k.name());
        meta.addProperty("source_gear_id", s.data().id().toString());
        meta.addProperty("source_definition", s.def().getId());
        meta.addProperty("target_gear_id", t.data().id().toString());
        meta.addProperty("target_definition", t.def().getId());
        meta.add("source_before", json(s.data()));
        meta.add("target_before", json(t.data()));
        meta.add("target_after", json(after));
        meta.addProperty("energy", cost);
        JsonObject srcMeta = meta.deepCopy();
        srcMeta.addProperty("gear_id", s.data().id().toString());
        srcMeta.addProperty("result", "DESTROYED");
        JsonObject dstMeta = meta.deepCopy();
        dstMeta.addProperty("gear_id", t.data().id().toString());
        if (!returned.isEmpty()) {
            JsonArray r = new JsonArray();
            returned.forEach((id, n) -> { for (int i = 0; i < n; i++) r.add(id); });
            dstMeta.add("returned_fragments", r);
        }

        service.commit(new CraftService.Commit(pid, "inherit/" + UUID.randomUUID(),
                s.data().id() + ">" + t.data().id() + ":" + k, "INHERIT", cost,
                List.of(new CraftService.ItemTx(s.def().getId(), "INHERIT_SOURCE", srcMeta),
                        new CraftService.ItemTx(t.def().getId(), "INHERIT_TARGET", dstMeta)), meta.deepCopy()), result -> {
            service.end(pid);
            if (result.ok()) service.gear().write(dstStack, t.def(), after);
            List<ItemStack> give = new ArrayList<>(List.of(dstStack));
            if (result.ok()) give.addAll(back); else give.add(srcStack);
            if (!player.isOnline()) {
                service.returnOffline(pid, give, result.ok() ? "INHERIT_RESULT" : "INHERIT_REFUND");
                return;
            }
            if (inv.getItem(t.slot()) == null) { inv.setItem(t.slot(), dstStack); give.remove(dstStack); }
            if (!result.ok() && inv.getItem(s.slot()) == null) { inv.setItem(s.slot(), srcStack); give.remove(srcStack); }
            service.give(player, give);
            if (result.ok()) {
                energy = result.energyAfter();
                source = null;
                msg(kindName(k) + " moved onto " + t.def().getDisplay() + " (" + valueOf(k, after) + "). "
                        + s.def().getDisplay() + " was destroyed." + (back.isEmpty() ? "" : " Old fragments returned."), NamedTextColor.GREEN);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 0.8f);
            } else {
                if (result.status() == CraftService.Status.NO_ENERGY) energy = result.energyAfter();
                msg("Transfer failed (" + (result.status() == CraftService.Status.NO_ENERGY ? "not enough Energy" : result.status().name())
                        + "). Nothing changed.", NamedTextColor.RED);
            }
            service.persist(player);
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        });
    }

    private static JsonObject json(GearItems.GearData d) {
        JsonObject o = new JsonObject();
        o.addProperty("advancement", d.advancement());
        o.addProperty("refine", d.refine());
        o.addProperty("pity", d.pity());
        o.addProperty("quality", d.quality());
        JsonArray e = new JsonArray();
        d.engraves().forEach(e::add);
        o.add("engraves", e);
        return o;
    }

    private void msg(String s, NamedTextColor c) {
        player.sendMessage(Component.text("[Inherit] " + s, c));
    }
}
