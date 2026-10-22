package dev.voidrecycler;

import dev.voidrecycler.block.VoidRecyclerBlock;
import dev.voidrecycler.command.VRCommands;
import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.economy.VRDataMaps;
import dev.voidrecycler.network.TargetClickPayload;
import dev.voidrecycler.registry.VRRegistries;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/** Common (both sides) entry point. */
@Mod(VoidRecycler.MOD_ID)
public final class VoidRecycler {
    public static final String MOD_ID = "void_recycler";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public VoidRecycler(IEventBus modBus, ModContainer container) {
        VRRegistries.register(modBus);

        container.registerConfig(ModConfig.Type.SERVER, VRConfig.SPEC);

        modBus.addListener(VRDataMaps::onRegister);
        modBus.addListener(VRRegistries::onRegisterCapabilities);
        modBus.addListener(VRRegistries::onBuildCreativeTab);
        modBus.addListener(TargetClickPayload::register);

        NeoForge.EVENT_BUS.addListener(VRCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(VoidRecyclerBlock::onBreakBlock);
    }
}
