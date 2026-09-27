package nl.senne.pricetracker;

import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;

/** Reads Hypixel SkyBlock metadata off an {@link ItemStack}. */
public final class ItemResolver {

    private ItemResolver() {
    }

    /**
     * The SkyBlock item id (e.g. "HYPERION", "ENCHANTED_DIAMOND"), stored by Hypixel
     * in the item's custom NBT under ExtraAttributes.id. Returns null for non-SkyBlock items.
     */
    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return null;
        }
        CompoundTag root = custom.copyTag();
        CompoundTag extra = root.getCompoundOrEmpty("ExtraAttributes");

        // On 26.x Hypixel flattens the id (and enchantments/petInfo) to the root of
        // custom_data; older data nested them under ExtraAttributes — read both.
        String base = firstNonEmpty(root.getStringOr("id", ""), extra.getStringOr("id", ""));
        if (base.isEmpty()) {
            return null;
        }
        base = base.toUpperCase(Locale.ROOT).replace(":", "-");

        // A few "container" ids don't price under their generic id; re-derive from NBT.
        // Tag formats verified against the Coflnet API.
        switch (base) {
            case "ENCHANTED_BOOK" -> {
                CompoundTag ench = nonEmpty(root.getCompoundOrEmpty("enchantments"),
                        extra.getCompoundOrEmpty("enchantments"));
                if (ench.size() == 1) { // single-enchant books map to a bazaar product
                    String k = ench.keySet().iterator().next();
                    return "ENCHANTMENT_" + k.toUpperCase(Locale.ROOT) + "_" + ench.getIntOr(k, 0);
                }
            }
            case "PET" -> {
                String type = petType(firstNonEmpty(root.getStringOr("petInfo", ""),
                        extra.getStringOr("petInfo", "")));
                if (type != null) {
                    return "PET_" + type.toUpperCase(Locale.ROOT); // aggregate over tiers (Coflnet has no tier tag)
                }
            }
            case "RUNE", "UNIQUE_RUNE" -> {
                CompoundTag runes = nonEmpty(root.getCompoundOrEmpty("runes"),
                        extra.getCompoundOrEmpty("runes"));
                if (!runes.isEmpty()) {
                    String k = runes.keySet().iterator().next();
                    return "RUNE_" + k.toUpperCase(Locale.ROOT); // Coflnet tag has no level
                }
            }
            default -> {
            }
        }
        return base;
    }

    private static String firstNonEmpty(String a, String b) {
        a = a == null ? "" : a.trim();
        if (!a.isEmpty()) {
            return a;
        }
        return b == null ? "" : b.trim();
    }

    private static CompoundTag nonEmpty(CompoundTag a, CompoundTag b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** Extract the "type" field from a pet's petInfo JSON string. */
    private static String petType(String petInfo) {
        if (petInfo == null || petInfo.isBlank()) {
            return null;
        }
        try {
            var o = JsonParser.parseString(petInfo).getAsJsonObject();
            if (o.has("type") && !o.get("type").isJsonNull()) {
                return o.get("type").getAsString();
            }
        } catch (Exception ignored) {
            // malformed petInfo — fall through
        }
        return null;
    }

    /** Debug: the item's raw custom-data NBT (SNBT), or a note if absent. */
    public static String debugCustomData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return "no CUSTOM_DATA (item=" + stack.getItem() + ")";
        }
        return custom.copyTag().toString();
    }

    /** Human-readable name shown in the item's tooltip, colour codes stripped. */
    public static String displayName(ItemStack stack, String fallback) {
        if (stack == null || stack.isEmpty()) {
            return fallback;
        }
        String name = stack.getHoverName().getString();
        name = name.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        return name.isEmpty() ? fallback : name;
    }
}
