package com.xauusd.mobileengine;

/** Pure timing/price helpers for the two Smart Auto Open Action modes. */
public final class OpenActionEntryPolicy {
    private OpenActionEntryPolicy() {}

    /**
     * One-third of the preceding candle's effective range.
     * Adapt to short candles with a 0.36 ATR floor and cap very long candles at 1.5 ATR.
     */
    public static double oneThirdTravelThreshold(Candle previous, double atr) {
        if (previous == null || !Double.isFinite(atr) || atr <= 0.0
                || !Double.isFinite(previous.h) || !Double.isFinite(previous.l)
                || previous.h < previous.l) return 0.0;
        double previousRange = previous.h - previous.l;
        double referenceRange = Math.max(atr * 0.36, Math.min(previousRange, atr * 1.50));
        return referenceRange / 3.0;
    }

    /**
     * Sweep-first must first travel one-third of an adaptive candle range away from
     * the new M1 open: BUY measures the downward excursion; SELL the upward excursion.
     * High/low excursions persist after a recoil, so the wick can form before READY.
     */
    public static boolean hasOneThirdCandleTravel(Candle[] m1, String side, double atr) {
        if (m1 == null || m1.length < 2 || side == null) return false;
        boolean buy = "BUY".equals(side);
        boolean sell = "SELL".equals(side);
        if (!buy && !sell) return false;

        Candle previous = m1[m1.length - 2];
        Candle live = m1[m1.length - 1];
        double threshold = oneThirdTravelThreshold(previous, atr);
        if (threshold <= 0.0 || !Double.isFinite(live.o)
                || !Double.isFinite(live.h) || !Double.isFinite(live.l)
                || live.h < live.l || live.o < live.l || live.o > live.h) return false;

        double excursion = buy ? live.o - live.l : live.h - live.o;
        return Double.isFinite(excursion) && excursion >= threshold;
    }

    /**
     * Adaptive exhaustion travel distance: require a meaningful extension, but not a fixed
     * one-third candle rule. The live extreme must then recoil before Open Action 1 can fire.
     */
    public static double exhaustionTravelThreshold(Candle previous, double atr) {
        if (previous == null || !Double.isFinite(atr) || atr <= 0.0
                || !Double.isFinite(previous.h) || !Double.isFinite(previous.l)
                || previous.h < previous.l) return 0.0;
        double previousRange = previous.h - previous.l;
        return Math.max(atr * 0.20, Math.min(previousRange * 0.22, atr * 0.65));
    }

    /** Adaptive one-bounce distance, smaller than the former fixed 0.15 ATR/10-point gate. */
    public static double bounceThreshold(double atr, double wickLength, double point) {
        if (!Double.isFinite(atr) || atr <= 0.0 || !Double.isFinite(wickLength)
                || wickLength < 0.0 || !Double.isFinite(point) || point <= 0.0) return 0.0;
        double wickAdaptive = wickLength > 0.0
                ? Math.min(atr * 0.08, wickLength * 0.20) : atr * 0.025;
        return Math.max(point * 2.0, Math.max(atr * 0.025, wickAdaptive));
    }

    /**
     * Keep the pending LIMIT in the outer 10% of the rejection wick, rather than
     * pulling it back toward the candle body/current market.
     */
    public static double wickTipOffset(double wickLength, double atr, double minTick) {
        if (!Double.isFinite(wickLength) || wickLength <= 0.0
                || !Double.isFinite(atr) || atr <= 0.0
                || !Double.isFinite(minTick) || minTick <= 0.0) return 0.0;
        double desired = Math.max(minTick, Math.min(atr * 0.02, wickLength * 0.08));
        return Math.min(wickLength * 0.10, desired);
    }
}
