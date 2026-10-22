package dev.voidrecycler.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * The Void Recycler block. No ticker is provided on purpose ({@link EntityBlock#getTicker} defaults to null).
 */
public class VoidRecyclerBlock extends Block implements EntityBlock {
    public static final MapCodec<VoidRecyclerBlock> CODEC = simpleCodec(VoidRecyclerBlock::new);

    public VoidRecyclerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VoidRecyclerBlockEntity(pos, state);
    }

    /** Right click (with any item in hand) opens the GUI. Manual use works even while redstone-locked. */
    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof VoidRecyclerBlockEntity be) {
                if (be.getOwnerId() == null) {
                    be.setOwner(player); // unclaimed (dispenser-placed, /setblock, ...): the first user owns it
                }
                if (!be.canAccess(player)) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendOverlayMessage(Component.translatable("message.void_recycler.locked", be.getOwnerName()));
                    }
                    return InteractionResult.SUCCESS;
                }
                player.openMenu(be);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The placing player becomes the owner. Declared without {@code @Override} on purpose: if the vanilla signature
     * ever differs this is simply never called and the "first user claims it" fallback in
     * {@link #useWithoutItem} takes over.
     */
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof VoidRecyclerBlockEntity be) {
            be.setOwner(player);
        }
    }

    /** Registered on the NeoForge event bus: players without access cannot break a locked recycler. */
    public static void onBreakBlock(BreakBlockEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof VoidRecyclerBlockEntity be
                && !be.canAccess(event.getPlayer())) {
            event.setCanceled(true);
            event.setNotifyClient(true);
            if (event.getPlayer() instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendOverlayMessage(Component.translatable("message.void_recycler.locked", be.getOwnerName()));
            }
        }
    }

    /** Also used by vanilla to let spectators look into the menu. */
    @Override
    public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof VoidRecyclerBlockEntity be ? be : null;
    }

    // ----- redstone comparator -----------------------------------------------------------------------------------

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /*
     * Vanilla changed this method's signature between versions (with / without the Direction parameter) and the
     * exact 26.1 form could not be compiled against while this was written. Both forms are declared WITHOUT
     * @Override: whichever one matches the real signature overrides it, the other is just a harmless helper.
     */
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof VoidRecyclerBlockEntity be ? be.computeComparatorSignal() : 0;
    }

    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return getAnalogOutputSignal(state, level, pos, Direction.UP);
    }
}
