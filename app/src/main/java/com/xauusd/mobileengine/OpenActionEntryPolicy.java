package com.xauusd.mobileengine;

/** Pure timing/price helpers for the two Smart Auto Open Action modes. */
public final class OpenActionEntryPolicy {
    private OpenActionEntryPolicy() {}

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
