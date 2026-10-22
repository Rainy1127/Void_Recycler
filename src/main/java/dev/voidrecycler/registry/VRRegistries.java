package dev.voidrecycler.registry;

import com.mojang.serialization.Codec;
import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.block.VoidRecyclerBlock;
import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.menu.VoidRecyclerMenu;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** All registry objects of the mod in one place. */
public final class VRRegistries {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(VoidRecycler.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(VoidRecycler.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, VoidRecycler.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, VoidRecycler.MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, VoidRecycler.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, VoidRecycler.MOD_ID);

    /** How many upgrades of each kind fit into a recycler (also their item stack size). */
    public static final int MAX_UPGRADES = 4;

    public static final DeferredBlock<VoidRecyclerBlock> VOID_RECYCLER_BLOCK = BLOCKS.registerBlock(
            "void_recycler",
            VoidRecyclerBlock::new,
            props -> props
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(3.5F, 9.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());

    public static final DeferredItem<BlockItem> VOID_RECYCLER_ITEM = ITEMS.registerSimpleBlockItem(VOID_RECYCLER_BLOCK);

    /** +efficiency per item, see {@code amplifier_bonus} in the server config. */
    public static final DeferredItem<Item> AMPLIFIER_ITEM = ITEMS.registerItem(
            "resonance_amplifier", Item::new, props -> props.stacksTo(MAX_UPGRADES));

    /** x(1 + n) pipe extract cap. */
    public static final DeferredItem<Item> THROUGHPUT_ITEM = ITEMS.registerItem(
            "throughput_module", Item::new, props -> props.stacksTo(MAX_UPGRADES));

    /** Portable balance: carries {@link #STORED_POINTS}; filled / emptied in the recycler GUI. */
    public static final DeferredItem<Item> CAPSULE_ITEM = ITEMS.registerItem(
            "resonance_capsule", Item::new, props -> props.stacksTo(1));

    public static final Supplier<BlockEntityType<VoidRecyclerBlockEntity>> VOID_RECYCLER_BE = BLOCK_ENTITIES.register(
            "void_recycler",
            () -> new BlockEntityType<>(VoidRecyclerBlockEntity::new, VOID_RECYCLER_BLOCK.get()));

    public static final Supplier<MenuType<VoidRecyclerMenu>> VOID_RECYCLER_MENU = MENUS.register(
            "void_recycler",
            () -> new MenuType<>(VoidRecyclerMenu::new, FeatureFlags.DEFAULT_FLAGS));

    /** Resonance Points kept on the dropped/picked-up block item (tooltip + restored on placement). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> STORED_POINTS = COMPONENTS.registerComponentType(
            "stored_points",
            builder -> builder.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));

    /** Upgrades travel with the block item when it is broken (the loot table copies them). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> STORED_AMPLIFIERS = COMPONENTS.registerComponentType(
            "stored_amplifiers",
            builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> STORED_THROUGHPUT = COMPONENTS.registerComponentType(
            "stored_throughput",
            builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    // sounds are defined in assets/void_recycler/sounds.json (they re-use vanilla events, a resource pack can replace them)
    public static final Supplier<SoundEvent> RECYCLE_SOUND = SOUNDS.register("recycle", id -> SoundEvent.createVariableRangeEvent(id));
    public static final Supplier<SoundEvent> FABRICATE_SOUND = SOUNDS.register("fabricate", id -> SoundEvent.createVariableRangeEvent(id));

    private VRRegistries() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
        SOUNDS.register(modBus);
    }

    /** Exposes the pull/push-by-pipes item handler. One handler instance per block entity, same one for every side. */
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                VOID_RECYCLER_BE.get(),
                (be, side) -> be.getItemHandler());
    }

    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(VOID_RECYCLER_ITEM.get());
            event.accept(AMPLIFIER_ITEM.get());
            event.accept(THROUGHPUT_ITEM.get());
            event.accept(CAPSULE_ITEM.get());
        }
    }
}
