package dev.voidrecycler.network;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.menu.VoidRecyclerMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server: a button of the Void Recycler GUI was pressed
 * ({@code VoidRecyclerMenu.ACTION_*}), with one int argument (direction, registry id, ...).
 *
 * <p>Nothing here is trusted: the server re-checks the open menu, the distance, the access rights and
 * whatever the argument refers to, and works with ITS OWN view of the cursor stack.
 */
public record MenuActionPayload(int containerId, int action, int arg) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MenuActionPayload> TYPE =
            new CustomPacketPayload.Type<>(VoidRecycler.id("menu_action"));

    public static final StreamCodec<ByteBuf, MenuActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MenuActionPayload::containerId,
            ByteBufCodecs.VAR_INT, MenuActionPayload::action,
            ByteBufCodecs.VAR_INT, MenuActionPayload::arg,
            MenuActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called from {@link TargetClickPayload#register} so both payloads share one registrar. */
    static void registerWith(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, STREAM_CODEC, MenuActionPayload::handleOnServer);
    }

    private static void handleOnServer(MenuActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof VoidRecyclerMenu menu
                && menu.containerId == payload.containerId()) {
            menu.handleAction(player, payload.action(), payload.arg());
        }
    }
}
