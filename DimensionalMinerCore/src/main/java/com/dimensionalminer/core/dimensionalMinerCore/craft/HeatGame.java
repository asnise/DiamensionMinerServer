package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Forge Heat: a turn-based crafting mini-game that decides the crafted grade (Normal / Fine /
 * Masterwork). Pure server-side state; the client only picks actions.
 * <ul>
 *   <li>Progress must reach the goal before Integrity runs out (every action costs Integrity).</li>
 *   <li>Quality at completion picks the grade. Running out of Integrity still crafts the item at Normal,
 *       so playing is never worse than Quick Craft.</li>
 *   <li>The furnace Heat changes every turn and is shown before the player picks: HOT / WHITE_HOT boost
 *       Quality gains, UNSTABLE doubles the Integrity cost.</li>
 * </ul>
 */
public final class HeatGame {

    public enum Heat { NORMAL, HOT, WHITE_HOT, UNSTABLE }

    public enum Action { HAMMER, FOLD, TEMPER, QUENCH, RUSH }

    public enum End { NONE, COMPLETE, CRACKED, STOPPED }

    /** Numbers from config craft.minigame (defaults below). Bp = basis points (10000 = x1). */
    public record Rules(int progressGoal, int integrity, int focus, int fineAt, int masterworkAt, int integrityCost,
                        int hammer, int fold, int temper, int temperFocus, int quench, int quenchFocus,
                        int rush, int rushChanceBp, int[] heatWeights, int hotBp, int whiteHotBp, int unstableCostBp,
                        boolean enabled) {

        public static Rules from(FileConfiguration cfg) {
            ConfigurationSection s = cfg.getConfigurationSection("craft.minigame");
            if (s == null) s = cfg.createSection("craft.minigame");
            ConfigurationSection w = s.getConfigurationSection("heat_weights");
            int[] weights = {
                    w == null ? 55 : w.getInt("NORMAL", 55), w == null ? 25 : w.getInt("HOT", 25),
                    w == null ? 8 : w.getInt("WHITE_HOT", 8), w == null ? 12 : w.getInt("UNSTABLE", 12)};
            return new Rules(
                    Math.max(1, s.getInt("progress_goal", 100)), Math.max(1, s.getInt("integrity", 70)),
                    Math.max(0, s.getInt("focus", 50)), s.getInt("fine_at", 50), s.getInt("masterwork_at", 95),
                    Math.max(1, s.getInt("integrity_cost", 10)),
                    s.getInt("hammer_progress", 25), s.getInt("fold_quality", 15),
                    s.getInt("temper_quality", 30), s.getInt("temper_focus", 20),
                    s.getInt("quench_integrity", 20), s.getInt("quench_focus", 15),
                    s.getInt("rush_progress", 60), s.getInt("rush_chance_bp", 5000), weights,
                    s.getInt("hot_quality_bp", 15000), s.getInt("white_hot_quality_bp", 25000),
                    s.getInt("unstable_cost_bp", 15000), s.getBoolean("enabled", true));
        }

        public String grade(int quality) {
            if (quality >= masterworkAt) return "MASTERWORK";
            if (quality >= fineAt) return "FINE";
            return "NORMAL";
        }
    }

    private final Rules rules;
    private final RandomGenerator rng;
    private int progress, quality, integrity, focus, turn;
    private Heat heat = Heat.NORMAL;
    private End end = End.NONE;
    private final JsonArray log = new JsonArray();

    public HeatGame(Rules rules, RandomGenerator rng) {
        this.rules = rules;
        this.rng = rng;
        this.integrity = rules.integrity();
        this.focus = rules.focus();
    }

    public Rules rules() { return rules; }
    public int progress() { return progress; }
    public int quality() { return quality; }
    public int integrity() { return integrity; }
    public int focus() { return focus; }
    public int turn() { return turn; }
    public Heat heat() { return heat; }
    public End end() { return end; }
    public boolean over() { return end != End.NONE; }

    /** Grade the item gets right now: only a completed piece can be better than Normal. */
    public String grade() {
        return end == End.COMPLETE ? rules.grade(quality) : "NORMAL";
    }

    /** Grade the current Quality would give if the piece were completed. */
    public String projectedGrade() {
        return rules.grade(quality);
    }

