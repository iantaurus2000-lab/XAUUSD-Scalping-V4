package com.xauusd.mobileengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Separate Stage-1 analysis engine for the APK-supported markets (XAUUSD, BTCUSD, ETHUSD).
 *
 * This is an auditable Lorentzian-style 5-nearest-neighbour prototype, not a
 * byte-for-byte port of the TradingView "Lorentzian Classification" script.
 * It uses closed candles only and never places orders.
 */
public final class ExcellentMachineEngine {
    private static final int DONCHIAN_PERIOD = 64;
    private static final int EMA_PERIOD = 150;
    private static final int NEIGHBORS = 5;
    private static final int MAX_BARS_BACK = 200;
    private static final int LABEL_HORIZON = 4;
    private static final int MIN_BARS = 180;

    private ExcellentMachineEngine() {}

    public static final class Result {
        public final boolean ready;
        public final String side, reason, donchianSide, emaSide, mlSide;
        public final int buyVotes, sellVotes, confidence, barsUsed;
        public final long candleTime;
        public final double signalClose, donchianHigh, donchianLow, donchianMid;
        public final double ema150, rsi6, cci15, rsi14, atr;
        public final double entry, sl, tp1, tp2, tp3;

        private Result(boolean ready, String side, String reason,
                       String donchianSide, String emaSide, String mlSide,
                       int buyVotes, int sellVotes, int confidence, int barsUsed,
                       long candleTime, double signalClose,
                       double donchianHigh, double donchianLow, double donchianMid,
                       double ema150, double rsi6, double cci15, double rsi14, double atr,
                       double entry, double sl, double tp1, double tp2, double tp3) {
            this.ready=ready; this.side=side; this.reason=reason;
            this.donchianSide=donchianSide; this.emaSide=emaSide; this.mlSide=mlSide;
            this.buyVotes=buyVotes; this.sellVotes=sellVotes; this.confidence=confidence;
            this.barsUsed=barsUsed; this.candleTime=candleTime; this.signalClose=signalClose;
            this.donchianHigh=donchianHigh; this.donchianLow=donchianLow; this.donchianMid=donchianMid;
            this.ema150=ema150; this.rsi6=rsi6; this.cci15=cci15; this.rsi14=rsi14; this.atr=atr;
            this.entry=entry; this.sl=sl; this.tp1=tp1; this.tp2=tp2; this.tp3=tp3;
        }
    }

    private static final class Sample {
        final double distance;
        final int label;
        Sample(double distance, int label) { this.distance=distance; this.label=label; }
    }

