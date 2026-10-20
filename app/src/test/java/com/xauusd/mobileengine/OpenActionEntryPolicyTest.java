package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpenActionEntryPolicyTest {
    @Test public void exhaustionThresholdAdaptsToShortAverageAndLongCandles() {
        Candle shortCandle = new Candle(60_000L, 100.0, 100.1, 100.0, 100.05);
        Candle averageCandle = new Candle(60_000L, 100.0, 101.0, 100.0, 100.5);
        Candle longCandle = new Candle(60_000L, 100.0, 103.0, 100.0, 102.0);
        assertEquals(0.20, OpenActionEntryPolicy.exhaustionTravelThreshold(shortCandle, 1.0), 1e-9);
        assertEquals(0.22, OpenActionEntryPolicy.exhaustionTravelThreshold(averageCandle, 1.0), 1e-9);
        assertEquals(0.65, OpenActionEntryPolicy.exhaustionTravelThreshold(longCandle, 1.0), 1e-9);
    }

    @Test public void invalidCandleOrAtrDoesNotCreateExhaustionThreshold() {
        Candle valid = new Candle(60_000L, 100.0, 101.0, 100.0, 100.5);
        Candle invalid = new Candle(60_000L, 100.0, 99.0, 100.0, 99.5);
        assertEquals(0.0, OpenActionEntryPolicy.exhaustionTravelThreshold(valid, 0.0), 1e-9);
        assertEquals(0.0, OpenActionEntryPolicy.exhaustionTravelThreshold(valid, Double.NaN), 1e-9);
        assertEquals(0.0, OpenActionEntryPolicy.exhaustionTravelThreshold(invalid, 1.0), 1e-9);
    }

    @Test public void wickTipOffsetStaysNearTipForShortAndLongWicks() {
        double shortWickOffset = OpenActionEntryPolicy.wickTipOffset(0.03, 0.5, 0.001);
        double longWickOffset = OpenActionEntryPolicy.wickTipOffset(0.50, 0.5, 0.001);
        assertTrue(shortWickOffset > 0.0 && shortWickOffset <= 0.03 * 0.10);
        assertTrue(longWickOffset > 0.0 && longWickOffset <= 0.50 * 0.10);
        assertEquals(0.0024, shortWickOffset, 1e-9);
        assertEquals(0.01, longWickOffset, 1e-9);
    }

    @Test public void invalidSideOrInvalidAtrDoesNotPassSweepGate() {
        Candle[] bars = {
            new Candle(60_000L, 100, 101, 99, 100),
            new Candle(120_000L, 100, 101, 99, 100)
        };
        assertFalse(OpenActionEntryPolicy.hasOneThirdCandleTravel(bars, "WAIT", 1.0));
        assertFalse(OpenActionEntryPolicy.hasOneThirdCandleTravel(bars, "BUY", 0.0));
    }
    @Test public void bounceThresholdAdaptsToWickLengthAndStaysBelowOldFixedGate() {
        double shortWick = OpenActionEntryPolicy.bounceThreshold(1.0, 0.10, 0.001);
        double longWick = OpenActionEntryPolicy.bounceThreshold(1.0, 0.80, 0.001);
        assertEquals(0.025, shortWick, 1e-9);
        assertEquals(0.08, longWick, 1e-9);
        assertTrue(shortWick < 0.15);
        assertTrue(longWick < 0.15);
        assertEquals(0.0, OpenActionEntryPolicy.bounceThreshold(0.0, 0.1, 0.001), 1e-9);
    }

}
