package nl.senne.pricetracker;

/** Coin/number formatting helpers. */
public final class Format {

    private Format() {
    }

    /** Compact coin amount, e.g. 1_234_567 -> "1.23M", 3400 -> "3.4k". */
    public static String coins(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000L) return trim(v / 1_000_000_000L) + "B";
        if (a >= 1_000_000L) return trim(v / 1_000_000L) + "M";
        if (a >= 1_000L) return trim(v / 1_000L) + "k";
        return trim(v);
    }

    /** Exact grouped amount, e.g. "1,234,567". */
    public static String exact(double v) {
        return String.format("%,d", Math.round(v));
    }

    private static String trim(double v) {
        String s = String.format("%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
