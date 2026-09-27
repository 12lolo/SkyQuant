package nl.senne.pricetracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import nl.senne.pricetracker.model.PriceModels.BazaarPoint;
import nl.senne.pricetracker.model.PriceModels.Market;
import nl.senne.pricetracker.model.PriceModels.PricePoint;
import nl.senne.pricetracker.model.PriceModels.Range;
import nl.senne.pricetracker.config.PriceConfig;
import nl.senne.pricetracker.model.PriceModels.Result;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** One freely-resizable price panel. Renders in local coordinates (0,0..width,height). */
public class PriceWindow {

    static final int TITLE_H = 15;
    private static final int MIN_W = 190;
    private static final int MIN_H = 130;
    private static final int COMPACT_H = 180; // below this, collapse to graph-only view
    private static final int GRIP = 8;
    private static final long REFRESH_MS = 30_000L;

    // Colours (ARGB)
    private static final int PANEL = 0xF0121418;
    private static final int TITLE_BG = 0xFF1E2230;
    private static final int TITLE_BG_PIN = 0xFF3A2E12;
    private static final int BORDER = 0xFF3A3F4C;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int DIM = 0xFF6C7079;
    private static final int GOLD = 0xFFFFD25A;
    private static final int GREEN = 0xFF55E06B;
    private static final int RED = 0xFFF06868;
    private static final int GRID = 0x22FFFFFF;
    private static final int CROSS = 0x99FFFFFF;
    private static final int BTN = 0xFF2A2F3A;
    private static final int BTN_HOVER = 0xFF3A4150;
    private static final int BTN_ACTIVE = 0xFF4468C0;
    private static final int ACCENT = 0xFF5AA0FF;
    private static final int SHADOW = 0x60000000;

    private String tag;
    private String displayName;
    private Range range = Range.DAY;
    private boolean pinned;
    private boolean manualCompact;

    // Hover tooltip is deferred to the end of render() so it draws on top of everything.
    private List<String> pendingTip;
    private int pendingTipX;
    private int pendingTipY;

    int px;
    int py;
    int width = 300;
    int height = 236;

    private volatile Result result;
    private volatile boolean loading;
    private volatile String error;
    private volatile long token;
    private long lastFetch;
    private volatile long lastSuccess;

    private boolean dragging;
    private boolean resizing;
    private int dragDx;
    private int dragDy;
    private boolean wantClose;

    PriceWindow(String tag, String name, int x, int y) {
        this.tag = tag;
        this.displayName = name;
        this.px = x;
        this.py = y;
        this.range = nl.senne.pricetracker.config.PriceConfig.get().defaultRange;
        fetch();
    }

    /** Restore a persisted window (does not fetch until first render tick). */
    PriceWindow(nl.senne.pricetracker.Persistence.WindowState s) {
        this.tag = s.tag;
        this.displayName = s.name;
        this.px = s.px;
        this.py = s.py;
        this.width = Math.max(MIN_W, s.width);
        this.height = Math.max(MIN_H, s.height);
        this.pinned = s.pinned;
        this.manualCompact = s.compact;
        try {
            this.range = Range.valueOf(s.range);
        } catch (Exception ignored) {
            this.range = Range.DAY;
        }
        fetch();
    }

    Persistence.WindowState toState() {
        Persistence.WindowState s = new Persistence.WindowState();
        s.tag = tag;
        s.name = displayName;
        s.px = px;
        s.py = py;
        s.width = width;
        s.height = height;
        s.pinned = pinned;
        s.compact = manualCompact;
        s.range = range.name();
        return s;
    }

    boolean isPinned() {
        return pinned;
    }

