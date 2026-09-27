package nl.senne.pricetracker;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * /graph &lt;item name&gt; (alias /watch): opens the price graph for any item, with
 * autocomplete backed by {@link ItemCatalog}.
 */
public final class Commands {

    private static final String[] NAMES = {"graph", "watch"};

    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            for (String name : NAMES) {
                dispatcher.register(ClientCommands.literal(name)
                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    for (String s : ItemCatalog.suggest(builder.getRemaining(), 50)) {
                                        builder.suggest(s);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    open(ctx.getSource(), StringArgumentType.getString(ctx, "item"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal("§eUsage: /" + name + " <item name>"));
                            return 1;
                        }));
            }
        });
    }

    private static void open(FabricClientCommandSource source, String text) {
        if (!ItemCatalog.isLoaded()) {
            source.sendFeedback(Component.literal("§e[Price Tracker] Item list still loading, try again in a moment…"));
            ItemCatalog.ensureLoaded();
            return;
        }
        ItemCatalog.Entry e = ItemCatalog.resolve(text);
        if (e == null) {
            source.sendFeedback(Component.literal("§c[Price Tracker] No item matching §f" + text));
            return;
        }
        PriceOverlay.show(e.tag(), e.name());
        Minecraft mc = Minecraft.getInstance();
        // Open a host screen so the window is visible immediately (deferred so chat closes first).
        mc.execute(() -> mc.setScreenAndShow(new PriceHostScreen()));
    }
}
