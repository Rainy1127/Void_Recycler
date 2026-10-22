package dev.voidrecycler.economy;

import com.mojang.serialization.Codec;
import dev.voidrecycler.VoidRecycler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-wide store of the shared point pools ("Resonance Link"): one balance per owner UUID. A Void Recycler that is
 * linked reads and writes its owner's pool instead of its own counter, so all linked blocks of one owner share one
 * balance. It is plain saved data, nothing ticks and every access is a single hash lookup.
 */
public final class LinkedPools extends SavedData {
    private static final Codec<LinkedPools> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG)
            .xmap(LinkedPools::new, pools -> pools.balances);

    public static final SavedDataType<LinkedPools> TYPE = new SavedDataType<>(
            VoidRecycler.id("linked_pools"), () -> new LinkedPools(new HashMap<>()), CODEC);

    private final Map<String, Long> balances;

    private LinkedPools(Map<String, Long> loaded) {
        this.balances = new HashMap<>(loaded);
    }

    /** The pools live in the overworld's data storage, shared by every dimension. */
    public static LinkedPools of(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public long get(UUID owner) {
        return Math.max(0L, balances.getOrDefault(owner.toString(), 0L));
    }

    public void set(UUID owner, long value) {
        long clamped = Math.max(0L, value);
        Long old = balances.put(owner.toString(), clamped);
        if (old == null || old != clamped) {
            setDirty(true);
        }
    }
}
