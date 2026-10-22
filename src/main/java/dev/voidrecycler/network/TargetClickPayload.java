package dev.voidrecycler.network;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.menu.VoidRecyclerMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server: "I clicked the Target Slot of the menu with this container id".
 * The server decides what that means using ITS OWN cursor stack, so the client cannot spoof an item.
 */
public record TargetClickPayload(int containerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TargetClickPayload> TYPE =
            new CustomPacketPayload.Type<>(VoidRecycler.id("target_click"));

    public static final StreamCodec<ByteBuf, TargetClickPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TargetClickPayload::containerId,
            TargetClickPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ----- registration + server handler (this class is common code: no client classes are referenced) -------------

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, TargetClickPayload::handleOnServer);
        MenuActionPayload.registerWith(registrar);
    }

    private static void handleOnServer(TargetClickPayload payload, IPayloadContext context) {
        // runs on the main server thread (default handler thread)
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof VoidRecyclerMenu menu
                && menu.containerId == payload.containerId()) {
            menu.handleTargetClick(player);
        }
    }
}
