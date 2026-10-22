package dev.voidrecycler.integration.jei;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Draws one line of text that is shrunk to fit a maximum width instead of spilling out of the JEI recipe box. */
final class JeiText {
    private JeiText() {}

    static void fit(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, int maxWidth) {
        int width = font.width(text);
        if (width <= maxWidth) {
            g.text(font, text, x, y, color, false);
            return;
        }
        float scale = (float) maxWidth / (float) width;
        g.pose().pushMatrix();
        g.pose().translate(x, y + (1.0F - scale) * 4.0F);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }
}
