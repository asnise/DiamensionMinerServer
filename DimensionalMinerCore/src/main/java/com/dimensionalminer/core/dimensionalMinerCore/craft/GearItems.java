package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ContentRegistryManager;
import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gear state lives on the ItemStack (PDC) and travels with it through InventorySync:
 * gear_id (UUID), gear_adv, gear_refine, gear_pity, gear_quality. History of every change is in
 * operations + item_transactions (metadata.gear_id). Lore is rebuilt from GearMath on each change.
 */
public final class GearItems {

    /**
     * engraves = fragment definition ids in slot order (at most {@link #MAX_ENGRAVES}).
     * durability = current durability, -1 = never used (full).
     */
    public record GearData(UUID id, int advancement, int refine, int pity, String quality, List<String> engraves, int durability) {
        public GearData {
            engraves = List.copyOf(engraves);
        }
        public GearData(UUID id, int advancement, int refine, int pity, String quality) {
            this(id, advancement, refine, pity, quality, List.of(), -1);
        }
        public GearData(UUID id, int advancement, int refine, int pity, String quality, List<String> engraves) {
            this(id, advancement, refine, pity, quality, engraves, -1);
        }
        public GearData withAdvancement(int v) { return new GearData(id, v, refine, pity, quality, engraves, durability); }
        public GearData withRefine(int v, int newPity) { return new GearData(id, advancement, v, newPity, quality, engraves, durability); }
        public GearData withEngraves(List<String> e) { return new GearData(id, advancement, refine, pity, quality, e, durability); }
        public GearData withDurability(int d) { return new GearData(id, advancement, refine, pity, quality, engraves, d); }
    }

    public static final int MAX_ENGRAVES = 3;

    private static final Map<String, String> STAT_LABELS = new LinkedHashMap<>();
    static {
        STAT_LABELS.put("attack_damage", "Attack Damage");
        STAT_LABELS.put("attack_speed", "Attack Speed");
        STAT_LABELS.put("defense", "Defense");
        STAT_LABELS.put("mining_speed", "Mining Speed");
        STAT_LABELS.put("crit_chance", "Crit Chance");
        STAT_LABELS.put("luck", "Mining Luck");
    }

    private final ContentRegistryManager registry;
    private final ForgeRules rules;
    private final NamespacedKey kId, kAdv, kRefine, kPity, kQuality, kEngraves, kDur;

    public GearItems(Plugin plugin, ContentRegistryManager registry, ForgeRules rules) {
        this.registry = registry;
        this.rules = rules;
        this.kId = new NamespacedKey(plugin, "gear_id");
        this.kAdv = new NamespacedKey(plugin, "gear_adv");
        this.kRefine = new NamespacedKey(plugin, "gear_refine");
        this.kPity = new NamespacedKey(plugin, "gear_pity");
        this.kQuality = new NamespacedKey(plugin, "gear_quality");
        this.kEngraves = new NamespacedKey(plugin, "gear_engraves");
        this.kDur = new NamespacedKey(plugin, "gear_dur");
    }

    public ForgeRules rules() { return rules; }

