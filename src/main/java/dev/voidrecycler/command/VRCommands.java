package dev.voidrecycler.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.economy.FabricationPrice;
import dev.voidrecycler.economy.PriceGenerator;
import dev.voidrecycler.economy.RecipePricing;
import dev.voidrecycler.economy.PointMath;
import dev.voidrecycler.economy.RecyclingValue;
import dev.voidrecycler.economy.VRDataMaps;
import dev.voidrecycler.registry.VRRegistries;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /voidrecycler validate} - sanity check for modpack makers; {@code points} / {@code owner} - admin tools.
 *
 * <p>It inspects both data maps together with the current config and reports:
 * <ul>
 *   <li><b>errors</b>: items that recycle for at least as many points as they cost to fabricate
 *       (an infinite item loop), and non-positive prices;</li>
 *   <li><b>warnings</b>: items that return 50% or more of their fabrication cost, and negative recycling values.</li>
 * </ul>
 * Loops that go through several different items are NOT detected, only the direct 1:1 loop.
 */
public final class VRCommands {
    private static final int MAX_LISTED = 12;
    /** Fabrication price = recycling value x this, matches the bundled default data. */
    private static final double DEFAULT_MARKUP = 2.0D;

    private VRCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("voidrecycler")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("validate").executes(VRCommands::validate))
                .then(Commands.literal("generate")
                        .executes(ctx -> generate(ctx, DEFAULT_MARKUP))
                        .then(Commands.argument("markup", DoubleArgumentType.doubleArg(0.1D, 1000.0D))
                                .executes(ctx -> generate(ctx, DoubleArgumentType.getDouble(ctx, "markup")))))
                .then(Commands.literal("points")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.literal("get").executes(ctx -> points(ctx, Mode.GET, 0L)))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                .executes(ctx -> points(ctx, Mode.SET, LongArgumentType.getLong(ctx, "amount")))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                .executes(ctx -> points(ctx, Mode.ADD, LongArgumentType.getLong(ctx, "amount")))))))
                .then(Commands.literal("owner")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.literal("clear").executes(VRCommands::clearOwner)))));
    }

    private enum Mode { GET, SET, ADD }

    private static VoidRecyclerBlockEntity recyclerAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return ctx.getSource().getLevel().getBlockEntity(pos) instanceof VoidRecyclerBlockEntity be ? be : null;
    }

    /** Debug / admin: read or change the balance of the Void Recycler at a position. */
    private static int points(CommandContext<CommandSourceStack> ctx, Mode mode, long amount) throws CommandSyntaxException {
        VoidRecyclerBlockEntity be = recyclerAt(ctx);
        if (be == null) {
            ctx.getSource().sendFailure(Component.literal("There is no Void Recycler at that position."));
            return 0;
        }
        switch (mode) {
            case SET -> {
                be.takePoints(Long.MAX_VALUE);
                be.addPoints(amount);
            }
            case ADD -> be.addPoints(amount);
            case GET -> { }
        }
        final long now = be.getPoints();
        ctx.getSource().sendSuccess(() -> Component.literal("Balance: " + PointMath.formatPoints(now) + " Resonance Points")
                .withStyle(ChatFormatting.AQUA), false);
        return (int) Math.min(Integer.MAX_VALUE, now);
    }

    /** Admin: releases an abandoned block; the next player who opens it becomes the owner. */
    private static int clearOwner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        VoidRecyclerBlockEntity be = recyclerAt(ctx);
        if (be == null) {
            ctx.getSource().sendFailure(Component.literal("There is no Void Recycler at that position."));
            return 0;
        }
        be.clearOwner();
        ctx.getSource().sendSuccess(() -> Component.literal("Owner cleared: the next player to open the block claims it.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * {@code /voidrecycler generate [markup]}: prices every craftable item that has no entry yet, derived from the
     * recycling values you already defined. Writes ready-to-use data map files, changes nothing in the running game.
     */
    private static int generate(CommandContext<CommandSourceStack> ctx, double markup) {
        CommandSourceStack source = ctx.getSource();

        Map<String, Long> known = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && Economy.isRecyclingKnown(item)) {
                known.put(BuiltInRegistries.ITEM.getKey(item).toString(), Economy.rawRecyclingValue(item));
            }
        }
        if (known.isEmpty()) {
            source.sendFailure(Component.literal("No recycling values are defined yet: nothing to derive prices from."));
            return 0;
        }

        Map<String, Long> derived = PriceGenerator.derive(known, RecipePricing.scanCrafting(source.getLevel()), 64);

        Map<String, Long> recycling = new TreeMap<>();
        Map<String, Long> fabrication = new TreeMap<>();
        for (Map.Entry<String, Long> entry : derived.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR || Economy.isProtected(item)) {
                continue;
            }
            if (!Economy.isRecyclingBlocked(item)) {
                recycling.put(entry.getKey(), entry.getValue());
            }
            boolean hasPrice = item.builtInRegistryHolder().getData(VRDataMaps.FABRICATION_PRICES) != null;
            if (!hasPrice && !Economy.isFabricationBlocked(item)) {
                fabrication.put(entry.getKey(), PriceGenerator.markup(entry.getValue(), markup));
            }
        }

        Path dir = source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                .resolve("void_recycler_generated").resolve("data").resolve("void_recycler")
                .resolve("data_maps").resolve("item");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("recycling_values.json"),
                    PriceGenerator.toDataMapJson(recycling, "points"), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("fabrication_prices.json"),
                    PriceGenerator.toDataMapJson(fabrication, "cost"), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            source.sendFailure(Component.literal("Could not write the files: " + failure.getMessage()));
            return 0;
        }

        long maxEfficiencyMilli = PointMath.effectiveEfficiencyMilli(
                VRConfig.efficiencyMilli(), VRConfig.amplifierBonusMilli(), VRRegistries.MAX_UPGRADES);
        source.sendSuccess(() -> Component.literal("Generated " + recycling.size() + " recycling values and "
                + fabrication.size() + " fabrication prices (markup x" + markup + ", from " + known.size()
                + " seed values).").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("Written to " + dir).withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Copy the 'data' folder into a data pack (or your modpack's pack), /reload, "
                + "then run /voidrecycler validate. Existing entries were never touched.").withStyle(ChatFormatting.GRAY), false);
        if (markup * 1000.0D <= maxEfficiencyMilli) {
            source.sendSuccess(() -> Component.literal("[WARN] markup x" + markup + " is not above the maximum efficiency ("
                    + Economy.formatMilli(maxEfficiencyMilli * 100L) + "%): generated items would form recycling loops.")
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return recycling.size() + fabrication.size();
    }

    private static int validate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        Map<ResourceKey<Item>, RecyclingValue> recycling = BuiltInRegistries.ITEM.getDataMap(VRDataMaps.RECYCLING_VALUES);
        Map<ResourceKey<Item>, FabricationPrice> prices = BuiltInRegistries.ITEM.getDataMap(VRDataMaps.FABRICATION_PRICES);
        long efficiencyMilli = VRConfig.efficiencyMilli();
        // the best a player can reach: all amplifier slots filled
        long maxEfficiencyMilli = PointMath.effectiveEfficiencyMilli(
                VRConfig.efficiencyMilli(), VRConfig.amplifierBonusMilli(), VRRegistries.MAX_UPGRADES);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<ResourceKey<Item>, RecyclingValue> entry : recycling.entrySet()) {
            if (entry.getValue().points() < 0L) {
                warnings.add(entry.getKey().identifier() + ": negative recycling value (" + entry.getValue().points() + ") is treated as 0");
            }
        }

        for (Map.Entry<ResourceKey<Item>, FabricationPrice> entry : prices.entrySet()) {
            ResourceKey<Item> key = entry.getKey();
            Item priced = BuiltInRegistries.ITEM.getValue(key.identifier());
            if (priced != null && (Economy.isFabricationBlocked(priced) || Economy.isRecyclingBlocked(priced))) {
                continue; // a never_fabricate / never_recycle tag makes a loop impossible for this item
            }
            long cost = entry.getValue().cost();
            if (cost <= 0L) {
                errors.add(key.identifier() + ": fabrication cost " + cost + " is not positive, the item cannot be fabricated");
                continue;
            }
            RecyclingValue back = recycling.get(key);
            if (back == null || back.points() <= 0L) {
                continue;
            }
            // points returned by recycling one item, in thousandths, compared with the cost in thousandths
            long returnedMilli = Economy.satMul(back.points(), efficiencyMilli);
            long costMilli = Economy.satMul(cost, 1000L);
            if (returnedMilli >= costMilli) {
                errors.add(key.identifier() + ": LOOP - recycles for " + Economy.formatMilli(returnedMilli)
                        + " but costs " + Economy.formatPoints(cost) + " to fabricate");
            } else if (Economy.satMul(back.points(), maxEfficiencyMilli) >= costMilli) {
                warnings.add(key.identifier() + ": LOOP once " + VRRegistries.MAX_UPGRADES + " Resonance Amplifiers are installed ("
                        + Economy.formatMilli(maxEfficiencyMilli * 100L) + "% efficiency): recycles for "
                        + Economy.formatMilli(Economy.satMul(back.points(), maxEfficiencyMilli))
                        + " but costs " + Economy.formatPoints(cost));
            } else if (returnedMilli * 2L >= costMilli) {
                warnings.add(key.identifier() + ": recycling returns " + (returnedMilli * 100L / costMilli)
                        + "% of the fabrication cost");
            }
        }

        source.sendSuccess(() -> Component.literal("Void Recycler: " + recycling.size() + " recycling values, "
                + prices.size() + " fabrication prices, efficiency " + Economy.formatMilli(efficiencyMilli * 100L)
                + "% (max " + Economy.formatMilli(maxEfficiencyMilli * 100L) + "% with amplifiers), void_unknown_items="
                + VRConfig.voidUnknownItems()).withStyle(ChatFormatting.AQUA), false);

        report(source, "ERROR", errors, ChatFormatting.RED);
        report(source, "WARN", warnings, ChatFormatting.GOLD);

        if (errors.isEmpty() && warnings.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No problems found.").withStyle(ChatFormatting.GREEN), false);
        }
        return errors.size() + warnings.size();
    }

    private static void report(CommandSourceStack source, String label, List<String> lines, ChatFormatting color) {
        int shown = Math.min(lines.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            String line = lines.get(i);
            source.sendSuccess(() -> Component.literal("[" + label + "] " + line).withStyle(color), false);
        }
        if (lines.size() > shown) {
            int more = lines.size() - shown;
            source.sendSuccess(() -> Component.literal("[" + label + "] ... and " + more + " more").withStyle(color), false);
        }
    }
}
