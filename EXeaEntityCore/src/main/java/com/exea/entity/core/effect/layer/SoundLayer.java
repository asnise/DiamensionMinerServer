package com.exea.entity.core.effect.layer;

import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.Json;
import com.exea.entity.core.effect.LayerRun;
import com.exea.entity.core.effect.LayerTemplate;
import com.google.gson.JsonObject;
import org.bukkit.SoundCategory;

import java.util.Set;
import java.util.function.Consumer;

/** §1.8: a sound at the anchor (any sound key, resource-pack sounds included). */
public final class SoundLayer {

    private SoundLayer() { }

    record Template(String sound, float volume, Json.Range pitch, SoundCategory category) implements LayerTemplate {
        public LayerRun start(EffectInstance e, EffectDefinition.Layer l) {
            return new LayerRun(e, l) {
                @Override
                protected void emit(double progress) {
                    Frame f = frame();
                    if (f != null) f.world.playSound(f.location(), sound, category, volume, (float) pitch.roll(effect.random));
                }
            };
        }

        public Set<String> keys() { return Set.of("sound", "volume", "pitch", "category"); }
    }

    public static LayerTemplate compile(JsonObject j, Consumer<String> warn) {
        String sound = Json.str(j, "sound", null);
        if (sound == null || sound.isBlank()) {
            warn.accept("\"sound\" is required");
            return null;
        }
        SoundCategory cat;
        try {
            cat = SoundCategory.valueOf(Json.str(j, "category", "hostile").toUpperCase());
        } catch (IllegalArgumentException e) {
            warn.accept("unknown category, using hostile");
            cat = SoundCategory.HOSTILE;
        }
        return new Template(sound.trim().toLowerCase(), (float) Json.num(j, "volume", 1), Json.range(j, "pitch", 1, 1), cat);
    }
}