    boolean wantClose() {
        return wantClose;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void retarget(String newTag, String newName) {
        this.tag = newTag;
        this.displayName = newName;
        this.result = null; // different item — clear the old chart
        this.error = null;
        fetch();
    }

    // ---- data ------------------------------------------------------------

    private void fetch() {
        loading = true;
        lastFetch = System.currentTimeMillis();
        long myToken = ++token;
        // Keep the currently-shown chart during a background refresh; only replace on success.
        CoflApi.lookup(tag, displayName, range).whenComplete((res, err) -> Minecraft.getInstance().execute(() -> {
            if (myToken != token) return;
            loading = false;
            if (err != null) {
                if (result == null) error = rootMessage(err); // only surface if nothing to show
            } else {
                result = res;
                error = null;
                lastSuccess = System.currentTimeMillis();
            }
        }));
    }

    void tickRefresh() {
        long interval = Math.max(10, nl.senne.pricetracker.config.PriceConfig.get().refreshSeconds) * 1000L;
        if (!loading && System.currentTimeMillis() - lastFetch > interval) {
            fetch();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ---- input -----------------------------------------------------------

    /** @return true if consumed. sm* = screen coords, lm* = local coords. */
    boolean handleClick(int smx, int smy, int lmx, int lmy, int button) {
        if (button != 0) return true;
        // resize grip (bottom-right corner) takes priority
        if (lmx >= width - GRIP && lmy >= height - GRIP) {
            resizing = true;
            return true;
        }
        if (in(lmx, lmy, closeR())) { wantClose = true; return true; }
        if (in(lmx, lmy, lookupR())) { runIngameLookup(); return true; }
        if (in(lmx, lmy, pinR())) { pinned = !pinned; return true; }
        if (in(lmx, lmy, compactR())) { manualCompact = !manualCompact; return true; }
        for (Range r : Range.values()) {
            if (in(lmx, lmy, rangeR(r.ordinal()))) {
                if (range != r) { range = r; fetch(); }
                return true;
            }
        }
        if (lmy < TITLE_H) {
            dragging = true;
            dragDx = smx - px;
            dragDy = smy - py;
        }
        return true;
    }

    void stopInteract() {
        dragging = false;
        resizing = false;
    }

    /** Per-frame update while a mouse button is held (move or resize). */
    void updateInteract(int smx, int smy, int screenW, int screenH) {
        // Poll the real button so a missed release event can't leave us stuck dragging/resizing.
        if ((dragging || resizing) && !leftMouseDown()) {
            dragging = false;
            resizing = false;
            return;
        }
        if (resizing) {
            width = clamp(smx - px, MIN_W, Math.max(MIN_W, screenW - px));
            height = clamp(smy - py, MIN_H, Math.max(MIN_H, screenH - py));
        } else if (dragging) {
            px = clamp(smx - dragDx, 0, Math.max(0, screenW - width));
            py = clamp(smy - dragDy, 0, Math.max(0, screenH - height));
        }
    }

    /**
     * Rescale position and size proportionally when the GUI-scaled screen size changes
     * (resolution or GUI-scale switch), so the window keeps the same relative footprint
     * and stays on-screen instead of remaining at its old pixel dimensions.
     */
    void reflow(int oldW, int oldH, int newW, int newH) {
        if (oldW <= 0 || oldH <= 0) return;
        double rx = (double) newW / oldW;
        double ry = (double) newH / oldH;
        width = clamp((int) Math.round(width * rx), MIN_W, Math.max(MIN_W, newW));
        height = clamp((int) Math.round(height * ry), MIN_H, Math.max(MIN_H, newH));
        px = clamp((int) Math.round(px * rx), 0, Math.max(0, newW - width));
        py = clamp((int) Math.round(py * ry), 0, Math.max(0, newH - height));
    }

    private static boolean leftMouseDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void runIngameLookup() {
        String query = niceName(tag);
        String cmd = (result != null && result.market() == Market.AUCTION) ? "ahsearch " + query : "bz " + query;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    // ---- render (local coords) -------------------------------------------

    void render(GuiGraphicsExtractor gfx, Font font, int lmx, int lmy) {
        pendingTip = null; // reset each frame; drawn last so it stays on top

        // drop shadow (bottom-right), panel, title bar + accent line
        gfx.fill(3, 3, width + 4, height + 4, SHADOW);
        gfx.fill(0, 0, width, height, PANEL);
        gfx.fill(0, 0, width, TITLE_H, pinned ? TITLE_BG_PIN : TITLE_BG);
        gfx.fill(0, TITLE_H, width, TITLE_H + 1, pinned ? GOLD : ACCENT);
        gfx.outline(0, 0, width, height, BORDER);

        boolean compact = manualCompact || height < COMPACT_H;
        if (!compact) {
            gfx.text(font, trim(font, displayName, width - 96), 5, 4, WHITE);
        }
        drawCompactIcon(gfx, compactR(), compact, in(lmx, lmy, compactR()));
        drawPin(gfx, pinR(), pinned, in(lmx, lmy, pinR()));
        int[] lk = lookupR();
        gfx.fill(lk[0], lk[1], lk[0] + lk[2], lk[1] + lk[3], in(lmx, lmy, lk) ? BTN_HOVER : BTN);
        gfx.centeredText(font, "Lookup", lk[0] + lk[2] / 2, lk[1] + 3, WHITE);
        int[] c = closeR();
        gfx.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], in(lmx, lmy, c) ? RED : BTN);
        gfx.text(font, "x", c[0] + 4, c[1] + 2, WHITE);

        int x = 6;
        int y = TITLE_H + 4;
        if (!compact) {
            gfx.text(font, tag, x, y, DIM);
            y += 11;
        }

        if (result != null) {
            renderBody(gfx, font, x, y, lmx, lmy, compact);
        } else if (error != null) {
            gfx.text(font, "Error: " + error, x, y, RED);
        } else {
            gfx.text(font, "Fetching prices…", x, y, GRAY);
        }

        for (Range r : Range.values()) {
            int[] b = rangeR(r.ordinal());
            boolean hover = in(lmx, lmy, b);
            int col = r == range ? BTN_ACTIVE : (hover ? BTN_HOVER : BTN);
            gfx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], col);
            gfx.centeredText(font, r.label, b[0] + b[2] / 2, b[1] + 4, r == range ? WHITE : GRAY);
        }

        drawGrip(gfx, in(lmx, lmy, new int[]{width - GRIP, height - GRIP, GRIP, GRIP}));

        if (pendingTip != null) {
            drawTooltip(gfx, font, pendingTipX, pendingTipY, pendingTip);
        }
    }