    public int cost() {
        return (int) ((long) rules.integrityCost() * (heat == Heat.UNSTABLE ? rules.unstableCostBp() : GearMath.BP) / GearMath.BP);
    }

    public int qualityGain(int base) {
        int bp = switch (heat) {
            case HOT -> rules.hotBp();
            case WHITE_HOT -> rules.whiteHotBp();
            default -> GearMath.BP;
        };
        return (int) ((long) base * bp / GearMath.BP);
    }

    /** Why the action can't be used now, or null. */
    public String blocked(Action a) {
        if (over()) return "The piece is finished";
        return switch (a) {
            case TEMPER -> focus < rules.temperFocus() ? "Needs " + rules.temperFocus() + " Focus" : null;
            case QUENCH -> focus < rules.quenchFocus() ? "Needs " + rules.quenchFocus() + " Focus"
                    : integrity >= rules.integrity() ? "Integrity is already full" : null;
            default -> null;
        };
    }

    /** Applies one action and rolls the next Heat. Returns a short result line for chat. */
    public String apply(Action a) {
        if (blocked(a) != null) return blocked(a);
        Heat used = heat;
        int dP = 0, dQ = 0, dI = 0, dF = 0;
        boolean hit = true;
        switch (a) {
            case HAMMER -> { dP = rules.hammer(); dI = -cost(); }
            case FOLD -> { dQ = qualityGain(rules.fold()); dI = -cost(); }
            case TEMPER -> { dQ = qualityGain(rules.temper()); dI = -cost(); dF = -rules.temperFocus(); }
            case QUENCH -> { dI = Math.min(rules.quench(), rules.integrity() - integrity); dF = -rules.quenchFocus(); }
            case RUSH -> {
                hit = rng.nextInt(GearMath.BP) < rules.rushChanceBp();
                dP = hit ? rules.rush() : 0;
                dI = -cost();
            }
        }
        progress = Math.min(rules.progressGoal(), progress + dP);
        quality = Math.min(100, quality + dQ);
        integrity = Math.max(0, integrity + dI);
        focus = Math.max(0, focus + dF);
        turn++;

        JsonObject step = new JsonObject();
        step.addProperty("turn", turn);
        step.addProperty("heat", used.name());
        step.addProperty("action", a.name());
        if (a == Action.RUSH) step.addProperty("hit", hit);
        step.addProperty("progress", progress);
        step.addProperty("quality", quality);
        step.addProperty("integrity", integrity);
        log.add(step);

        if (progress >= rules.progressGoal()) end = End.COMPLETE;
        else if (integrity <= 0) end = End.CRACKED;
        else heat = rollHeat(used);

        String name = a.name().charAt(0) + a.name().substring(1).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(name);
        if (a == Action.RUSH && !hit) sb.append(": missed");
        if (dP > 0) sb.append(" +").append(dP).append(" Progress");
        if (dQ > 0) sb.append(" +").append(dQ).append(" Quality");
        if (dI > 0) sb.append(" +").append(dI).append(" Integrity");
        if (dI < 0) sb.append(" ").append(dI).append(" Integrity");
        return sb.toString();
    }

    /** Ends the game early: the item is crafted at Normal. */
    public void stop() {
        if (!over()) end = End.STOPPED;
    }

    private Heat rollHeat(Heat previous) {
        int[] w = rules.heatWeights();
        int total = 0;
        for (int x : w) total += Math.max(0, x);
        if (total <= 0) return Heat.NORMAL;
        int r = rng.nextInt(total);
        Heat next = Heat.NORMAL;
        for (int i = 0; i < w.length; i++) {
            r -= Math.max(0, w[i]);
            if (r < 0) { next = Heat.values()[i]; break; }
        }
        // White-hot never lasts two turns in a row.
        return next == Heat.WHITE_HOT && previous == Heat.WHITE_HOT ? Heat.NORMAL : next;
    }

    /** Summary stored in item_transactions.metadata. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("end", end.name());
        o.addProperty("progress", progress);
        o.addProperty("quality", quality);
        o.addProperty("integrity", integrity);
        o.addProperty("turns", turn);
        o.add("steps", log);
        return o;
    }
}