    /** Content definition id of a stack (PDC definition_id), or null for vanilla items. */
    public String definitionId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(registry.getDefIdKey(), PersistentDataType.STRING);
    }

    /** Gear definition of the stack, or null if it is not gear. */
    public ItemDefinition gearDefinition(ItemStack stack) {
        ItemDefinition def = registry.getItem(definitionId(stack));
        return def != null && def.isGear() ? def : null;
    }

    /** Gear data stored on the stack; null if it has no gear_id yet. */
    public GearData read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return null;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(kId, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return new GearData(UUID.fromString(id),
                    pdc.getOrDefault(kAdv, PersistentDataType.INTEGER, 0),
                    pdc.getOrDefault(kRefine, PersistentDataType.INTEGER, 0),
                    pdc.getOrDefault(kPity, PersistentDataType.INTEGER, 0),
                    pdc.getOrDefault(kQuality, PersistentDataType.STRING, "NORMAL"),
                    splitEngraves(pdc.get(kEngraves, PersistentDataType.STRING)),
                    pdc.has(kDur, PersistentDataType.INTEGER) ? pdc.get(kDur, PersistentDataType.INTEGER) : legacyDurability(stack));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Durability of a piece that has no gear_dur yet (made before durability existed, or from
     * /dm give): taken from the vanilla wear on the stack so the bar and the lore agree; -1 = full.
     */
    private int legacyDurability(ItemStack stack) {
        ItemDefinition def = gearDefinition(stack);
        if (def == null || !(stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) || !dmg.hasDamage()) return -1;
        return Math.max(0, maxDurability(def) - dmg.getDamage());
    }

    /**
     * Rewrites gear that has no gear_dur yet (vanilla wear from before durability existed), so the
     * bar, the lore and the stored value match. Returns how many stacks were fixed.
     */
    public int normalize(Player p) {
        int fixed = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            ItemDefinition def = gearDefinition(s);
            if (def == null || s.getItemMeta().getPersistentDataContainer().has(kDur, PersistentDataType.INTEGER)) continue;
            GearData d = read(s);
            if (d == null) d = new GearData(UUID.randomUUID(), 0, 0, 0, "NORMAL", List.of(), legacyDurability(s));
            write(s, def, d);
            inv.setItem(i, s);
            fixed++;
        }
        return fixed;
    }

    /**
     * Gear data of a gear stack; a gear item that never got an id (e.g. from /dm give or the mailbox)
     * is given a fresh one, written onto the stack. Returns null for non-gear.
     */
    public GearData readOrAssign(ItemStack stack) {
        ItemDefinition def = gearDefinition(stack);
        if (def == null) return null;
        GearData data = read(stack);
        if (data != null) return data;
        data = new GearData(UUID.randomUUID(), 0, 0, 0, "NORMAL", List.of(), legacyDurability(stack));
        write(stack, def, data);
        return data;
    }

    /** A brand-new gear item of the definition. */
    public ItemStack create(ItemDefinition def, GearData data) {
        ItemStack stack = registry.createItemStack(def.getId(), 1);
        write(stack, def, data);
        return stack;
    }

    /** Writes the data into the stack's PDC and rebuilds its name and lore. */
    public void write(ItemStack stack, ItemDefinition def, GearData data) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kId, PersistentDataType.STRING, data.id().toString());
        pdc.set(kAdv, PersistentDataType.INTEGER, data.advancement());
        pdc.set(kRefine, PersistentDataType.INTEGER, data.refine());
        pdc.set(kPity, PersistentDataType.INTEGER, data.pity());
        pdc.set(kQuality, PersistentDataType.STRING, data.quality());
        if (data.engraves().isEmpty()) pdc.remove(kEngraves);
        else pdc.set(kEngraves, PersistentDataType.STRING, String.join(",", data.engraves()));
        int max = maxDurability(def), cur = durability(def, data);
        pdc.set(kDur, PersistentDataType.INTEGER, cur);
        // Vanilla bar shows our durability; vanilla wear itself is cancelled (DurabilityListener).
        // Only on bases that have a vanilla bar (tools/armor); a stackable base can't take max_damage.
        if (stack.getType().getMaxDurability() > 0 && meta instanceof org.bukkit.inventory.meta.Damageable dmg) {
            dmg.setMaxDamage(max);
            dmg.setDamage(Math.min(max - 1, max - cur));
        }

        TextColor rarity = rarityColor(def.getRarity());
        Component name = text(def.getDisplay(), rarity).decorate(TextDecoration.BOLD);
        if (data.refine() > 0) name = name.append(text(" +" + data.refine(), NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        meta.displayName(name);
        meta.lore(lore(def, data));
        stack.setItemMeta(meta);
    }

    /** Final value of every base stat of the definition for this gear state (stat → value). */
    public int maxDurability(ItemDefinition def) {
        return def.getDurability() != null && def.getDurability() > 0 ? def.getDurability() : rules.defaultDurability();
    }

    /** Current durability (an unused piece is full). */
    public int durability(ItemDefinition def, GearData data) {
        int max = maxDurability(def);
        return data.durability() < 0 ? max : Math.min(max, data.durability());
    }

    public boolean broken(ItemDefinition def, GearData data) {
        return durability(def, data) <= 0;
    }

    public Map<String, Double> stats(ItemDefinition def, GearData data) {
        // GDD: durability 0 turns the piece's bonuses off (Base stats only) until it is repaired.
        if (broken(def, data)) data = new GearData(data.id(), 0, 0, 0, "NORMAL", List.of(), 0);
        Map<String, Double> out = new LinkedHashMap<>();
        int quality = rules.qualityBp(data.quality());
        int refineBonus = rules.refineBonusBp(data.refine());
        // Engrave modifiers: flats add after Core, percents of all fragments are summed (not chained).
        Map<String, Long> flat = new LinkedHashMap<>();
        Map<String, Integer> pct = new LinkedHashMap<>();
        for (ItemDefinition.Engrave e : engraveSpecs(data)) {
            e.flat().forEach((k, v) -> flat.merge(k, GearMath.milli(v), Long::sum));
            e.percentBp().forEach((k, v) -> pct.merge(k, v, Integer::sum));
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(def.getBaseStats().keySet());
        keys.addAll(flat.keySet());
        for (String k : keys) {
            double base = def.getBaseStats().getOrDefault(k, 0.0);
            double growth = def.getGrowthStats().getOrDefault(k, base * rules.defaultGrowthRatio());
            long core = GearMath.coreMilli(GearMath.milli(base), GearMath.milli(growth), data.advancement(), quality, refineBonus);
            out.put(k, GearMath.fromMilli(GearMath.finalMilli(core, flat.getOrDefault(k, 0L), pct.getOrDefault(k, 0), 0, Long.MAX_VALUE)));
        }
        return out;
    }

    /** Fragment definitions engraved on the piece (unknown ids, e.g. removed content, are skipped). */
    public List<ItemDefinition.Engrave> engraveSpecs(GearData data) {
        List<ItemDefinition.Engrave> out = new ArrayList<>();
        for (String id : data.engraves()) {
            ItemDefinition f = registry.getItem(id);
            if (f != null && f.isFragment()) out.add(f.getEngrave());
        }
        return out;
    }

    /** "+5 Attack Damage, +10% Attack Damage" */
    public static String describe(ItemDefinition.Engrave e) {
        List<String> parts = new ArrayList<>();
        e.flat().forEach((k, v) -> parts.add((v >= 0 ? "+" : "") + fmt(v) + " " + statLabel(k)));
        e.percentBp().forEach((k, v) -> parts.add((v >= 0 ? "+" : "") + fmt(v / 100.0) + "% " + statLabel(k)));
        return parts.isEmpty() ? "no stats" : String.join(", ", parts);
    }

    public static String statLabel(String key) {
        return STAT_LABELS.getOrDefault(key, key.replace('_', ' '));
    }

    private static List<String> splitEngraves(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) if (!s.isBlank() && out.size() < MAX_ENGRAVES) out.add(s.trim());
        return out;
    }

    public List<Component> lore(ItemDefinition def, GearData data) {
        List<Component> lore = new ArrayList<>();
        TextColor rarity = rarityColor(def.getRarity());
        lore.add(text(def.getRarity().toUpperCase(Locale.ROOT) + " ", rarity)
                .append(text(def.getCategory().toUpperCase(Locale.ROOT), NamedTextColor.GRAY)));
        lore.add(text("Advancement ", NamedTextColor.GRAY).append(text(data.advancement() + "/" + GearMath.MAX_ADVANCEMENT, NamedTextColor.YELLOW))
                .append(text("   Refine ", NamedTextColor.GRAY)).append(text("+" + data.refine() + "/+" + GearMath.MAX_REFINE, NamedTextColor.GOLD)));
        lore.add(text("Quality ", NamedTextColor.GRAY).append(text(qualityName(data.quality()), qualityColor(data.quality()))));
        int dur = durability(def, data), maxDur = maxDurability(def);
        lore.add(text("Durability ", NamedTextColor.GRAY).append(text(dur + "/" + maxDur,
                dur <= 0 ? NamedTextColor.RED : dur * 4 <= maxDur ? NamedTextColor.GOLD : NamedTextColor.GREEN)));
        if (dur <= 0) lore.add(text("BROKEN: only Base stats until repaired", NamedTextColor.RED));
        Map<String, Double> stats = stats(def, data);
        if (stats.values().stream().anyMatch(v -> v != 0)) {
            lore.add(Component.empty());
            for (Map.Entry<String, Double> e : stats.entrySet()) {
                double base = def.getBaseStats().getOrDefault(e.getKey(), 0.0);
                if (e.getValue() == 0 && base == 0) continue;
                double gain = e.getValue() - base;
                Component line = text(" " + statLabel(e.getKey()) + " ", NamedTextColor.GRAY)
                        .append(text(fmt(e.getValue()) + (e.getKey().equals("crit_chance") ? "%" : ""), NamedTextColor.WHITE));
                if (Math.abs(gain) >= 0.005) line = line.append(text(" (+" + fmt(gain) + ")", NamedTextColor.GREEN));
                lore.add(line);
            }
        }
        lore.add(Component.empty());
        lore.add(text("Engraves " + data.engraves().size() + "/" + MAX_ENGRAVES, NamedTextColor.LIGHT_PURPLE));
        for (int i = 0; i < MAX_ENGRAVES; i++) {
            if (i >= data.engraves().size()) { lore.add(text(" ◇ empty", NamedTextColor.DARK_GRAY)); continue; }
            ItemDefinition f = registry.getItem(data.engraves().get(i));
            lore.add(f == null || !f.isFragment()
                    ? text(" ◆ " + data.engraves().get(i) + " (unknown)", NamedTextColor.DARK_GRAY)
                    : text(" ◆ " + f.getDisplay() + " ", NamedTextColor.LIGHT_PURPLE).append(text(describe(f.getEngrave()), NamedTextColor.GRAY)));
        }
        if (def.getDescription() != null && !def.getDescription().isBlank()) {
            lore.add(Component.empty());
            lore.add(text(def.getDescription(), NamedTextColor.DARK_GRAY).decorate(TextDecoration.ITALIC));
        }
        if (!"UNBOUND".equalsIgnoreCase(def.getBinding())) lore.add(text("[Bound: " + def.getBinding() + "]", NamedTextColor.DARK_AQUA));
        lore.add(text("Gear #" + data.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY));
        return lore;
    }

    /**
     * Wears the gear in an equipment slot by {@code amount}. Returns true when this broke it
     * (durability reached 0); a broken piece is not worn further.
     */
    public boolean wear(Player p, org.bukkit.inventory.EquipmentSlot slot, int amount) {
        if (amount <= 0) return false;
        ItemStack s = p.getInventory().getItem(slot);
        ItemDefinition def = gearDefinition(s);
        if (def == null) return false;
        GearData d = readOrAssign(s);
        int cur = durability(def, d);
        if (cur <= 0) return false;
        int next = Math.max(0, cur - amount);
        write(s, def, d.withDurability(next));
        p.getInventory().setItem(slot, s);
        if (next == 0) {
            p.sendMessage(text("[Gear] " + def.getDisplay() + " broke! Only Base stats until it is repaired (/forge REPAIR or a Blacksmith).", NamedTextColor.RED));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
        }
        return next == 0;
    }

    /** Wears by a percentage of the max durability (rounded up). */
    public void wearPercent(Player p, org.bukkit.inventory.EquipmentSlot slot, double percent) {
        ItemDefinition def = gearDefinition(p.getInventory().getItem(slot));
        if (def != null) wear(p, slot, (int) Math.ceil(maxDurability(def) * percent / 100.0));
    }

    /** Inventory slot (0–40) of the gear with this id, or -1. */
    public int findSlot(Player player, UUID gearId) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            GearData d = read(inv.getItem(i));
            if (d != null && d.id().equals(gearId)) return i;
        }
        return -1;
    }

    public static String qualityName(String q) {
        return switch (q == null ? "NORMAL" : q.toUpperCase(Locale.ROOT)) {
            case "FINE" -> "Fine";
            case "MASTERWORK" -> "Masterwork";
            default -> "Normal";
        };
    }

    private static TextColor qualityColor(String q) {
        return switch (q == null ? "NORMAL" : q.toUpperCase(Locale.ROOT)) {
            case "FINE" -> NamedTextColor.AQUA;
            case "MASTERWORK" -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    public static TextColor rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> NamedTextColor.GREEN;
            case "RARE" -> NamedTextColor.AQUA;
            case "EPIC" -> NamedTextColor.DARK_PURPLE;
            case "LEGENDARY" -> NamedTextColor.GOLD;
            case "MYTHIC" -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static Component text(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
