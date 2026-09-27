package nl.senne.pricetracker.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import nl.senne.pricetracker.model.PriceModels.Range;

@Config(name = "price-tracker")
public class PriceConfig implements ConfigData {

    @ConfigEntry.Gui.Tooltip
    public Range defaultRange = Range.DAY;

    @ConfigEntry.BoundedDiscrete(min = 10, max = 300)
    @ConfigEntry.Gui.Tooltip
    public int refreshSeconds = 30;

    @ConfigEntry.Gui.Tooltip
    public boolean persistWindows = true;

    @ConfigEntry.Gui.Tooltip
    public boolean debugItemNbt = false;

    @ConfigEntry.Gui.CollapsibleObject
    public DetailLines details = new DetailLines();

    public static class DetailLines {
        @ConfigEntry.Gui.Tooltip public boolean change = true;
        @ConfigEntry.Gui.Tooltip public boolean volume = true;
        @ConfigEntry.Gui.Tooltip public boolean orders = true;
        @ConfigEntry.Gui.Tooltip public boolean margin = true;
        @ConfigEntry.Gui.Tooltip public boolean lowHigh = true;
        @ConfigEntry.Gui.Tooltip public boolean liquidity = true;
        @ConfigEntry.Gui.Tooltip public boolean binDepth = true;
        @ConfigEntry.Gui.Tooltip public boolean npc = true;
    }

    private static boolean registered;

    /** Register with AutoConfig once (call from the mod initializer). */
    public static void register() {
        if (!registered) {
            AutoConfig.register(PriceConfig.class, GsonConfigSerializer::new);
            registered = true;
        }
    }

    public static PriceConfig get() {
        register();
        return AutoConfig.getConfigHolder(PriceConfig.class).getConfig();
    }
}
