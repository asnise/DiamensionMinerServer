package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forge Heat window. Layout (row, col): result preview (0,1) · furnace heat (0,4) · help (0,8)
 * · bars Progress / Quality / Integrity / Focus on rows 1-4, cols 3-7 · actions (5,0-4) · STOP (5,6-8).
 * The craft's inputs were already taken by {@link CraftMenu}; whatever ends the game (completion,
 * Integrity 0, STOP, closing the window, leaving) hands it back to {@link CraftMenu#finish}.
 */
public final class HeatMenu extends GuiMenu {

    /** Open games (main thread only); refunded on plugin disable. */
    private static final Set<HeatMenu> ACTIVE = new HashSet<>();

    private static final int SLOT_RESULT = slot(0, 1), SLOT_HEAT = slot(0, 4), SLOT_HELP = slot(0, 8);
    private static final int[] SLOT_STOP = {slot(5, 6), slot(5, 7), slot(5, 8)};
    private static final HeatGame.Action[] ACTIONS = HeatGame.Action.values(); // slots (5,0)..(5,4)

    private final CraftMenu parent;
    private final CraftMenu.Pending pending;
    private final HeatGame game;
    private boolean done;

    private HeatMenu(CraftService service, CraftMenu parent, CraftMenu.Pending pending) {
        super(service, parent.player(), parent.packs().hasPack(parent.player()));
        this.parent = parent;
        this.pending = pending;
        this.game = new HeatGame(service.heatRules(), ThreadLocalRandom.current());
    }

    static void open(CraftService service, CraftMenu parent, CraftMenu.Pending pending) {
        HeatMenu menu = new HeatMenu(service, parent, pending);
        menu.inventory = Bukkit.createInventory(menu, ROWS * 9, menu.title('', "Forge Heat"));
        ACTIVE.add(menu);
        menu.render();
        menu.player.openInventory(menu.inventory);
        menu.player.playSound(menu.player.getLocation(), Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 1f, 1f);
    }

    /** Plugin disable: no DB work is possible any more, so every held input goes back to its player. */
    public static void refundAll(CraftService service) {
        for (HeatMenu m : new ArrayList<>(ACTIVE)) {
            m.done = true;
            if (m.player.isOnline()) {
                m.player.closeInventory();
                service.give(m.player, m.pending.taken());
            } else {
                service.returnOffline(m.player.getUniqueId(), m.pending.taken(), "CRAFT_REFUND");
            }
            service.end(m.player.getUniqueId());
        }
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------ render

    private void render() {
        inventory.clear();
        HeatGame.Rules r = game.rules();
        inventory.setItem(SLOT_RESULT, resultIcon());
        inventory.setItem(SLOT_HEAT, heatIcon());
        inventory.setItem(SLOT_HELP, sprite("blank", Material.BOOK, text("How to play", NamedTextColor.GOLD), lines(
                text("Fill Progress before Integrity runs out.", NamedTextColor.GRAY),
                text("Quality at the end sets the grade:", NamedTextColor.GRAY),
                text(" " + r.fineAt() + "+ Fine (Base x" + mult("FINE") + ")", NamedTextColor.AQUA),
                text(" " + r.masterworkAt() + "+ Masterwork (Base x" + mult("MASTERWORK") + ")", NamedTextColor.LIGHT_PURPLE),
                text("Out of Integrity, STOP or closing = Normal.", NamedTextColor.DARK_GRAY),
                text("Materials and Energy are used either way.", NamedTextColor.DARK_GRAY))));

        bar(1, "pbar", Material.ORANGE_STAINED_GLASS_PANE, "Progress", game.progress(), r.progressGoal(), NamedTextColor.GOLD,
                "Reach " + r.progressGoal() + " to finish the piece");
        bar(2, "qbar", Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Quality", game.quality(), 100, NamedTextColor.AQUA,
                "Grade now: " + GearItems.qualityName(game.projectedGrade()));
        bar(3, "dbar", Material.LIME_STAINED_GLASS_PANE, "Integrity", game.integrity(), r.integrity(), NamedTextColor.GREEN,
                "Each action costs " + game.cost() + (game.heat() == HeatGame.Heat.UNSTABLE ? " (Unstable!)" : ""));
        bar(4, "fbar", Material.MAGENTA_STAINED_GLASS_PANE, "Focus", game.focus(), Math.max(1, r.focus()), NamedTextColor.LIGHT_PURPLE,
                "Spent by Temper and Quench");

        for (int i = 0; i < ACTIONS.length; i++) inventory.setItem(slot(5, i), actionIcon(ACTIONS[i]));
        ItemStack stop = button(true, Material.RED_STAINED_GLASS_PANE, text("STOP", NamedTextColor.RED),
                lines(text("Finish now: crafted at Normal grade.", NamedTextColor.GRAY)));
        for (int s : SLOT_STOP) inventory.setItem(s, stop);
        fillEmpty();
    }

    private String mult(String grade) {
        return GearItems.fmt(service.rules().qualityBp(grade) / (double) GearMath.BP);
    }

    /** Five sprites per bar, 4 steps each (20 steps total). */
    private void bar(int row, String model, Material fallback, String label, int value, int max, TextColor color, String hint) {
        int steps = (int) Math.round(Math.clamp(value / (double) max, 0, 1) * 20);
        List<Component> lore = lines(text(value + " / " + max, color), text(hint, NamedTextColor.DARK_GRAY));
        for (int i = 0; i < 5; i++) {
            int fill = Math.clamp(steps - 4 * i, 0, 4);
            inventory.setItem(slot(row, 3 + i), sprite(model + fill, fill >= 2 ? fallback : Material.GRAY_STAINED_GLASS_PANE,
                    text(label + " " + value + "/" + max, color), lore));
        }
    }

    private ItemStack heatIcon() {
        HeatGame.Heat h = game.heat();
        record Look(String model, Material mat, String name, NamedTextColor color, String effect) {}
        Look l = switch (h) {
            case NORMAL -> new Look("heat_normal", Material.ORANGE_CONCRETE, "Normal", NamedTextColor.GOLD, "No bonus");
            case HOT -> new Look("heat_hot", Material.RED_CONCRETE, "Hot", NamedTextColor.RED,
                    "Quality gains x" + GearItems.fmt(game.rules().hotBp() / (double) GearMath.BP));
            case WHITE_HOT -> new Look("heat_white", Material.WHITE_CONCRETE, "White-hot", NamedTextColor.WHITE,
                    "Quality gains x" + GearItems.fmt(game.rules().whiteHotBp() / (double) GearMath.BP));
            case UNSTABLE -> new Look("heat_unstable", Material.PURPLE_CONCRETE, "Unstable", NamedTextColor.DARK_PURPLE,
                    "Integrity cost x" + GearItems.fmt(game.rules().unstableCostBp() / (double) GearMath.BP));
        };
        return sprite(l.model(), l.mat(), text("Furnace: " + l.name(), l.color()), lines(
                text(l.effect(), NamedTextColor.GRAY),
                text("Turn " + (game.turn() + 1) + " · changes after every action", NamedTextColor.DARK_GRAY)));
    }

    private ItemStack resultIcon() {
        Recipe.Output out = pending.recipe().mainOutput();
        ItemDefinition def = service.plugin().getContentRegistryManager().getItem(out.item());
        if (def == null || !def.isGear()) return service.displayStack(out.item(), out.qty());
        GearItems.GearData preview = new GearItems.GearData(UUID.randomUUID(), 0, 0, 0, game.projectedGrade());
        ItemStack icon = service.gear().create(def, preview);
        List<Component> lore = new ArrayList<>(service.gear().lore(def, preview));
        lore.removeLast(); // "Gear #..." of the throwaway preview id
        lore.add(Component.empty());
        lore.add(text("Preview if you finish with this Quality", NamedTextColor.DARK_GRAY));
        ItemMeta m = icon.getItemMeta();
        m.lore(lore);
        icon.setItemMeta(m);
        return icon;
    }

    private ItemStack actionIcon(HeatGame.Action a) {
        HeatGame.Rules r = game.rules();
        String blocked = game.blocked(a);
        String what = switch (a) {
            case HAMMER -> "+" + r.hammer() + " Progress";
            case FOLD -> "+" + game.qualityGain(r.fold()) + " Quality";
            case TEMPER -> "+" + game.qualityGain(r.temper()) + " Quality · -" + r.temperFocus() + " Focus";
            case QUENCH -> "+" + r.quench() + " Integrity · -" + r.quenchFocus() + " Focus";
            case RUSH -> GearItems.fmt(r.rushChanceBp() / 100.0) + "% chance: +" + r.rush() + " Progress";
        };
        Material mat = switch (a) {
            case HAMMER -> Material.ANVIL;
            case FOLD -> Material.PAPER;
            case TEMPER -> Material.BLAZE_POWDER;
            case QUENCH -> Material.WATER_BUCKET;
            case RUSH -> Material.FEATHER;
        };
        String name = a.name().charAt(0) + a.name().substring(1).toLowerCase(java.util.Locale.ROOT);
        List<Component> lore = lines(text(what, NamedTextColor.GRAY));
        if (a != HeatGame.Action.QUENCH) lore.add(text("Costs " + game.cost() + " Integrity", NamedTextColor.DARK_GRAY));
        if (blocked != null) lore.add(text(blocked, NamedTextColor.RED));
        String model = "act_" + a.name().toLowerCase(java.util.Locale.ROOT) + (blocked != null ? "_off" : "");
        return sprite(model, blocked != null ? Material.GRAY_DYE : mat,
                text(name, blocked != null ? NamedTextColor.GRAY : NamedTextColor.YELLOW), lore);
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected void clickTop(int slot, boolean shift) {
        if (done) return;
        if (slot == SLOT_STOP[0] || slot == SLOT_STOP[1] || slot == SLOT_STOP[2]) {
            game.stop();
            complete(true);
            return;
        }
        int row = slot / 9, col = slot % 9;
        if (row != 5 || col >= ACTIONS.length) return;
        HeatGame.Action a = ACTIONS[col];
        String blocked = game.blocked(a);
        if (blocked != null) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f);
            return;
        }
        String line = game.apply(a);
        player.sendActionBar(Component.text(line, NamedTextColor.GOLD));
        player.playSound(player.getLocation(), switch (a) {
            case HAMMER, RUSH -> Sound.BLOCK_ANVIL_USE;
            case FOLD, TEMPER -> Sound.BLOCK_SMITHING_TABLE_USE;
            case QUENCH -> Sound.BLOCK_FIRE_EXTINGUISH;
        }, 0.6f, 1f + ThreadLocalRandom.current().nextFloat() * 0.2f);
        if (game.over()) complete(true);
        else render();
    }

    @Override
    protected void closed() {
        if (done) return;
        game.stop();
        complete(false);
    }

    private void complete(boolean reopen) {
        if (done) return;
        done = true;
        ACTIVE.remove(this);
        String grade = game.grade();
        switch (game.end()) {
            case COMPLETE -> player.sendMessage(Component.text("[Forge] Finished with Quality " + game.quality() + " → "
                    + GearItems.qualityName(grade) + ".", grade.equals("NORMAL") ? NamedTextColor.GRAY : NamedTextColor.GREEN));
            case CRACKED -> player.sendMessage(Component.text("[Forge] Integrity ran out. The piece is finished at Normal.", NamedTextColor.YELLOW));
            default -> player.sendMessage(Component.text("[Forge] Stopped. The piece is finished at Normal.", NamedTextColor.GRAY));
        }
        if (!grade.equals("NORMAL")) player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        parent.finish(pending, game);
        if (reopen) Bukkit.getScheduler().runTask(service.plugin(), parent::reopen);
    }
}
