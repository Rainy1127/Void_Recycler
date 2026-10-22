package dev.voidrecycler.block;

import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.economy.LinkedPools;
import dev.voidrecycler.economy.PointMath;
import dev.voidrecycler.menu.VoidRecyclerMenu;
import dev.voidrecycler.registry.VRRegistries;
import dev.voidrecycler.transfer.VoidRecyclerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The whole state of a Void Recycler: a point balance, a sub-point accumulator and the fabrication target.
 *
 * <p><b>Tick-less by design</b>: this class has no ticker. Everything is event driven:
 * <ul>
 *   <li>points change only when something is inserted/extracted/bought,</li>
 *   <li>the redstone kill-switch is cached per game tick and recomputed lazily on demand,</li>
 *   <li>the comparator is notified only when its output value really changed.</li>
 * </ul>
 */
public class VoidRecyclerBlockEntity extends BlockEntity implements MenuProvider {
    private static final String KEY_POINTS = "resonance_points";
    private static final String KEY_FRACTION = "fraction_milli";
    private static final String KEY_TARGET = "fabrication_target";
    private static final String KEY_AMPLIFIERS = "amplifiers";
    private static final String KEY_THROUGHPUT = "throughput_modules";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_OWNER_NAME = "owner_name";
    private static final String KEY_ACCESS = "access_mode";
    private static final String KEY_LINKED = "linked";
    private static final String KEY_STAT_RECYCLED = "stat_recycled_milli";
    private static final String KEY_STAT_SPENT = "stat_spent";
    private static final String KEY_STAT_ITEMS_IN = "stat_items_recycled";
    private static final String KEY_STAT_ITEMS_OUT = "stat_items_fabricated";
    private static final String KEY_STAT_TOP = "stat_top";

    /** Distinct item types tracked for the "top recycled" statistic (bounds memory and NBT size). */
    private static final int MAX_TRACKED_ITEMS = 64;
    public static final int TOP_COUNT = 3;

    public static final int UPGRADE_AMPLIFIER = 0;
    public static final int UPGRADE_THROUGHPUT = 1;

    public static final int ACCESS_OPEN = 0;
    public static final int ACCESS_TEAM = 1;
    public static final int ACCESS_PRIVATE = 2;

    /** Minimum game ticks between two sound/particle bursts of one block (pipes can insert every tick). */
    private static final long FX_COOLDOWN_TICKS = 6L;

    /** Resonance points (64-bit). Saved in the block entity data. */
    private long points;
    /** Sub-point remainder in 1/1000 points; lets efficiencies like 0.5 never lose value to rounding. */
    private int fractionMilli;
    /** The item the fabricator produces, or null. Ghost item of the Target Slot. */
    private Item target;
    /** Installed upgrades (0 - {@link VRRegistries#MAX_UPGRADES} each). */
    private int amplifiers;
    private int throughputModules;

    /** Who placed the block; null = unclaimed (first player to open it claims it). */
    private UUID ownerId;
    private String ownerName = "";
    private int accessMode = ACCESS_OPEN;

    /** True = this block uses its owner's shared pool (see {@link LinkedPools}) instead of {@link #points}. */
    private boolean linked;

    // lifetime statistics of this block (O(1) updates; the top list is recomputed lazily, only when somebody looks)
    private long statRecycledMilli;
    private long statSpent;
    private long statItemsRecycled;
    private long statItemsFabricated;
    private final Map<Item, Long> recycledByItem = new HashMap<>();
    private final Item[] topItems = new Item[TOP_COUNT];
    private final long[] topCounts = new long[TOP_COUNT];
    private boolean topDirty = true;

    private long lastFxTick = -FX_COOLDOWN_TICKS; // NOT Long.MIN_VALUE: 'now - MIN_VALUE' overflows and would mute the block forever

    private final VoidRecyclerHandler itemHandler = new VoidRecyclerHandler(this);

    // redstone kill-switch cache (recomputed at most once per game tick, only when somebody asks)
    private long lockCheckedAtTick = Long.MIN_VALUE;
    private boolean locked;

    // last comparator output we told the neighbours about
    private int lastComparatorSignal = -1;

