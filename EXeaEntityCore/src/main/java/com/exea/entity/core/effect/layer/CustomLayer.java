package com.exea.entity.core.effect.layer;

import com.exea.entity.api.effect.EffectLayerContext;
import com.exea.entity.api.effect.EffectLayerHandler;
import com.exea.entity.core.effect.EffectDefinition;
import com.exea.entity.core.effect.EffectInstance;
import com.exea.entity.core.effect.Frame;
import com.exea.entity.core.effect.LayerRun;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** A layer type registered by another plugin through {@code EXeaAPI.registerEffectLayer}. */
public final class CustomLayer extends LayerRun {

    private final EffectLayerHandler handler;

    public CustomLayer(EffectInstance e, EffectDefinition.Layer l, EffectLayerHandler handler) {
        super(e, l);
        this.handler = handler;
    }

    @Override
    protected void emit(double progress) {
        Frame f = frame();
        if (f == null) return;
        Location at = f.location();
        handler.play(new EffectLayerContext() {
            public String getEffectId() { return effect.def.id; }
            public Location getLocation() { return at.clone(); }
            public float getYaw() { return f.yaw; }
            public double getScale() { return effect.scale; }
            public Entity getTarget() { return effect.target; }
            public Entity getSource() { return effect.origin.entity(); }
            public JsonObject getJson() { return layer.json; }
            public double getProgress() { return progress; }
            public int getTick() { return age; }
        });
    }
}
