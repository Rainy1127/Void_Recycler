package dev.voidrecycler.menu;

import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.registry.VRRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Menu of the Void Recycler.
 *
 * <p>Real slots: {@code 3..38} = player inventory. Everything else is <i>virtual</i> (nothing is ever stored in a
 * vanilla container): Input (anything put in is destroyed and credited), Target (ghost), Output (taking from it pays
 * and creates the item), the two upgrade slots (backed by the block entity's counters), the control buttons and the
 * catalog grid. Buttons and catalog cells are slots only to get hover detection and click routing for free; the
 * screen turns clicks on them into {@link dev.voidrecycler.network.MenuActionPayload}s or local state changes.
 *
 * <p>The same class runs on both logical sides. {@link #be} is {@code null} on the client; the client only reads the
 * synced {@link #data} and predicts the results, the server is authoritative and the only side that spends points.
 */
public class VoidRecyclerMenu extends AbstractContainerMenu {
    /** Number of synced ints, see {@link VoidRecyclerBlockEntity#createMenuData(Player)}. */
    public static final int DATA_COUNT = 26;
    private static final int D_POINTS_LO = 0;
    private static final int D_POINTS_HI = 1;
    private static final int D_TARGET = 2;
    private static final int D_PRICE_LO = 3;
    private static final int D_PRICE_HI = 4;
    private static final int D_UPGRADES = 5; // 5 = amplifiers, 6 = throughput
    private static final int D_EFFICIENCY = 7;
    private static final int D_ACCESS = 8;
    private static final int D_MANAGE = 9;
    private static final int D_LINKED = 10;
    private static final int D_STAT_RECYCLED = 11; // lo, hi
    private static final int D_STAT_SPENT = 13; // lo, hi
    private static final int D_STAT_ITEMS_IN = 15; // lo, hi
    private static final int D_STAT_ITEMS_OUT = 17; // lo, hi
    private static final int D_TOP_ID = 19; // 3 entries: registry id + 1, 0 = none
    private static final int D_TOP_COUNT = 22; // 3 entries
    private static final int D_LOCKED = 25;

    // ----- slot indices ---------------------------------------------------------------------------------------
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_TARGET = 1;
    public static final int SLOT_OUTPUT = 2;
    public static final int PLAYER_START = 3;
    public static final int PLAYER_END = 39; // exclusive
    public static final int SLOT_UPGRADE_AMP = 39;
    public static final int SLOT_UPGRADE_THR = 40;
    public static final int SLOT_BTN_BATCH = 41;
    public static final int SLOT_BTN_DEPOSIT = 42;
    public static final int SLOT_BTN_WITHDRAW = 43;
    public static final int SLOT_BTN_CATALOG = 44;
    public static final int SLOT_BTN_ACCESS = 45;
    public static final int SLOT_BTN_LINK = 46;
    public static final int SLOT_CAT_PREV = 47;
    public static final int SLOT_CAT_NEXT = 48;
    public static final int SLOT_CAT_SORT = 49;
    public static final int SLOT_CAT_FILTER = 50;
    public static final int SLOT_CAT_GRID = 51;
    public static final int SLOT_COUNT = SLOT_CAT_GRID + CatalogState.PER_PAGE;

    // ----- layout (relative to the panel's top-left corner), shared with the screen -------------------------------
    public static final int INPUT_X = 24;
    public static final int INPUT_Y = 62;
    public static final int TARGET_X = 76;
    public static final int TARGET_Y = 62;
    public static final int OUTPUT_X = 134;
    public static final int OUTPUT_Y = 62;
    public static final int CONTROLS_Y = 114;
    public static final int INVENTORY_Y = 146;
    public static final int UPGRADE_AMP_X = 134;
    public static final int UPGRADE_THR_X = 152;
    /** Catalog side panel (drawn to the right of the main panel). */
    public static final int CAT_X = 182;
    public static final int CAT_W = 112;
    public static final int CAT_BTN_Y = 22;
    public static final int CAT_GRID_X = CAT_X + 3;
    public static final int CAT_GRID_Y = 44;

    // ----- actions sent by MenuActionPayload ------------------------------------------------------------------
    public static final int ACTION_BATCH = 0;
    public static final int ACTION_CAPSULE_DEPOSIT = 1;
    public static final int ACTION_CAPSULE_WITHDRAW = 2;
    public static final int ACTION_ACCESS = 3;
    public static final int ACTION_SELECT_TARGET = 4;
    public static final int ACTION_LINK = 5;

    /** Amounts the Output Slot offers per click (cycled with the batch button). */
    public static final int[] BATCH_VALUES = {1, 8, 32, 64};

    /** Backing "inventory" for our virtual slots. Never used for storage. */
    private static final Container NO_STORAGE = new SimpleContainer(0);

    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final VoidRecyclerBlockEntity be;
    private final DataSlot batch = DataSlot.standalone();
    private final CatalogState catalog = new CatalogState();

    /** Client constructor (used by the MenuType). */
    public VoidRecyclerMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null);
    }

    /** Server constructor. */
    public VoidRecyclerMenu(int containerId, Inventory playerInventory, VoidRecyclerBlockEntity be) {
        this(containerId, playerInventory, be.createMenuData(playerInventory.player),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()), be);
    }

    private VoidRecyclerMenu(int containerId, Inventory playerInventory, ContainerData data,
                             ContainerLevelAccess access, VoidRecyclerBlockEntity be) {
        super(VRRegistries.VOID_RECYCLER_MENU.get(), containerId);
        checkContainerDataCount(data, DATA_COUNT);
        this.data = data;
        this.access = access;
        this.be = be;

        addDataSlots(data);
        addDataSlot(batch);

        addSlot(new InputSlot(INPUT_X, INPUT_Y));
        addSlot(new TargetSlot(TARGET_X, TARGET_Y));
        addSlot(new OutputSlot(OUTPUT_X, OUTPUT_Y));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, INVENTORY_Y + 58));
        }

        addSlot(new UpgradeSlot(VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER, UPGRADE_AMP_X, CONTROLS_Y));
        addSlot(new UpgradeSlot(VoidRecyclerBlockEntity.UPGRADE_THROUGHPUT, UPGRADE_THR_X, CONTROLS_Y));

        for (int i = 0; i < 6; i++) {
            addSlot(new ControlSlot(8 + 18 * i + 1, CONTROLS_Y, false));
        }
        addSlot(new ControlSlot(CAT_X + 3, CAT_BTN_Y, true));
        addSlot(new ControlSlot(CAT_X + 21, CAT_BTN_Y, true));
        addSlot(new ControlSlot(CAT_X + 75, CAT_BTN_Y, true));
        addSlot(new ControlSlot(CAT_X + 93, CAT_BTN_Y, true));

        for (int i = 0; i < CatalogState.PER_PAGE; i++) {
            addSlot(new CatalogCell(i, CAT_GRID_X + (i % CatalogState.COLS) * 18, CAT_GRID_Y + (i / CatalogState.COLS) * 18));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Synced state (valid on both sides)
    // ---------------------------------------------------------------------------------------------------------

    public long points() {
        return ((long) data.get(D_POINTS_HI) << 32) | (data.get(D_POINTS_LO) & 0xFFFFFFFFL);
    }

    /** @return price of one target item, 0 when there is no target. */
    public long price() {
        return ((long) data.get(D_PRICE_HI) << 32) | (data.get(D_PRICE_LO) & 0xFFFFFFFFL);
    }

    /** @return the selected fabrication target, or null. */
    public Item target() {
        int id = data.get(D_TARGET);
        if (id < 0) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.byId(id);
        return item == null || item == Items.AIR ? null : item;
    }

    /** How many target items the balance can pay for right now. */
    public long affordable() {
        long price = price();
        return target() == null || price <= 0L ? 0L : points() / price;
    }

    /** Recycling efficiency of the block in thousandths (config + amplifiers). */
    public int efficiencyMilli() {
        return data.get(D_EFFICIENCY);
    }

    public int upgradeCount(int kind) {
        return data.get(D_UPGRADES + kind);
    }

    public int accessMode() {
        return data.get(D_ACCESS);
    }

    /** True for the owner (or everybody on an unclaimed block): may change the access mode. */
    public boolean canManage() {
        return data.get(D_MANAGE) != 0;
    }

    /** True while the redstone kill-switch blocks pipes. */
    public boolean locked() {
        return data.get(D_LOCKED) != 0;
    }

    /** True while the block uses its owner's shared pool (Resonance Link). */
    public boolean linked() {
        return data.get(D_LINKED) != 0;
    }

    private long wide(int loIndex) {
        return ((long) data.get(loIndex + 1) << 32) | (data.get(loIndex) & 0xFFFFFFFFL);
    }

    public long statRecycled() {
        return wide(D_STAT_RECYCLED);
    }

    public long statSpent() {
        return wide(D_STAT_SPENT);
    }

    public long statItemsRecycled() {
        return wide(D_STAT_ITEMS_IN);
    }

    public long statItemsFabricated() {
        return wide(D_STAT_ITEMS_OUT);
    }

    /** @return the {@code rank}-th most recycled item (0 = best) or null. */
    public Item topItem(int rank) {
        int id = data.get(D_TOP_ID + rank) - 1;
        if (id < 0) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.byId(id);
        return item == null || item == Items.AIR ? null : item;
    }

    public int topCount(int rank) {
        return data.get(D_TOP_COUNT + rank);
    }

    /** Items per Output Slot click: one of {@link #BATCH_VALUES}. */
    public int batchAmount() {
        return BATCH_VALUES[Math.floorMod(batch.get(), BATCH_VALUES.length)];
    }

    public CatalogState catalog() {
        return catalog;
    }

    /** Slots that are only buttons (their clicks are routed by the screen, never by vanilla). */
    public static boolean isButton(int index) {
        return index >= SLOT_BTN_BATCH && index < SLOT_CAT_GRID;
    }

    public static boolean isCatalogCell(int index) {
        return index >= SLOT_CAT_GRID && index < SLOT_COUNT;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Server-side actions (called from the network payloads)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Click on the ghost Target Slot. With an item on the cursor: select it (if it has a fabrication price).
     * With an empty cursor: clear the selection. The cursor item itself is never consumed.
     */
    public void handleTargetClick(ServerPlayer sender) {
        if (!allowed(sender)) {
            return;
        }
        ItemStack carried = getCarried();
        if (carried.isEmpty()) {
            be.setTarget(null);
        } else if (Economy.isFabricable(carried.getItem())) {
            be.setTarget(carried.getItem());
        } else {
            sender.sendOverlayMessage(Component.translatable("message.void_recycler.not_fabricable"));
        }
    }

    /** Generic button / catalog action, see the {@code ACTION_*} constants. */
    public void handleAction(ServerPlayer sender, int action, int arg) {
        if (!allowed(sender)) {
            return;
        }
        switch (action) {
            case ACTION_BATCH -> batch.set(Math.floorMod(batch.get() + (arg < 0 ? -1 : 1), BATCH_VALUES.length));
            case ACTION_CAPSULE_DEPOSIT -> capsuleDeposit(sender);
            case ACTION_CAPSULE_WITHDRAW -> capsuleWithdraw(sender);
            case ACTION_ACCESS -> {
                if (!be.cycleAccessMode(sender)) {
                    sender.sendOverlayMessage(Component.translatable("message.void_recycler.not_owner"));
                }
            }
            case ACTION_LINK -> {
                if (!be.toggleLink(sender)) {
                    sender.sendOverlayMessage(Component.translatable("message.void_recycler.not_owner"));
                }
            }
            case ACTION_SELECT_TARGET -> {
                Item item = BuiltInRegistries.ITEM.byId(arg);
                if (item != null && item != Items.AIR && Economy.isFabricable(item)) {
                    be.setTarget(item);
                }
            }
            default -> { }
        }
    }

    private boolean allowed(ServerPlayer sender) {
        return be != null && stillValid(sender) && be.canAccess(sender);
    }

    /** Capsule on the cursor -> its points go into the block; the (now empty) capsule stays on the cursor. */
    private void capsuleDeposit(ServerPlayer sender) {
        ItemStack carried = getCarried();
        if (!carried.is(VRRegistries.CAPSULE_ITEM.get()) || carried.getCount() != 1) {
            sender.sendOverlayMessage(Component.translatable("message.void_recycler.hold_capsule"));
            return;
        }
        Long stored = carried.get(VRRegistries.STORED_POINTS.get());
        if (stored == null || stored <= 0L) {
            sender.sendOverlayMessage(Component.translatable("message.void_recycler.capsule_empty"));
            return;
        }
        be.addPoints(stored);
        carried.remove(VRRegistries.STORED_POINTS.get());
        be.playFx(true);
    }

    /** Capsule on the cursor -> the whole balance moves into the capsule. */
    private void capsuleWithdraw(ServerPlayer sender) {
        ItemStack carried = getCarried();
        if (!carried.is(VRRegistries.CAPSULE_ITEM.get()) || carried.getCount() != 1) {
            sender.sendOverlayMessage(Component.translatable("message.void_recycler.hold_capsule"));
            return;
        }
        Long stored = carried.get(VRRegistries.STORED_POINTS.get());
        long current = stored == null ? 0L : Math.max(0L, stored);
        long moved = be.takePoints(Long.MAX_VALUE - current);
        if (moved <= 0L) {
            sender.sendOverlayMessage(Component.translatable("message.void_recycler.no_points"));
            return;
        }
        carried.set(VRRegistries.STORED_POINTS.get(), current + moved);
        be.playFx(false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Buying
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Pays for and creates up to {@code amount} target items. Server: spends the points. Client: pure prediction.
     * @return the created stack or {@link ItemStack#EMPTY}
     */
    private ItemStack buy(int amount) {
        Item item = target();
        long price = price();
        if (item == null || price <= 0L || amount <= 0) {
            return ItemStack.EMPTY;
        }
        int count = (int) Math.min(Math.min((long) amount, affordable()), new ItemStack(item).getMaxStackSize());
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        if (be != null && !be.trySpend(price * count, item, count)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    /** Shift-click on the Output Slot: buy the maximum affordable amount, up to one full stack, into the inventory. */
    private void buyIntoInventory() {
        Item item = target();
        long price = price();
        if (item == null || price <= 0L) {
            return;
        }
        int want = (int) Math.min(affordable(), new ItemStack(item).getMaxStackSize());
        if (want <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(item, want);
        // moves as much as fits and shrinks `stack` accordingly: we only pay for what actually arrived
        moveItemStackTo(stack, PLAYER_START, PLAYER_END, true);
        int bought = want - stack.getCount();
        if (bought > 0 && be != null) {
            be.trySpend(price * bought, item, bought);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Vanilla plumbing
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player quickMover, int index) {
        if (index == SLOT_OUTPUT) {
            buyIntoInventory();
        } else if (index == SLOT_UPGRADE_AMP || index == SLOT_UPGRADE_THR) {
            // upgrade -> inventory
            Slot slot = this.slots.get(index);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                moveItemStackTo(stack, PLAYER_START, PLAYER_END, true);
                slot.set(stack);
            }
        } else if (index >= PLAYER_START && index < PLAYER_END) {
            Slot slot = this.slots.get(index);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int kind = upgradeKindOf(stack);
            if (kind >= 0) {
                // upgrade -> its slot
                int have = upgradeCount(kind);
                int moved = Math.min(VRRegistries.MAX_UPGRADES - have, stack.getCount());
                if (moved > 0) {
                    data.set(D_UPGRADES + kind, have + moved);
                    stack.shrink(moved);
                    slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack);
                    slot.setChanged();
                }
            } else if (Economy.isRecyclingKnown(stack.getItem())) {
                // Shift-click from the inventory recycles the stack - but ONLY items that have a recycling value.
                // Unknown items (tools, armor, ...) can only be voided on purpose, by placing them on the Input Slot.
                if (be != null) {
                    be.recycle(stack.getItem(), stack.getCount());
                }
                slot.set(ItemStack.EMPTY);
                slot.setChanged();
            }
        }
        // ALWAYS return EMPTY: vanilla would otherwise repeat the shift-click until the slot runs dry.
        return ItemStack.EMPTY;
    }

    private static int upgradeKindOf(ItemStack stack) {
        if (stack.is(VRRegistries.AMPLIFIER_ITEM.get())) {
            return VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER;
        }
        if (stack.is(VRRegistries.THROUGHPUT_ITEM.get())) {
            return VoidRecyclerBlockEntity.UPGRADE_THROUGHPUT;
        }
        return -1;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, VRRegistries.VOID_RECYCLER_BLOCK.get());
    }

    /** No click-drag distribution over anything but the player inventory, it would swallow items. */
    @Override
    public boolean canDragTo(Slot slot) {
        return !isVirtual(slot) && super.canDragTo(slot);
    }

    /** Double-click "collect all" must not pull items out of the virtual slots. */
    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot slot) {
        return !isVirtual(slot) && super.canTakeItemForPickAll(carried, slot);
    }

    private static boolean isVirtual(Slot slot) {
        return slot.index < PLAYER_START || slot.index >= PLAYER_END;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Virtual slots
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The manual input: it always looks empty. Whatever a player places in it (click, right-click, number key swap)
     * ends up in {@link #set(ItemStack)}, which destroys the stack and credits its value on the server.
     */
    public final class InputSlot extends Slot {
        InputSlot(int x, int y) {
            super(NO_STORAGE, 0, x, y);
        }

        @Override
        public ItemStack getItem() {
            return ItemStack.EMPTY;
        }

        @Override
        public void set(ItemStack stack) {
            // the client only predicts (its cursor stack shrinks anyway), the server credits the points
            if (be != null && !stack.isEmpty()) {
                be.recycle(stack.getItem(), stack.getCount());
            }
        }

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && Economy.canVoid(stack.getItem());
        }

        @Override
        public boolean mayPickup(Player p) {
            return false;
        }
    }

    /**
     * Ghost slot: displays the selected target on both sides, but can neither be filled nor emptied through
     * normal slot logic (so no vanilla click path can ever duplicate or delete an item). Clicks on it are sent
     * to the server as a dedicated payload by the screen.
     */
    public final class TargetSlot extends Slot {
        private Item cachedItem;
        private ItemStack cachedStack = ItemStack.EMPTY;

        TargetSlot(int x, int y) {
            super(NO_STORAGE, 0, x, y);
        }

        @Override
        public ItemStack getItem() {
            Item item = target();
            if (item != cachedItem) {
                cachedItem = item;
                cachedStack = item == null ? ItemStack.EMPTY : new ItemStack(item);
            }
            // vanilla code may mutate returned stacks, hand out copies
            return cachedStack.isEmpty() ? ItemStack.EMPTY : cachedStack.copy();
        }

        @Override
        public void set(ItemStack stack) {}

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            return false;
        }

        /** Renders the stack as a "fake" item (no held-entity context): suits a ghost preview. */
        @Override
        public boolean isFake() {
            return true;
        }
    }

    /**
     * Virtual shop slot. {@link #getItem()} shows the batch (1 / 8 / 32 / 64, limited by stack size and balance) of
     * the target; taking it out calls {@link #remove(int)} which pays and creates the items. Cursor, right-click and
     * click-to-accumulate behave like any vanilla output slot.
     */
    public final class OutputSlot extends Slot {
        OutputSlot(int x, int y) {
            super(NO_STORAGE, 0, x, y);
        }

        @Override
        public ItemStack getItem() {
            Item item = target();
            if (item == null) {
                return ItemStack.EMPTY;
            }
            long can = affordable();
            if (can <= 0L) {
                return ItemStack.EMPTY;
            }
            int count = (int) Math.min(Math.min((long) batchAmount(), can), new ItemStack(item).getMaxStackSize());
            return new ItemStack(item, Math.max(1, count));
        }

        @Override
        public void set(ItemStack stack) {}

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            return buy(amount);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            return affordable() > 0L;
        }

        @Override
        public void onQuickCraft(ItemStack picked, ItemStack result) {}
    }

    /**
     * Upgrade slot backed by a counter of the block entity (read/written through the container data, so the
     * client predicts clicks and the server stays authoritative). Looks and behaves like a normal slot that holds up
     * to {@link VRRegistries#MAX_UPGRADES} items of one kind.
     */
    public final class UpgradeSlot extends Slot {
        private final int kind;

        UpgradeSlot(int kind, int x, int y) {
            super(NO_STORAGE, 0, x, y);
            this.kind = kind;
        }

        private Item upgradeItem() {
            return kind == VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER
                    ? VRRegistries.AMPLIFIER_ITEM.get() : VRRegistries.THROUGHPUT_ITEM.get();
        }

        @Override
        public ItemStack getItem() {
            int count = data.get(D_UPGRADES + kind);
            return count <= 0 ? ItemStack.EMPTY : new ItemStack(upgradeItem(), count);
        }

        @Override
        public void set(ItemStack stack) {
            data.set(D_UPGRADES + kind, stack.isEmpty() ? 0 : stack.getCount());
        }

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            int have = data.get(D_UPGRADES + kind);
            int taken = Math.min(amount, have);
            if (taken <= 0) {
                return ItemStack.EMPTY;
            }
            data.set(D_UPGRADES + kind, have - taken);
            return new ItemStack(upgradeItem(), taken);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(upgradeItem());
        }

        /** Declared without @Override: harmless if the vanilla method is named differently. */
        public int getMaxStackSize() {
            return VRRegistries.MAX_UPGRADES;
        }
    }

    /** A button: empty, can't hold anything. Hover detection and click routing are done by the screen. */
    public final class ControlSlot extends Slot {
        private final boolean catalogOnly;

        ControlSlot(int x, int y, boolean catalogOnly) {
            super(NO_STORAGE, 0, x, y);
            this.catalogOnly = catalogOnly;
        }

        @Override
        public ItemStack getItem() {
            return ItemStack.EMPTY;
        }

        @Override
        public void set(ItemStack stack) {}

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            return false;
        }

        /** Catalog buttons exist only while the catalog panel is open. */
        @Override
        public boolean isActive() {
            return !catalogOnly || catalog.isOpen();
        }

        /** No white hover overlay, the screen draws its own (no @Override: tolerated if absent). */
        public boolean isHighlightable() {
            return false;
        }
    }

    /** One cell of the catalog grid: a ghost item (client only), click = select as fabrication target. */
    public final class CatalogCell extends Slot {
        private final int gridIndex;
        private Item cachedItem;
        private ItemStack cachedStack = ItemStack.EMPTY;

        CatalogCell(int gridIndex, int x, int y) {
            super(NO_STORAGE, 0, x, y);
            this.gridIndex = gridIndex;
        }

        public int registryId() {
            Item item = catalog.itemAt(gridIndex);
            return item == null ? -1 : BuiltInRegistries.ITEM.getId(item);
        }

        @Override
        public ItemStack getItem() {
            Item item = catalog.itemAt(gridIndex);
            if (item == null) {
                return ItemStack.EMPTY;
            }
            if (item != cachedItem) {
                cachedItem = item;
                cachedStack = new ItemStack(item);
            }
            return cachedStack.copy();
        }

        @Override
        public void set(ItemStack stack) {}

        @Override
        public void setChanged() {}

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            return false;
        }

        @Override
        public boolean isActive() {
            return catalog.isOpen();
        }

        @Override
        public boolean isFake() {
            return true;
        }

        public boolean isHighlightable() {
            return false;
        }
    }
}
