package com.exea.entity.api.effect;

/**
 * A layer type added by another plugin. A layer whose {@code "type"} is not built in calls the handler
 * registered under that name, once for a burst or every {@code interval} while its {@code duration}
 * runs. Register with {@code EXeaAPI.registerEffectLayer("lightning", ctx -> ...)}.
 */
@FunctionalInterface
public interface EffectLayerHandler {

    void play(EffectLayerContext context);
}
