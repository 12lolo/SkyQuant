package nl.senne.pricetracker.model;

import java.time.Duration;
import java.util.List;

/**
 * Plain data holders for Coflnet SkyBlock API responses.
 * Kept deliberately minimal — only the fields the UI uses.
 */
public final class PriceModels {

    private PriceModels() {
    }

    public enum Market {
        BAZAAR,
        AUCTION,
        UNKNOWN
    }

    public enum Range {
        DAY("1d", Duration.ofDays(1)),
        WEEK("1w", Duration.ofDays(7)),
        MONTH("1mo", Duration.ofDays(30)),
        HALF_YEAR("6mo", Duration.ofDays(182)),
        YEAR("1y", Duration.ofDays(365));

        public final String label;
        public final Duration span;

        Range(String label, Duration span) {
            this.label = label;
            this.span = span;
        }
    }

    /** GET /api/bazaar/{tag}/snapshot */
    public record BazaarSnapshot(double buyPrice, double sellPrice,
                                 long buyVolume, long sellVolume,
                                 long buyMovingWeek, long sellMovingWeek,
                                 long buyOrders, long sellOrders) {
    }

    /** One point of GET /api/bazaar/{tag}/history/{range} */
    public record BazaarPoint(long timeMillis, double buy, double sell) {
    }

    /** GET /api/item/price/{tag} — aggregate of recent sold auctions. */
    public record ItemPrice(double min, double median, double mean, double max, double volume) {
    }

    /** One point of GET /api/item/price/{tag}/history/{range} */
    public record PricePoint(long timeMillis, double min, double max, double avg, double volume) {
    }

    /** GET /api/auctions/tag/{tag}/active/bin — the lowest live BINs (endpoint returns up to ~10). */
    public record LowestBin(String itemName, long price, long second, long third, int listed) {
    }

    /**
     * Everything the lookup screen needs, resolved asynchronously.
     * Whichever fields apply to the item's market are populated; the rest are null.
     */
    public record Result(String tag,
                         String displayName,
                         Market market,
                         BazaarSnapshot bazaar,
                         List<BazaarPoint> bazaarHistory,
                         LowestBin lowestBin,
                         ItemPrice auctionPrice,
                         List<PricePoint> auctionHistory,
                         double npcSell) {
    }
}
