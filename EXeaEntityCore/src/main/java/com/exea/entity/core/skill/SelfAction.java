package com.exea.entity.core.skill;

import com.exea.entity.core.effect.Json;
import com.google.gson.JsonObject;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.NamespacedKey;

import java.util.Set;
import java.util.function.Consumer;

/** §2.4 {@code self}: heal (fraction of max HP), invulnerable for seconds, movement speed × for seconds. */
record SelfAction(double heal, double invulnerable, double speed, double seconds) implements SkillAction {

    static final NamespacedKey SPEED_KEY = new NamespacedKey("exea", "skill_speed");

    public Set<String> keys() { return Set.of("heal", "invulnerable", "speed", "seconds"); }

    static SkillAction compile(JsonObject j, Consumer<String> warn) {
        SelfAction a = new SelfAction(Math.max(0, Math.min(1, Json.num(j, "heal", 0))), Math.max(0, Json.num(j, "invulnerable", 0)),
                Math.max(0.05, Json.num(j, "speed", 1)), Math.max(0, Json.num(j, "seconds", 3)));
        if (a.heal == 0 && a.invulnerable == 0 && a.speed == 1) warn.accept("does nothing (set heal, invulnerable or speed)");
        return a;
    }

    public void run(ActionContext ctx) {
        LivingEntity d = ctx.driver();
        if (heal > 0) {
            AttributeInstance max = d.getAttribute(Attribute.MAX_HEALTH);
            double cap = max != null ? max.getValue() : d.getHealth();
            d.setHealth(Math.min(cap, d.getHealth() + cap * heal));
            ctx.caster.refreshName();
        }
        if (invulnerable > 0) ctx.caster.makeInvulnerable((int) Math.round(invulnerable * 20));
        if (speed != 1 && seconds > 0) {
            AttributeInstance ms = d.getAttribute(Attribute.MOVEMENT_SPEED);
            if (ms != null) {
                ms.removeModifier(SPEED_KEY);
                ms.addTransientModifier(new AttributeModifier(SPEED_KEY, speed - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
                ctx.caster.later((int) Math.round(seconds * 20), () -> {
                    AttributeInstance m = d.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (m != null) m.removeModifier(SPEED_KEY);
                });
            }
        }
    }
}
