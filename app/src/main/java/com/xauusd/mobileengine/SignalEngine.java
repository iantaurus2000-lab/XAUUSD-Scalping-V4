package com.xauusd.mobileengine;

/**
 * Simple responsive trend/wick engine.
 * M5 EMA50/EMA200 defines the directional bias. The last CLOSED M1 candle
 * confirms continuation or wick rejection. Fibonacci-zone entry gating is
 * intentionally removed; broker quote freshness and spread remain mandatory.
 */
public final class SignalEngine {
    private SignalEngine() {}
    private static final int M5_TREND_HISTORY = 201;

    public static SignalResult evaluate(Candle[] m1, Candle[] m5,
                                        double bid, double ask,
                                        double spreadBid, double spreadAsk) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < M5_TREND_HISTORY)
            return wait("MENUNGGU DATA M1/M5 YANG CUKUP", false, false, false, false);
        if (!QuoteSafety.valid(bid, ask))
            return wait("QUOTE MT5 TIDAK VALID", false, false, false, false);

        double atrM1 = atr(m1, 14), atrM5 = atr(m5, 14);
        boolean atrOk = Double.isFinite(atrM1) && atrM1 > 0.0
            && Double.isFinite(atrM5) && atrM5 > 0.0;
        boolean spreadOk = atrOk && QuoteSafety.spreadAcceptable(spreadBid, spreadAsk, atrM1);
        if (!atrOk) return wait("ATR BELUM VALID", false, spreadOk, false, false);

        int n5 = m5.length;
        double ema50 = emaUntil(m5, 50, n5 - 2);
        double ema200 = emaUntil(m5, 200, n5 - 2);
        boolean buyBias = ema50 > ema200;
        boolean sellBias = ema50 < ema200;
        String bias = buyBias ? "BUY" : sellBias ? "SELL" : "NEUTRAL";
        if (!buyBias && !sellBias)
            return wait("ARAH TREN M5 BELUM JELAS", atrOk, spreadOk, false, false, bias);

        // Only the last CLOSED M1 candle is used to avoid unstable live-candle signals.
        Candle c1 = m1[m1.length - 2];
        double o = c1.o, h = c1.h, l = c1.l, c = c1.c;
        double body = Math.abs(c - o);
        double range = Math.max(h - l, 1e-9);
        double lowerWick = Math.min(o, c) - l;
        double upperWick = h - Math.max(o, c);
        boolean bullishWick = lowerWick >= Math.max(body * 0.50, atrM1 * 0.05)
            && c >= l + range * 0.55;
        boolean bearishWick = upperWick >= Math.max(body * 0.50, atrM1 * 0.05)
            && c <= l + range * 0.45;
        boolean confirmed = buyBias ? (c > o || bullishWick) : (c < o || bearishWick);
        boolean wick = buyBias ? bullishWick : bearishWick;
        if (!confirmed)
            return wait("MENUNGGU KONFIRMASI CANDLE / REJECTION WICK M1", atrOk, spreadOk, wick, true, bias);
        if (!spreadOk)
            return wait("SPREAD MT5 TERLALU LEBAR", atrOk, false, wick, true, bias);

        double mid = (bid + ask) * 0.5;
        double shift = mid - m1[m1.length - 1].c;
        double entry = buyBias ? ask : bid;
        double buffer = Math.max(atrM1 * 0.15, Math.max(0.0, spreadAsk - spreadBid) * 2.0);
        double sl = buyBias ? (l + shift - buffer) : (h + shift + buffer);
        // If the rejection candle is unusually small, keep a practical ATR-based stop.
        if (buyBias && entry - sl < atrM1 * 0.35) sl = entry - atrM1 * 0.75;
        if (sellBias && sl - entry < atrM1 * 0.35) sl = entry + atrM1 * 0.75;
        double risk = Math.abs(entry - sl);
        double tp1 = buyBias ? entry + risk : entry - risk;
        double tp2 = buyBias ? entry + risk * 1.8 : entry - risk * 1.8;
        if (!(risk > 0.0) || !Double.isFinite(risk) || entry <= 0.0 || sl <= 0.0)
            return wait("LEVEL ENTRY / SL TIDAK VALID", atrOk, spreadOk, wick, true, bias);

        double trendStrength = Math.min(1.0, Math.abs(ema50 - ema200) / Math.max(atrM5 * 0.5, 1e-8));
        double candleStrength = Math.min(1.0, Math.max(body, Math.max(lowerWick, upperWick)) / Math.max(atrM1, 1e-8));
        int score = Math.min(98, 75 + (int)Math.round(12 * trendStrength)
            + (int)Math.round(8 * candleStrength) + (wick ? 3 : 0));
        int confidence = score >= 90 ? 5 : score >= 80 ? 4 : 3;
        String side = buyBias ? "BUY" : "SELL";
        return new SignalResult(side, bias, entry, sl, tp1, tp2, 1.8,
            score, confidence, "TREND M5 + CANDLE / WICK M1 • " + side,
            false, wick, true, true, false, false, atrOk, spreadOk);
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
