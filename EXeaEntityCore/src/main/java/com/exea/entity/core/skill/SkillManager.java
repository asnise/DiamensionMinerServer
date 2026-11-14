package com.exea.entity.core.skill;

import com.exea.entity.api.skill.SkillActionHandler;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.manager.EntityManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads movesets ({@code models/<id>_moveset.json} from the jar, then {@code plugins/EXeaEntityCore/models/},
 * which wins) and keeps the action types other plugins registered. Main thread only.
 */
public final class SkillManager {

    /** Something a skill leaves running (a projectile, a dash). False from tick() = done. */
    public interface Task {
        boolean tick();

        default void stop() { }
    }

    private static final SkillManager INSTANCE = new SkillManager();

    public static SkillManager get() {
        return INSTANCE;
    }

    private Plugin plugin;
    private final Map<String, Moveset> movesets = new TreeMap<>();
    private final Map<String, SkillActionHandler> customActions = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    public void start(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Reloads every moveset. Live entities keep the moveset they spawned with. Call after models and effects. */
    public void reload() {
        if (plugin == null) return;
        movesets.clear();
        warned.clear();
        try {
            java.net.URL jarUrl = SkillManager.class.getProtectionDomain().getCodeSource().getLocation();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(jarUrl.toURI()))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("models/") && name.endsWith("_moveset.json")) {
                        String id = name.substring("models/".length(), name.length() - "_moveset.json".length());
                        try (InputStream in = plugin.getResource(name)) {
                            if (in != null) load(id, in, "jar:" + name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] Could not list movesets in the plugin jar: " + e.getMessage());
        }
        File[] files = new File(plugin.getDataFolder(), "models").listFiles((d, n) -> n.endsWith("_moveset.json"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - "_moveset.json".length());
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    load(id, in, "models/" + f.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning("[EXea] " + f + ": " + e.getMessage());
                }
            }
        }
        checkModels();
        if (!movesets.isEmpty()) plugin.getLogger().info("[EXea] Movesets: " + movesets.keySet());
    }

    private void load(String id, InputStream in, String where) {
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject j = JsonParser.parseReader(r).getAsJsonObject();
            Moveset m = Moveset.parse(id, j, msg -> plugin.getLogger().warning("[EXea] " + where + ": " + msg));
            if (m != null) movesets.put(id, m);
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] " + where + ": not valid JSON (" + e.getMessage() + "); skipped");
        }
    }

    /** Animations each model is missing, and effect ids that are not loaded (§0 load-time checks). */
    private void checkModels() {
        EffectManager fx = EffectManager.get();
        for (Moveset m : movesets.values()) {
            Set<String> anims = EntityManager.getInstance().getAnimationNames(m.id);
            for (Moveset.Skill s : m.skills.values()) {
                if (s.animation != null && !anims.isEmpty() && !anims.contains(s.animation)) {
                    plugin.getLogger().warning("[EXea] moveset " + m.id + ": skill " + s.id + " plays unknown animation \"" + s.animation + "\"");
                }
                for (Moveset.ActionSpec a : s.actions) checkEffects(m.id, s.id, a, fx);
            }
        }
    }

    private void checkEffects(String model, String skill, Moveset.ActionSpec a, EffectManager fx) {
        String where = "[EXea] moveset " + model + ": skill " + skill + ": ";
        if (a.action instanceof FxAction f && fx.get(f.id()) == null) plugin.getLogger().warning(where + "fx \"" + f.id() + "\" is not a loaded effect");
        if (a.action instanceof ProjectileAction p) {
            if (p.trail() != null && !p.trail().isBlank() && fx.get(p.trail()) == null) plugin.getLogger().warning(where + "trail \"" + p.trail() + "\" is not a loaded effect");
            for (Moveset.ActionSpec h : p.onHit()) checkEffects(model, skill, h, fx);
        }
        if (a.action instanceof MoveAction mv) {
            if (mv.fxIn() != null && fx.get(mv.fxIn()) == null) plugin.getLogger().warning(where + "fx_in \"" + mv.fxIn() + "\" is not a loaded effect");
            if (mv.fxOut() != null && fx.get(mv.fxOut()) == null) plugin.getLogger().warning(where + "fx_out \"" + mv.fxOut() + "\" is not a loaded effect");
        }
    }

    /** The moveset for a model: the meta's {@code moveset} key if set, else {@code <model>_moveset.json}. */
    public Moveset forModel(String modelId, String metaMoveset) {
        return movesets.get(metaMoveset != null ? metaMoveset : modelId);
    }

    public Set<String> ids() {
        return movesets.keySet();
    }

    public void registerAction(String type, SkillActionHandler h) {
        if (h == null) customActions.remove(type);
        else customActions.put(type, h);
    }

    SkillActionHandler customAction(String type) {
        return customActions.get(type);
    }

    public void warnOnce(String key, String msg) {
        if (plugin != null && warned.add(key)) plugin.getLogger().warning("[EXea] " + msg);
    }
}
