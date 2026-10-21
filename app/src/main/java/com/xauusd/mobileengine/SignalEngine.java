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
            return wait("MENUNGGU RIWAYAT M1/M5 • EMA200 M5 MEMERLUKAN 200 CANDLE",
                false, false, false, false);
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
        final boolean spreadOk = atrOk && QuoteSafety.spreadAcceptable(spreadBid, spreadAsk, atrM1);
        if (!atrOk) return wait("ATR BELUM VALID", false, spreadOk, false, false);

        // Use closed M5 bars for direction. The currently forming bar is not allowed
        // to flip the trend every tick.
        double ema50 = emaUntil(m5, 50, m5n - 2);
        double ema200 = emaUntil(m5, 200, m5n - 2);
        double ema50Past = emaUntil(m5, 50, Math.max(1, m5n - 7));
        Candle m5Closed = m5[m5n - 2];
        double previousBlockHigh = Double.NEGATIVE_INFINITY;
        double previousBlockLow = Double.POSITIVE_INFINITY;
        double recentBlockHigh = Double.NEGATIVE_INFINITY;
        double recentBlockLow = Double.POSITIVE_INFINITY;
        for (int i = m5n - 12; i <= m5n - 8; i++) {
            previousBlockHigh = Math.max(previousBlockHigh, m5[i].h);
            previousBlockLow = Math.min(previousBlockLow, m5[i].l);
        }
        for (int i = m5n - 7; i <= m5n - 3; i++) {
            recentBlockHigh = Math.max(recentBlockHigh, m5[i].h);
            recentBlockLow = Math.min(recentBlockLow, m5[i].l);
        }
        boolean higherHighs = recentBlockHigh > previousBlockHigh;
        boolean higherLows = recentBlockLow > previousBlockLow;
        boolean lowerHighs = recentBlockHigh < previousBlockHigh;
        boolean lowerLows = recentBlockLow < previousBlockLow;
        double emaGap = Math.abs(ema50 - ema200);
        boolean gapClear = emaGap >= Math.max(atrM5 * 0.08, Math.abs(m5Closed.c) * 1e-7);
        boolean trendBuy = gapClear && ema50 > ema200 && ema50 > ema50Past
            && m5Closed.c > ema50 && higherHighs && higherLows;
        boolean trendSell = gapClear && ema50 < ema200 && ema50 < ema50Past
            && m5Closed.c < ema50 && lowerHighs && lowerLows;
        String bias = trendBuy ? "BUY" : trendSell ? "SELL" : "NEUTRAL";
        if (!trendBuy && !trendSell) {
            return wait("KONSOLIDASI / STRUKTUR M5 BELUM VALID • FIBO TIDAK DIPAKSA",
                atrOk, spreadOk, false, false, bias);
        }

        // Anchor a recent M1 impulse with closed candles only; do not let the live
        // candle continuously redraw the swing used to validate its own entry.
        int swingEnd = m1n - 3; // confirmation candle is m1n-2
        int swingStart = Math.max(0, swingEnd - M1_SWING_LOOKBACK + 1);
        double swingHigh = Double.NEGATIVE_INFINITY;
        double swingLow = Double.POSITIVE_INFINITY;
        int swingHighIndex = -1, swingLowIndex = -1;
        for (int i = swingStart; i <= swingEnd; i++) {
            if (m1[i].h > swingHigh) { swingHigh = m1[i].h; swingHighIndex = i; }
            if (m1[i].l < swingLow) { swingLow = m1[i].l; swingLowIndex = i; }
        }
        double swingRange = swingHigh - swingLow;
        if (swingHighIndex < 0 || swingLowIndex < 0
                || !Double.isFinite(swingRange) || swingRange < atrM1 * 0.75) {
            return wait("MENUNGGU SWING M1 YANG CUKUP KUAT UNTUK FIBONACCI",
                atrOk, spreadOk, false, false, bias);
        }
        if (trendBuy && swingLowIndex >= swingHighIndex) {
            return wait("MENUNGGU IMPULS NAIK M1 • LOW HARUS LEBIH DAHULU DARI HIGH",
                atrOk, spreadOk, false, false, bias);
        }
        if (trendSell && swingHighIndex >= swingLowIndex) {
            return wait("MENUNGGU IMPULS TURUN M1 • HIGH HARUS LEBIH DAHULU DARI LOW",
                atrOk, spreadOk, false, false, bias);
        }

        // Convention matches the app Fibonacci ladder:
        // BUY: 0 at swing high; 100 at swing low; -27 above the old high.
        // SELL: 0 at swing low; 100 at swing high; -27 below the old low.
        double fib0 = trendBuy ? swingHigh : swingLow;
        double fib50 = trendBuy ? swingHigh - swingRange * FIB_ENTRY_INNER
                                : swingLow + swingRange * FIB_ENTRY_INNER;
        double fib618 = trendBuy ? swingHigh - swingRange * FIB_ENTRY_OUTER
                                 : swingLow + swingRange * FIB_ENTRY_OUTER;
        double fib786 = trendBuy ? swingHigh - swingRange * FIB_STOP
                                 : swingLow + swingRange * FIB_STOP;
        double fibTarget2 = trendBuy ? swingHigh - swingRange * FIB_TARGET2
                                     : swingLow + swingRange * FIB_TARGET2;

        // Align chart-feed OHLC/Fibonacci prices to the current MT5 quote coordinate.
        double mid = (bid + ask) * 0.5;
        double priceShift = mid - live.c;
        fib0 += priceShift;
        fib50 += priceShift;
        fib618 += priceShift;
        fib786 += priceShift;
        fibTarget2 += priceShift;

        double zoneLow = Math.min(fib50, fib618);
        double zoneHigh = Math.max(fib50, fib618);
        double spread = Math.max(0.0, spreadAsk - spreadBid);
        double buffer = Math.max(atrM1 * 0.08, spread * 2.0);
        double entry = trendBuy ? ask : bid;
        double sl = trendBuy ? fib786 - buffer : fib786 + buffer;
        double tp1 = fib0;
        double tp2 = fibTarget2;

        // Confirmation must be a CLOSED M1 candle, directionally close, and overlap
        // the Fibonacci entry zone. The live broker quote must still be near that zone.
        double zoneTolerance = atrM1 * 0.12;
        double confirmationOpen = confirmation.o + priceShift;
        double confirmationHigh = confirmation.h + priceShift;
        double confirmationLow = confirmation.l + priceShift;
        double confirmationClose = confirmation.c + priceShift;
        boolean candleTouchesZone = confirmationLow <= zoneHigh + zoneTolerance
            && confirmationHigh >= zoneLow - zoneTolerance;
        boolean buyCandleConfirmed = confirmationClose > confirmationOpen
            && confirmationClose >= fib618 - atrM1 * 0.08;
        boolean sellCandleConfirmed = confirmationClose < confirmationOpen
            && confirmationClose <= fib618 + atrM1 * 0.08;
        boolean quoteInZone = entry >= zoneLow - zoneTolerance && entry <= zoneHigh + zoneTolerance;
        boolean candleConfirmed = trendBuy ? buyCandleConfirmed : sellCandleConfirmed;
        boolean wick = trendBuy
            ? (Math.min(confirmation.o, confirmation.c) - confirmation.l) >= Math.abs(confirmation.c - confirmation.o) * 0.20
            : (confirmation.h - Math.max(confirmation.o, confirmation.c)) >= Math.abs(confirmation.c - confirmation.o) * 0.20;

        if (!candleTouchesZone || !candleConfirmed || !quoteInZone) {
            String reason = !quoteInZone
                ? "MENUNGGU HARGA KEMBALI KE ZONA FIBO 50–61.8%"
                : "MENUNGGU CANDLE KONFIRMASI DI ZONA FIBO 50–61.8%";
            return wait(reason, atrOk, spreadOk, wick, true, bias);
        }
        if (!spreadOk) return wait("SPREAD MT5 TERLALU LEBAR", atrOk, false, wick, true, bias);

        // Fail closed when the calculated Fibonacci levels do not leave valid risk/reward.
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
        String setup = "FIB 50–61.8% + CANDLE CONFIRM • " + side;
        double trendStrength = Math.min(1.0, emaGap / Math.max(atrM5 * 0.50, 1e-8));
        double bodyStrength = Math.min(1.0, Math.abs(confirmationClose - confirmationOpen)
            / Math.max(atrM1, 1e-8));
        double zoneHalfWidth = Math.max((zoneHigh - zoneLow) * 0.5 + zoneTolerance, 1e-8);
        double entryPrecision = 1.0 - Math.min(1.0,
            Math.abs(entry - (zoneLow + zoneHigh) * 0.5) / zoneHalfWidth);
        int score = Math.min(100, 75 + (int)Math.round(8.0 * trendStrength)
            + (int)Math.round(10.0 * bodyStrength) + (int)Math.round(7.0 * entryPrecision)
            + (wick ? 3 : 0));
        int confidence = score >= 93 ? 5 : (score >= 80 ? 4 : 3);
        // Legacy signal flags that are not part of this setup stay false; wick is
        // a displayed optional confluence, not an entry requirement.
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
