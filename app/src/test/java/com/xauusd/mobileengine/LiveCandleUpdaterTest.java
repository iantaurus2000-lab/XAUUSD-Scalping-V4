package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveCandleUpdaterTest {
    @Test public void updatesLiveBarExtremesAndClose() {
        Candle[] bars={new Candle(60_000L,100,101,99,100.5)};
        Candle[] same=LiveCandleUpdater.update(bars,60_000L,98.5,119_000L);
        assertSame(bars,same);
        assertEquals(101.0,bars[0].h,1e-9);
        assertEquals(98.5,bars[0].l,1e-9);
        assertEquals(98.5,bars[0].c,1e-9);
    }

    @Test public void createsNextBarOnlyAtOneExactBoundary() {
        Candle[] bars={new Candle(60_000L,100,101,99,100.5)};
        Candle[] next=LiveCandleUpdater.update(bars,60_000L,102,120_000L);
        assertEquals(2,next.length);
        assertEquals(120_000L,next[1].t);
        assertEquals(100.5,next[1].o,1e-9);
        assertEquals(102.0,next[1].h,1e-9);
        assertEquals(100.5,next[1].l,1e-9);
        assertEquals(102.0,next[1].c,1e-9);
    }

    @Test public void doesNotFabricateMissingBarsOrAcceptInvalidPrice() {
        Candle[] bars={new Candle(60_000L,100,101,99,100.5)};
        assertSame(bars,LiveCandleUpdater.update(bars,60_000L,102,240_000L));
        assertSame(bars,LiveCandleUpdater.update(bars,60_000L,Double.NaN,119_000L));
    }
}