package dev.voidrecycler.transfer;

import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.economy.Economy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The block's {@link ResourceHandler} exposed to pipes and hoppers (capability {@code Capabilities.Item.BLOCK}).
 *
 * <p>It is a single <i>virtual</i> index with two faces:
 * <ul>
 *   <li><b>insert</b>: any item is accepted and instantly voided; its recycling value is converted into points.</li>
 *   <li><b>extract</b>: the item selected in the Target Slot is fabricated on demand, paid with points.
 *       Nothing is ever stored, nothing ticks: the item only comes into existence inside the extract call.</li>
 * </ul>
 *
 * Both directions are disabled while the block is powered by redstone (the kill-switch).
 * State changes are transactional through a {@link SnapshotJournal}, so a pipe that fails to place the
 * extracted items elsewhere rolls the points back automatically.
 */
public final class VoidRecyclerHandler implements ResourceHandler<ItemResource> {
    private final VoidRecyclerBlockEntity be;
    private final Journal journal = new Journal();

    private static final int FX_NONE = 0;
    private static final int FX_RECYCLE = 1;
    private static final int FX_FABRICATE = 2;
    /** Effect to play once the outermost transaction really commits (a rolled-back transfer makes no noise). */
    private int pendingFx = FX_NONE;

    // statistics of the running transaction; handed to the block only when the outermost transaction commits
    private final Map<Item, Integer> pendingRecycled = new HashMap<>();
    private long pendingGainMilli;
    private Item pendingFabricated;
    private int pendingFabricatedCount;
    private long pendingSpent;

    public VoidRecyclerHandler(VoidRecyclerBlockEntity be) {
        this.be = be;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public int size() {
        return 1;
    }

    @Override
    public ItemResource getResource(int index) {
        Objects.checkIndex(index, 1);
        Item target = be.getTarget();
        if (target == null || be.isLocked() || affordable(target) <= 0L) {
            return ItemResource.EMPTY;
        }
        return ItemResource.of(target);
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, 1);
        Item target = be.getTarget();
        if (target == null || be.isLocked()) {
            return 0L;
        }
        return Math.min(affordable(target), be.maxExtractPerCall());
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        Objects.checkIndex(index, 1);
        if (be.isLocked()) {
            return 0L;
        }
        // An empty resource asks for the general capacity: we accept (practically) everything.
        return resource.isEmpty() || accepts(resource) ? Integer.MAX_VALUE : 0L;
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        Objects.checkIndex(index, 1);
        return !be.isLocked() && accepts(resource);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Insert = recycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Objects.checkIndex(index, 1);
        if (amount == 0 || be.isLocked() || !accepts(resource)) {
            return 0;
        }
        long unitValue = Economy.rawRecyclingValue(resource.getItem());
        if (unitValue > 0L && be.efficiencyMilli() > 0) {
            journal.updateSnapshots(transaction);
            pendingGainMilli = Economy.satAdd(pendingGainMilli, be.applyGain(unitValue, amount));
            pendingRecycled.merge(resource.getItem(), amount, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
            pendingFx = FX_RECYCLE;
        }
        // Items worth nothing are still swallowed ("void"), no state changes -> nothing to roll back.
        return amount;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Extract = fabricate
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Objects.checkIndex(index, 1);
        if (amount == 0 || be.isLocked()) {
            return 0;
        }
        Item target = be.getTarget();
        if (target == null || !resource.is(target) || !resource.isComponentsPatchEmpty()) {
            return 0;
        }
        long price = Economy.fabricationPrice(target);
        if (price <= 0L) {
            return 0;
        }
        int count = (int) Math.min(Math.min((long) amount, be.getPoints() / price), be.maxExtractPerCall());
        if (count <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        pendingFx = FX_FABRICATE;
        be.applySpend(price * count); // count <= points / price, so this can never overflow or go negative
        pendingFabricated = target;
        pendingFabricatedCount += count;
        pendingSpent = Economy.satAdd(pendingSpent, price * count);
        return count;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** How many items of the target the current points can pay for. */
    private long affordable(Item target) {
        long price = Economy.fabricationPrice(target);
        return price <= 0L ? 0L : be.getPoints() / price;
    }

    /** Is this item accepted for voiding? Unknown items depend on the {@code void_unknown_items} option. */
    private static boolean accepts(ItemResource resource) {
        if (resource.isEmpty()) {
            return false;
        }
        return Economy.canVoid(resource.getItem());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Transaction support
    // ---------------------------------------------------------------------------------------------------------

    private void clearPendingStats() {
        pendingRecycled.clear();
        pendingGainMilli = 0L;
        pendingFabricated = null;
        pendingFabricatedCount = 0;
        pendingSpent = 0L;
    }

    private record State(long points, int fractionMilli) {}

    private final class Journal extends SnapshotJournal<State> {
        @Override
        protected State createSnapshot() {
            return new State(be.getPoints(), be.getFractionMilli());
        }

        @Override
        protected void revertToSnapshot(State snapshot) {
            be.restoreState(snapshot.points(), snapshot.fractionMilli());
            pendingFx = FX_NONE;
            clearPendingStats();
        }

        @Override
        protected void onRootCommit(State originalState) {
            // Called once, outside of any transaction, after the outermost transaction committed successfully.
            if (!pendingRecycled.isEmpty()) {
                be.recordRecycled(pendingRecycled, pendingGainMilli);
            }
            if (pendingFabricated != null) {
                be.recordFabricated(pendingFabricated, pendingFabricatedCount, pendingSpent);
            }
            clearPendingStats();
            be.commitChanged();
            if (pendingFx != FX_NONE) {
                be.playFx(pendingFx == FX_FABRICATE);
                pendingFx = FX_NONE;
            }
        }
    }
}
