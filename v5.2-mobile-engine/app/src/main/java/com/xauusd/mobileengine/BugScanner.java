package com.xauusd.mobileengine;

import java.util.ArrayList;
import java.util.Locale;

public final class BugScanner {
    private BugScanner() {}

    public static String scan(ArrayList<StrategyEngine.Candle> m1,
                              ArrayList<StrategyEngine.Candle> m5,
                              ArrayList<StrategyEngine.Candle> m15,
                              String source, double mid, double spread, long now) {
        if (mid <= 0) return "PRICE_INVALID";
        if (source != null && source.contains("SIM")) return "REAL_FEED_FALLBACK";
        if (m1 == null || m1.size() < 30) return "M1_HISTORY_SHORT";
        if (m5 == null || m5.size() < 20) return "M5_HISTORY_SHORT";
        if (spread <= 0 || spread > 5.0) return String.format(Locale.US, "SPREAD_ANOMALY %.2f", spread);

        StrategyEngine.Candle last = m1.get(m1.size() - 1);
        long t = last.t < 100000000000L ? last.t * 1000L : last.t;
        if (now - t > 180000L) return "M1_STALE";

        int flat = 0, invalid = 0;
        int start = Math.max(0, m1.size() - 20);
        for (int i = start; i < m1.size(); i++) {
            StrategyEngine.Candle c = m1.get(i);
            if (c.open <= 0 || c.high <= 0 || c.low <= 0 || c.close <= 0 ||
                c.high < Math.max(c.open, c.close) || c.low > Math.min(c.open, c.close)) {
                invalid++;
                continue;
            }
            if (Math.abs(c.close - c.open) < 0.00001) flat++;
        }
        if (invalid > 0) return "OHLC_INVALID";
        if (flat >= 16) return "CANDLE_BODY_FLAT";
        return "";
    }
}
