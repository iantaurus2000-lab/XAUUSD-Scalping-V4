package com.xauusd.mobileengine;

/** ATR utility used for broker-side spread safety checks on manual market orders. */
public final class MarketVolatility {
    private MarketVolatility() {}
    public static double atr(Candle[] candles, int period) {
        if (candles == null || candles.length < 2 || period < 1) return 0.0;
        int count = Math.min(period, candles.length - 1);
        double total = 0.0;
        for (int i = candles.length - count; i < candles.length; i++) {
            Candle c = candles[i], p = candles[i - 1];
            double tr = Math.max(c.h - c.l,
                    Math.max(Math.abs(c.h - p.c), Math.abs(c.l - p.c)));
            if (!Double.isFinite(tr) || tr < 0.0) return 0.0;
            total += tr;
        }
        return total / count;
    }
}