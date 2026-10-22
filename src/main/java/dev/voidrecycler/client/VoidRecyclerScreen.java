package dev.voidrecycler.client;

import dev.voidrecycler.block.VoidRecyclerBlockEntity;
import dev.voidrecycler.config.VRClientConfig;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.menu.CatalogState;
import dev.voidrecycler.menu.VoidRecyclerMenu;
import dev.voidrecycler.network.MenuActionPayload;
import dev.voidrecycler.network.TargetClickPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Tech-minimalist dark-mode GUI: OLED black panel, dark-grey cards, neon cyan for the balance,
 * neon orange for prices and red for "cannot afford". Everything is drawn from code for now;
 * a custom texture can later replace {@link #extractBackground}.
 *
 * <p>Animations (counting balance, +N popups, pulsing output slot, flowing arrow) are purely cosmetic, cost a few
 * fills per frame and can be switched off in the client config.
 */
public class VoidRecyclerScreen extends AbstractContainerScreen<VoidRecyclerMenu> {
    static final int IMAGE_W = 176;
    static final int IMAGE_H = 224;

    // ARGB palette
    private static final int OLED_BLACK = 0xFF000000;
    private static final int PANEL_BG = 0xFF0A0A0E;
    private static final int PANEL_EDGE = 0xFF1C1C26;
    private static final int TITLE_BG = 0xFF101016;
    private static final int CARD_BG = 0xFF12121A;
    private static final int SLOT_BG = 0xFF08080B;
    private static final int SLOT_EDGE = 0xFF2E2E3C;
    private static final int NEON_CYAN = 0xFF00FFFF;
    private static final int CYAN_DIM = 0xFF0B7A7A;
    private static final int CYAN_GLOW = 0x2200FFFF;
    private static final int NEON_ORANGE = 0xFFFF9F1C;
    private static final int NEON_RED = 0xFFFF3B3B;
    private static final int NEON_GREEN = 0x39FF88; // rgb only (alpha is added by the user)
    private static final int TEXT_GRAY = 0xFF7A7A88;
    private static final int TEXT_LIGHT = 0xFFC8C8D2;
    private static final int GHOST_TINT = 0x88000000;

    // cosmetic state (client only)
    private double displayedPoints;
    private long lastPoints;
    private long deltaPoints;
    private int deltaTicks;
    private int openTicks;

    /** The catalog panel stays open between GUI sessions (client memory only, nothing is saved to disk). */
    private static boolean rememberCatalogOpen;

    // cached target name for the title bar
    private Item cachedNameItem;
    private Component cachedName = Component.empty();

    public VoidRecyclerScreen(VoidRecyclerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IMAGE_W, IMAGE_H);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ticking: smooth counter + delta popup
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        this.menu.catalog().setOpen(rememberCatalogOpen, this.menu.points());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        long actual = this.menu.points();
        this.menu.catalog().onPointsChanged(actual);
        // the first ticks only fill the synced values, don't treat the initial balance as a change
        if (openTicks < 5) {
            openTicks++;
            displayedPoints = actual;
            lastPoints = actual;
            return;
        }
        if (actual != lastPoints) {
            deltaPoints = actual - lastPoints;
            deltaTicks = 40;
            lastPoints = actual;
        } else if (deltaTicks > 0) {
            deltaTicks--;
        }
        if (!VRClientConfig.guiAnimations()) {
            displayedPoints = actual;
        } else {
            double diff = actual - displayedPoints;
            displayedPoints = Math.abs(diff) < 1.0D ? actual : displayedPoints + diff * 0.3D;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Input: clicks on the ghost Target Slot are not slot clicks, they are sent as a dedicated payload
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput containerInput) {
        if (slot != null) {
            final int index = slot.index;
            final boolean click = containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE;
            if (index == VoidRecyclerMenu.SLOT_TARGET) {
                boolean plainClick = containerInput != ContainerInput.QUICK_MOVE
                        && containerInput != ContainerInput.THROW
                        && containerInput != ContainerInput.SWAP
                        && containerInput != ContainerInput.CLONE;
                if (plainClick) {
                    ClientPacketDistributor.sendToServer(new TargetClickPayload(this.menu.containerId));
                }
                return;
            }
            if (VoidRecyclerMenu.isButton(index) || VoidRecyclerMenu.isCatalogCell(index)) {
                // buttons and catalog cells never go through vanilla slot logic
                if (click) {
                    onButton(slot, buttonNum);
                }
                return;
            }
        }
        super.slotClicked(slot, slotId, buttonNum, containerInput);
    }

    private void onButton(Slot slot, int mouseButton) {
        final CatalogState catalog = this.menu.catalog();
        final long points = this.menu.points();
        final int index = slot.index;
        if (VoidRecyclerMenu.isCatalogCell(index)) {
            int id = ((VoidRecyclerMenu.CatalogCell) slot).registryId();
            if (id >= 0 && mouseButton == 0) {
                send(VoidRecyclerMenu.ACTION_SELECT_TARGET, id);
                clickSound();
            }
            return;
        }
        switch (index) {
            case VoidRecyclerMenu.SLOT_BTN_BATCH -> send(VoidRecyclerMenu.ACTION_BATCH, mouseButton == 1 ? -1 : 1);
            case VoidRecyclerMenu.SLOT_BTN_DEPOSIT -> send(VoidRecyclerMenu.ACTION_CAPSULE_DEPOSIT, 0);
            case VoidRecyclerMenu.SLOT_BTN_WITHDRAW -> send(VoidRecyclerMenu.ACTION_CAPSULE_WITHDRAW, 0);
            case VoidRecyclerMenu.SLOT_BTN_ACCESS -> send(VoidRecyclerMenu.ACTION_ACCESS, 0);
            case VoidRecyclerMenu.SLOT_BTN_LINK -> send(VoidRecyclerMenu.ACTION_LINK, 0);
            case VoidRecyclerMenu.SLOT_BTN_CATALOG -> {
                rememberCatalogOpen = !catalog.isOpen();
                catalog.setOpen(rememberCatalogOpen, points);
            }
            case VoidRecyclerMenu.SLOT_CAT_PREV -> catalog.changePage(-1);
            case VoidRecyclerMenu.SLOT_CAT_NEXT -> catalog.changePage(1);
            case VoidRecyclerMenu.SLOT_CAT_SORT -> catalog.cycleSort(points);
            case VoidRecyclerMenu.SLOT_CAT_FILTER -> catalog.toggleAffordableOnly(points);
            default -> { }
        }
        clickSound();
    }

    private void send(int action, int arg) {
        ClientPacketDistributor.sendToServer(new MenuActionPayload(this.menu.containerId, action, arg));
    }

    private void clickSound() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    /** Wheel over the catalog = page, wheel over the Output Slot / batch button = change the batch amount. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            final int dir = scrollY > 0.0D ? -1 : 1;
            final Slot hovered = getHoveredSlot();
            if (this.menu.catalog().isOpen() && mouseX >= getLeftPos() + VoidRecyclerMenu.CAT_X
                    && mouseX < getLeftPos() + VoidRecyclerMenu.CAT_X + VoidRecyclerMenu.CAT_W) {
                this.menu.catalog().changePage(dir);
                return true;
            }
            if (hovered != null && (hovered.index == VoidRecyclerMenu.SLOT_OUTPUT
                    || hovered.index == VoidRecyclerMenu.SLOT_BTN_BATCH)) {
                send(VoidRecyclerMenu.ACTION_BATCH, dir);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Area covered by the catalog panel, for JEI's "keep the overlay away from this" logic. */
    public List<Rect2i> getExtraAreas() {
        if (!this.menu.catalog().isOpen()) {
            return List.of();
        }
        return List.of(new Rect2i(getLeftPos() + VoidRecyclerMenu.CAT_X - 2, getTopPos(),
                VoidRecyclerMenu.CAT_W + 2, IMAGE_H));
    }

    /** Screen-space rectangle of the Target Slot, for JEI's ghost drag & drop. */
    public Rect2i getTargetArea() {
        return new Rect2i(getLeftPos() + VoidRecyclerMenu.TARGET_X, getTopPos() + VoidRecyclerMenu.TARGET_Y, 16, 16);
    }

    /** Called by the JEI integration when an ingredient was dropped on the Target Slot. */
    public void selectTargetFromGhost(Item item) {
        if (item != null && Economy.isFabricable(item)) {
            send(VoidRecyclerMenu.ACTION_SELECT_TARGET, net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        final int x = getLeftPos();
        final int y = getTopPos();

        drawPanel(g, x, y);
        drawPointsCard(g, x, y);
        drawFabricator(g, x, y);
        drawInfoArea(g, x, y);
        drawControls(g, x, y, mouseX, mouseY);
        drawStatsTooltip(g, x, y, mouseX, mouseY);
        drawInventoryFrames(g, x, y);
        if (this.menu.catalog().isOpen()) {
            drawCatalog(g, x, y);
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        // soft outer glow
        frame(g, x - 1, y - 1, x + IMAGE_W + 1, y + IMAGE_H + 1, CYAN_GLOW);

        g.fill(x, y, x + IMAGE_W, y + IMAGE_H, PANEL_BG);
        frame(g, x, y, x + IMAGE_W, y + IMAGE_H, PANEL_EDGE);

        // title bar
        g.fill(x + 1, y + 1, x + IMAGE_W - 1, y + 15, TITLE_BG);
        g.fill(x + 1, y + 15, x + IMAGE_W - 1, y + 16, CYAN_DIM);
        text(g, Component.translatable("gui.void_recycler.title"), x + 8, y + 4, NEON_CYAN);

        // selected target on the right side of the title bar
        Item target = this.menu.target();
        if (target != cachedNameItem) {
            cachedNameItem = target;
            cachedName = target == null ? Component.empty() : new ItemStack(target).getHoverName();
        }
        if (target != null) {
            int w = this.font.width(cachedName);
            if (w <= 84) {
                text(g, cachedName, x + IMAGE_W - 8 - w, y + 4, NEON_ORANGE);
            }
        }

        // neon corner brackets (same motif as the block texture)
        final int c = 6;
        corner(g, x, y, c, 1, 1);
        corner(g, x + IMAGE_W, y, c, -1, 1);
        corner(g, x, y + IMAGE_H, c, 1, -1);
        corner(g, x + IMAGE_W, y + IMAGE_H, c, -1, -1);
    }

    private void drawPointsCard(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 8, y + 20, x + 168, y + 50, CARD_BG);
        frame(g, x + 8, y + 20, x + 168, y + 50, PANEL_EDGE);
        final boolean flash = deltaTicks > 0 && deltaPoints != 0L && VRClientConfig.guiAnimations();
        final boolean blocked = this.menu.locked();
        // accent bar: cyan normally, green / orange while a gain / spend popup is showing, red while redstone-locked
        int accent = blocked ? NEON_RED : flash ? (deltaPoints > 0L ? NEON_GREEN | 0xFF000000 : NEON_ORANGE) : NEON_CYAN;
        g.fill(x + 8, y + 20, x + 10, y + 50, accent);

        text(g, Component.translatable(this.menu.linked() ? "gui.void_recycler.points_linked" : "gui.void_recycler.points"), x + 15, y + 24, this.menu.linked() ? NEON_CYAN : TEXT_GRAY);

        // the (animated) balance, e.g. 1,500,000 - shrunk if it would not fit (25 characters for Long.MAX_VALUE)
        long shown = VRClientConfig.guiAnimations() ? (long) displayedPoints : this.menu.points();
        Component value = Component.literal(Economy.formatPoints(shown));
        int width = this.font.width(value);
        float scale = 1.5F;
        float maxWidth = 148.0F;
        if (width * scale > maxWidth) {
            scale = maxWidth / width;
        }
        g.pose().pushMatrix();
        g.pose().translate(x + 15, y + 34 + (1.5F - scale) * 4.0F);
        g.pose().scale(scale, scale);
        g.text(this.font, value, 0, 0, NEON_CYAN, false);
        g.pose().popMatrix();

        // efficiency of this block (config + amplifiers) - replaced by the popup while one is visible
        final boolean popup = deltaTicks > 0 && deltaPoints != 0L && VRClientConfig.guiAnimations();
        if (!popup && blocked) {
            Component lockedText = Component.translatable("gui.void_recycler.locked");
            int pulse = 160 + (int) (95.0D * (0.5D + 0.5D * Math.sin(System.currentTimeMillis() / 220.0D)));
            g.text(this.font, lockedText, x + 164 - this.font.width(lockedText), y + 24, 0xFF000000 | (pulse << 16) | 0x3030, false);
        } else if (!popup) {
            Component eff = Component.translatable("gui.void_recycler.efficiency", Economy.formatMilli(this.menu.efficiencyMilli() * 100L));
            g.text(this.font, eff, x + 164 - this.font.width(eff), y + 24, this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER) > 0 ? NEON_GREEN | 0xFF000000 : TEXT_GRAY, false);
        }

        // "+N" / "-N" popup that fades out
        if (popup) {
            int alpha = Math.min(255, deltaTicks * 10);
            int rgb = deltaPoints > 0L ? NEON_GREEN : (NEON_ORANGE & 0xFFFFFF);
            Component delta = Component.literal((deltaPoints > 0L ? "+" : "-") + Economy.formatPoints(Math.abs(deltaPoints)));
            g.text(this.font, delta, x + 164 - this.font.width(delta), y + 24, (alpha << 24) | rgb, false);
        }
    }

    /** Hovering the points card (and no slot) shows the block's lifetime statistics as a tooltip. */
    private void drawStatsTooltip(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY) {
        if (getHoveredSlot() != null || mouseX < x + 8 || mouseX >= x + 168 || mouseY < y + 20 || mouseY >= y + 50) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.void_recycler.stats.title").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("gui.void_recycler.stats.recycled",
                Economy.formatPoints(this.menu.statRecycled()), Economy.formatPoints(this.menu.statItemsRecycled()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.void_recycler.stats.fabricated",
                Economy.formatPoints(this.menu.statItemsFabricated()), Economy.formatPoints(this.menu.statSpent()))
                .withStyle(ChatFormatting.GOLD));
        boolean any = false;
        for (int rank = 0; rank < VoidRecyclerBlockEntity.TOP_COUNT; rank++) {
            Item top = this.menu.topItem(rank);
            if (top == null) {
                continue;
            }
            if (!any) {
                lines.add(Component.translatable("gui.void_recycler.stats.top").withStyle(ChatFormatting.DARK_GRAY));
                any = true;
            }
            lines.add(Component.translatable("gui.void_recycler.stats.top_entry", rank + 1,
                    new ItemStack(top).getHoverName(), Economy.formatPoints(this.menu.topCount(rank)))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (this.menu.linked()) {
            lines.add(Component.translatable("gui.void_recycler.stats.linked").withStyle(ChatFormatting.AQUA));
        }
        g.setTooltipForNextFrame(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    private void drawFabricator(GuiGraphicsExtractor g, int x, int y) {
        final int ix = VoidRecyclerMenu.INPUT_X;
        final int iy = VoidRecyclerMenu.INPUT_Y;
        final int tx = VoidRecyclerMenu.TARGET_X;
        final int ty = VoidRecyclerMenu.TARGET_Y;
        final int ox = VoidRecyclerMenu.OUTPUT_X;
        final int oy = VoidRecyclerMenu.OUTPUT_Y;
        final boolean hasTarget = this.menu.target() != null;
        final boolean canAfford = this.menu.affordable() > 0L;
        final boolean anim = VRClientConfig.guiAnimations();
        final double pulse = anim ? 0.5D + 0.5D * Math.sin(System.currentTimeMillis() / 260.0D) : 1.0D;

        // captions
        centered(g, Component.translatable("gui.void_recycler.input"), x + ix + 8, y + iy - 12, TEXT_GRAY);
        centered(g, Component.translatable("gui.void_recycler.target"), x + tx + 8, y + ty - 12, TEXT_GRAY);
        centered(g, Component.translatable("gui.void_recycler.output"), x + ox + 8, y + oy - 12, TEXT_GRAY);

        // slot wells: input cyan, target orange once chosen, output pulses cyan when payable / red when not
        slotWell(g, x + ix - 1, y + iy - 1, CYAN_DIM, 18);
        slotWell(g, x + tx - 1, y + ty - 1, hasTarget ? NEON_ORANGE : SLOT_EDGE, 18);
        int outEdge = !hasTarget ? SLOT_EDGE : canAfford ? lerpColor(CYAN_DIM, NEON_CYAN, pulse) : NEON_RED;
        slotWell(g, x + ox - 1, y + oy - 1, outEdge, 18);

        // divider between "recycle" (left) and "fabricate" (right)
        g.fill(x + 54, y + 56, x + 55, y + 82, PANEL_EDGE);

        // arrow target -> output, with a bright dash flowing along it while something can be bought
        g.fill(x + 98, y + 70, x + 122, y + 71, CYAN_DIM);
        for (int i = 0; i < 5; i++) {
            g.fill(x + 122 + i, y + 66 + i, x + 123 + i, y + 75 - i, canAfford ? NEON_CYAN : CYAN_DIM);
        }
        if (canAfford && anim) {
            int offset = (int) ((System.currentTimeMillis() / 70L) % 24L);
            int dashEnd = Math.min(24, offset + 6);
            g.fill(x + 98 + offset, y + 70, x + 98 + dashEnd, y + 71, NEON_CYAN);
        }
    }

    private void drawInfoArea(GuiGraphicsExtractor g, int x, int y) {
        final Slot hovered = getHoveredSlot();
        final Item target = this.menu.target();

        Component hint = null;
        Component hint2 = null;
        int hintColor = TEXT_LIGHT;
        int hint2Color = TEXT_LIGHT;

        if (hovered != null && hovered.index == VoidRecyclerMenu.SLOT_INPUT) {
            ItemStack carried = this.menu.getCarried();
            if (carried.isEmpty()) {
                hint = Component.translatable("gui.void_recycler.hint.insert");
            } else if (!Economy.canVoid(carried.getItem())) {
                hint = Component.translatable("gui.void_recycler.hint.rejected");
                hintColor = NEON_RED;
            } else {
                hint = Component.translatable("gui.void_recycler.hint.recycle", Economy.formatMilli(stackGainMilli(carried)));
                hintColor = NEON_CYAN;
            }
        } else if (hovered != null && hovered.index == VoidRecyclerMenu.SLOT_TARGET) {
            ItemStack carried = this.menu.getCarried();
            if (carried.isEmpty()) {
                hint = Component.translatable(target == null ? "gui.void_recycler.hint.pick_item" : "gui.void_recycler.hint.clear");
            } else if (Economy.isFabricable(carried.getItem())) {
                hint = Component.translatable("gui.void_recycler.hint.set");
                hintColor = NEON_ORANGE;
            } else {
                hint = Component.translatable("gui.void_recycler.hint.invalid");
                hintColor = NEON_RED;
            }
        } else if (hovered != null && hovered.index == VoidRecyclerMenu.SLOT_OUTPUT) {
            if (this.menu.affordable() > 0L) {
                hint = Component.translatable("gui.void_recycler.hint.buy");
                hintColor = NEON_CYAN;
            } else if (target != null) {
                hint = Component.translatable("gui.void_recycler.not_enough");
                hintColor = NEON_RED;
            }
        } else if (hovered != null && VoidRecyclerMenu.isButton(hovered.index) && hovered.index < VoidRecyclerMenu.SLOT_CAT_PREV) {
            switch (hovered.index) {
                case VoidRecyclerMenu.SLOT_BTN_BATCH -> {
                    hint = Component.translatable("gui.void_recycler.hint.batch", this.menu.batchAmount());
                    hint2 = Component.translatable("gui.void_recycler.hint.batch2");
                    hintColor = NEON_CYAN;
                    hint2Color = TEXT_GRAY;
                }
                case VoidRecyclerMenu.SLOT_BTN_DEPOSIT -> {
                    hint = Component.translatable("gui.void_recycler.hint.deposit");
                    hint2 = Component.translatable("gui.void_recycler.hint.capsule2");
                    hintColor = NEON_CYAN;
                    hint2Color = TEXT_GRAY;
                }
                case VoidRecyclerMenu.SLOT_BTN_WITHDRAW -> {
                    hint = Component.translatable("gui.void_recycler.hint.withdraw");
                    hint2 = Component.translatable("gui.void_recycler.hint.capsule2");
                    hintColor = NEON_ORANGE;
                    hint2Color = TEXT_GRAY;
                }
                case VoidRecyclerMenu.SLOT_BTN_CATALOG -> {
                    hint = Component.translatable(this.menu.catalog().isOpen() ? "gui.void_recycler.hint.catalog_close" : "gui.void_recycler.hint.catalog_open");
                    hintColor = NEON_ORANGE;
                }
                case VoidRecyclerMenu.SLOT_BTN_LINK -> {
                    hint = Component.translatable(this.menu.linked() ? "gui.void_recycler.hint.link.on" : "gui.void_recycler.hint.link.off");
                    hintColor = this.menu.linked() ? NEON_CYAN : TEXT_LIGHT;
                    hint2 = Component.translatable(this.menu.canManage() ? "gui.void_recycler.hint.link2" : "gui.void_recycler.hint.access_owner");
                    hint2Color = TEXT_GRAY;
                }
                case VoidRecyclerMenu.SLOT_BTN_ACCESS -> {
                    hint = Component.translatable("gui.void_recycler.hint.access." + this.menu.accessMode());
                    hintColor = accessColor();
                    hint2 = Component.translatable(this.menu.canManage() ? "gui.void_recycler.hint.access_change" : "gui.void_recycler.hint.access_owner");
                    hint2Color = TEXT_GRAY;
                }
                default -> { }
            }
        } else if (hovered != null && (hovered.index == VoidRecyclerMenu.SLOT_UPGRADE_AMP || hovered.index == VoidRecyclerMenu.SLOT_UPGRADE_THR)) {
            if (hovered.index == VoidRecyclerMenu.SLOT_UPGRADE_AMP) {
                hint = Component.translatable("gui.void_recycler.hint.upgrade_amp");
                hint2 = Component.translatable("gui.void_recycler.hint.upgrade_amp2", Economy.formatMilli(this.menu.efficiencyMilli() * 100L));
            } else {
                hint = Component.translatable("gui.void_recycler.hint.upgrade_thr");
                hint2 = Component.translatable("gui.void_recycler.hint.upgrade_thr2", 1 + this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_THROUGHPUT));
            }
            hintColor = NEON_CYAN;
            hint2Color = TEXT_GRAY;
        } else if (hovered != null && hovered.hasItem()) {
            // hovering an inventory item: show what it is worth here (replaces cluttering every tooltip)
            ItemStack stack = hovered.getItem();
            if (Economy.isRecyclingKnown(stack.getItem())) {
                hint = Component.translatable("gui.void_recycler.hint.item_recycles", Economy.formatMilli(stackGainMilli(stack)));
                hintColor = NEON_CYAN;
            }
            long price = Economy.fabricationPrice(stack.getItem());
            if (price > 0L) {
                Component line = Component.translatable("gui.void_recycler.hint.item_costs", Economy.formatPoints(price));
                if (hint == null) {
                    hint = line;
                    hintColor = NEON_ORANGE;
                } else {
                    hint2 = line;
                    hint2Color = NEON_ORANGE;
                }
            }
        }
        if (hint == null && hovered == null && target == null) {
            hint = Component.translatable("gui.void_recycler.hint.select_target");
            hintColor = TEXT_GRAY;
        }

        if (hint != null && hint2 != null) {
            centeredFit(g, hint, x + IMAGE_W / 2, y + 86, hintColor, 156);
            centeredFit(g, hint2, x + IMAGE_W / 2, y + 97, hint2Color, 156);
        } else if (hint != null) {
            centeredFit(g, hint, x + IMAGE_W / 2, y + 92, hintColor, 156);
        } else if (target != null) {
            // cost (left) and affordable count (right)
            long price = this.menu.price();
            long affordable = this.menu.affordable();

            text(g, Component.translatable("gui.void_recycler.cost"), x + 8, y + 84, TEXT_GRAY);
            text(g, Component.literal(Economy.formatPoints(price)), x + 8, y + 94, NEON_ORANGE);

            Component label = Component.translatable("gui.void_recycler.affordable");
            Component count = Component.literal(Economy.formatPoints(affordable));
            text(g, label, x + 168 - this.font.width(label), y + 84, TEXT_GRAY);
            text(g, count, x + 168 - this.font.width(count), y + 94, affordable > 0L ? NEON_CYAN : NEON_RED);
        } else {
            centeredFit(g, Component.translatable("gui.void_recycler.hint.select_target"),
                    x + IMAGE_W / 2, y + 92, TEXT_GRAY, 156);
        }

        // progress bar towards the next item, like a durability bar
        g.fill(x + 8, y + 106, x + 168, y + 110, OLED_BLACK);
        frame(g, x + 8, y + 106, x + 168, y + 110, PANEL_EDGE);
        long price = this.menu.price();
        if (target != null && price > 0L) {
            double fraction = (double) (this.menu.points() % price) / (double) price;
            int filled = (int) Math.round(158.0D * fraction);
            if (filled > 0) {
                boolean ready = this.menu.affordable() > 0L;
                g.fill(x + 9, y + 107, x + 9 + filled, y + 109, ready ? NEON_CYAN : NEON_ORANGE);
                g.fill(x + 9, y + 107, x + 9 + filled, y + 108, ready ? 0xFFA0FFFF : 0xFFFFD080); // highlight
            }
        }
        // segment ticks make the bar read like a charge meter
        for (int i = 1; i < 8; i++) {
            g.fill(x + 8 + i * 20, y + 107, x + 9 + i * 20, y + 109, SLOT_BG);
        }
    }

    private void drawInventoryFrames(GuiGraphicsExtractor g, int x, int y) {
        text(g, Component.translatable("gui.void_recycler.inventory"), x + 8, y + VoidRecyclerMenu.INVENTORY_Y - 11, TEXT_GRAY);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotWell(g, x + 7 + col * 18, y + VoidRecyclerMenu.INVENTORY_Y - 1 + row * 18, SLOT_EDGE, 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slotWell(g, x + 7 + col * 18, y + VoidRecyclerMenu.INVENTORY_Y - 1 + 58, SLOT_EDGE, 18);
        }
    }

    /** Called after the slots were drawn, coordinates are relative to the panel: dims the ghost item. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // no vanilla title / "Inventory" labels, the panel draws its own
        if (this.menu.target() != null) {
            g.fill(VoidRecyclerMenu.TARGET_X, VoidRecyclerMenu.TARGET_Y,
                    VoidRecyclerMenu.TARGET_X + 16, VoidRecyclerMenu.TARGET_Y + 16, GHOST_TINT);
        }
        if (this.menu.catalog().isOpen()) {
            // dim the catalog items the balance cannot pay for
            final long points = this.menu.points();
            for (int i = 0; i < CatalogState.PER_PAGE; i++) {
                Item item = this.menu.catalog().itemAt(i);
                if (item != null && Economy.fabricationPrice(item) > points) {
                    int cx = VoidRecyclerMenu.CAT_GRID_X + (i % CatalogState.COLS) * 18;
                    int cy = VoidRecyclerMenu.CAT_GRID_Y + (i / CatalogState.COLS) * 18;
                    g.fill(cx, cy, cx + 16, cy + 16, 0x99000000);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Controls row (batch, capsule, catalog, access) and upgrade slots
    // ---------------------------------------------------------------------------------------------------------

    private int accessColor() {
        return switch (this.menu.accessMode()) {
            case VoidRecyclerBlockEntity.ACCESS_TEAM -> NEON_ORANGE;
            case VoidRecyclerBlockEntity.ACCESS_PRIVATE -> NEON_RED;
            default -> NEON_GREEN | 0xFF000000;
        };
    }

    private void drawControls(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY) {
        final Slot hovered = getHoveredSlot();
        final int hoverIndex = hovered == null ? -1 : hovered.index;
        final int by = y + VoidRecyclerMenu.CONTROLS_Y;

        // divider between the buttons (left) and the upgrade slots (right)
        g.fill(x + 125, by - 1, x + 126, by + 17, PANEL_EDGE);

        // 0: batch amount
        button(g, x + 9, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_BATCH, NEON_CYAN);
        Component batchText = Component.literal("x" + this.menu.batchAmount());
        g.text(this.font, batchText, x + 9 + 8 - this.font.width(batchText) / 2, by + 4, NEON_CYAN, false);

        // 1: capsule -> block, 2: block -> capsule
        button(g, x + 27, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_DEPOSIT, NEON_CYAN);
        arrow(g, x + 27 + 8, by + 8, 1, NEON_CYAN);
        button(g, x + 45, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_WITHDRAW, NEON_ORANGE);
        arrow(g, x + 45 + 8, by + 8, -1, NEON_ORANGE);

        // 3: catalog
        button(g, x + 63, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_CATALOG || this.menu.catalog().isOpen(), NEON_ORANGE);
        gridIcon(g, x + 63 + 8, by + 8, this.menu.catalog().isOpen() ? NEON_ORANGE : TEXT_LIGHT);

        // 4: access mode
        int lockColor = accessColor();
        button(g, x + 81, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_ACCESS, lockColor);
        lockIcon(g, x + 81 + 8, by + 8, lockColor, this.menu.accessMode() == VoidRecyclerBlockEntity.ACCESS_OPEN);

        // 5: Resonance Link (shared pool of all linked blocks of the owner)
        int linkColor = this.menu.linked() ? NEON_CYAN : TEXT_GRAY;
        button(g, x + 99, by, hoverIndex == VoidRecyclerMenu.SLOT_BTN_LINK || this.menu.linked(), linkColor);
        linkIcon(g, x + 99 + 8, by + 8, this.menu.linked() ? NEON_CYAN : TEXT_LIGHT);

        // upgrade slots (real slots; the wells show a faint symbol while empty)
        final int ax = x + VoidRecyclerMenu.UPGRADE_AMP_X;
        final int tx = x + VoidRecyclerMenu.UPGRADE_THR_X;
        final boolean ampFull = this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER) > 0;
        final boolean thrFull = this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_THROUGHPUT) > 0;
        slotWell(g, ax - 1, by - 1, ampFull ? NEON_GREEN | 0xFF000000 : CYAN_DIM, 18);
        slotWell(g, tx - 1, by - 1, thrFull ? NEON_CYAN : CYAN_DIM, 18);
        // four pips above each upgrade slot show how many of the four are installed
        final int ampCount = this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_AMPLIFIER);
        final int thrCount = this.menu.upgradeCount(VoidRecyclerBlockEntity.UPGRADE_THROUGHPUT);
        for (int i = 0; i < 4; i++) {
            g.fill(ax + i * 4, by - 3, ax + i * 4 + 3, by - 1, i < ampCount ? NEON_GREEN | 0xFF000000 : SLOT_EDGE);
            g.fill(tx + i * 4, by - 3, tx + i * 4 + 3, by - 1, i < thrCount ? NEON_CYAN : SLOT_EDGE);
        }
        if (!ampFull) {
            plus(g, ax + 8, by + 8, CYAN_DIM);
        }
        if (!thrFull) {
            chevrons(g, tx + 8, by + 8, CYAN_DIM);
        }
    }

    /** A 18x18 button well around the 16x16 slot whose top-left is (sx, sy). */
    private static void button(GuiGraphicsExtractor g, int sx, int sy, boolean lit, int color) {
        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, lit ? 0xFF16161F : SLOT_BG);
        frame(g, sx - 1, sy - 1, sx + 17, sy + 17, lit ? color : SLOT_EDGE);
    }

    /** Vertical arrow centered at (cx, cy); dir 1 = pointing down (deposit), -1 = pointing up (withdraw). */
    private static void arrow(GuiGraphicsExtractor g, int cx, int cy, int dir, int color) {
        if (dir > 0) {
            g.fill(cx - 1, cy - 5, cx + 1, cy + 1, color);
            for (int i = 0; i < 4; i++) {
                g.fill(cx - 4 + i, cy + 1 + i, cx + 4 - i, cy + 2 + i, color);
            }
            g.fill(cx - 5, cy + 6, cx + 5, cy + 7, color); // the "tray" the points flow into
        } else {
            g.fill(cx - 1, cy - 1, cx + 1, cy + 5, color);
            for (int i = 0; i < 4; i++) {
                g.fill(cx - 4 + i, cy - 2 - i, cx + 4 - i, cy - 1 - i, color);
            }
            g.fill(cx - 5, cy + 6, cx + 5, cy + 7, color);
        }
    }

    private static void horizontalArrow(GuiGraphicsExtractor g, int cx, int cy, int dir, int color) {
        // triangle: dir 1 = right, -1 = left
        for (int i = 0; i < 4; i++) {
            if (dir > 0) {
                g.fill(cx - 2 + i, cy - 4 + i, cx - 1 + i, cy + 4 - i, color);
            } else {
                g.fill(cx + 1 - i, cy - 4 + i, cx + 2 - i, cy + 4 - i, color);
            }
        }
    }

    private static void gridIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                g.fill(cx - 5 + c * 4, cy - 5 + r * 4, cx - 2 + c * 4, cy - 2 + r * 4, color);
            }
        }
    }

    private static void lockIcon(GuiGraphicsExtractor g, int cx, int cy, int color, boolean open) {
        g.fill(cx - 4, cy - 1, cx + 4, cy + 5, color); // body
        g.fill(cx - 1, cy + 1, cx + 1, cy + 3, OLED_BLACK); // keyhole
        int top = open ? cy - 7 : cy - 5;
        g.fill(cx - 3, top, cx - 2, cy - 1, color); // left leg
        g.fill(cx - 3, top, cx + 3, top + 1, color); // arc
        g.fill(cx + 2, top, cx + 3, open ? cy - 4 : cy - 1, color); // right leg (shorter when open)
    }

    /** Two interlocked chain links. */
    private static void linkIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
        frame(g, cx - 6, cy - 4, cx + 1, cy + 2, color);
        frame(g, cx - 1, cy - 1, cx + 6, cy + 5, color);
    }

    private static void plus(GuiGraphicsExtractor g, int cx, int cy, int color) {
        g.fill(cx - 1, cy - 4, cx + 1, cy + 4, color);
        g.fill(cx - 4, cy - 1, cx + 4, cy + 1, color);
    }

    private static void chevrons(GuiGraphicsExtractor g, int cx, int cy, int color) {
        for (int k = 0; k < 2; k++) {
            int ox = cx - 5 + k * 5;
            for (int i = 0; i < 4; i++) {
                g.fill(ox + i, cy - 4 + i, ox + i + 1, cy - 3 + i, color);
                g.fill(ox + i, cy + 3 - i, ox + i + 1, cy + 4 - i, color);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Catalog panel (right side)
    // ---------------------------------------------------------------------------------------------------------

    private void drawCatalog(GuiGraphicsExtractor g, int x, int y) {
        final CatalogState catalog = this.menu.catalog();
        final int px = x + VoidRecyclerMenu.CAT_X;
        final int w = VoidRecyclerMenu.CAT_W;
        final Slot hovered = getHoveredSlot();
        final int hoverIndex = hovered == null ? -1 : hovered.index;

        frame(g, px - 1, y - 1, px + w + 1, y + IMAGE_H + 1, CYAN_GLOW);
        g.fill(px, y, px + w, y + IMAGE_H, PANEL_BG);
        frame(g, px, y, px + w, y + IMAGE_H, PANEL_EDGE);
        g.fill(px + 1, y + 1, px + w - 1, y + 15, TITLE_BG);
        g.fill(px + 1, y + 15, px + w - 1, y + 16, CYAN_DIM);
        text(g, Component.translatable("gui.void_recycler.catalog"), px + 8, y + 4, NEON_ORANGE);
        Component count = Component.literal(Economy.formatPoints(catalog.size()));
        g.text(this.font, count, px + w - 8 - this.font.width(count), y + 4, TEXT_GRAY, false);

        // toolbar: prev | page | next ... sort | filter
        final int by = y + VoidRecyclerMenu.CAT_BTN_Y;
        button(g, px + 3, by, hoverIndex == VoidRecyclerMenu.SLOT_CAT_PREV, NEON_CYAN);
        horizontalArrow(g, px + 3 + 8, by + 8, -1, NEON_CYAN);
        button(g, px + 21, by, hoverIndex == VoidRecyclerMenu.SLOT_CAT_NEXT, NEON_CYAN);
        horizontalArrow(g, px + 21 + 8, by + 8, 1, NEON_CYAN);
        Component pageText = Component.literal((catalog.page() + 1) + "/" + catalog.pageCount());
        g.text(this.font, pageText, px + 39 + (36 - this.font.width(pageText)) / 2, by + 4, TEXT_LIGHT, false);

        button(g, px + 75, by, hoverIndex == VoidRecyclerMenu.SLOT_CAT_SORT, NEON_ORANGE);
        sortIcon(g, px + 75 + 8, by + 8, catalog.sort(), NEON_ORANGE);
        button(g, px + 93, by, hoverIndex == VoidRecyclerMenu.SLOT_CAT_FILTER || catalog.affordableOnly(), NEON_ORANGE);
        funnelIcon(g, px + 93 + 8, by + 8, catalog.affordableOnly() ? NEON_ORANGE : TEXT_LIGHT);

        // grid wells (the current target is outlined in orange)
        final Item target = this.menu.target();
        for (int i = 0; i < CatalogState.PER_PAGE; i++) {
            int cx = x + VoidRecyclerMenu.CAT_GRID_X + (i % CatalogState.COLS) * 18;
            int cy = y + VoidRecyclerMenu.CAT_GRID_Y + (i / CatalogState.COLS) * 18;
            Item item = catalog.itemAt(i);
            boolean selected = item != null && item == target;
            slotWell(g, cx - 1, cy - 1, selected ? NEON_ORANGE : (item == null ? PANEL_EDGE : SLOT_EDGE), 18);
        }
        if (catalog.size() == 0) {
            centeredFit(g, Component.translatable("gui.void_recycler.catalog.empty"), px + w / 2, y + VoidRecyclerMenu.CAT_GRID_Y + 56, TEXT_GRAY, w - 12);
        }

        // footer: what is under the mouse
        final int fy = y + 176;
        g.fill(px + 6, fy - 4, px + w - 6, fy - 3, PANEL_EDGE);
        Component line1 = null;
        Component line2 = null;
        int c1 = TEXT_LIGHT;
        int c2 = TEXT_GRAY;
        if (hovered != null && VoidRecyclerMenu.isCatalogCell(hovered.index) && hovered.hasItem()) {
            ItemStack stack = hovered.getItem();
            line1 = stack.getHoverName();
            c1 = NEON_ORANGE;
            long price = Economy.fabricationPrice(stack.getItem());
            line2 = Component.translatable("gui.void_recycler.catalog.cost", Economy.formatPoints(price));
            c2 = price <= this.menu.points() ? NEON_CYAN : NEON_RED;
        } else if (hoverIndex == VoidRecyclerMenu.SLOT_CAT_SORT) {
            line1 = Component.translatable("gui.void_recycler.catalog.sort");
            line2 = Component.translatable("gui.void_recycler.catalog.sort." + catalog.sort().ordinal());
            c1 = NEON_ORANGE;
        } else if (hoverIndex == VoidRecyclerMenu.SLOT_CAT_FILTER) {
            line1 = Component.translatable("gui.void_recycler.catalog.filter");
            line2 = Component.translatable(catalog.affordableOnly() ? "gui.void_recycler.catalog.filter.on" : "gui.void_recycler.catalog.filter.off");
            c1 = NEON_ORANGE;
        } else if (hoverIndex == VoidRecyclerMenu.SLOT_CAT_PREV || hoverIndex == VoidRecyclerMenu.SLOT_CAT_NEXT) {
            line1 = Component.translatable("gui.void_recycler.catalog.page");
            line2 = Component.translatable("gui.void_recycler.catalog.wheel");
            c1 = NEON_CYAN;
        } else {
            line1 = Component.translatable("gui.void_recycler.catalog.pick");
            line2 = Component.translatable("gui.void_recycler.catalog.sort." + catalog.sort().ordinal());
        }
        centeredFit(g, line1, px + w / 2, fy, c1, w - 12);
        centeredFit(g, line2, px + w / 2, fy + 12, c2, w - 12);
        if (hovered == null || !VoidRecyclerMenu.isCatalogCell(hovered.index)) {
            Component filter = Component.translatable(catalog.affordableOnly() ? "gui.void_recycler.catalog.filter.on" : "gui.void_recycler.catalog.filter.off");
            centeredFit(g, filter, px + w / 2, fy + 24, catalog.affordableOnly() ? NEON_ORANGE : TEXT_GRAY, w - 12);
        }
    }

    private static void sortIcon(GuiGraphicsExtractor g, int cx, int cy, CatalogState.Sort sort, int color) {
        switch (sort) {
            case NAME -> { // "A-Z": stair of bars, short on top
                g.fill(cx - 5, cy - 4, cx - 1, cy - 2, color);
                g.fill(cx - 5, cy - 1, cx + 2, cy + 1, color);
                g.fill(cx - 5, cy + 2, cx + 5, cy + 4, color);
            }
            case PRICE_ASC -> { // short bars growing downwards + up arrow
                g.fill(cx - 5, cy - 4, cx - 1, cy - 2, color);
                g.fill(cx - 5, cy - 1, cx + 1, cy + 1, color);
                g.fill(cx - 5, cy + 2, cx + 3, cy + 4, color);
                g.fill(cx + 4, cy - 4, cx + 5, cy + 4, color);
            }
            case PRICE_DESC -> {
                g.fill(cx - 5, cy - 4, cx + 3, cy - 2, color);
                g.fill(cx - 5, cy - 1, cx + 1, cy + 1, color);
                g.fill(cx - 5, cy + 2, cx - 1, cy + 4, color);
                g.fill(cx + 4, cy - 4, cx + 5, cy + 4, color);
            }
        }
    }

    private static void funnelIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
        g.fill(cx - 5, cy - 4, cx + 5, cy - 2, color);
        g.fill(cx - 3, cy - 2, cx + 3, cy, color);
        g.fill(cx - 1, cy, cx + 1, cy + 5, color);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Points (in thousandths) that recycling the whole stack would yield with the current efficiency. */
    private long stackGainMilli(ItemStack stack) {
        return Economy.satMul(Economy.satMul(Economy.rawRecyclingValue(stack.getItem()), stack.getCount()),
                this.menu.efficiencyMilli());
    }

    private void text(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
        g.text(this.font, text, x, y, color, false);
    }

    private void centered(GuiGraphicsExtractor g, Component text, int centerX, int y, int color) {
        g.text(this.font, text, centerX - this.font.width(text) / 2, y, color, false);
    }

    /** Centered text that is scaled down instead of overflowing the panel. */
    private void centeredFit(GuiGraphicsExtractor g, Component text, int centerX, int y, int color, int maxWidth) {
        int width = this.font.width(text);
        if (width <= maxWidth) {
            centered(g, text, centerX, y, color);
            return;
        }
        float scale = (float) maxWidth / (float) width;
        g.pose().pushMatrix();
        g.pose().translate(centerX - maxWidth / 2.0F, y + (1.0F - scale) * 4.0F);
        g.pose().scale(scale, scale);
        g.text(this.font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** 1px border, right/bottom exclusive like {@code fill}. */
    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** L-shaped neon bracket whose corner sits at (x, y); dx/dy point into the panel. */
    private static void corner(GuiGraphicsExtractor g, int x, int y, int len, int dx, int dy) {
        int hx0 = dx > 0 ? x : x - len;
        int hx1 = dx > 0 ? x + len : x;
        int vy0 = dy > 0 ? y : y - len;
        int vy1 = dy > 0 ? y + len : y;
        int hy = dy > 0 ? y : y - 1;
        int vx = dx > 0 ? x : x - 1;
        g.fill(hx0, hy, hx1, hy + 1, NEON_CYAN);
        g.fill(vx, vy0, vx + 1, vy1, NEON_CYAN);
    }

    /** Dark recessed well with a coloured 1px edge. */
    private static void slotWell(GuiGraphicsExtractor g, int x, int y, int edge, int size) {
        g.fill(x, y, x + size, y + size, SLOT_BG);
        g.fill(x + 1, y + 1, x + size - 1, y + 2, OLED_BLACK); // inner shadow, top
        g.fill(x + 1, y + 1, x + 2, y + size - 1, OLED_BLACK); // inner shadow, left
        frame(g, x, y, x + size, y + size, edge);
    }

    private static int lerpColor(int from, int to, double t) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int gr = (int) (((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }
}
