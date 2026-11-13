package com.exea.entity.api;

import com.exea.entity.api.behavior.EXeaBehavior;
import org.bukkit.entity.EntityType;

/**
 * Optional settings for {@link EXeaAPI#spawn(String, org.bukkit.Location, SpawnOptions)}. Anything left
 * unset comes from the model's {@code <id>_meta.json}, then from EXea's defaults.
 *
 * <pre>{@code
 * EXeaEntity boss = EXeaAPI.getInstance().spawn("void_overseer", loc,
 *         SpawnOptions.create().driver(EntityType.ZOMBIE).health(2000).displayName("Overseer of Floor 3"));
 * }</pre>
 */
public final class SpawnOptions {

    private EntityType driver;
    private Boolean ai;
    private Double health;
    private Double attackDamage;
    private Double movementSpeed;
    private Double hitboxScale;
    private Double followRange;
    private String displayName;
    private Boolean nameVisible;
    private EXeaBehavior behavior;

    public static SpawnOptions create() {
        return new SpawnOptions();
    }

    /**
     * Driver entity type: any living entity (a {@code Mob} for walking/attacking AI, {@code ARMOR_STAND}
     * for a statue). Default: meta {@code driver}, else HUSK (with AI) or ARMOR_STAND (without AI).
     */
    public SpawnOptions driver(EntityType type) {
        this.driver = type;
        return this;
    }

    /** Vanilla mob AI on or off. Default true. */
    public SpawnOptions ai(boolean ai) {
        this.ai = ai;
        return this;
    }

    public SpawnOptions health(double health) {
        this.health = health;
        return this;
    }

    public SpawnOptions attackDamage(double attackDamage) {
        this.attackDamage = attackDamage;
        return this;
    }

    public SpawnOptions movementSpeed(double movementSpeed) {
        this.movementSpeed = movementSpeed;
        return this;
    }

    /** Driver size (vanilla SCALE attribute): hitbox only, the model is not resized. */
    public SpawnOptions hitboxScale(double hitboxScale) {
        this.hitboxScale = hitboxScale;
        return this;
    }

    public SpawnOptions followRange(double followRange) {
        this.followRange = followRange;
        return this;
    }

    /** Name shown above the entity (legacy colour codes with §). */
    public SpawnOptions displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    /** Show the name + health tag. Default true. */
    public SpawnOptions nameVisible(boolean nameVisible) {
        this.nameVisible = nameVisible;
        return this;
    }

    /** Behavior for this one entity; wins over {@link EXeaAPI#registerBehavior}. */
    public SpawnOptions behavior(EXeaBehavior behavior) {
        this.behavior = behavior;
        return this;
    }

    public EntityType driver() {
        return driver;
    }

    public Boolean ai() {
        return ai;
    }

    public Double health() {
        return health;
    }

    public Double attackDamage() {
        return attackDamage;
    }

    public Double movementSpeed() {
        return movementSpeed;
    }

    public Double hitboxScale() {
        return hitboxScale;
    }

    public Double followRange() {
        return followRange;
    }

    public String displayName() {
        return displayName;
    }

    public Boolean nameVisible() {
        return nameVisible;
    }

    public EXeaBehavior behavior() {
        return behavior;
    }
}
