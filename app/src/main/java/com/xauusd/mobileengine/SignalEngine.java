package com.xauusd.mobileengine;

/**
 * Single Fibonacci trend-retracement engine.
 *
 * Trend: M5 EMA50/EMA200 plus higher-high/higher-low or lower-high/lower-low structure.
 * Setup: M1 impulse swing, retracement into the 50%-61.8% zone, and a directional
 * confirmation candle that intersects the zone. Signals are market-entry signals;
 * the queue/Gateway remains responsible for final broker validation.
 */
public final class SignalEngine {
    private SignalEngine() {}

    private static final int M5_TREND_HISTORY = 201;
    private static final int M1_SWING_LOOKBACK = 20;
    private static final double FIB_ENTRY_OUTER = 0.618;
    private static final double FIB_ENTRY_INNER = 0.500;
    private static final double FIB_STOP = 0.786;
    private static final double FIB_TARGET2 = -0.270;

    public static SignalResult evaluate(Candle[] m1, Candle[] m5,
                                        double bid, double ask,
                                        double spreadBid, double spreadAsk) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < M5_TREND_HISTORY) {
            return wait("MENUNGGU DATA M1/M5 YANG CUKUP", false, false, false, false);
        }
        if (!QuoteSafety.valid(bid, ask)) {
            return wait("QUOTE MT5 TIDAK VALID", false, false, false, false);
        }

        final int m1n = m1.length;
        final int m5n = m5.length;
        final Candle live = m1[m1n - 1];
        final Candle confirmation = m1[m1n - 2];
        final double atrM1 = atr(m1, 14);
        final double atrM5 = atr(m5, 14);
        final boolean atrOk = Double.isFinite(atrM1) && atrM1 > 0.0
            && Double.isFinite(atrM5) && atrM5 > 0.0;
        final boolean spreadOk = atrOk
            && QuoteSafety.spreadAcceptable(spreadBid, spreadAsk, atrM1);
        if (!atrOk) return wait("ATR BELUM VALID", false, spreadOk, false, false);

        // Simple M5 trend filter: EMA50 above EMA200 = BUY bias, below = SELL bias.
        // No extra swing-structure, EMA-slope, or minimum-gap conditions.
        double ema50 = emaUntil(m5, 50, m5n - 2);
        double ema200 = emaUntil(m5, 200, m5n - 2);
        Candle m5Closed = m5[m5n - 2];
        boolean trendBuy = ema50 > ema200;
        boolean trendSell = ema50 < ema200;
        String bias = trendBuy ? "BUY" : trendSell ? "SELL" : "NEUTRAL";
        if (!trendBuy && !trendSell) {
            return wait("EMA50/EMA200 M5 BELUM MENENTUKAN ARAH",
                atrOk, spreadOk, false, false, bias);
        }

        // Find the strongest correctly ordered impulse in recent CLOSED M1 bars.
        // This avoids rejecting the whole signal just because the absolute high/low
        // happened in the opposite order elsewhere in the lookback window.
        int swingEnd = m1n - 2;
        int swingStart = Math.max(0, swingEnd - M1_SWING_LOOKBACK + 1);
        double swingHigh = Double.NEGATIVE_INFINITY;
        double swingLow = Double.POSITIVE_INFINITY;
        int swingHighIndex = -1;
        int swingLowIndex = -1;
        double swingRange = 0.0;

        if (trendBuy) {
            for (int i = swingStart; i < swingEnd; i++) {
                double low = m1[i].l;
                for (int j = i + 1; j <= swingEnd; j++) {
                    double range = m1[j].h - low;
                    if (range > swingRange) {
                        swingRange = range;
                        swingLow = low;
                        swingHigh = m1[j].h;
                        swingLowIndex = i;
                        swingHighIndex = j;
                    }
                }
            }
        } else {
            for (int i = swingStart; i < swingEnd; i++) {
                double high = m1[i].h;
                for (int j = i + 1; j <= swingEnd; j++) {
                    double range = high - m1[j].l;
                    if (range > swingRange) {
                        swingRange = range;
                        swingHigh = high;
                        swingLow = m1[j].l;
                        swingHighIndex = i;
                        swingLowIndex = j;
                    }
                }
            }
        }

        if (swingHighIndex < 0 || swingLowIndex < 0
                || !Double.isFinite(swingRange) || swingRange < atrM1 * 0.50) {
            return wait("MENUNGGU IMPULSE M1 UNTUK FIBONACCI",
                atrOk, spreadOk, false, false, bias);
        }

        // Fibonacci retracement zone is widened to 38.2%-61.8%.
        // BUY: 0 at impulse high; SELL: 0 at impulse low.
        double fib0 = trendBuy ? swingHigh : swingLow;
        double fib382 = trendBuy ? swingHigh - swingRange * 0.382
                                 : swingLow + swingRange * 0.382;
        double fib50 = trendBuy ? swingHigh - swingRange * 0.500
                                : swingLow + swingRange * 0.500;
        double fib618 = trendBuy ? swingHigh - swingRange * 0.618
                                 : swingLow + swingRange * 0.618;
        double fib786 = trendBuy ? swingHigh - swingRange * FIB_STOP
                                 : swingLow + swingRange * FIB_STOP;
        double fibTarget2 = trendBuy ? swingHigh - swingRange * FIB_TARGET2
                                     : swingLow + swingRange * FIB_TARGET2;

        // Align chart-feed candle levels to the live MT5 broker quote.
        double mid = (bid + ask) * 0.5;
        double priceShift = mid - live.c;
        fib0 += priceShift;
        fib382 += priceShift;
        fib50 += priceShift;
        fib618 += priceShift;
        fib786 += priceShift;
        fibTarget2 += priceShift;

        double zoneLow = Math.min(fib382, fib618);
        double zoneHigh = Math.max(fib382, fib618);
        double spread = Math.max(0.0, spreadAsk - spreadBid);
        double buffer = Math.max(atrM1 * 0.08, spread * 2.0);
        double entry = trendBuy ? ask : bid;
        double sl = trendBuy ? fib786 - buffer : fib786 + buffer;
        double tp1 = fib0;
        double tp2 = fibTarget2;

        double tolerance = Math.max(atrM1 * 0.20, spread * 2.0);
        boolean quoteInZone = entry >= zoneLow - tolerance && entry <= zoneHigh + tolerance;

        double o = confirmation.o + priceShift;
        double h = confirmation.h + priceShift;
        double l = confirmation.l + priceShift;
        double c = confirmation.c + priceShift;
        double body = Math.abs(c - o);
        double candleRange = Math.max(h - l, 1e-9);
        double lowerWick = Math.min(o, c) - l;
        double upperWick = h - Math.max(o, c);
        boolean bullishRejection = lowerWick >= Math.max(body * 0.50, atrM1 * 0.05)
            && c >= l + candleRange * 0.55;
        boolean bearishRejection = upperWick >= Math.max(body * 0.50, atrM1 * 0.05)
            && c <= l + candleRange * 0.45;
        boolean candleConfirmed = trendBuy
            ? (c > o || bullishRejection)
            : (c < o || bearishRejection);
        boolean wick = trendBuy ? lowerWick >= body * 0.50 : upperWick >= body * 0.50;

        if (!quoteInZone) {
            return wait("MENUNGGU HARGA KE ZONA FIBO 38.2-61.8%",
                atrOk, spreadOk, wick, true, bias);
        }
        if (!candleConfirmed) {
            return wait("MENUNGGU CANDLE ARAH / WICK REJECTION M1",
                atrOk, spreadOk, wick, true, bias);
        }
        if (!spreadOk) return wait("SPREAD MT5 TERLALU LEBAR", atrOk, false, wick, true, bias);

        boolean validGeometry = trendBuy
            ? sl < entry && tp1 > entry && tp2 > tp1
            : sl > entry && tp1 < entry && tp2 < tp1;
        if (!validGeometry || entry <= 0.0 || sl <= 0.0 || tp1 <= 0.0 || tp2 <= 0.0) {
            return wait("GEOMETRI ENTRY / SL / TP FIBONACCI TIDAK VALID",
                atrOk, spreadOk, wick, true, bias);
        }
        double risk = Math.abs(entry - sl);
        double reward = Math.abs(tp2 - entry);
        double rr = risk > 0.0 ? reward / risk : 0.0;
        if (!Double.isFinite(rr) || rr < 1.0) {
            return wait("RR FIBONACCI DI BAWAH 1:1 • SETUP DITAHAN",
                atrOk, spreadOk, wick, true, bias);
        }

        String side = trendBuy ? "BUY" : "SELL";
        String setup = "FIB 38.2-61.8% + KONFIRMASI M1 • " + side;
        double trendStrength = Math.min(1.0, Math.abs(ema50 - ema200)
            / Math.max(atrM5 * 0.50, 1e-8));
        double bodyStrength = Math.min(1.0, Math.max(body, Math.max(lowerWick, upperWick))
            / Math.max(atrM1, 1e-8));
        double zoneHalfWidth = Math.max((zoneHigh - zoneLow) * 0.5 + tolerance, 1e-8);
        double entryPrecision = 1.0 - Math.min(1.0,
            Math.abs(entry - (zoneLow + zoneHigh) * 0.5) / zoneHalfWidth);
        int score = Math.min(100, 75 + (int)Math.round(8.0 * trendStrength)
            + (int)Math.round(10.0 * bodyStrength) + (int)Math.round(7.0 * entryPrecision)
            + (wick ? 3 : 0));
        int confidence = score >= 93 ? 5 : (score >= 80 ? 4 : 3);
        return new SignalResult(side, bias, entry, sl, tp1, tp2, rr,
            score, confidence, setup, false, wick, true, true, false, false, atrOk, spreadOk);
    }

    private static SignalResult wait(String setup, boolean atrOk, boolean spreadOk,
                                     boolean wick, boolean structure) {
        return wait(setup, atrOk, spreadOk, wick, structure, "NEUTRAL");
    }

    private static SignalResult wait(String setup, boolean atrOk, boolean spreadOk,
                                     boolean wick, boolean structure, String bias) {
        return new SignalResult("WAIT", bias, 0, 0, 0, 0, 0, 0, 1, setup,
            false, wick, structure, false, false, false, atrOk, spreadOk);
    }

    static double ema(Candle[] a, int period) {
        return emaUntil(a, period, a == null ? -1 : a.length - 1);
    }

    private static double emaUntil(Candle[] a, int period, int endInclusive) {
        if (a == null || a.length == 0 || period <= 0 || endInclusive < 0) return 0.0;
        int end = Math.min(endInclusive, a.length - 1);
        double k = 2.0 / (period + 1.0);
        double value = a[0].c;
        for (int i = 1; i <= end; i++) value = a[i].c * k + value * (1.0 - k);
        return value;
    }

    static double atr(Candle[] a, int period) {
        if (a == null || a.length < 2 || period <= 0) return 0.0;
        int count = Math.min(period, a.length - 1);
        double sum = 0.0;
        for (int i = a.length - count; i < a.length; i++) {
            Candle current = a[i], previous = a[i - 1];
            sum += Math.max(current.h - current.l,
                Math.max(Math.abs(current.h - previous.c), Math.abs(current.l - previous.c)));
        }
        return sum / count;
    }
}
