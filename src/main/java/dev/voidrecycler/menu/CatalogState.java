package dev.voidrecycler.menu;

import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.economy.FabricationPrice;
import dev.voidrecycler.economy.VRDataMaps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-side view model of the catalog panel (all fabricable items, paged, sortable, filterable).
 * Lives in the menu so the catalog slots can read it, but the server never opens it: on the server
 * {@link #isOpen()} stays false and the catalog slots are always empty.
 */
public final class CatalogState {
    public static final int COLS = 6;
    public static final int ROWS = 7;
    public static final int PER_PAGE = COLS * ROWS;

    public enum Sort {
        NAME, PRICE_ASC, PRICE_DESC;

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private boolean open;
    private int page;
    private Sort sort = Sort.NAME;
    private boolean affordableOnly;
    private long rebuiltForPoints = Long.MIN_VALUE;
    private List<Item> items = List.of();

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open, long points) {
        this.open = open;
        if (open) {
            rebuild(points);
        }
    }

    public Sort sort() {
        return sort;
    }

    public boolean affordableOnly() {
        return affordableOnly;
    }

    public void cycleSort(long points) {
        sort = sort.next();
        page = 0;
        rebuild(points);
    }

    public void toggleAffordableOnly(long points) {
        affordableOnly = !affordableOnly;
        page = 0;
        rebuild(points);
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
    }

    public int size() {
        return items.size();
    }

    public void changePage(int delta) {
        page = Math.floorMod(page + delta, pageCount());
    }

    /** @return the item shown at grid position {@code gridIndex} (0 - 41) on the current page, or null. */
    public Item itemAt(int gridIndex) {
        if (!open) {
            return null;
        }
        int i = page * PER_PAGE + gridIndex;
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** Only the "affordable" filter depends on the balance; re-sorts cheaply when the balance changed. */
    public void onPointsChanged(long points) {
        if (open && affordableOnly && points != rebuiltForPoints) {
            rebuild(points);
        }
    }

    public void rebuild(long points) {
        rebuiltForPoints = points;
        Map<ResourceKey<Item>, FabricationPrice> prices = BuiltInRegistries.ITEM.getDataMap(VRDataMaps.FABRICATION_PRICES);
        List<Item> list = new ArrayList<>(prices.size());
        Map<Item, String> names = new HashMap<>();
        for (Map.Entry<ResourceKey<Item>, FabricationPrice> entry : prices.entrySet()) {
            Identifier id = entry.getKey().identifier();
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) {
                continue;
            }
            // Economy applies the never_fabricate tag, so blocked items return 0 here
            long cost = Economy.fabricationPrice(item);
            if (cost <= 0L || (affordableOnly && cost > points)) {
                continue;
            }
            list.add(item);
            if (sort == Sort.NAME) {
                names.put(item, new ItemStack(item).getHoverName().getString());
            }
        }
        switch (sort) {
            case NAME -> list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(names.get(a), names.get(b)));
            case PRICE_ASC -> list.sort((a, b) -> Long.compare(Economy.fabricationPrice(a), Economy.fabricationPrice(b)));
            case PRICE_DESC -> list.sort((a, b) -> Long.compare(Economy.fabricationPrice(b), Economy.fabricationPrice(a)));
        }
        this.items = list;
        this.page = Math.min(page, pageCount() - 1);
    }
}