    public static Result evaluate(String market, String timeframe, Candle[] candles) {
        if (!MarketSupport.isSupportedMarket(market)) {
            return waitResult("MARKET TIDAK DIDUKUNG • PILIH XAUUSD / BTCUSD / ETHUSD", 0, 0, 0L, 0.0);
        }
        if (candles == null || candles.length < MIN_BARS) {
            return waitResult("RIWAYAT OHLC KURANG • PERLU MINIMAL " + MIN_BARS + " CANDLE", 
                    candles == null ? 0 : candles.length, 0, 0L, 0.0);
        }

        // Feeds normally append the currently forming bar last. Only candles[-2]
        // and earlier are eligible so the analysis waits for the candle to close.
        final int end = candles.length - 2;
        for (int i = 0; i <= end; i++) {
            if (!valid(candles[i])) {
                return waitResult("DATA OHLC TIDAK VALID PADA INDEX " + i, candles.length, 0,
                        candles[end] == null ? 0L : candles[end].t, 0.0);
            }
        }
        if (end < EMA_PERIOD || end - DONCHIAN_PERIOD < 1) {
            return waitResult("MENUNGGU PEMANASAN EMA150 / DONCHIAN64",
                    candles.length, 0, candles[end].t, candles[end].c);
        }

        Candle signal = candles[end];
        double ema = ema(candles, end, EMA_PERIOD);
        String emaSide = signal.c > ema ? "BUY" : signal.c < ema ? "SELL" : "NEUTRAL";

        double high = highestHigh(candles, end - DONCHIAN_PERIOD + 1, end);
        double low = lowestLow(candles, end - DONCHIAN_PERIOD + 1, end);
        double mid = (high + low) * 0.5;
        double prevHigh = highestHigh(candles, end - DONCHIAN_PERIOD, end - 1);
        double prevLow = lowestLow(candles, end - DONCHIAN_PERIOD, end - 1);
        double prevMid = (prevHigh + prevLow) * 0.5;
        String donchianSide = signal.c > mid && mid >= prevMid ? "BUY"
                : signal.c < mid && mid <= prevMid ? "SELL" : "NEUTRAL";

        double currentRsi6 = rsi(candles, end, 6);
        double currentCci15 = cci(candles, end, 15);
        double currentRsi14 = rsi(candles, end, 14);
        double currentAtr = atr(candles, end, 14);
        double[] currentFeatures = features(currentRsi6, currentCci15, currentRsi14);

        List<Sample> samples = new ArrayList<>();
        int start = Math.max(20, end - MAX_BARS_BACK);
        int lastTrain = end - LABEL_HORIZON;
        for (int i = start; i <= lastTrain; i++) {
            if (i + LABEL_HORIZON > end) continue;
            double r6 = rsi(candles, i, 6);
            double c15 = cci(candles, i, 15);
            double r14 = rsi(candles, i, 14);
            double[] f = features(r6, c15, r14);
            int label = candles[i + LABEL_HORIZON].c >= candles[i].c ? 1 : -1;
            samples.add(new Sample(lorentzianDistance(currentFeatures, f), label));
        }
        Collections.sort(samples, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) {
                return Double.compare(a.distance, b.distance);
            }
        });
        int buyVotes = 0, sellVotes = 0;
        int used = Math.min(NEIGHBORS, samples.size());
        for (int i = 0; i < used; i++) {
            if (samples.get(i).label > 0) buyVotes++;
            else sellVotes++;
        }
        String mlSide = used < NEIGHBORS || buyVotes == sellVotes ? "NEUTRAL"
                : buyVotes > sellVotes ? "BUY" : "SELL";
        int confidence = used == 0 ? 0 : Math.max(buyVotes, sellVotes) * 100 / used;

        boolean aligned = "BUY".equals(donchianSide) && "BUY".equals(emaSide) && "BUY".equals(mlSide)
                || "SELL".equals(donchianSide) && "SELL".equals(emaSide) && "SELL".equals(mlSide);
        String reason;
        if (used < NEIGHBORS) reason = "DATA TRAINING KNN BELUM CUKUP";
        else if ("NEUTRAL".equals(donchianSide)) reason = "DONCHIAN64 BELUM MENENTUKAN TREND";
        else if ("NEUTRAL".equals(emaSide)) reason = "HARGA SAMA DENGAN EMA150";
        else if (!donchianSide.equals(emaSide)) reason = "DONCHIAN64 DAN EMA150 BERTENTANGAN";
        else if ("NEUTRAL".equals(mlSide)) reason = "VOTE ML IMBANG";
        else if (!mlSide.equals(emaSide)) reason = "VOTE ML BELUM SEARAH DENGAN TREND";
        else reason = "SEMUA FILTER SEARAH • MENUNGGU ORDER MANUAL/TAHAP LANJUTAN";

        boolean ready = aligned && currentAtr > 0.0 && Double.isFinite(currentAtr);
        double entry = 0.0, sl = 0.0, tp1 = 0.0, tp2 = 0.0, tp3 = 0.0;
        if (ready) {
            entry = signal.c; // reference only: confirmed signal candle close
            double buffer = Math.max(currentAtr * 0.15, 0.01);
            if ("BUY".equals(emaSide)) {
                sl = signal.l - buffer;
                double risk = entry - sl;
                if (risk > 0) { tp1=entry+risk; tp2=entry+2*risk; tp3=entry+3*risk; }
            } else {
                sl = signal.h + buffer;
                double risk = sl - entry;
                if (risk > 0) { tp1=entry-risk; tp2=entry-2*risk; tp3=entry-3*risk; }
            }
            if (entry <= 0 || sl <= 0 || tp1 <= 0 || tp2 <= 0 || tp3 <= 0) {
                ready = false;
                reason = "LEVEL REFERENSI SL/TP TIDAK VALID";
                entry=sl=tp1=tp2=tp3=0.0;
            }
        }
        return new Result(ready, ready ? emaSide : "WAIT", reason, donchianSide, emaSide, mlSide,
                buyVotes, sellVotes, confidence, candles.length, signal.t, signal.c,
                high, low, mid, ema, currentRsi6, currentCci15, currentRsi14, currentAtr,
                entry, sl, tp1, tp2, tp3);
    }

    private static Result waitResult(String reason, int bars, int ignored, long time, double close) {
        return new Result(false, "WAIT", reason, "N/A", "N/A", "N/A", 0, 0, 0, bars,
                time, close, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static boolean valid(Candle c) {
        return c != null && Double.isFinite(c.o) && Double.isFinite(c.h)
                && Double.isFinite(c.l) && Double.isFinite(c.c)
                && c.o > 0 && c.h >= c.l && c.h >= Math.max(c.o, c.c)
                && c.l <= Math.min(c.o, c.c);
    }

    private static double[] features(double rsi6, double cci15, double rsi14) {
        return new double[] { rsi6 / 100.0, 0.5 + Math.atan(cci15 / 100.0) / Math.PI, rsi14 / 100.0 };
    }

    // Lorentzian-style distance compresses large one-feature differences.
    private static double lorentzianDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum += Math.log1p(Math.abs(a[i] - b[i]));
        return sum;
    }

    private static double ema(Candle[] a, int end, int period) {
        double k = 2.0 / (period + 1.0), value = a[0].c;
        for (int i = 1; i <= end; i++) value = a[i].c * k + value * (1.0 - k);
        return value;
    }

    private static double rsi(Candle[] a, int end, int period) {
        if (end < period) return 50.0;
        double gain = 0.0, loss = 0.0;
        for (int i = 1; i <= period; i++) {
            double d = a[i].c - a[i - 1].c;
            if (d > 0) gain += d; else loss -= d;
        }
        double avgGain = gain / period, avgLoss = loss / period;
        for (int i = period + 1; i <= end; i++) {
            double d = a[i].c - a[i - 1].c;
            avgGain = (avgGain * (period - 1) + Math.max(0.0, d)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(0.0, -d)) / period;
        }
        if (avgLoss <= 1e-12) return avgGain <= 1e-12 ? 50.0 : 100.0;
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
    }

    private static double cci(Candle[] a, int end, int period) {
        if (end + 1 < period) return 0.0;
        double sum = 0.0;
        for (int i = end - period + 1; i <= end; i++) sum += typical(a[i]);
        double mean = sum / period, deviation = 0.0;
        for (int i = end - period + 1; i <= end; i++) deviation += Math.abs(typical(a[i]) - mean);
        double meanDeviation = deviation / period;
        if (meanDeviation <= 1e-12) return 0.0;
        return (typical(a[end]) - mean) / (0.015 * meanDeviation);
    }

    private static double typical(Candle c) { return (c.h + c.l + c.c) / 3.0; }

    private static double atr(Candle[] a, int end, int period) {
        if (end < 1) return 0.0;
        int count = Math.min(period, end);
        double sum = 0.0;
        for (int i = end - count + 1; i <= end; i++) {
            Candle curr = a[i], prev = a[i - 1];
            sum += Math.max(curr.h - curr.l,
                    Math.max(Math.abs(curr.h - prev.c), Math.abs(curr.l - prev.c)));
        }
        return sum / count;
    }

    private static double highestHigh(Candle[] a, int start, int end) {
        double value = -Double.MAX_VALUE;
        for (int i = Math.max(0, start); i <= end; i++) value = Math.max(value, a[i].h);
        return value;
    }

    private static double lowestLow(Candle[] a, int start, int end) {
        double value = Double.MAX_VALUE;
        for (int i = Math.max(0, start); i <= end; i++) value = Math.min(value, a[i].l);
        return value;
    }
}
