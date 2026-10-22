package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExcellentMachineBacktestTest {
    @Test public void supportsAllConfiguredMarketsAndRefusesUnknownMarket() {
        Candle[] bars=synthetic(360);
        assertTrue(ExcellentMachineBacktest.run("XAUUSDm","M1",bars).valid);
        assertTrue(ExcellentMachineBacktest.run("BTCUSDm","M1",bars).valid);
        assertTrue(ExcellentMachineBacktest.run("ETHUSDm","M1",bars).valid);
        ExcellentMachineBacktest.Report unknown=ExcellentMachineBacktest.run("EURUSD","M1",bars);
        assertFalse(unknown.valid);
        assertTrue(unknown.reason.contains("TIDAK DIDUKUNG"));
    }

    @Test public void refusesShortHistory() {
        ExcellentMachineBacktest.Report r=ExcellentMachineBacktest.run("XAUUSDm","M1",synthetic(150));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("MINIMAL"));
    }

    @Test public void refusesNonChronologicalHistory() {
        Candle[] bars=synthetic(360);
        bars[200]=new Candle(bars[199].t,bars[200].o,bars[200].h,bars[200].l,bars[200].c);
        ExcellentMachineBacktest.Report r=ExcellentMachineBacktest.run("XAUUSD","M1",bars);
        assertFalse(r.valid);
        assertTrue(r.reason.contains("KRONOLOGIS"));
    }

    @Test public void sameBarStopAndTargetIsAmbiguousNotWin() {
        Candle b=new Candle(1L,2400.0,2403.0,2397.0,2401.0);
        assertEquals("AMBIGUOUS",ExcellentMachineBacktest.classifyOutcome("BUY",b,2398.0,2402.0));
        assertEquals("AMBIGUOUS",ExcellentMachineBacktest.classifyOutcome("SELL",b,2402.0,2398.0));
    }

    @Test public void validHistoryProducesConsistentReport() {
        ExcellentMachineBacktest.Report r=ExcellentMachineBacktest.run("XAUUSD","M1",synthetic(360));
        assertTrue(r.valid);
        assertEquals(359,r.closedBars);
        assertTrue(r.evaluatedCandles>0);
        assertTrue(r.oneR.entries>=r.oneR.wins);
        assertTrue(r.twoR.entries>=r.twoR.wins);
        assertTrue(r.threeR.entries>=r.threeR.wins);
        assertEquals(r.oneR.entries,r.oneR.wins+r.oneR.losses+r.oneR.ambiguous+r.oneR.open);
    }

    private static Candle[] synthetic(int size) {
        Candle[] bars=new Candle[size];
        for(int i=0;i<size;i++) {
            double base=2400.0+i*0.06+Math.sin(i*0.21)*1.4;
            double open=base+Math.sin(i*0.37)*0.22;
            double close=base+Math.cos(i*0.29)*0.18;
            double high=Math.max(open,close)+0.7;
            double low=Math.min(open,close)-0.7;
            bars[i]=new Candle(i*60_000L+1L,open,high,low,close);
        }
        return bars;
    }
}
