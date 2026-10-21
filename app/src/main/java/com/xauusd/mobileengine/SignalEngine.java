package com.xauusd.mobileengine;

/**
 * Unified M1 wick-rejection + Fibonacci LIMIT strategy.
 *
 * M5 EMA direction is the default bias. A clear, dominant M1 rejection wick
 * may validate the opposite side so a valid reversal is not discarded merely
 * because the EMA has not crossed yet. Fibonacci is the preferred entry map;
 * when a valid retracement level is already passed/unavailable, the engine
 * falls back to a geometrically valid wick-end LIMIT instead of suppressing
 * every signal. Broker quote/spread and valid SL/TP geometry remain mandatory.
 */
public final class SignalEngine {
    private SignalEngine() {}
    private static final int MIN_M5_HISTORY = 60;
    private static final int FIB_SWING_LOOKBACK = 40;
    private static final int FIB_RECENT_LOOKBACK = 18;

    public static SignalResult evaluate(Candle[] m1, Candle[] m5,
                                        double bid, double ask,
                                        double spreadBid, double spreadAsk) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < MIN_M5_HISTORY)
            return wait("MENUNGGU DATA OHLC M1/M5", false, false, false, false);
        if (!QuoteSafety.valid(bid, ask))
            return wait("QUOTE MT5 TIDAK VALID", false, false, false, false);

        double atrM1 = atr(m1, 14), atrM5 = atr(m5, 14);
        boolean atrOk = Double.isFinite(atrM1) && atrM1 > 0.0
            && Double.isFinite(atrM5) && atrM5 > 0.0;
        boolean spreadOk = atrOk && QuoteSafety.spreadAcceptable(spreadBid, spreadAsk, atrM1);
        if (!atrOk) return wait("ATR BELUM VALID", false, spreadOk, false, false);

        int endM5 = m5.length - 2; // use completed M5 candles
        int slowPeriod = m5.length >= 201 ? 200 : Math.max(55, Math.min(100, m5.length - 2));
        int fastPeriod = Math.min(50, Math.max(20, slowPeriod / 2));
        double emaFast = emaUntil(m5, fastPeriod, endM5);
        double emaSlow = emaUntil(m5, slowPeriod, endM5);
        boolean buyBias = emaFast >= emaSlow;
        String bias = buyBias ? "BUY" : "SELL";

        // Only use the last closed M1 candle for a stable trigger.
        int lastClosed = m1.length - 2;
        Candle c = m1[lastClosed];
        if (c == null || !validCandle(c))
            return wait("CANDLE M1 TIDAK VALID", atrOk, spreadOk, false, false, bias);

        double o = c.o, h = c.h, l = c.l, close = c.c;
        double body = Math.abs(close - o);
        double candleRange = Math.max(h - l, 1e-9);
        double lowerWick = Math.max(0.0, Math.min(o, close) - l);
        double upperWick = Math.max(0.0, h - Math.max(o, close));
        double wickFloor = Math.max(body * 0.45, atrM1 * 0.03);
        boolean bullishWick = lowerWick >= wickFloor && close >= l + candleRange * 0.50;
        boolean bearishWick = upperWick >= wickFloor && close <= h - candleRange * 0.50;

        // A clearly dominant rejection wick can override the EMA bias; otherwise
        // trade with M5 bias and accept a matching candle body OR rejection wick.
        String side;
        if (bullishWick && (!bearishWick || lowerWick >= upperWick * 1.10)) {
            side = "BUY";
        } else if (bearishWick && (!bullishWick || upperWick > lowerWick * 1.10)) {
            side = "SELL";
        } else {
            side = bias;
        }
        boolean buy = "BUY".equals(side);
        boolean wick = buy ? bullishWick : bearishWick;
        boolean alignedWithM5 = side.equals(bias);
        boolean confirmed = buy ? (close >= o || bullishWick) : (close <= o || bearishWick);
        if (!confirmed)
            return wait("MENUNGGU CANDLE ARAH M5 / WICK REJECTION M1", atrOk, spreadOk, wick, true, bias);
        if (!spreadOk)
            return wait("SPREAD MT5 DI ATAS BATAS AMAN ATR", atrOk, false, wick, true, bias);

        double mid = (bid + ask) * 0.5;
        double shift = mid - m1[m1.length - 1].c; // align feed OHLC to the current broker quote
        double spread = Math.max(0.0, spreadAsk - spreadBid);
        double buffer = Math.max(atrM1 * 0.15, spread * 1.25);

        FibSwing swing = findSwing(m1, buy, atrM1, spread);
        double entry = 0.0, sl = 0.0, tp1 = 0.0, tp2 = 0.0;
        String entryModel = "WICK LIMIT";
        double chosenFib = 0.0;

        if (swing != null) {
            double range = swing.high - swing.low;
            double[] ratios = new double[]{0.618, 0.500, 0.382, 0.786};
            double anchor = buy ? l + shift : h + shift;
            double clearance = Math.max(atrM1 * 0.01, spread * 0.25);
            double bestDistance = Double.POSITIVE_INFINITY;
            for (double ratio : ratios) {
                double level = buy ? swing.high - range * ratio : swing.low + range * ratio;
                level += shift;
                boolean validSide = buy ? level < ask - clearance : level > bid + clearance;
                if (!validSide) continue;
                double distance = Math.abs(level - anchor);
                if (distance < bestDistance && distance <= Math.max(atrM1 * 1.25, range * 0.28)) {
                    bestDistance = distance;
                    entry = level;
                    chosenFib = ratio;
                }
            }

            if (entry > 0.0) {
                double fib786 = buy ? swing.high - range * 0.786 + shift
                                    : swing.low + range * 0.786 + shift;
                sl = buy ? Math.min(l + shift, fib786) - buffer
                         : Math.max(h + shift, fib786) + buffer;
                // TP1 returns to the swing origin's opposite endpoint; TP2 is the
                // 127.2% extension. Keep both targets on the profitable side.
                tp1 = buy ? swing.high + shift : swing.low + shift;
                tp2 = buy ? swing.high + range * 0.272 + shift
                          : swing.low - range * 0.272 + shift;
                boolean geometry = buy ? (sl < entry && tp1 > entry && tp2 > tp1)
                                       : (sl > entry && tp1 < entry && tp2 < tp1);
                if (geometry) entryModel = "FIBO " + fibLabel(chosenFib) + " + " + (wick ? "WICK REJECTION" : "CANDLE");
                else { entry = 0.0; chosenFib = 0.0; }
            }
        }

        // Fallback keeps the strategy responsive when the swing level has been
        // passed or the latest wick does not sit near a usable Fib price.
        if (entry <= 0.0) {
            double entryOffset = Math.max(atrM1 * 0.03, spread * 0.25);
            entry = buy ? l + shift + entryOffset : h + shift - entryOffset;
            sl = buy ? l + shift - buffer : h + shift + buffer;
            if (buy && entry - sl < atrM1 * 0.35) sl = entry - atrM1 * 0.75;
            if (!buy && sl - entry < atrM1 * 0.35) sl = entry + atrM1 * 0.75;
            double risk = Math.abs(entry - sl);
            tp1 = buy ? entry + risk : entry - risk;
            tp2 = buy ? entry + risk * 1.8 : entry - risk * 1.8;
            entryModel = "WICK LIMIT FALLBACK";
        }

        boolean geometry = buy ? (entry < ask && sl < entry && tp1 > entry && tp2 > tp1)
                               : (entry > bid && sl > entry && tp1 < entry && tp2 < tp1);
        if (!geometry || !Double.isFinite(entry) || !Double.isFinite(sl)
                || !Double.isFinite(tp1) || !Double.isFinite(tp2)
                || entry <= 0.0 || sl <= 0.0) {
            return wait("LIMIT BELUM DI SISI HARGA YANG VALID", atrOk, spreadOk, wick, true, bias);
        }

        double trendStrength = Math.min(1.0,
            Math.abs(emaFast - emaSlow) / Math.max(atrM5 * 0.35, 1e-8));
        double candleStrength = Math.min(1.0,
            Math.max(body, Math.max(lowerWick, upperWick)) / Math.max(atrM1, 1e-8));
        int score = Math.min(98, 70 + (int)Math.round(10 * trendStrength)
            + (int)Math.round(8 * candleStrength) + (wick ? 8 : 0)
            + (chosenFib > 0.0 ? 4 : 0) - (!alignedWithM5 ? 4 : 0));
        int confidence = score >= 90 ? 5 : score >= 80 ? 4 : 3;
        boolean sweep = buy ? (lastClosed > 0 && l < m1[lastClosed - 1].l && close > m1[lastClosed - 1].l)
                            : (lastClosed > 0 && h > m1[lastClosed - 1].h && close < m1[lastClosed - 1].h);
        return new SignalResult(side, bias, entry, sl, tp1, tp2,
            Math.abs(tp1 - entry) / Math.max(Math.abs(entry - sl), 1e-8),
            score, confidence, entryModel + " • M5 EMA " + (slowPeriod == 200 ? "50/200" : fastPeriod + "/" + slowPeriod),
            sweep, wick, true, alignedWithM5, false, false, atrOk, spreadOk);
    }

    private static boolean validCandle(Candle c) {
        return Double.isFinite(c.o) && Double.isFinite(c.h) && Double.isFinite(c.l)
            && Double.isFinite(c.c) && c.o > 0.0 && c.h >= c.l
            && c.h >= Math.max(c.o, c.c) && c.l <= Math.min(c.o, c.c);
    }

    private static final class FibSwing {
        final double low, high;
        FibSwing(double low, double high) { this.low = low; this.high = high; }
    }

    private static FibSwing findSwing(Candle[] a, boolean buy, double atr, double spread) {
        int end = a.length - 2;
        int recentStart = Math.max(1, end - FIB_RECENT_LOOKBACK + 1);
        int historyStart = Math.max(0, end - FIB_SWING_LOOKBACK + 1);
        if (end - historyStart < 8) return null;
        int pivot = recentStart;
        if (buy) {
            for (int i = recentStart + 1; i <= end; i++)
                if (a[i].h >= a[pivot].h) pivot = i;
            if (pivot <= historyStart) return null;
            int lowIndex = historyStart;
            for (int i = historyStart + 1; i < pivot; i++)
                if (a[i].l < a[lowIndex].l) lowIndex = i;
            double low = a[lowIndex].l, high = a[pivot].h;
            if (!(high > low) || high - low < Math.max(atr * 0.60, spread * 2.0)) return null;
            return new FibSwing(low, high);
        } else {
            for (int i = recentStart + 1; i <= end; i++)
                if (a[i].l <= a[pivot].l) pivot = i;
            if (pivot <= historyStart) return null;
            int highIndex = historyStart;
            for (int i = historyStart + 1; i < pivot; i++)
                if (a[i].h > a[highIndex].h) highIndex = i;
            double low = a[pivot].l, high = a[highIndex].h;
            if (!(high > low) || high - low < Math.max(atr * 0.60, spread * 2.0)) return null;
            return new FibSwing(low, high);
        }
    }

    private static String fibLabel(double ratio) {
        if (Math.abs(ratio - 0.382) < 0.001) return "38.2%";
        if (Math.abs(ratio - 0.500) < 0.001) return "50%";
        if (Math.abs(ratio - 0.618) < 0.001) return "61.8%";
        return "78.6%";
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
        double k = 2.0 / (period + 1.0), value = a[0].c;
        for (int i = 1; i <= end; i++) value = a[i].c * k + value * (1.0 - k);
        return value;
    }
    static double atr(Candle[] a, int period) {
        if (a == null || a.length < 2 || period <= 0) return 0.0;
        int end = a.length - 2; // closed candles only
        if (end < 1) return 0.0;
        int count = Math.min(period, end);
        double sum = 0.0;
        for (int i = end - count + 1; i <= end; i++) {
            Candle current = a[i], previous = a[i - 1];
            if (current == null || previous == null || !validCandle(current) || !validCandle(previous)) return 0.0;
            sum += Math.max(current.h - current.l,
                Math.max(Math.abs(current.h - previous.c), Math.abs(current.l - previous.c)));
        }
        return sum / count;
    }
}
