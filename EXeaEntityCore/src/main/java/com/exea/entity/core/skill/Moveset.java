package com.exea.entity.core.skill;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One parsed {@code <model>_moveset.json} (docs/FORMATS.md §2). Immutable after loading. */
public final class Moveset {

    private static final Set<String> TOP = Set.of("$schema", "format", "ai", "phases", "skills");
    private static final Set<String> AI = Set.of("mode", "think_interval", "global_cooldown", "target", "chase_range", "keep_distance",
            "idle_animation", "move_animation", "melee");
    private static final Set<String> PHASE = Set.of("id", "hp_above", "skills", "speed", "on_enter", "invulnerable_on_enter");
    private static final Set<String> SKILL = Set.of("name", "animation", "speed", "duration", "cooldown", "weight", "priority", "internal",
            "conditions", "target", "face", "root", "interruptible", "interrupt_damage", "actions", "followup");
    private static final Set<String> CONDITIONS = Set.of("range", "hp", "targets_min", "targets_radius", "line_of_sight", "chance", "target_airborne");
    static final Set<String> ACTION_BASE = Set.of("at", "type", "repeat", "bone", "anchor");
    static final Set<String> TARGET_MODES = Set.of("current", "nearest", "farthest", "random", "lowest_hp");

    public record Ai(boolean auto, double thinkInterval, double globalCooldown, String target, Double chaseRange,
                     double keepDistance, String idleAnimation, String moveAnimation, boolean melee) { }

    public record Phase(String id, double hpAbove, List<String> skills, double speed, String onEnter, double invulnerableOnEnter) { }

    /** All must pass. Null fields are not checked. */
    public record Conditions(double[] range, double[] hp, Integer targetsMin, double targetsRadius, Boolean lineOfSight,
                             Double chance, Boolean targetAirborne) {
        static final Conditions NONE = new Conditions(null, null, null, 6, null, null, null);
    }

    /** One action of a skill: shared keys plus the compiled type ({@code action}, null for a plugin type). */
    public static final class ActionSpec {
        public final double at;
        public final String type;
        public final int repeatCount;
        public final double repeatInterval;
        public final String bone;
        public final EffectDefinition.Anchor anchor;
        public final JsonObject json;
        public final SkillAction action;

        ActionSpec(JsonObject j, String type, SkillAction action) {
            this.at = Math.max(0, Json.num(j, "at", 0));
            this.type = type;
            JsonObject rep = Json.obj(j, "repeat");
            this.repeatCount = rep == null ? 1 : Math.max(1, (int) Json.num(rep, "count", 1));
            this.repeatInterval = rep == null ? 0.25 : Math.max(0.05, Json.num(rep, "interval", 0.25));
            String b = Json.str(j, "bone", null);
            this.bone = b == null || b.isBlank() ? null : b.toLowerCase();
            this.anchor = EffectDefinition.Anchor.parse(Json.obj(j, "anchor"));
            this.json = j;
            this.action = action;
        }
    }

    public static final class Skill {
        public final String id, name, animation, target, face;
        public final double speed, cooldown, weight, interruptDamage;
        public final Double duration;
        public final int priority;
        public final boolean internal, root, interruptible;
        public final Conditions conditions;
        public final List<ActionSpec> actions;
        public final String followup;
        public final double followupChance;

