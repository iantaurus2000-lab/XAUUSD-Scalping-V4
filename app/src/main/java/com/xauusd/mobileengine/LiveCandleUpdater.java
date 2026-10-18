package com.xauusd.mobileengine;

import java.util.Arrays;

/** Updates only the forming candle from fresh feed ticks; no synthetic multi-bar gap fill. */
public final class LiveCandleUpdater {
    private LiveCandleUpdater() {}

    public static long periodMillis(String timeframe) {
        if (timeframe == null) return 60_000L;
        switch (timeframe.toUpperCase(java.util.Locale.US)) {
            case "M1": case "1M": return 60_000L;
            case "M5": case "5M": return 300_000L;
            case "M15": case "15M": return 900_000L;
            case "H1": case "1H": return 3_600_000L;
            case "H4": case "4H": return 14_400_000L;
            case "D1": case "1D": return 86_400_000L;
            default: return 60_000L;
        }
    }

    /** Update the active bar, append one bar at the next boundary, but never invent gap bars. */
    public static Candle[] update(Candle[] candles, long periodMs, double price, long nowMs) {
        if (candles == null || candles.length == 0 || periodMs <= 0L
                || !Double.isFinite(price) || price <= 0.0 || nowMs <= 0L) return candles;
        Candle last = candles[candles.length - 1];
        if (last == null || last.t <= 0L) return candles;
        long currentBar = (nowMs / periodMs) * periodMs;
        if (currentBar < last.t) return candles;
        if (currentBar == last.t) {
            last.h = Math.max(last.h, price);
            last.l = Math.min(last.l, price);
            last.c = price;
            return candles;
        }
        if (currentBar - last.t != periodMs) return candles;
        Candle next = new Candle(currentBar, last.c,
                Math.max(last.c, price), Math.min(last.c, price), price);
        if (candles.length < 120) {
            Candle[] result = Arrays.copyOf(candles, candles.length + 1);
            result[result.length - 1] = next;
            return result;
        }
        Candle[] result = new Candle[candles.length];
        System.arraycopy(candles, 1, result, 0, candles.length - 1);
        result[result.length - 1] = next;
        return result;
    }
}