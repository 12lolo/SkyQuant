package nl.senne.pricetracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The full SkyBlock item list (name + Coflnet tag), fetched once from
 * https://sky.coflnet.com/api/items and cached for command autocomplete.
 * This is the equivalent of SkyHanni's use of the NEU item repo.
 */
public final class ItemCatalog {

    public record Entry(String name, String tag) {
    }

    private static volatile List<Entry> entries = List.of();
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private ItemCatalog() {
    }

    public static boolean isLoaded() {
        return !entries.isEmpty();
    }

    /** Fetch the catalog once (no-op if already loaded or in progress). */
    public static void ensureLoaded() {
        if (isLoaded() || !loading.compareAndSet(false, true)) {
            return;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://sky.coflnet.com/api/items"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "price-tracker-mod/1.0 (Fabric)")
                .GET()
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> JsonParser.parseString(resp.body()))
                .whenComplete((el, err) -> {
                    try {
                        if (err == null && el != null && el.isJsonArray()) {
                            entries = parse(el.getAsJsonArray());
                            PriceTracker.LOG.info("[price-tracker] item catalog loaded: {} items", entries.size());
                        } else {
                            PriceTracker.LOG.warn("[price-tracker] item catalog failed to load", err);
                        }
                    } finally {
                        loading.set(false);
                    }
                });
    }

    private static List<Entry> parse(JsonArray arr) {
        List<Entry> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("name") || !o.has("tag")) continue;
            String name = o.get("name").isJsonNull() ? "" : o.get("name").getAsString();
            String tag = o.get("tag").isJsonNull() ? "" : o.get("tag").getAsString();
            if (name.isEmpty() || tag.isEmpty() || "null".equals(name)) continue;
            out.add(new Entry(name, tag));
        }
        return out;
    }

    /** Names containing the query (case-insensitive), prefix matches first, capped at {@code limit}. */
    public static List<String> suggest(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (Entry e : entries) {
            String ln = e.name.toLowerCase(Locale.ROOT);
            if (q.isEmpty() || ln.startsWith(q)) {
                prefix.add(e.name);
            } else if (ln.contains(q)) {
                contains.add(e.name);
            }
            if (prefix.size() >= limit) break;
        }
        for (String s : contains) {
            if (prefix.size() >= limit) break;
            prefix.add(s);
        }
        return prefix;
    }

    /** Resolve free text to an item: exact name, else prefix, else contains. */
    public static Entry resolve(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return null;
        Entry prefix = null, contains = null;
        for (Entry e : entries) {
            String ln = e.name.toLowerCase(Locale.ROOT);
            if (ln.equals(t)) return e;
            if (prefix == null && ln.startsWith(t)) prefix = e;
            else if (contains == null && ln.contains(t)) contains = e;
        }
        return prefix != null ? prefix : contains;
    }
}