        Skill(String id, JsonObject j, List<ActionSpec> actions, Consumer<String> warn) {
            this.id = id;
            this.name = Json.str(j, "name", id);
            String a = Json.str(j, "animation", null);
            this.animation = a == null ? null : a.toLowerCase();
            this.speed = Math.max(0.05, Json.num(j, "speed", 1));
            this.duration = Json.numOrNull(j, "duration");
            this.cooldown = Math.max(0, Json.num(j, "cooldown", 0));
            this.weight = Math.max(0, Json.num(j, "weight", 1));
            this.priority = (int) Json.num(j, "priority", 0);
            this.internal = Json.bool(j, "internal", false);
            String t = Json.str(j, "target", null);
            if (t != null && !TARGET_MODES.contains(t)) {
                warn.accept("target must be one of " + TARGET_MODES + "; using the ai default");
                t = null;
            }
            this.target = t;
            String f = Json.str(j, "face", "start");
            if (!Set.of("start", "track", "none").contains(f)) {
                warn.accept("face must be start, track or none; using start");
                f = "start";
            }
            this.face = f;
            this.root = Json.bool(j, "root", false);
            this.interruptible = Json.bool(j, "interruptible", false);
            this.interruptDamage = Json.num(j, "interrupt_damage", 0);
            JsonObject c = Json.obj(j, "conditions");
            if (c != null) for (String k : c.keySet()) if (!CONDITIONS.contains(k)) warn.accept("conditions: unknown key \"" + k + "\" ignored");
            this.conditions = c == null ? Conditions.NONE : new Conditions(pair(c, "range"), pair(c, "hp"),
                    c.has("targets_min") ? (int) Json.num(c, "targets_min", 0) : null, Json.num(c, "targets_radius", 6),
                    c.has("line_of_sight") ? Json.bool(c, "line_of_sight", false) : null, Json.numOrNull(c, "chance"),
                    c.has("target_airborne") ? Json.bool(c, "target_airborne", false) : null);
            this.actions = List.copyOf(actions);
            JsonObject fu = Json.obj(j, "followup");
            this.followup = fu == null ? null : Json.str(fu, "skill", null);
            this.followupChance = fu == null ? 0 : Json.num(fu, "chance", 1);
        }

        private static double[] pair(JsonObject o, String key) {
            Json.Range r = o.has(key) ? Json.range(o, key, 0, 0) : null;
            return r == null ? null : new double[]{r.min(), r.max()};
        }
    }

    public final String id;
    public final Ai ai;
    public final List<Phase> phases;
    public final Map<String, Skill> skills;

    private Moveset(String id, Ai ai, List<Phase> phases, Map<String, Skill> skills) {
        this.id = id;
        this.ai = ai;
        this.phases = List.copyOf(phases);
        this.skills = Collections.unmodifiableMap(skills);
    }

