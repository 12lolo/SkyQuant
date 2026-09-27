package nl.senne.pricetracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saves/restores the set of open price windows across game sessions. */
public final class Persistence {

    /** Serialized state of one window. */
    public static class WindowState {
        public String tag;
        public String name;
        public int px;
        public int py;
        public int width;
        public int height;
        public boolean pinned;
        public boolean compact;
        public String range;
    }

    public static class Layout {
        public List<WindowState> windows = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Persistence() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("price-tracker-windows.json");
    }

    public static Layout load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Layout l = GSON.fromJson(Files.readString(f), Layout.class);
                if (l != null && l.windows != null) {
                    return l;
                }
            }
        } catch (Exception ignored) {
            // corrupt/unreadable — start fresh
        }
        return new Layout();
    }

    public static void save(Layout layout) {
        try {
            Files.writeString(file(), GSON.toJson(layout));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
