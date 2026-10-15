package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmartLimitReentryTest {
    @Test public void countsElapsedMinuteBarsWithoutGoingNegative() {
        assertEquals(3, SmartLimitReentry.elapsedM1Bars(60000L, 240000L));
        assertEquals(0, SmartLimitReentry.elapsedM1Bars(240000L, 60000L));
        assertEquals(0, SmartLimitReentry.elapsedM1Bars(0L, 240000L));
    }

    @Test public void requiresDirectionalProgressForContinuation() {
        Candle[] up = new Candle[]{
            new Candle(0,99,100,98,99),
            new Candle(60000,100,102,99.5,101),
            new Candle(120000,101,103,100.5,102),
            new Candle(180000,102,104,101.5,103),
            new Candle(240000,103,104,102.5,103.5)
        };
        assertTrue(SmartLimitReentry.hasContinuation(up,"BUY"));
        assertFalse(SmartLimitReentry.hasContinuation(up,"SELL"));
    }

    @Test public void doesNotTreatFlatBarsAsContinuation() {
        Candle[] flat = new Candle[]{
            new Candle(0,100,101,99,100),
            new Candle(60000,100,101,99,100),
            new Candle(120000,100,101,99,100),
            new Candle(180000,100,101,99,100),
            new Candle(240000,100,101,99,100)
        };
        assertFalse(SmartLimitReentry.hasContinuation(flat,"BUY"));
        assertFalse(SmartLimitReentry.hasContinuation(flat,"SELL"));
    }

    @Test public void requiresFavorableMoveAndMateriallyDifferentReplacement() {
        assertTrue(SmartLimitReentry.movedAwayInFavor("BUY",100,100.4,100.5,1));
        assertFalse(SmartLimitReentry.movedAwayInFavor("BUY",100,100.1,100.2,1));
        assertTrue(SmartLimitReentry.movedAwayInFavor("SELL",100,99.5,99.6,1));
        assertTrue(SmartLimitReentry.materiallyDifferentEntry(100,100.2,1));
        assertFalse(SmartLimitReentry.materiallyDifferentEntry(100,100.05,1));
    }

    @Test public void blocksReplacementBeyondConfiguredAtrDistance() {
        assertTrue(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,2001.5,1,1.5));
        assertFalse(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,2001.51,1,1.5));
        assertTrue(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,1998.5,1,1.5));
        assertFalse(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,2001,0,1.5));
        assertFalse(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,2001,1,0.4));
        assertFalse(SmartLimitReentry.withinMaxDistanceFromOriginalEntry(2000,Double.NaN,1,1.5));
    }
}