    private void renderBody(GuiGraphicsExtractor gfx, Font font, int x, int y, int lmx, int lmy, boolean compact) {
        Result r = result;
        PriceConfig.DetailLines d = PriceConfig.get().details;
        long[] times;
        double[][] series;
        int[] colors;
        String[] names;

        if (r.market() == Market.BAZAAR) {
            List<BazaarPoint> hh = r.bazaarHistory() == null ? List.of() : r.bazaarHistory();
            times = new long[hh.size()];
            double[] buy = new double[hh.size()];
            double[] sell = new double[hh.size()];
            for (int i = 0; i < hh.size(); i++) {
                times[i] = hh.get(i).timeMillis();
                buy[i] = hh.get(i).buy();
                sell[i] = hh.get(i).sell();
            }
            series = new double[][]{buy, sell};
            colors = new int[]{GREEN, RED};
            names = new String[]{"buy", "sell"};

            if (!compact) {
                gfx.text(font, "Bazaar", x, y, GOLD);
                y += 11;
                List<Detail> items = new ArrayList<>();
                if (r.bazaar() != null) {
                    items.add(new Detail("Buy: " + Format.coins(r.bazaar().buyPrice()), GREEN,
                            "Insta-buy", "What you pay to buy now", "(fills the lowest sell offer)."));
                    items.add(new Detail("Sell: " + Format.coins(r.bazaar().sellPrice()), RED,
                            "Insta-sell", "What you get selling now", "(fills the highest buy order)."));
                    if (d.change) addIfNotNull(items, changeDetail(buy));
                    if (d.volume) items.add(new Detail("Vol: " + Format.coins(r.bazaar().buyVolume())
                            + "/" + Format.coins(r.bazaar().sellVolume()), DIM,
                            "Volume", "Items in buy orders /", "sell offers right now."));
                    if (d.orders) items.add(new Detail("Ord: " + Format.coins(r.bazaar().buyOrders())
                            + "/" + Format.coins(r.bazaar().sellOrders()), DIM,
                            "Orders", "Number of separate buy", "orders and sell offers."));
                    if (d.margin) {
                        double curMargin = r.bazaar().buyPrice() - r.bazaar().sellPrice();
                        double sum = 0;
                        int cnt = 0;
                        for (BazaarPoint p : hh) {
                            if (p.buy() > 0 && p.sell() > 0) { sum += p.buy() - p.sell(); cnt++; }
                        }
                        items.add(new Detail("Margin: " + Format.coins(curMargin) + " ~" + Format.coins(cnt > 0 ? sum / cnt : 0), GOLD,
                                "Margin", "Insta-buy minus insta-sell:", "the raw flip spread per item.",
                                "'~' = average over the range.", "(before the ~1.25% bazaar tax)"));
                    }
                    if (d.liquidity) items.add(new Detail("Liq: ~" + Format.coins(Math.round(r.bazaar().sellMovingWeek() / 7.0)) + "/d", DIM,
                            "Liquidity", "Approx items traded per day", "(weekly volume / 7).", "Higher = flips fill faster."));
                }
                if (d.npc) addIfNotNull(items, npcDetail(r));
                addIfNotNull(items, updatedDetail());
                y = drawGrid(gfx, font, x, y, lmx, lmy, items);
            }
        } else if (r.market() == Market.AUCTION) {
            List<PricePoint> hh = r.auctionHistory() == null ? List.of() : r.auctionHistory();
            times = new long[hh.size()];
            double[] avg = new double[hh.size()];
            for (int i = 0; i < hh.size(); i++) {
                times[i] = hh.get(i).timeMillis();
                avg[i] = hh.get(i).avg();
            }
            series = new double[][]{avg};
            colors = new int[]{GREEN};
            names = new String[]{"avg"};

            if (!compact) {
                gfx.text(font, "Auction House", x, y, GOLD);
                y += 11;
                List<Detail> items = new ArrayList<>();
                if (r.lowestBin() != null) {
                    items.add(new Detail("BIN: " + Format.coins(r.lowestBin().price()), GREEN,
                            "Lowest BIN", "Cheapest Buy-It-Now", "auction listed right now."));
                    if (d.binDepth && r.lowestBin().second() > 0) {
                        String listed = r.lowestBin().listed() >= 10 ? "10+" : String.valueOf(r.lowestBin().listed());
                        items.add(new Detail("2nd: " + Format.coins(r.lowestBin().second())
                                + (r.lowestBin().third() > 0 ? " 3rd:" + Format.coins(r.lowestBin().third()) : "")
                                + " (" + listed + ")", DIM,
                                "BIN depth", "Next cheapest BINs and how", "many are currently listed.",
                                "Shows how much undercut room."));
                    }
                }
                if (r.auctionPrice() != null && r.auctionPrice().median() > 0) {
                    items.add(new Detail("Med: " + Format.coins(r.auctionPrice().median()), WHITE,
                            "Sold median", "Middle price of recently", "sold auctions (typical value)."));
                }
                if (d.change) addIfNotNull(items, changeDetail(avg));
                if (d.lowHigh) {
                    double lo = Double.MAX_VALUE, hi = 0;
                    for (PricePoint p : hh) {
                        if (p.min() > 0) lo = Math.min(lo, p.min());
                        if (p.max() > 0) hi = Math.max(hi, p.max());
                    }
                    if (hi > 0) {
                        items.add(new Detail("L/H: " + Format.coins(lo == Double.MAX_VALUE ? 0 : lo)
                                + "/" + Format.coins(hi), DIM,
                                "Low / High", "Cheapest and priciest sale", "within the selected range."));
                    }
                }
                if (d.liquidity && r.auctionPrice() != null && r.auctionPrice().volume() > 0) {
                    items.add(new Detail("Sold: ~" + Format.coins(Math.round(r.auctionPrice().volume())), DIM,
                            "Sold volume", "Roughly how many sold", "recently. Higher = more liquid."));
                }
                if (d.npc) addIfNotNull(items, npcDetail(r));
                addIfNotNull(items, updatedDetail());
                y = drawGrid(gfx, font, x, y, lmx, lmy, items);
            }
        } else {
            gfx.text(font, "No Bazaar/AH price data", x, y, GRAY);
            y += 10;
            gfx.text(font, "(item may be untradeable)", x, y, DIM);
            return;
        }

        int topReserve = compact ? 12 : 0; // room for the name overlay band
        int graphTop = compact ? TITLE_H + 3 : y + 2;
        int graphBottom = height - 23;
        drawGraph(gfx, font, 6, graphTop, width - 12, graphBottom - graphTop, topReserve,
                times, series, colors, names, r.market(), lmx, lmy);

        if (compact) {
            String nm = trim(font, displayName, width - 20);
            int w = font.width(nm);
            gfx.fill(9, graphTop + 2, 12 + w, graphTop + 12, 0xC0000000);
            gfx.text(font, nm, 11, graphTop + 3, WHITE);
        }
    }

