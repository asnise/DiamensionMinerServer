package com.exea.entity.core.timeline;

import org.bukkit.Particle;
import org.bukkit.Sound;

public class TimelineEvent {

    // #region [Variables/Fields]
    private final float timestamp;
    private final String eventTag;
    private Sound sound;
    private String customSoundKey;
    private float soundVolume = 1.0f;
    private float soundPitch = 1.0f;
    private Particle particle;
    private int particleCount = 10;
    private double particleSpread = 0.5;
    private double hitboxRange = 0.0;
    private double hitboxDamage = 0.0;
    // #endregion

    // #region [Properties]
    public float getTimestamp() {
        return timestamp;
    }

    public String getEventTag() {
        return eventTag;
    }

    public Sound getSound() {
        return sound;
    }

    public void setSound(Sound sound) {
        this.sound = sound;
    }

    public String getCustomSoundKey() {
        return customSoundKey;
    }

    public void setCustomSoundKey(String customSoundKey) {
        this.customSoundKey = customSoundKey;
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(float soundVolume) {
        this.soundVolume = soundVolume;
    }

    public float getSoundPitch() {
        return soundPitch;
    }

    public void setSoundPitch(float soundPitch) {
        this.soundPitch = soundPitch;
    }

    public Particle getParticle() {
        return particle;
    }

    public void setParticle(Particle particle) {
        this.particle = particle;
    }

    public int getParticleCount() {
        return particleCount;
    }

    public void setParticleCount(int particleCount) {
        this.particleCount = particleCount;
    }

    public double getParticleSpread() {
        return particleSpread;
    }

    public void setParticleSpread(double particleSpread) {
        this.particleSpread = particleSpread;
    }

    public double getHitboxRange() {
        return hitboxRange;
    }

    public void setHitboxRange(double hitboxRange) {
        this.hitboxRange = hitboxRange;
    }

    public double getHitboxDamage() {
        return hitboxDamage;
    }

    public void setHitboxDamage(double hitboxDamage) {
        this.hitboxDamage = hitboxDamage;
    }
    // #endregion

    // #region [Public Methods]
    public TimelineEvent(float timestamp, String eventTag) {
        this.timestamp = timestamp;
        this.eventTag = eventTag;
    }
    // #endregion
}
