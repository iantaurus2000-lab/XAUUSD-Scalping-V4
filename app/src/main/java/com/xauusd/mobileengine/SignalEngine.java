package com.xauusd.mobileengine;

import java.util.Locale;

public final class SignalEngine {
    private SignalEngine() {}

    public static SignalResult evaluate(Candle[] m1, Candle[] m5, double bid, double ask) {
        Candle z = m1[m1.length - 1], p = m1[m1.length - 2];
        Candle z5 = m5[m5.length - 1], p5 = m5[m5.length - 2];

        double atr = atr(m1, 14);
        double range = Math.max(atr, z.h - z.l);
        double body = Math.abs(z.c - z.o);
        double upper = z.h - Math.max(z.o, z.c);
        double lower = Math.min(z.o, z.c) - z.l;

        double e9 = ema(m1, 9), e21 = ema(m1, 21), e50 = ema(m1, 50);
        double e9_5 = ema(m5, 9), e21_5 = ema(m5, 21), e50_5 = ema(m5, 50);

        boolean biasBuy = e9_5 > e21_5 && e21_5 > e50_5;
        boolean biasSell = e9_5 < e21_5 && e21_5 < e50_5;
        String bias = biasBuy ? "BUY" : biasSell ? "SELL" : "NEUTRAL";

        boolean wickBuy = lower > Math.max(body * 1.4, range * .25);
        boolean wickSell = upper > Math.max(body * 1.4, range * .25);
        boolean bosBuy = z.c > p.h;
        boolean bosSell = z.c < p.l;

        double prevLow = Math.min(p.l, m1[m1.length - 3].l);
        double prevHigh = Math.max(p.h, m1[m1.length - 3].h);
        boolean sweepBuy = z.l < prevLow && z.c > prevLow;
        boolean sweepSell = z.h > prevHigh && z.c < prevHigh;

        double rsi = rsi(m1, 14);
        double macd = ema(m1,12) - ema(m1,26);
        boolean rsiBuy = rsi >= 50 && rsi <= 72;
        boolean rsiSell = rsi <= 50 && rsi >= 28;
        boolean macdBuy = macd > 0;
        boolean macdSell = macd < 0;
        boolean emaBuy = e9 > e21 && e21 >= e50;
        boolean emaSell = e9 < e21 && e21 <= e50;

        double spread = ask > 0 && bid > 0 ? ask - bid : 0;
        boolean spreadOk = spread <= Math.max(0.50, atr * .20);
        boolean atrOk = atr > 0;

        int buyPoints = 0, sellPoints = 0;
        if (biasBuy) buyPoints += 2;
        if (biasSell) sellPoints += 2;
        if (wickBuy) buyPoints += 2;
        if (wickSell) sellPoints += 2;
        if (sweepBuy) buyPoints += 2;
        if (sweepSell) sellPoints += 2;
        if (bosBuy) buyPoints += 2;
        if (bosSell) sellPoints += 2;
        if (emaBuy) buyPoints++;
        if (emaSell) sellPoints++;
        if (rsiBuy) buyPoints++;
        if (rsiSell) sellPoints++;
        if (macdBuy) buyPoints++;
        if (macdSell) sellPoints++;
        if (atrOk) { buyPoints++; sellPoints++; }
        if (spreadOk) { buyPoints++; sellPoints++; }

        String side = "WAIT";
        int points = Math.max(buyPoints, sellPoints);
        if (buyPoints >= 8 && buyPoints > sellPoints + 1) side = "BUY";
        else if (sellPoints >= 8 && sellPoints > buyPoints + 1) side = "SELL";

        double entry = side.equals("BUY") ? (ask > 0 ? ask : z.c) :
                       side.equals("SELL") ? (bid > 0 ? bid : z.c) : 0;
        double sl = 0, tp1 = 0, tp2 = 0, rr = 0;
        if (entry > 0) {
            double dist = Math.max(atr * 1.15, range * 1.10);
            if ("BUY".equals(side)) {
                sl = entry - dist; tp1 = entry + dist * 1.0; tp2 = entry + dist * 2.0;
            } else if ("SELL".equals(side)) {
                sl = entry + dist; tp1 = entry - dist * 1.0; tp2 = entry - dist * 2.0;
            }
            rr = 2.0;
        }

        int score = Math.min(99, 45 + points * 4);
        int confidence = points >= 13 ? 5 : points >= 11 ? 4 : points >= 9 ? 3 : points >= 7 ? 2 : 1;
        String setup = side.equals("WAIT") ? "Menunggu konfirmasi" : "Liquidity Sweep + Wick Rejection + BOS";

        return new SignalResult(side, bias, entry, sl, tp1, tp2, rr, score, confidence, setup,
            sweepBuy || sweepSell, wickBuy || wickSell, bosBuy || bosSell,
            emaBuy || emaSell, rsiBuy || rsiSell, macdBuy || macdSell, atrOk, spreadOk);
    }

    static double ema(Candle[] a, int n) {
        double k = 2.0 / (n + 1.0), e = a[0].c;
        for (int i = 1; i < a.length; i++) e = a[i].c * k + e * (1 - k);
        return e;
    }

    static double atr(Candle[] a, int n) {
        int k = Math.min(n, a.length - 1);
        if (k <= 0) return 0;
        double sum = 0;
        for (int i = a.length - k; i < a.length; i++) {
            Candle x = a[i], prev = a[i - 1];
            sum += Math.max(x.h - x.l, Math.max(Math.abs(x.h - prev.c), Math.abs(x.l - prev.c)));
        }
        return sum / k;
    }

    static double rsi(Candle[] a, int n) {
        int k = Math.min(n, a.length - 1);
        double gain = 0, loss = 0;
        for (int i = a.length - k; i < a.length; i++) {
            double d = a[i].c - a[i - 1].c;
            if (d > 0) gain += d; else loss -= d;
        }
        if (loss < 1e-9) return 100;
        double rs = (gain / k) / (loss / k);
        return 100 - 100 / (1 + rs);
    }
}