    /** One detail field: display text, colour, and a hover-explanation (first line = title). */
    private static final class Detail {
        final String text;
        final int color;
        final String[] help;

        Detail(String text, int color, String... help) {
            this.text = text;
            this.color = color;
            this.help = help;
        }
    }

    private static void addIfNotNull(List<Detail> list, Detail d) {
        if (d != null) list.add(d);
    }

    /** Lay detail fields out in two columns (left fills first); hovering one queues its explanation. */
    private int drawGrid(GuiGraphicsExtractor gfx, Font font, int x0, int y0, int lmx, int lmy, List<Detail> items) {
        int n = items.size();
        if (n == 0) return y0;
        int rows = (n + 1) / 2;
        int colW = (width - 12) / 2;
        for (int i = 0; i < n; i++) {
            boolean left = i < rows;
            int cx = left ? x0 : x0 + colW;
            int cy = y0 + (left ? i : i - rows) * 10;
            Detail it = items.get(i);
            gfx.text(font, it.text, cx, cy, it.color);
            if (it.help.length > 0 && in(lmx, lmy, new int[]{cx, cy - 1, Math.max(font.width(it.text), 40) + 2, 10})) {
                List<String> t = new ArrayList<>(it.help.length);
                java.util.Collections.addAll(t, it.help);
                pendingTip = t;
                pendingTipX = lmx;
                pendingTipY = lmy;
            }
        }
        return y0 + rows * 10;
    }

