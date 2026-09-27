package nl.senne.pricetracker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import nl.senne.pricetracker.config.PriceConfig;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PriceTracker implements ClientModInitializer {

    public static final String MOD_ID = "price-tracker";
    public static final Logger LOG = LoggerFactory.getLogger("price-tracker");

    private static KeyMapping lookupKey;

    @Override
    public void onInitializeClient() {
        PriceConfig.register();
        lookupKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.price-tracker.lookup",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                KeyMapping.Category.MISC));
        Commands.register();
        ItemCatalog.ensureLoaded();
        PriceOverlay.restore();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PriceOverlay.save());
        LOG.info("[price-tracker] initialized; lookup keybind registered (default Y)");

        // Draw/interact the overlay on container screens (game GUIs) and our own host
        // screen (opened by /graph). Fabric resets per-screen listeners each init.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            boolean hosts = screen instanceof AbstractContainerScreen<?> || screen instanceof PriceHostScreen;
            if (!hosts) {
                return;
            }
            ScreenEvents.afterExtract(screen).register(
                    (scr, gfx, mouseX, mouseY, tickDelta) -> PriceOverlay.render(gfx, mouseX, mouseY));
            // Return false to cancel vanilla handling when the overlay ate the click.
            ScreenMouseEvents.allowMouseClick(screen).register(
                    (scr, mouseEvent) -> !PriceOverlay.handleClick(mouseEvent.button()));
            ScreenMouseEvents.afterMouseRelease(screen).register((scr, mouseEvent, handled) -> {
                PriceOverlay.handleRelease();
                return handled;
            });
            // Keybind lookup only where there's a hovered slot.
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> {
                    if (lookupKey.matches(keyEvent) && scr instanceof AbstractContainerScreen<?> container) {
                        onLookup(client, container);
                    }
                });
            }
        });
    }

    private static void onLookup(Minecraft client, AbstractContainerScreen<?> container) {
        Slot slot = container.hoveredSlot;
        if (slot == null || !slot.hasItem()) {
            return;
        }
        ItemStack stack = slot.getItem();
        String id = ItemResolver.skyblockId(stack);
        if (id == null) {
            if (PriceConfig.get().debugItemNbt) {
                LOG.info("[price-tracker] unresolved item; custom_data={}", ItemResolver.debugCustomData(stack));
            }
            if (client.player != null) {
                String label = stack.getHoverName().getString().replaceAll("(?i)§[0-9A-FK-OR]", "");
                client.player.sendSystemMessage(Component.literal(
                        "§c[Price Tracker] Couldn't find a SkyBlock price for §f" + label));
            }
            return;
        }
        PriceOverlay.show(id, ItemResolver.displayName(stack, id));
    }
}