    public VoidRecyclerBlockEntity(BlockPos pos, BlockState state) {
        super(VRRegistries.VOID_RECYCLER_BE.get(), pos, state);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------------------------

    public VoidRecyclerHandler getItemHandler() {
        return itemHandler;
    }

    /** The balance this block works with: its own, or the owner's shared pool while linked. */
    public long getPoints() {
        LinkedPools pools = pools();
        return pools != null ? pools.get(ownerId) : points;
    }

    private void setBalance(long value) {
        LinkedPools pools = pools();
        if (pools != null) {
            pools.set(ownerId, value);
        } else {
            points = value;
        }
    }

    /** @return the shared pool store when this block is linked (server side, claimed), otherwise null. */
    private LinkedPools pools() {
        if (linked && ownerId != null && this.level instanceof ServerLevel server) {
            return LinkedPools.of(server);
        }
        return null;
    }

    public boolean isLinked() {
        return linked;
    }

    public int getFractionMilli() {
        return fractionMilli;
    }

    /** @return the fabrication target or null if none is selected. */
    public Item getTarget() {
        return target;
    }

    /** Server side. Pass null to clear. Items without a fabrication price are ignored. */
    public void setTarget(Item newTarget) {
        if (newTarget == Items.AIR) {
            newTarget = null;
        }
        if (newTarget != null && !Economy.isFabricable(newTarget)) {
            return;
        }
        if (this.target != newTarget) {
            this.target = newTarget;
            commitChanged();
        }
    }

    /** @return price of one target item, 0 if there is no valid target. Used by the GUI data sync. */
    public long getTargetPrice() {
        return target == null ? 0L : Economy.fabricationPrice(target);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Redstone kill-switch
    // ---------------------------------------------------------------------------------------------------------

    /**
     * @return true while the block receives a redstone signal. The result is cached for the current game tick,
     *         so pipes calling the handler hundreds of times per tick cost one neighbour check per tick at most.
     */
    public boolean isLocked() {
        Level lvl = this.level;
        if (lvl == null || lvl.isClientSide()) {
            return false;
        }
        long now = lvl.getGameTime();
        if (now != lockCheckedAtTick) {
            lockCheckedAtTick = now;
            locked = lvl.hasNeighborSignal(this.worldPosition);
        }
        return locked;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Point maths (raw, without side effects: callers decide when to commit)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Adds {@code unitValue * amount * efficiency} points. Math is done in thousandths of a point,
     * the remainder is carried in {@link #fractionMilli} so nothing is lost.
     */
    public long applyGain(long unitValue, int amount) {
        long gainMilli = Economy.satMul(Economy.satMul(unitValue, amount), efficiencyMilli());
        long totalFraction = Economy.satAdd(gainMilli % 1000L, fractionMilli);
        long whole = Economy.satAdd(gainMilli / 1000L, totalFraction / 1000L);
        fractionMilli = (int) (totalFraction % 1000L);
        setBalance(Economy.satAdd(getPoints(), whole));
        return gainMilli;
    }

    /** Removes points; the caller must have verified that enough points are available. */
    public void applySpend(long cost) {
        setBalance(Math.max(0L, getPoints() - cost));
    }

    /** Rollback hook used by the transaction journal. */
    public void restoreState(long restoredPoints, int restoredFractionMilli) {
        setBalance(restoredPoints);
        this.fractionMilli = restoredFractionMilli;
    }

    /**
     * GUI / hand path: destroys {@code count} items of the given type and credits their value (server side).
     * Unknown or worthless items simply vanish.
     */
    public void recycle(Item item, int count) {
        long unitValue = Economy.rawRecyclingValue(item);
        if (count > 0 && unitValue > 0L && efficiencyMilli() > 0) {
            long gainMilli = applyGain(unitValue, count);
            recordRecycled(item, count, gainMilli);
            commitChanged();
            playFx(false);
        }
    }

    /** GUI purchase path: atomically pay, persist, notify. @return false when the balance is too low. */
    public boolean trySpend(long cost, Item item, int count) {
        if (cost < 0L || getPoints() < cost) {
            return false;
        }
        applySpend(cost);
        recordFabricated(item, count, cost);
        commitChanged();
        playFx(true);
        return true;
    }

    /** Marks the chunk dirty and refreshes the comparator if (and only if) its output changed. */
    public void commitChanged() {
        setChanged();
        updateComparator();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Upgrades
    // ---------------------------------------------------------------------------------------------------------

    public int getUpgradeCount(int kind) {
        return kind == UPGRADE_AMPLIFIER ? amplifiers : throughputModules;
    }

    /** Server and menu path: clamps to 0..MAX_UPGRADES and persists. */
    public void setUpgradeCount(int kind, int count) {
        int clamped = Math.max(0, Math.min(VRRegistries.MAX_UPGRADES, count));
        if (getUpgradeCount(kind) == clamped) {
            return;
        }
        if (kind == UPGRADE_AMPLIFIER) {
            amplifiers = clamped;
        } else {
            throughputModules = clamped;
        }
        commitChanged();
    }

    /** Recycling efficiency of THIS block in thousandths: config value plus amplifiers, capped at 100%. */
    public int efficiencyMilli() {
        return PointMath.effectiveEfficiencyMilli(VRConfig.efficiencyMilli(), VRConfig.amplifierBonusMilli(), amplifiers);
    }

    /** Pipe extract cap of THIS block: config value times (1 + throughput modules). */
    public int maxExtractPerCall() {
        return PointMath.effectiveExtractCap(VRConfig.maxExtractPerCall(), throughputModules, 65_536);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ownership / access
    // ---------------------------------------------------------------------------------------------------------

    /** @return the owner's UUID or null when unclaimed. */
    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int getAccessMode() {
        return accessMode;
    }

    /** Makes {@code player} the owner (server). */
    public void setOwner(Player player) {
        this.ownerId = player.getUUID();
        this.ownerName = player.getScoreboardName();
        setChanged();
    }

    public void clearOwner() {
        this.ownerId = null;
        this.ownerName = "";
        this.accessMode = ACCESS_OPEN;
        this.linked = false;
        setChanged();
    }

    /** Owner, creative players and everyone on an unclaimed block may change the settings. */
    public boolean canManage(Player player) {
        return ownerId == null || ownerId.equals(player.getUUID()) || player.isCreative();
    }

    /** May this player open the GUI / break the block? */
    public boolean canAccess(Player player) {
        if (accessMode == ACCESS_OPEN || canManage(player)) {
            return true;
        }
        if (accessMode == ACCESS_TEAM && this.level != null) {
            // team membership is stored by player NAME in the scoreboard, so this also works while the owner is offline
            var team = this.level.getScoreboard().getPlayersTeam(ownerName);
            return team != null && team.equals(player.getTeam());
        }
        return false;
    }

    /** OPEN -> TEAM -> PRIVATE -> OPEN. @return false when the player may not change it. */
    public boolean cycleAccessMode(Player player) {
        if (!canManage(player)) {
            return false;
        }
        if (ownerId == null) {
            setOwner(player); // changing the lock of an unclaimed block claims it
        }
        accessMode = (accessMode + 1) % 3;
        setChanged();
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Capsule support
    // ---------------------------------------------------------------------------------------------------------

    /** Adds raw (already final) points, saturating. */
    public void addPoints(long amount) {
        if (amount > 0L) {
            setBalance(Economy.satAdd(getPoints(), amount));
            commitChanged();
        }
    }

    /** Removes up to {@code max} points from the balance. @return how many were really removed. */
    public long takePoints(long max) {
        long balance = getPoints();
        long taken = Math.max(0L, Math.min(balance, max));
        if (taken > 0L) {
            setBalance(balance - taken);
            commitChanged();
        }
        return taken;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Resonance Link (shared pool)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Joins or leaves the owner's shared pool. Linking moves this block's own balance into the pool; unlinking leaves
     * the pool as it is and the block starts again with an empty balance of its own.
     * Linking an unclaimed block claims it for the player. @return false when the player may not change the settings.
     */
    public boolean toggleLink(Player player) {
        if (!canManage(player) || !(this.level instanceof ServerLevel server)) {
            return false;
        }
        if (ownerId == null) {
            setOwner(player);
        }
        if (linked) {
            linked = false;
        } else {
            LinkedPools pools = LinkedPools.of(server);
            pools.set(ownerId, Economy.satAdd(pools.get(ownerId), points));
            points = 0L;
            linked = true;
        }
        commitChanged();
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Statistics (lifetime counters of this block; updated only when something really happened)
    // ---------------------------------------------------------------------------------------------------------

    public void recordRecycled(Item item, int count, long gainMilli) {
        if (count <= 0) {
            return;
        }
        statRecycledMilli = Economy.satAdd(statRecycledMilli, gainMilli);
        statItemsRecycled = Economy.satAdd(statItemsRecycled, count);
        if (!recycledByItem.containsKey(item) && recycledByItem.size() >= MAX_TRACKED_ITEMS) {
            // table full: forget the least recycled item (runs at most once per NEW item type, 64 entries)
            Item smallest = null;
            long smallestCount = Long.MAX_VALUE;
            for (Map.Entry<Item, Long> entry : recycledByItem.entrySet()) {
                if (entry.getValue() < smallestCount) {
                    smallestCount = entry.getValue();
                    smallest = entry.getKey();
                }
            }
            if (smallest != null) {
                recycledByItem.remove(smallest);
            }
        }
        recycledByItem.merge(item, (long) count, Economy::satAdd);
        topDirty = true;
        setChanged();
    }

    public void recordFabricated(Item item, int count, long spent) {
        if (count <= 0) {
            return;
        }
        statItemsFabricated = Economy.satAdd(statItemsFabricated, count);
        statSpent = Economy.satAdd(statSpent, spent);
        setChanged();
    }

    /** Bulk variant for the pipe handler, called once per committed transaction. */
    public void recordRecycled(Map<Item, Integer> items, long gainMilli) {
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : items.entrySet()) {
            // the gain is accounted once, on the first item
            recordRecycled(entry.getKey(), entry.getValue(), first ? gainMilli : 0L);
            first = false;
        }
    }

    public long statRecycledPoints() {
        return statRecycledMilli / 1000L;
    }

    public long statSpentPoints() {
        return statSpent;
    }

    public long statItemsRecycled() {
        return statItemsRecycled;
    }

    public long statItemsFabricated() {
        return statItemsFabricated;
    }

    private void refreshTop() {
        if (!topDirty) {
            return;
        }
        topDirty = false;
        for (int i = 0; i < TOP_COUNT; i++) {
            topItems[i] = null;
            topCounts[i] = 0L;
        }
        for (Map.Entry<Item, Long> entry : recycledByItem.entrySet()) {
            long count = entry.getValue();
            for (int i = 0; i < TOP_COUNT; i++) {
                if (count > topCounts[i]) {
                    for (int j = TOP_COUNT - 1; j > i; j--) {
                        topItems[j] = topItems[j - 1];
                        topCounts[j] = topCounts[j - 1];
                    }
                    topItems[i] = entry.getKey();
                    topCounts[i] = count;
                    break;
                }
            }
        }
    }

    private int topId(int rank) {
        refreshTop();
        return topItems[rank] == null ? 0 : BuiltInRegistries.ITEM.getId(topItems[rank]) + 1;
    }

    private int topCount(int rank) {
        refreshTop();
        return (int) Math.min(Integer.MAX_VALUE, topCounts[rank]);
    }

    private String encodeTop() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Long> entry : recycledByItem.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(BuiltInRegistries.ITEM.getKey(entry.getKey())).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private void decodeTop(String text) {
        recycledByItem.clear();
        topDirty = true;
        if (text.isEmpty()) {
            return;
        }
        for (String part : text.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0 || recycledByItem.size() >= MAX_TRACKED_ITEMS) {
                continue;
            }
            Identifier id = Identifier.tryParse(part.substring(0, eq));
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) {
                continue;
            }
            try {
                long count = Long.parseLong(part.substring(eq + 1));
                if (count > 0L) {
                    recycledByItem.put(item, count);
                }
            } catch (NumberFormatException malformed) {
                // skip the broken entry
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Effects (server only, throttled, no ticker: called when something actually happened)
    // ---------------------------------------------------------------------------------------------------------

    public void playFx(boolean fabricate) {
        if (!(this.level instanceof ServerLevel server) || !VRConfig.effects()) {
            return;
        }
        long now = server.getGameTime();
        if (now - lastFxTick < FX_COOLDOWN_TICKS) {
            return;
        }
        lastFxTick = now;
        float pitch = 0.9F + server.getRandom().nextFloat() * 0.2F;
        double x = this.worldPosition.getX() + 0.5D;
        double y = this.worldPosition.getY() + 1.05D;
        double z = this.worldPosition.getZ() + 0.5D;
        if (fabricate) {
            server.playSound(null, this.worldPosition, VRRegistries.FABRICATE_SOUND.get(), SoundSource.BLOCKS, 0.6F, pitch);
            server.sendParticles(ParticleTypes.END_ROD, x, y, z, 5, 0.18D, 0.05D, 0.18D, 0.03D);
        } else {
            server.playSound(null, this.worldPosition, VRRegistries.RECYCLE_SOUND.get(), SoundSource.BLOCKS, 0.5F, pitch);
            server.sendParticles(ParticleTypes.PORTAL, x, y - 0.3D, z, 12, 0.25D, 0.25D, 0.25D, 0.5D);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Comparator
    // ---------------------------------------------------------------------------------------------------------

    /**
     * With a target selected: number of items the balance can pay for (0 - 15, saturating).
     * Without a target: number of decimal digits of the balance (0 - 15), a cheap "fill level" indicator.
     */
    public int computeComparatorSignal() {
        long balance = getPoints();
        if (balance <= 0L) {
            return 0;
        }
        long price = getTargetPrice();
        if (price > 0L) {
            return (int) Math.min(15L, balance / price);
        }
        int digits = 0;
        for (long p = balance; p > 0L && digits < 15; p /= 10L) {
            digits++;
        }
        return digits;
    }

    private void updateComparator() {
        Level lvl = this.level;
        if (lvl == null || lvl.isClientSide()) {
            return;
        }
        int signal = computeComparatorSignal();
        if (signal != lastComparatorSignal) {
            lastComparatorSignal = signal;
            lvl.updateNeighbourForOutputSignal(this.worldPosition, getBlockState().getBlock());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong(KEY_POINTS, points);
        if (fractionMilli != 0) {
            output.putInt(KEY_FRACTION, fractionMilli);
        }
        if (target != null) {
            output.putString(KEY_TARGET, BuiltInRegistries.ITEM.getKey(target).toString());
        }
        if (amplifiers > 0) {
            output.putInt(KEY_AMPLIFIERS, amplifiers);
        }
        if (throughputModules > 0) {
            output.putInt(KEY_THROUGHPUT, throughputModules);
        }
        if (ownerId != null) {
            output.putString(KEY_OWNER, ownerId.toString());
            output.putString(KEY_OWNER_NAME, ownerName);
        }
        if (accessMode != ACCESS_OPEN) {
            output.putInt(KEY_ACCESS, accessMode);
        }
        if (linked) {
            output.putInt(KEY_LINKED, 1);
        }
        if (statRecycledMilli > 0L) {
            output.putLong(KEY_STAT_RECYCLED, statRecycledMilli);
        }
        if (statSpent > 0L) {
            output.putLong(KEY_STAT_SPENT, statSpent);
        }
        if (statItemsRecycled > 0L) {
            output.putLong(KEY_STAT_ITEMS_IN, statItemsRecycled);
        }
        if (statItemsFabricated > 0L) {
            output.putLong(KEY_STAT_ITEMS_OUT, statItemsFabricated);
        }
        if (!recycledByItem.isEmpty()) {
            output.putString(KEY_STAT_TOP, encodeTop());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        points = Math.max(0L, input.getLongOr(KEY_POINTS, 0L));
        fractionMilli = Math.max(0, Math.min(999, input.getIntOr(KEY_FRACTION, 0)));
        amplifiers = Math.max(0, Math.min(VRRegistries.MAX_UPGRADES, input.getIntOr(KEY_AMPLIFIERS, 0)));
        throughputModules = Math.max(0, Math.min(VRRegistries.MAX_UPGRADES, input.getIntOr(KEY_THROUGHPUT, 0)));
        accessMode = Math.max(ACCESS_OPEN, Math.min(ACCESS_PRIVATE, input.getIntOr(KEY_ACCESS, ACCESS_OPEN)));
        ownerName = input.getStringOr(KEY_OWNER_NAME, "");
        linked = input.getIntOr(KEY_LINKED, 0) != 0;
        statRecycledMilli = Math.max(0L, input.getLongOr(KEY_STAT_RECYCLED, 0L));
        statSpent = Math.max(0L, input.getLongOr(KEY_STAT_SPENT, 0L));
        statItemsRecycled = Math.max(0L, input.getLongOr(KEY_STAT_ITEMS_IN, 0L));
        statItemsFabricated = Math.max(0L, input.getLongOr(KEY_STAT_ITEMS_OUT, 0L));
        decodeTop(input.getStringOr(KEY_STAT_TOP, ""));
        ownerId = null;
        String ownerText = input.getStringOr(KEY_OWNER, "");
        if (!ownerText.isEmpty()) {
            try {
                ownerId = UUID.fromString(ownerText);
            } catch (IllegalArgumentException malformed) {
                ownerId = null;
            }
        }
        target = null;
        String id = input.getStringOr(KEY_TARGET, "");
        if (!id.isEmpty()) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                Item item = BuiltInRegistries.ITEM.getValue(parsed);
                if (item != Items.AIR) {
                    target = item;
                }
            }
        }
    }

    /** Called when the block is broken with the loot table's copy_components: stores the balance on the item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (points > 0L) {
            components.set(VRRegistries.STORED_POINTS.get(), points);
        }
        if (amplifiers > 0) {
            components.set(VRRegistries.STORED_AMPLIFIERS.get(), amplifiers);
        }
        if (throughputModules > 0) {
            components.set(VRRegistries.STORED_THROUGHPUT.get(), throughputModules);
        }
    }

    /** Called when the block item is placed: restores the balance from the item. */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Long stored = components.get(VRRegistries.STORED_POINTS.get());
        if (stored != null) {
            points = Math.max(0L, stored);
        }
        Integer amps = components.get(VRRegistries.STORED_AMPLIFIERS.get());
        if (amps != null) {
            amplifiers = Math.max(0, Math.min(VRRegistries.MAX_UPGRADES, amps));
        }
        Integer thr = components.get(VRRegistries.STORED_THROUGHPUT.get());
        if (thr != null) {
            throughputModules = Math.max(0, Math.min(VRRegistries.MAX_UPGRADES, thr));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.void_recycler.void_recycler");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new VoidRecyclerMenu(containerId, inventory, this);
    }

    /**
     * Live view of the state for the menu's data slots. Everything is split into 32-bit ints because the vanilla
     * container data channel only transports ints:
     * 0/1 = points (low/high), 2 = target registry id (-1 none), 3/4 = target price (low/high),
     * 5 = amplifiers, 6 = throughput modules, 7 = efficiency (thousandths), 8 = access mode,
     * 9 = 1 if {@code viewer} may change the settings (owner), 10 = 1 while linked to the shared pool,
     * 11-18 = lifetime statistics (recycled points, spent points, items recycled, items fabricated; low/high pairs),
     * 19-21 = registry id + 1 of the three most recycled items (0 = none), 22-24 = their counts (saturated),
     * 25 = 1 while the redstone kill-switch is active.
     */
    public ContainerData createMenuData(Player viewer) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> (int) getPoints();
                    case 1 -> (int) (getPoints() >>> 32);
                    case 2 -> target == null ? -1 : BuiltInRegistries.ITEM.getId(target);
                    case 3 -> (int) getTargetPrice();
                    case 4 -> (int) (getTargetPrice() >>> 32);
                    case 5 -> amplifiers;
                    case 6 -> throughputModules;
                    case 7 -> efficiencyMilli();
                    case 8 -> accessMode;
                    case 9 -> canManage(viewer) ? 1 : 0;
                    case 10 -> linked ? 1 : 0;
                    case 25 -> isLocked() ? 1 : 0;
                    case 11 -> (int) statRecycledPoints();
                    case 12 -> (int) (statRecycledPoints() >>> 32);
                    case 13 -> (int) statSpentPoints();
                    case 14 -> (int) (statSpentPoints() >>> 32);
                    case 15 -> (int) statItemsRecycled;
                    case 16 -> (int) (statItemsRecycled >>> 32);
                    case 17 -> (int) statItemsFabricated;
                    case 18 -> (int) (statItemsFabricated >>> 32);
                    case 19, 20, 21 -> topId(index - 19);
                    case 22, 23, 24 -> topCount(index - 22);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // only the upgrade slots write through (the menu uses this for both click prediction and the server)
                if (index == 5) {
                    setUpgradeCount(UPGRADE_AMPLIFIER, value);
                } else if (index == 6) {
                    setUpgradeCount(UPGRADE_THROUGHPUT, value);
                }
            }

            @Override
            public int getCount() {
                return VoidRecyclerMenu.DATA_COUNT;
            }
        };
    }
}
