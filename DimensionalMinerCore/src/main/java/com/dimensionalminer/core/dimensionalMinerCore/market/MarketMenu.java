package com.dimensionalminer.core.dimensionalMinerCore.market;

import com.dimensionalminer.core.dimensionalMinerCore.craft.GuiMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** /ah browse window: 45 listings per page, bottom row = paging, my listings / all, refresh. */
final class MarketMenu extends GuiMenu {

    static final int PER_PAGE = 45;
    private static final int PREV = 45, MINE = 48, REFRESH = 49, NEXT = 53;

    private final MarketManager market;
    private final boolean mine;
    private final int page;
    private final List<MarketService.Listing> rows;
    private final boolean hasNext;

    MarketMenu(MarketManager market, Player player, boolean mine, int page, List<MarketService.Listing> rows) {
        super(null, player, false);
        this.market = market;
        this.mine = mine;
        this.page = page;
        this.hasNext = rows.size() > PER_PAGE;
        this.rows = rows.subList(0, Math.min(PER_PAGE, rows.size()));
    }

    void open() {
        inventory = Bukkit.createInventory(this, ROWS * 9, Component.text(mine ? "Market · My listings" : "Market · Page " + (page + 1)));
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            MarketService.Listing l = rows.get(i);
            ItemStack s = MarketManager.safeItem(l).clone();
            ItemMeta m = s.getItemMeta();
            List<Component> lore = m.hasLore() && m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(text("Price: " + l.price() + " COIN", NamedTextColor.GOLD));
            lore.add(text("Seller: " + l.sellerName(), NamedTextColor.GRAY));
            long left = Math.max(0, l.expiresAtMillis() - now) / 60_000;
            lore.add(text("Expires in " + left / 60 + "h " + left % 60 + "m", NamedTextColor.DARK_GRAY));
            lore.add(text(mine || l.sellerName().equals(player.getName()) ? "Click to cancel" : "Click to buy", NamedTextColor.YELLOW));
            m.lore(lore);
            s.setItemMeta(m);
            inventory.setItem(i, s);
        }
        if (rows.isEmpty()) inventory.setItem(22, named(Material.PAPER, mine ? "You have no active listings" : "No listings yet", NamedTextColor.GRAY));
        if (page > 0) inventory.setItem(PREV, named(Material.ARROW, "Previous page", NamedTextColor.YELLOW));
        if (hasNext) inventory.setItem(NEXT, named(Material.ARROW, "Next page", NamedTextColor.YELLOW));
        inventory.setItem(MINE, named(mine ? Material.CHEST : Material.WRITABLE_BOOK, mine ? "Show all listings" : "My listings", NamedTextColor.AQUA));
        inventory.setItem(REFRESH, named(Material.SUNFLOWER, "Refresh", NamedTextColor.GREEN));
        fillEmpty();
        player.openInventory(inventory);
    }

    private static ItemStack named(Material mat, String name, NamedTextColor c) {
        ItemStack s = new ItemStack(mat);
        ItemMeta m = s.getItemMeta();
        m.displayName(text(name, c));
        s.setItemMeta(m);
        return s;
    }

    @Override
    protected void clickTop(int slot, boolean shift) {
        if (slot < rows.size()) {
            MarketService.Listing l = rows.get(slot);
            player.closeInventory();
            market.confirmBuy(player, l, mine, page);
            return;
        }
        switch (slot) {
            case PREV -> { if (page > 0) market.openBrowse(player, mine, page - 1); }
            case NEXT -> { if (hasNext) market.openBrowse(player, mine, page + 1); }
            case MINE -> market.openBrowse(player, !mine, 0);
            case REFRESH -> market.openBrowse(player, mine, page);
            default -> { }
        }
    }
}