    private Detail npcDetail(Result r) {
        if (r.npcSell() <= 0) return null;
        return new Detail("NPC: " + Format.coins(r.npcSell()), DIM,
                "NPC sell", "Coins an NPC pays for this.", "A hard price floor.");
    }

    /** Percentage change from the first to the last valid point of the selected range. */
    private Detail changeDetail(double[] s) {
        double first = 0, last = 0;
        for (double v : s) { if (v > 0) { first = v; break; } }
        for (int i = s.length - 1; i >= 0; i--) { if (s[i] > 0) { last = s[i]; break; } }
        if (first <= 0 || last <= 0) return null;
        double pct = (last - first) / first * 100;
        String arrow = pct >= 0 ? "▲" : "▼";
        return new Detail(arrow + " " + range.label + " " + (pct >= 0 ? "+" : "") + String.format("%.1f", pct) + "%",
                pct >= 0 ? GREEN : RED,
                "Change", "Price move across the", "selected time range.", "Green = up, red = down.");
    }

    private Detail updatedDetail() {
        if (lastSuccess == 0) return null;
        long ageSec = (System.currentTimeMillis() - lastSuccess) / 1000;
        long interval = Math.max(10, PriceConfig.get().refreshSeconds);
        boolean stale = ageSec > interval * 2;
        return new Detail(stale ? "stale " + ageSec + "s" : "upd " + ageSec + "s", stale ? RED : DIM,
                "Freshness", "Time since the last price", "refresh. 'stale' = behind.");
    }

