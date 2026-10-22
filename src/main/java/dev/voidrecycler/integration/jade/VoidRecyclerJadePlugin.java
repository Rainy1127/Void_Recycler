package dev.voidrecycler.integration.jade;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.block.VoidRecyclerBlock;
import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.economy.Economy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Optional Jade integration (compiled only when {@code jade_enabled=true}, see gradle.properties). Shows the balance,
 * efficiency, access mode and link state of a Void Recycler when you look at it. The server sends a few numbers only
 * while Jade is actually showing the tooltip, so it costs nothing otherwise.
 */
@WailaPlugin
public class VoidRecyclerJadePlugin implements IWailaPlugin {
    private static final Identifier DATA_UID = VoidRecycler.id("recycler_data");
    private static final Identifier UI_UID = VoidRecycler.id("recycler_info");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(DataProvider.INSTANCE, VoidRecyclerBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(TooltipProvider.INSTANCE, VoidRecyclerBlock.class);
    }

    /** Server side only: since Minecraft 1.21.6 Jade forbids one class from being both data and tooltip provider. */
    static final class DataProvider implements IServerDataProvider<BlockAccessor> {
        static final DataProvider INSTANCE = new DataProvider();

        @Override
        public Identifier getUid() {
            return DATA_UID;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (accessor.getBlockEntity() instanceof VoidRecyclerBlockEntity be) {
                data.putLong("points", be.getPoints());
                data.putInt("efficiency", be.efficiencyMilli());
                data.putInt("access", be.getAccessMode());
                data.putInt("linked", be.isLinked() ? 1 : 0);
                data.putString("owner", be.getOwnerName());
            }
        }
    }

    /** Client side only: reads the numbers the data provider sent and draws the tooltip lines. */
    static final class TooltipProvider implements IBlockComponentProvider {
        static final TooltipProvider INSTANCE = new TooltipProvider();

        @Override
        public Identifier getUid() {
            return UI_UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains("points")) {
                return;
            }
            tooltip.add(Component.translatable(data.getIntOr("linked", 0) != 0
                            ? "jade.void_recycler.pool" : "jade.void_recycler.points",
                    Economy.formatPoints(data.getLongOr("points", 0L))));
            tooltip.add(Component.translatable("jade.void_recycler.efficiency",
                    Economy.formatMilli(data.getIntOr("efficiency", 0) * 100L)));
            String owner = data.getStringOr("owner", "");
            if (!owner.isEmpty()) {
                tooltip.add(Component.translatable("jade.void_recycler.owner", owner,
                        Component.translatable("gui.void_recycler.hint.access." + data.getIntOr("access", 0))));
            }
        }
    }
}