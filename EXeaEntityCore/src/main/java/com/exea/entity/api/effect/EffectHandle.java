package com.exea.entity.api.effect;

/** A playing effect. Looping effects run until {@link #stop()} or until their origin is gone. */
public interface EffectHandle {

    /** The effect preset id. */
    String getId();

    /** False once it ended or was stopped. */
    boolean isActive();

    /** Ends it now and removes everything it spawned (flying blocks, decals, models). */
    void stop();
}