    // ---- graph -----------------------------------------------------------

    private void drawGraph(GuiGraphicsExtractor gfx, Font font, int bx, int by, int bw, int bh, int topReserve,
                           long[] times, double[][] series, int[] colors, String[] names, Market market,
                           int lmx, int lmy) {
        if (bh < 26 || bw < 40) return;
        gfx.fill(bx, by, bx + bw, by + bh, 0x30000000);
        gfx.outline(bx, by, bw, bh, BORDER);

        int n = times.length;
        if (n < 2) {
            gfx.centeredText(font, "no history for this range", bx + bw / 2, by + bh / 2 - 4, GRAY);
            if (range == Range.DAY || range == Range.WEEK) {
                gfx.centeredText(font, "(try a longer range)", bx + bw / 2, by + bh / 2 + 7, DIM);
            }
            return;
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] s : series) for (double v : s) {
            if (v <= 0) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == Double.MAX_VALUE) {
            gfx.centeredText(font, "no data", bx + bw / 2, by + bh / 2 - 4, GRAY);
            return;
        }
        if (max == min) max = min * 1.05 + 1;

        // topReserve keeps the max-value label + plot clear of the compact name overlay.
        int padTop = 6 + topReserve, padBottom = 16, padSide = 3;
        int plotX = bx + padSide, plotY = by + padTop;
        int plotW = bw - padSide * 2, plotH = bh - padTop - padBottom;
        // Headroom scales with height so lines never hug the edges on tall windows.
        int vInset = Math.max(8, Math.min(plotH / 8, 30));
        int valTop = plotY + vInset;
        int valH = Math.max(1, plotH - vInset * 2);

        double[][] sm = new double[series.length][];
        for (int i = 0; i < series.length; i++) sm[i] = smooth(series[i]);
        series = sm;

        int[] fracsY = {0, valH / 2, valH - 1};
        double[] valsY = {max, (max + min) / 2, min};
        for (int i = 0; i < 3; i++) {
            int ly = valTop + fracsY[i];
            gfx.fill(plotX, ly, plotX + plotW, ly + 1, GRID);
            String lbl = Format.coins(valsY[i]);
            int ty = ly - (i == 0 ? -2 : (i == 2 ? 8 : 4)); // max below its line, min above, mid centered
            gfx.fill(bx + 1, ty - 1, bx + 3 + font.width(lbl), ty + 8, 0xC0000000); // backdrop for legibility
            gfx.text(font, lbl, bx + 2, ty, DIM);
        }

        long tEnd = System.currentTimeMillis();
        long tStart = tEnd - range.span.toMillis();

        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter axisFmt = axisFormatter();
        int ticks = Math.max(2, Math.min(8, plotW / 60));
        for (int i = 0; i <= ticks; i++) {
            long t = tStart + (long) ((double) i / ticks * (tEnd - tStart));
            int lx = timeX(t, tStart, tEnd, plotX, plotW);
            if (i > 0 && i < ticks) gfx.fill(lx, plotY, lx + 1, plotY + plotH, GRID);
            String label = Instant.ofEpochMilli(t).atZone(zone).format(axisFmt);
            int lw = font.width(label);
            int tx = clamp(lx - lw / 2, bx + 1, bx + bw - lw - 1);
            gfx.text(font, label, tx, by + bh - 9, DIM);
        }

