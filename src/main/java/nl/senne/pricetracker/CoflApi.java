package nl.senne.pricetracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nl.senne.pricetracker.model.PriceModels.BazaarPoint;
import nl.senne.pricetracker.model.PriceModels.BazaarSnapshot;
import nl.senne.pricetracker.model.PriceModels.ItemPrice;
import nl.senne.pricetracker.model.PriceModels.LowestBin;
import nl.senne.pricetracker.model.PriceModels.Market;
import nl.senne.pricetracker.model.PriceModels.PricePoint;
import nl.senne.pricetracker.model.PriceModels.Range;
import nl.senne.pricetracker.model.PriceModels.Result;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin async client for the public Coflnet SkyBlock API (https://sky.coflnet.com).
 * No API key required. Coflnet is used because it exposes price *history*,
 * which the official Hypixel API does not.
 */
public final class CoflApi {

    private static final String BASE = "https://sky.coflnet.com/api";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_MS = 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Bound concurrent requests so several live windows can't burst past Coflnet's rate limit.
    private static final java.util.concurrent.Semaphore GATE = new java.util.concurrent.Semaphore(4);
    private static final java.util.concurrent.Executor GATE_EXEC =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "skyquant-http");
                t.setDaemon(true);
                return t;
            });

    private record Cached(long at, Result result) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private CoflApi() {
    }

    /**
     * Resolve everything the lookup screen needs for one item.
     * Detects Bazaar vs Auction House automatically, then fetches the matching
     * current price and history for the requested range.
     */
    public static CompletableFuture<Result> lookup(String tag, String displayName, Range range) {
        String key = tag + "|" + range.name();
        Cached c = CACHE.get(key);
        if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(c.result);
        }

        // NPC sell price (for reference); shared by both market branches.
        CompletableFuture<Double> npcF = getJson("/item/" + enc(tag) + "/details")
                .thenApply(CoflApi::parseNpc)
                .exceptionally(e -> 0.0);

        return getJson("/bazaar/" + enc(tag) + "/snapshot")
                .exceptionally(e -> null) // a failed/404 snapshot just means "not a bazaar item"
                .thenCompose(snapEl -> {
                    BazaarSnapshot snap = parseBazaarSnapshot(snapEl);
                    if (snap != null) {
                        // Bazaar item: current snapshot + buy/sell history for the range.
                        CompletableFuture<List<BazaarPoint>> histF = getJson(bazaarHistoryPath(tag, range))
                                .thenApply(CoflApi::parseBazaarHistory)
                                .exceptionally(e -> List.<BazaarPoint>of());
                        return histF.thenCombine(npcF, (hist, npc) -> new Result(tag, displayName,
                                Market.BAZAAR, snap, hist, null, null, null, npc));
                    }
                    // Auction House item: lowest live BIN + sold-price aggregate + sold history.
                    long cutoff = System.currentTimeMillis() - range.span.toMillis();
                    CompletableFuture<LowestBin> binF = getJson("/auctions/tag/" + enc(tag) + "/active/bin")
                            .thenApply(CoflApi::parseLowestBin)
                            .exceptionally(e -> null);
                    CompletableFuture<ItemPrice> priceF = getJson("/item/price/" + enc(tag))
                            .thenApply(CoflApi::parseItemPrice)
                            .exceptionally(e -> null);
                    CompletableFuture<List<PricePoint>> histF = getJson(auctionHistoryPath(tag, range))
                            .thenApply(CoflApi::parsePriceHistory)
                            .exceptionally(e -> List.<PricePoint>of())
                            .thenApply(list -> windowByTime(list, cutoff, range));

                    return CompletableFuture.allOf(binF, priceF, histF, npcF).thenApply(v -> {
                        LowestBin bin = binF.join();
                        ItemPrice price = priceF.join();
                        List<PricePoint> hist = histF.join();
                        Market m = (bin != null || price != null || !hist.isEmpty())
                                ? Market.AUCTION : Market.UNKNOWN;
                        return new Result(tag, displayName, m, null, null, bin, price, hist, npcF.join());
                    });
                })
                .whenComplete((res, err) -> {
                    if (res != null) CACHE.put(key, new Cached(System.currentTimeMillis(), res));
                });
    }

    private static final DateTimeFormatter REQ = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Bazaar: day/week are fixed paths; longer ranges use the custom start/end query. */
    private static String bazaarHistoryPath(String tag, Range range) {
        return switch (range) {
            case DAY -> "/bazaar/" + enc(tag) + "/history/day";
            case WEEK -> "/bazaar/" + enc(tag) + "/history/week";
            default -> "/bazaar/" + enc(tag) + "/history?start=" + isoAgo(range) + "&end=" + isoNow();
        };
    }

    /** Auction: day/week/month are fixed paths; 6mo/1y pull the full series and get windowed client-side. */
    private static String auctionHistoryPath(String tag, Range range) {
        return switch (range) {
            case DAY -> "/item/price/" + enc(tag) + "/history/day";
            case WEEK -> "/item/price/" + enc(tag) + "/history/week";
            case MONTH -> "/item/price/" + enc(tag) + "/history/month";
            default -> "/item/price/" + enc(tag) + "/history/full";
        };
    }

    private static List<PricePoint> windowByTime(List<PricePoint> in, long cutoff, Range range) {
        if (range == Range.DAY || range == Range.WEEK || range == Range.MONTH) {
            return in; // already scoped by the endpoint
        }
        List<PricePoint> out = new ArrayList<>();
        for (PricePoint p : in) {
            if (p.timeMillis() >= cutoff) out.add(p);
        }
        return out;
    }

    private static String isoNow() {
        return enc(LocalDateTime.now(ZoneOffset.UTC).format(REQ));
    }

    private static String isoAgo(Range range) {
        return enc(LocalDateTime.now(ZoneOffset.UTC).minus(range.span).format(REQ));
    }

    // ---- HTTP -------------------------------------------------------------

    private static CompletableFuture<JsonElement> getJson(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "skyquant-mod/1.0 (Fabric)")
                .GET()
                .build();
        // Acquire the permit off the render thread, then fire the request; release on completion.
        return CompletableFuture.runAsync(GATE::acquireUninterruptibly, GATE_EXEC)
                .thenCompose(v -> HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                        .whenComplete((r, e) -> GATE.release()))
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) {
                        throw new RuntimeException("rate limited (429)");
                    }
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("HTTP " + resp.statusCode() + " for " + path);
                    }
                    return JsonParser.parseString(resp.body());
                });
    }

    private static String enc(String tag) {
        return URLEncoder.encode(tag, StandardCharsets.UTF_8);
    }

    // ---- Parsing ----------------------------------------------------------

    private static BazaarSnapshot parseBazaarSnapshot(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        // A real bazaar product carries a productId and non-zero order data.
        if (!o.has("productId")) return null;
        double buy = d(o, "buyPrice");
        double sell = d(o, "sellPrice");
        if (buy <= 0 && sell <= 0) return null;
        return new BazaarSnapshot(buy, sell,
                l(o, "buyVolume"), l(o, "sellVolume"),
                l(o, "buyMovingWeek"), l(o, "sellMovingWeek"),
                l(o, "buyOrdersCount"), l(o, "sellOrdersCount"));
    }

    private static double parseNpc(JsonElement el) {
        if (el == null || !el.isJsonObject()) return 0.0;
        return d(el.getAsJsonObject(), "npcSellPrice");
    }

    private static List<BazaarPoint> parseBazaarHistory(JsonElement el) {
        List<BazaarPoint> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(new BazaarPoint(millis(o, "timestamp"), d(o, "buy"), d(o, "sell")));
        }
        out.sort((a, b) -> Long.compare(a.timeMillis(), b.timeMillis()));
        return out;
    }

    private static LowestBin parseLowestBin(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        if (arr.isEmpty()) return null;
        List<Long> bids = new ArrayList<>();
        String name = null;
        long best = Long.MAX_VALUE;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            long bid = l(o, "startingBid");
            if (bid <= 0) continue;
            bids.add(bid);
            if (bid < best) {
                best = bid;
                name = o.has("itemName") && !o.get("itemName").isJsonNull()
                        ? o.get("itemName").getAsString() : name;
            }
        }
        if (bids.isEmpty()) return null;
        bids.sort(Long::compare);
        return new LowestBin(name, bids.get(0),
                bids.size() > 1 ? bids.get(1) : 0,
                bids.size() > 2 ? bids.get(2) : 0,
                bids.size());
    }

    private static ItemPrice parseItemPrice(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        double median = d(o, "median"), mean = d(o, "mean"), max = d(o, "max"), volume = d(o, "volume");
        if (median <= 0 && mean <= 0 && max <= 0 && volume <= 0) {
            return null; // all-zero = no real sales data (e.g. untradeable items)
        }
        return new ItemPrice(d(o, "min"), median, mean, max, volume);
    }

    private static List<PricePoint> parsePriceHistory(JsonElement el) {
        List<PricePoint> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(new PricePoint(millis(o, "time"), d(o, "min"), d(o, "max"), d(o, "avg"), d(o, "volume")));
        }
        out.sort((a, b) -> Long.compare(a.timeMillis(), b.timeMillis()));
        return out;
    }

    // ---- small helpers ----------------------------------------------------

    private static double d(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0.0;
    }

    private static long l(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
    }

    private static long millis(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return 0L;
        String s = o.get(k).getAsString();
        try {
            return LocalDateTime.parse(s, REQ).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ex) {
            try {
                return java.time.Instant.parse(s).toEpochMilli(); // zoned/offset fallback
            } catch (Exception ex2) {
                return 0L;
            }
        }
    }
}