    /** Parses a file; problems go to {@code warn} with their JSON path and the broken part is skipped. Null if unusable. */
    public static Moveset parse(String id, JsonObject j, Consumer<String> warn) {
        if (Json.num(j, "format", 1) > 1) {
            warn.accept("format is newer than this EXea (1); skipped");
            return null;
        }
        for (String k : j.keySet()) if (!TOP.contains(k)) warn.accept("unknown key \"" + k + "\" ignored");
        JsonObject a = Json.obj(j, "ai");
        if (a != null) for (String k : a.keySet()) if (!AI.contains(k)) warn.accept("ai: unknown key \"" + k + "\" ignored");
        String mode = Json.str(a, "mode", "auto");
        if (!Set.of("auto", "manual").contains(mode)) warn.accept("ai.mode must be auto or manual; using auto");
        String target = Json.str(a, "target", "current");
        if (!TARGET_MODES.contains(target)) {
            warn.accept("ai.target must be one of " + TARGET_MODES + "; using current");
            target = "current";
        }
        Ai ai = new Ai(!"manual".equals(mode), Math.max(0.05, Json.num(a, "think_interval", 0.25)),
                Math.max(0, Json.num(a, "global_cooldown", 1)), target, Json.numOrNull(a, "chase_range"),
                Math.max(0, Json.num(a, "keep_distance", 0)), Json.str(a, "idle_animation", "idle").toLowerCase(),
                Json.str(a, "move_animation", "move").toLowerCase(), Json.bool(a, "melee", true));

        Map<String, Skill> skills = new LinkedHashMap<>();
        JsonObject sj = Json.obj(j, "skills");
        if (sj == null) {
            warn.accept("no \"skills\" object");
            return null;
        }
        for (Map.Entry<String, JsonElement> e : sj.entrySet()) {
            String sid = e.getKey();
            String where = "skills." + sid;
            if (!sid.matches("[a-z0-9_\\-/]+")) {
                warn.accept(where + ": skill ids are lower case a-z 0-9 _ - /; skipped");
                continue;
            }
            if (!e.getValue().isJsonObject()) {
                warn.accept(where + ": not an object");
                continue;
            }
            JsonObject s = e.getValue().getAsJsonObject();
            for (String k : s.keySet()) if (!SKILL.contains(k)) warn.accept(where + ": unknown key \"" + k + "\" ignored");
            List<ActionSpec> actions = parseActions(s.get("actions"), where + ".actions", warn);
            skills.put(sid, new Skill(sid, s, actions, m -> warn.accept(where + ": " + m)));
        }

        List<Phase> phases = new ArrayList<>();
        if (j.has("phases") && j.get("phases").isJsonArray()) {
            int i = 0;
            for (JsonElement pe : j.getAsJsonArray("phases")) {
                String where = "phases[" + i++ + "]";
                if (!pe.isJsonObject()) continue;
                JsonObject p = pe.getAsJsonObject();
                for (String k : p.keySet()) if (!PHASE.contains(k)) warn.accept(where + ": unknown key \"" + k + "\" ignored");
                String pid = Json.str(p, "id", null);
                if (pid == null) {
                    warn.accept(where + ": no id; skipped");
                    continue;
                }
                List<String> list = null;
                if (p.has("skills") && p.get("skills").isJsonArray()) {
                    list = new ArrayList<>();
                    for (JsonElement x : p.getAsJsonArray("skills")) {
                        String sid = x.getAsString();
                        if (!skills.containsKey(sid)) warn.accept(where + ": unknown skill \"" + sid + "\"");
                        else list.add(sid);
                    }
                }
                String onEnter = Json.str(p, "on_enter", null);
                if (onEnter != null && !skills.containsKey(onEnter)) {
                    warn.accept(where + ".on_enter: unknown skill \"" + onEnter + "\"");
                    onEnter = null;
                }
                phases.add(new Phase(pid, Math.max(0, Math.min(1, Json.num(p, "hp_above", 0))), list,
                        Math.max(0.05, Json.num(p, "speed", 1)), onEnter, Math.max(0, Json.num(p, "invulnerable_on_enter", 0))));
            }
        }
        for (Skill s : skills.values()) {
            if (s.followup != null && !skills.containsKey(s.followup)) warn.accept("skills." + s.id + ".followup: unknown skill \"" + s.followup + "\"");
            for (ActionSpec a2 : s.actions) {
                if (a2.action instanceof PresetSkillAction p && !skills.containsKey(p.skill())) {
                    warn.accept("skills." + s.id + ": preset_skill refers to unknown skill \"" + p.skill() + "\"");
                }
            }
        }
        return new Moveset(id, ai, phases, skills);
    }

    static List<ActionSpec> parseActions(JsonElement el, String where, Consumer<String> warn) {
        List<ActionSpec> out = new ArrayList<>();
        if (el == null) return out;
        if (!el.isJsonArray()) {
            warn.accept(where + ": must be an array");
            return out;
        }
        int i = 0;
        for (JsonElement ae : el.getAsJsonArray()) {
            String w = where + "[" + i++ + "]";
            if (!ae.isJsonObject()) {
                warn.accept(w + ": not an object");
                continue;
            }
            JsonObject aj = ae.getAsJsonObject();
            String type = Json.str(aj, "type", null);
            if (type == null) {
                warn.accept(w + ": no \"type\"");
                continue;
            }
            SkillAction.Factory f = SkillAction.BUILT_IN.get(type);
            SkillAction compiled = null;
            if (f != null) {
                Consumer<String> wa = m -> warn.accept(w + " (" + type + "): " + m);
                try {
                    compiled = f.compile(aj, wa);
                } catch (RuntimeException ex) {
                    wa.accept(ex.toString());
                }
                if (compiled == null) continue;
                for (String k : aj.keySet()) {
                    if (!ACTION_BASE.contains(k) && !compiled.keys().contains(k)) wa.accept("unknown key \"" + k + "\" ignored");
                }
            }
            out.add(new ActionSpec(aj, type, compiled));
        }
        out.sort((x, y) -> Double.compare(x.at, y.at));
        return out;
    }

    /** The phase for an HP fraction: the first one whose {@code hp_above} it is over (phases go high HP → low). */
    public int phaseIndexFor(double hpFraction) {
        for (int i = 0; i < phases.size(); i++) if (hpFraction > phases.get(i).hpAbove() || i == phases.size() - 1) return i;
        return -1;
    }
}
