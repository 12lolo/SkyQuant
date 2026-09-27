package nl.senne.pricetracker;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A transparent host screen opened by the /graph command so overlay windows are visible
 * and interactive even when no container GUI is open. The windows themselves are drawn
 * by the shared ScreenEvents.afterExtract hook.
 */
public class PriceHostScreen extends Screen {

    public PriceHostScreen() {
        super(Component.literal("SkyQuant"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        // no dim/blur — keep the game visible behind the windows
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