        // subtle area fill under the primary line
        int baseY = valTop + valH;
        int areaCol = (colors[0] & 0x00FFFFFF) | 0x20000000;
        int aPrevX = -1, aPrevY = -1;
        for (int i = 0; i < n; i++) {
            double v = series[0][i];
            if (v <= 0) { aPrevX = -1; continue; }
            int cx = timeX(times[i], tStart, tEnd, plotX, plotW);
            int cy = valTop + (int) Math.round((1 - (v - min) / (max - min)) * (valH - 1));
            if (aPrevX >= 0) {
                for (int xx = aPrevX; xx <= cx; xx++) {
                    int yy = cx == aPrevX ? cy
                            : (int) Math.round(aPrevY + (double) (cy - aPrevY) * (xx - aPrevX) / (cx - aPrevX));
                    if (yy < baseY) gfx.fill(xx, yy, xx + 1, baseY, areaCol);
                }
            }
            aPrevX = cx;
            aPrevY = cy;
        }

        for (int si = 0; si < series.length; si++) {
            double[] s = series[si];
            int color = colors[si];
            int prevX = -1, prevY = -1;
            for (int i = 0; i < n; i++) {
                double v = s[i];
                if (v <= 0) continue;
                int cx = timeX(times[i], tStart, tEnd, plotX, plotW);
                int cy = valTop + (int) Math.round((1 - (v - min) / (max - min)) * (valH - 1));
                if (prevX >= 0) line(gfx, prevX, prevY, cx, cy, color);
                else gfx.fill(cx, cy, cx + 2, cy + 2, color);
                prevX = cx;
                prevY = cy;
            }
        }

        // legend (top-right of the plot)
        int lgX = plotX + plotW - 3;
        for (int si = series.length - 1; si >= 0; si--) {
            String nm = market == Market.BAZAAR ? names[si] : "price";
            lgX -= font.width(nm) + 9;
            gfx.fill(lgX - 1, plotY + 1, lgX + font.width(nm) + 8, plotY + 10, 0xB0000000);
            gfx.fill(lgX, plotY + 3, lgX + 5, plotY + 8, colors[si]);
            gfx.text(font, nm, lgX + 7, plotY + 2, DIM);
        }

