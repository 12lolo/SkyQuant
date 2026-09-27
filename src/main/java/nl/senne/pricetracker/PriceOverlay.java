package nl.senne.pricetracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Manager for the set of open {@link PriceWindow}s. Handles layering, per-frame
 * rendering (with per-window position + scale), click routing and the "pin to spawn
 * a second window" behaviour.
 */
public final class PriceOverlay {

    private static final List<PriceWindow> windows = new ArrayList<>();
    private static int lastMouseX;
    private static int lastMouseY;
    private static long lastSaveMs;
    // Last GUI-scaled screen size we rendered at, to detect resolution / gui-scale changes.
    private static int lastSw;
    private static int lastSh;

    private PriceOverlay() {
    }

    /** Recreate persisted windows on startup (if enabled). */
    public static void restore() {
        if (!nl.senne.pricetracker.config.PriceConfig.get().persistWindows) return;
        for (Persistence.WindowState s : Persistence.load().windows) {
            if (s != null && s.tag != null) {
                windows.add(new PriceWindow(s));
            }
        }
    }

    /** Write the current layout to disk. */
    public static void save() {
        if (!nl.senne.pricetracker.config.PriceConfig.get().persistWindows) return;
        Persistence.Layout layout = new Persistence.Layout();
        for (PriceWindow w : windows) {
            layout.windows.add(w.toState());
        }
        Persistence.save(layout);
    }

    /**
     * Pressing the keybind on an item: reuse the front-most UNPINNED window (retarget it),
     * otherwise open a new window. Pinning a window therefore makes the next lookup a new one.
     */
    public static void show(String tag, String name) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            PriceWindow w = windows.get(i);
            if (!w.isPinned()) {
                w.retarget(tag, name);
                windows.remove(i);
                windows.add(w); // bring to front
                return;
            }
        }
        int n = windows.size();
        windows.add(new PriceWindow(tag, name, 30 + (n * 20) % 140, 30 + (n * 20) % 120));
    }

    public static void render(GuiGraphicsExtractor gfx, int mouseX, int mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        windows.removeIf(PriceWindow::wantClose);

        // Persist layout periodically so moves/resizes survive even without a clean exit.
        if (System.currentTimeMillis() - lastSaveMs > 5000) {
            lastSaveMs = System.currentTimeMillis();
            save();
        }

        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        // Screen size changed (resolution or GUI-scale switch): reflow open windows so they keep
        // the same relative size/position instead of staying at their old pixel dimensions.
        if (!windows.isEmpty() && lastSw > 0 && lastSh > 0 && (sw != lastSw || sh != lastSh)) {
            for (PriceWindow w : windows) w.reflow(lastSw, lastSh, sw, sh);
        }
        lastSw = sw;
        lastSh = sh;
        if (windows.isEmpty()) return;

        Matrix3x2fStack pose = gfx.pose();

        for (PriceWindow w : windows) {
            w.tickRefresh();
            w.updateInteract(mouseX, mouseY, sw, sh);
            pose.pushMatrix();
            pose.translate(w.px, w.py);
            w.render(gfx, mc.font, mouseX - w.px, mouseY - w.py);
            pose.popMatrix();
        }
    }

    /** @return true if a window consumed the click (so it must not reach the slots underneath). */
    public static boolean handleClick(int button) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            PriceWindow w = windows.get(i);
            if (lastMouseX >= w.px && lastMouseX < w.px + w.width()
                    && lastMouseY >= w.py && lastMouseY < w.py + w.height()) {
                boolean consumed = w.handleClick(lastMouseX, lastMouseY, lastMouseX - w.px, lastMouseY - w.py, button);
                windows.remove(i);
                windows.add(w); // bring to front
                return consumed;
            }
        }
        return false;
    }

    public static void handleRelease() {
        for (PriceWindow w : windows) {
            w.stopInteract();
        }
    }
}
