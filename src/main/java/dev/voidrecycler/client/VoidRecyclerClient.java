package dev.voidrecycler.client;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.config.VRClientConfig;
import dev.voidrecycler.registry.VRRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point ({@code dist = Dist.CLIENT}); this class and everything in the
 * {@code client} package is never loaded on a dedicated server.
 */
@Mod(value = VoidRecycler.MOD_ID, dist = Dist.CLIENT)
public final class VoidRecyclerClient {
    public VoidRecyclerClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, VRClientConfig.SPEC);
        // in-game config screen (Mods -> Void Recycler -> Config)
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(VoidRecyclerClient::registerScreens);
        NeoForge.EVENT_BUS.addListener(TooltipHandler::onItemTooltip);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(VRRegistries.VOID_RECYCLER_MENU.get(), VoidRecyclerScreen::new);
    }
}
