package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private Candle[] m5Trend(boolean bullish, int count) {
        Candle[] bars = new Candle[count];
        for (int i = 0; i < bars.length; i++) {
            double close = bullish ? 80.0 + i * 0.10 : 120.0 - i * 0.10;
            double open = bullish ? close - 0.02 : close + 0.02;
            bars[i] = new Candle((i + 1) * 300_000L, open, close + 0.08,
                close - 0.08, close);
        }
        return bars;
    }

    private Candle[] m1FibSetup(boolean bullish, boolean confirmation) {
        Candle[] bars = new Candle[40];
        for (int i = 0; i < bars.length; i++) {
            double close = 100.0;
            bars[i] = new Candle((i + 1) * 60_000L, close, 100.10, 99.90, close);
        }

        if (bullish) {
            // Closed swing low occurs before swing high: impulse 99.00 -> 102.00.
            bars[20] = new Candle(21 * 60_000L, 99.50, 100.00, 99.00, 99.85);
            bars[28] = new Candle(29 * 60_000L, 101.70, 102.00, 101.10, 101.80);
            // Lower rejection wick around the Fibonacci retracement zone.
            bars[38] = confirmation
                ? new Candle(39 * 60_000L, 100.17, 100.44, 100.08, 100.35)
                : new Candle(39 * 60_000L, 100.34, 100.44, 100.20, 100.24);
            bars[39] = new Candle(40 * 60_000L, 100.35, 100.40, 100.25, 100.30);
        } else {
            // Closed swing high occurs before swing low: impulse 102.00 -> 98.00.
            bars[20] = new Candle(21 * 60_000L, 101.50, 102.00, 101.20, 101.40);
            bars[28] = new Candle(29 * 60_000L, 98.50, 100.00, 98.00, 98.20);
            bars[38] = confirmation
                ? new Candle(39 * 60_000L, 100.43, 100.68, 100.12, 100.20)
                : new Candle(39 * 60_000L, 100.20, 100.44, 100.12, 100.35);
            bars[39] = new Candle(40 * 60_000L, 100.31, 100.38, 100.25, 100.31);
        }
        return bars;
    }

    @Test public void bullishTrendProducesFibBuyLimitAndTargetsWithShortM5History() {
        Candle[] m1 = m1FibSetup(true, true);
        SignalResult s = SignalEngine.evaluate(m1, m5Trend(true, 90),
            100.29, 100.31, 100.29, 100.31);

        assertEquals("BUY", s.side);
        assertEquals("BUY", s.m5Bias);
        assertTrue("Expected Fibonacci setup, got: " + s.setup, s.setup.startsWith("FIBO "));
        assertTrue(s.wickRejection);
        assertEquals(100.146, s.entry, 1e-7); // 61.8% retracement of 99 -> 102
        assertTrue(s.sl < s.entry);
        assertTrue(s.tp1 > s.entry);
        assertTrue(s.tp2 > s.tp1);
        assertEquals(102.0, s.tp1, 1e-8);
        assertEquals(102.816, s.tp2, 1e-7);
        assertTrue(s.atrFilter);
        assertTrue(s.spreadFilter);
    }

    @Test public void bearishTrendProducesMirroredFibSellLimitAndTargets() {
        Candle[] m1 = m1FibSetup(false, true);
        SignalResult s = SignalEngine.evaluate(m1, m5Trend(false, 90),
            100.30, 100.32, 100.30, 100.32);

        assertEquals("SELL", s.side);
        assertEquals("SELL", s.m5Bias);
        assertTrue("Expected Fibonacci setup, got: " + s.setup, s.setup.startsWith("FIBO "));
        assertTrue(s.wickRejection);
        assertEquals(100.472, s.entry, 1e-7); // 61.8% retracement of 102 -> 98
        assertTrue(s.sl > s.entry);
        assertTrue(s.tp1 < s.entry);
        assertTrue(s.tp2 < s.tp1);
        assertEquals(98.0, s.tp1, 1e-8);
        assertEquals(96.912, s.tp2, 1e-7);
        assertTrue(s.atrFilter);
        assertTrue(s.spreadFilter);
    }

    @Test public void dominantRejectionWickCanValidateOppositeDirection() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, false), m5Trend(true, 90),
            100.29, 100.31, 100.29, 100.31);
        // This test candle has an upper wick rejection even though M5 bias is BUY.
        assertEquals("SELL", s.side);
        assertEquals("BUY", s.m5Bias);
    }

    @Test public void extremeSpreadBlocksButNormalSpreadPasses() {
        Candle[] m1 = m1FibSetup(true, true);
        Candle[] m5 = m5Trend(true, 90);
        SignalResult normal = SignalEngine.evaluate(m1, m5, 100.29, 100.31, 100.29, 100.31);
        SignalResult extreme = SignalEngine.evaluate(m1, m5, 100.00, 100.60, 100.00, 100.60);
        assertTrue("Normal spread unexpectedly rejected: " + normal.setup, normal.spreadFilter);
        assertTrue(normal.side.equals("BUY") || normal.side.equals("SELL"));
        assertFalse("Extreme spread unexpectedly accepted", extreme.spreadFilter);
        assertEquals("WAIT", extreme.side);
        assertTrue(extreme.setup.contains("SPREAD"));
    }

    @Test public void insufficientM5HistoryWaitsButAdaptiveHistoryDoesNotNeed200Bars() {
        SignalResult adaptive = SignalEngine.evaluate(m1FibSetup(true, true), m5Trend(true, 90),
            100.29, 100.31, 100.29, 100.31);
        assertEquals("BUY", adaptive.side);

        SignalResult tooShort = SignalEngine.evaluate(m1FibSetup(true, true), m5Trend(true, 40),
            100.29, 100.31, 100.29, 100.31);
        assertEquals("WAIT", tooShort.side);
        assertTrue(tooShort.setup.contains("M1/M5"));
    }
}
