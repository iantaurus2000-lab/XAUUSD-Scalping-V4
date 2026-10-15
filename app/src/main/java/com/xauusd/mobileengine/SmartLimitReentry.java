package com.xauusd.mobileengine;

/**
 * Pure decision helpers for Smart LIMIT re-entry.
 * All timestamps passed here are epoch milliseconds from Candle.openTime.
 * This class never sends orders; MainActivity acts only after EA state/ACK guards.
 */
public final class SmartLimitReentry {
    private SmartLimitReentry() {}

    public static int elapsedM1Bars(long startedAtMs, long currentCandleAtMs) {
        if (startedAtMs <= 0L || currentCandleAtMs < startedAtMs) return 0;
        return (int) Math.min(Integer.MAX_VALUE,
                (currentCandleAtMs - startedAtMs) / 60000L);
    }

    public static double atr(Candle[] candles, int period) {
        if (candles == null || candles.length < 2 || period < 1) return 0.0;
        int count = Math.min(period, candles.length - 1);
        double total = 0.0;
        for (int i = candles.length - count; i < candles.length; i++) {
            Candle c = candles[i];
            Candle p = candles[i - 1];
            double tr = Math.max(c.h - c.l,
                    Math.max(Math.abs(c.h - p.c), Math.abs(c.l - p.c)));
            if (!Double.isFinite(tr) || tr < 0.0) return 0.0;
            total += tr;
        }
        return total / count;
    }

    /**
     * Uses the last three completed M1 candles (excludes the currently forming bar).
     * Two directional bodies, net progress, and minimum ATR displacement are required.
     */
    public static boolean hasContinuation(Candle[] candles, String side) {
        if (candles == null || candles.length < 5 || side == null) return false;
        boolean buy = "BUY".equals(side);
        boolean sell = "SELL".equals(side);
        if (!buy && !sell) return false;

        int first = candles.length - 4;
        int last = candles.length - 2;
        int directional = 0;
        for (int i = first; i <= last; i++) {
            Candle c = candles[i];
            if (buy ? c.c > c.o : c.c < c.o) directional++;
        }
        double volatility = atr(candles, 14);
        double net = buy
                ? candles[last].c - candles[first].o
                : candles[first].o - candles[last].c;
        return directional >= 2 && volatility > 0.0 && net >= volatility * 0.25
                && (buy ? candles[last].c > candles[first].c
                        : candles[last].c < candles[first].c);
    }

    public static boolean movedAwayInFavor(String side, double entry, double bid,
                                           double ask, double atr) {
        if (entry <= 0 || bid <= 0 || ask < bid || atr <= 0 || side == null) return false;
        double threshold = atr * 0.35;
        if ("BUY".equals(side)) return ask - entry >= threshold;
        if ("SELL".equals(side)) return entry - bid >= threshold;
        return false;
    }

    public static boolean materiallyDifferentEntry(double oldEntry, double newEntry,
                                                   double atr) {
        return Double.isFinite(oldEntry) && Double.isFinite(newEntry) && Double.isFinite(atr)
                && oldEntry > 0 && newEntry > 0 && atr > 0
                && Math.abs(oldEntry - newEntry) >= atr * 0.12;
    }

    /**
     * Reject a proposed replacement LIMIT if its entry has drifted too far
     * from the original pending-order entry. Distance is normalized by M1 ATR
     * so the same rule works across symbols with different price scales.
     */
    public static boolean withinMaxDistanceFromOriginalEntry(double originalEntry,
                                                              double proposedEntry,
                                                              double atr,
                                                              double maxAtrDistance) {
        if (!Double.isFinite(originalEntry) || !Double.isFinite(proposedEntry)
                || !Double.isFinite(atr) || !Double.isFinite(maxAtrDistance)
                || originalEntry <= 0 || proposedEntry <= 0 || atr <= 0
                || maxAtrDistance < 0.5 || maxAtrDistance > 5.0) return false;
        return Math.abs(proposedEntry - originalEntry) <= atr * maxAtrDistance;
    }
}
