package com.exea.entity.core.effect;

import com.exea.entity.core.effect.layer.DebrisLayer;
import com.exea.entity.core.effect.layer.DecalLayer;
import com.exea.entity.core.effect.layer.GroundWaveLayer;
import com.exea.entity.core.effect.layer.ModelLayer;
import com.exea.entity.core.effect.layer.ParticleLayer;
import com.exea.entity.core.effect.layer.PresetLayer;
import com.exea.entity.core.effect.layer.SoundLayer;
import com.exea.entity.core.effect.layer.TelegraphLayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A compiled layer type: checked once when the file loads, then started each time the effect plays. */
public interface LayerTemplate {

    LayerRun start(EffectInstance effect, EffectDefinition.Layer layer);

    /** Keys this type reads besides the shared ones (for the unknown-key warning). */
    Set<String> keys();

    /** Seconds things live after the layer's own duration (flying blocks, decals, models). */
    default double extraLife(EffectDefinition.Layer layer) {
        return 0;
    }

    interface Factory {
        /** Null (after a warning) when the layer can't work. */
        LayerTemplate compile(JsonObject json, Consumer<String> warn);
    }

    Map<String, Factory> BUILT_IN = Map.of(
            "particle", ParticleLayer::compile,
            "debris", DebrisLayer::compile,
            "ground_wave", GroundWaveLayer::compile,
            "decal", DecalLayer::compile,
            "telegraph", TelegraphLayer::compile,
            "model", ModelLayer::compile,
            "sound", SoundLayer::compile,
            "preset", PresetLayer::compile);
}