        if (in(lmx, lmy, new int[]{plotX, plotY, plotW, plotH})) {
            long t = tStart + (long) ((double) (lmx - plotX) / (plotW - 1) * (tEnd - tStart));
            int idx = nearestByTime(times, t);
            int cx = timeX(times[idx], tStart, tEnd, plotX, plotW);
            gfx.fill(cx, plotY, cx + 1, plotY + plotH, CROSS);
            List<String> lines = new ArrayList<>();
            lines.add(Instant.ofEpochMilli(times[idx]).atZone(zone).format(TIP_FMT));
            for (int si = 0; si < series.length; si++) {
                double v = series[si][idx];
                lines.add((market == Market.BAZAAR ? cap(names[si]) : "price") + ": "
                        + (v > 0 ? Format.coins(v) + " (" + Format.exact(v) + ")" : "—"));
            }
            if (market == Market.BAZAAR && series.length >= 2) {
                double b = series[0][idx], s = series[1][idx];
                if (b > 0 && s > 0) {
                    lines.add("Margin: " + Format.coins(b - s));
                }
            }
            // Defer to end of render() so it draws above the name overlay and everything else.
            pendingTip = lines;
            pendingTipX = lmx;
            pendingTipY = lmy;
        }
    }

    private DateTimeFormatter axisFormatter() {
        return switch (range) {
            case DAY -> DateTimeFormatter.ofPattern("HH:mm");
            case WEEK, MONTH -> DateTimeFormatter.ofPattern("dd/MM");
            case HALF_YEAR, YEAR -> DateTimeFormatter.ofPattern("MMM");
        };
    }

    private static final DateTimeFormatter TIP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private void drawTooltip(GuiGraphicsExtractor gfx, Font font, int atX, int atY, List<String> lines) {
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s));
        int h = lines.size() * 10 + 4;
        int tx = clamp(atX + 8, 2, Math.max(2, width - w - 6));
        int ty = Math.max(2, atY - h - 4);
        gfx.fill(tx - 2, ty - 2, tx + w + 4, ty + h, 0xF00A0A0A);
        gfx.outline(tx - 2, ty - 2, w + 6, h + 2, BORDER);
        int yy = ty + 1;
        for (int i = 0; i < lines.size(); i++) {
            gfx.text(font, lines.get(i), tx, yy, i == 0 ? GOLD : WHITE);
            yy += 10;
        }
    }

    // ---- icons -----------------------------------------------------------

    private void drawPin(GuiGraphicsExtractor gfx, int[] r, boolean active, boolean hover) {
        gfx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? BTN_HOVER : BTN);
        int col = active ? GOLD : GRAY;
        int cx = r[0] + r[2] / 2;
        int top = r[1] + 3;
        gfx.fill(cx - 2, top, cx + 3, top + 4, col);  // head
        gfx.fill(cx - 3, top + 4, cx + 4, top + 5, col); // brim
        gfx.fill(cx, top + 5, cx + 1, top + 9, col);  // needle
    }

    private void drawCompactIcon(GuiGraphicsExtractor gfx, int[] r, boolean active, boolean hover) {
        gfx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? BTN_HOVER : BTN);
        int col = active ? GOLD : GRAY;
        int ix = r[0] + 2, iy = r[1] + 3, iw = r[2] - 4;
        gfx.fill(ix, iy, ix + iw, iy + 2, col);       // header bar
        gfx.fill(ix, iy + 4, ix + iw, iy + 7, col);   // body block
    }

    private void drawGrip(GuiGraphicsExtractor gfx, boolean hover) {
        int col = hover ? WHITE : DIM;
        int x = width - 3, y = height - 3;
        for (int i = 0; i < 3; i++) {
            int o = i * 3;
            gfx.fill(x - o, y - 1, x - o + 1, y, col);
            gfx.fill(x - 1, y - o, x, y - o + 1, col);
        }
    }

    // ---- button rects (local coords) -------------------------------------

    private int[] closeR() { return new int[]{width - 14, 1, 13, 13}; }
    private int[] lookupR() { return new int[]{width - 60, 1, 44, 13}; }
    private int[] pinR() { return new int[]{width - 74, 1, 12, 13}; }
    private int[] compactR() { return new int[]{width - 88, 1, 12, 13}; }

    private int[] rangeR(int i) {
        int margin = 6, gap = 2;
        int total = width - margin * 2 - GRIP; // leave the bottom-right corner clear for the resize grip
        int bw = (total - gap * 4) / 5;
        return new int[]{margin + i * (bw + gap), height - 19, bw, 16};
    }

    // ---- helpers ---------------------------------------------------------

    private static int timeX(long t, long t0, long t1, int plotX, int plotW) {
        double f = (double) (t - t0) / Math.max(1L, t1 - t0);
        f = Math.max(0.0, Math.min(1.0, f));
        return plotX + (int) Math.round(f * (plotW - 1));
    }

    private static int nearestByTime(long[] times, long t) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < times.length; i++) {
            long d = Math.abs(times[i] - t);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static double[] smooth(double[] s) {
        int n = s.length, r = 1;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            if (s[i] <= 0) { out[i] = s[i]; continue; }
            double sum = 0;
            int cnt = 0;
            for (int j = i - r; j <= i + r; j++) if (j >= 0 && j < n && s[j] > 0) { sum += s[j]; cnt++; }
            out[i] = cnt > 0 ? sum / cnt : s[i];
        }
        return out;
    }

    private static boolean in(int mx, int my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String niceName(String id) {
        String[] parts = id.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.isEmpty() ? id : sb.toString();
    }

    private static String trim(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static void line(GuiGraphicsExtractor gfx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            gfx.fill(x0, y0, x0 + 2, y0 + 2, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }
}
