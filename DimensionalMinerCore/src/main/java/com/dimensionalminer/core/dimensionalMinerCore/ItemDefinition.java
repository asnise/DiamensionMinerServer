package com.dimensionalminer.core.dimensionalMinerCore;

import java.util.Collections;
import java.util.Map;

public class ItemDefinition {

    private final String id;
    private final String kind;
    private final String name;
    private final String display;
    private final String icon;
    private final String category;
    private final String rarity;
    private final String binding;
    private final int stack;
    private final Integer durability;
    private final String description;
    private final Map<String, Double> baseStats;

    public ItemDefinition(
            String id,
            String kind,
            String name,
            String display,
            String icon,
            String category,
            String rarity,
            String binding,
            int stack,
            Integer durability,
            String description,
            Map<String, Double> baseStats
    ) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.display = display;
        this.icon = icon;
        this.category = category != null ? category : "MATERIAL";
        this.rarity = rarity != null ? rarity : "COMMON";
        this.binding = binding != null ? binding : "UNBOUND";
        this.stack = stack > 0 ? stack : 64;
        this.durability = durability;
        this.description = description;
        this.baseStats = baseStats != null ? baseStats : Collections.emptyMap();
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getDisplay() {
        return display != null && !display.isEmpty() ? display : name;
    }

    public String getIcon() {
        return icon;
    }

    public String getCategory() {
        return category;
    }

    public String getRarity() {
        return rarity;
    }

    public String getBinding() {
        return binding;
    }

    public int getStack() {
        return stack;
    }

    public Integer getDurability() {
        return durability;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Double> getBaseStats() {
        return baseStats;
    }
}
