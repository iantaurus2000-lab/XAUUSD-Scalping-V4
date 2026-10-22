package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private Candle[] m5Trend(boolean bullish) {
        Candle[] bars = new Candle[240];
        for (int i = 0; i < bars.length; i++) {
            double close = bullish ? 80.0 + i * 0.10 : 120.0 - i * 0.10;
            double open = bullish ? close - 0.02 : close + 0.02;
            bars[i] = new Candle((i + 1) * 300_000L, open, close + 0.08,
                close - 0.08, close);
        }
        return bars;
    }

    private Candle[] m5Consolidation() {
        Candle[] bars = new Candle[240];
        for (int i = 0; i < bars.length; i++) {
            double close = 100.0 + (i % 2 == 0 ? 0.01 : -0.01);
            bars[i] = new Candle((i + 1) * 300_000L, close, close + 0.08,
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
            bars[20] = new Candle(21 * 60_000L, 99.50, 100.00, 99.00, 99.85);
            bars[28] = new Candle(29 * 60_000L, 101.70, 102.00, 101.10, 101.80);
            bars[38] = confirmation
                ? new Candle(39 * 60_000L, 100.17, 100.44, 100.08, 100.35)
                : new Candle(39 * 60_000L, 100.34, 100.44, 100.20, 100.24);
            // SignalEngine intentionally inspects the forming final M1 bar for responsiveness.
            bars[39] = confirmation
                ? new Candle(40 * 60_000L, 100.28, 100.40, 100.10, 100.35)
                : new Candle(40 * 60_000L, 100.35, 100.36, 100.29, 100.30);
        } else {
            bars[20] = new Candle(21 * 60_000L, 101.50, 102.00, 101.20, 101.40);
            bars[28] = new Candle(29 * 60_000L, 98.50, 100.00, 98.00, 98.20);
            bars[38] = confirmation
                ? new Candle(39 * 60_000L, 100.43, 100.50, 100.12, 100.20)
                : new Candle(39 * 60_000L, 100.20, 100.44, 100.12, 100.35);
            bars[39] = confirmation
                ? new Candle(40 * 60_000L, 100.35, 100.50, 100.12, 100.18)
                : new Candle(40 * 60_000L, 100.30, 100.36, 100.29, 100.35);
        }
        return bars;
    }

    @Test public void bullishTrendProducesGeometricallyValidFibonacciLimit() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, true), m5Trend(true),
            100.29, 100.31, 100.29, 100.31);
        assertEquals("BUY", s.side);
        assertEquals("BUY", s.m5Bias);
        assertTrue("Expected a Fib-based model: " + s.setup, s.setup.contains("FIBO"));
        assertTrue(s.entry < 100.31);
        assertTrue(s.sl < s.entry);
        assertTrue(s.tp1 > s.entry);
        assertTrue(s.tp2 > s.tp1);
        assertTrue(s.rr >= 1.0);
        assertTrue(s.spreadFilter);
        assertTrue(s.atrFilter);
    }

    @Test public void bearishTrendProducesGeometricallyValidFibonacciLimit() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(false, true), m5Trend(false),
            100.30, 100.32, 100.30, 100.32);
        assertEquals("SELL", s.side);
        assertEquals("SELL", s.m5Bias);
        assertTrue("Expected a Fib-based model: " + s.setup, s.setup.contains("FIBO"));
        assertTrue(s.entry > 100.30);
        assertTrue(s.sl > s.entry);
        assertTrue(s.tp1 < s.entry);
        assertTrue(s.tp2 < s.tp1);
        assertTrue(s.rr >= 1.0);
    }

    @Test public void aClearWickCanOverrideConsolidatingM5Bias() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, true), m5Consolidation(),
            100.29, 100.31, 100.29, 100.31);
        assertEquals("BUY", s.side);
        assertTrue(s.setup.contains("FIBO") || s.setup.contains("WICK LIMIT"));
        assertTrue(s.sl < s.entry);
        assertTrue(s.tp1 > s.entry);
        assertTrue(s.tp2 > s.tp1);
    }

    @Test public void candleWithoutDirectionalBodyOrRejectionWaits() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, false), m5Trend(true),
            100.29, 100.31, 100.29, 100.31);
        assertEquals("WAIT", s.side);
        assertTrue(s.setup.contains("CANDLE"));
    }

    @Test public void wideSpreadPreventsEntryEvenWhenWickSetupIsValid() {
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, true), m5Trend(true),
            100.10, 100.50, 100.10, 100.50);
        assertEquals("WAIT", s.side);
        assertTrue(s.setup.contains("SPREAD"));
        assertFalse(s.spreadFilter);
    }

    @Test public void insufficientM5HistoryFailsClosed() {
        Candle[] shortM5 = new Candle[10];
        for (int i = 0; i < shortM5.length; i++)
            shortM5[i] = new Candle((i + 1) * 300_000L, 100, 100.1, 99.9, 100);
        SignalResult s = SignalEngine.evaluate(m1FibSetup(true, true), shortM5,
            100.29, 100.31, 100.29, 100.31);
        assertEquals("WAIT", s.side);
        assertTrue(s.setup.contains("OHLC M1/M5"));
    }
}
