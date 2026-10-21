package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private Candle[] history(double open,double high,double low,double close) {
        Candle[] bars=new Candle[40];
        for(int i=0;i<bars.length-1;i++){
            double c=100.0+(i%3)*0.01;
            bars[i]=new Candle((i+1)*60_000L,c,c+0.20,c-0.20,c);
        }
        bars[bars.length-1]=new Candle(40*60_000L,open,high,low,close);
        return bars;
    }

    private Candle[] context() {
        Candle[] bars=new Candle[40];
        for(int i=0;i<bars.length;i++){
            double c=100.0+(i%3)*0.01;
            bars[i]=new Candle((i+1)*300_000L,c,c+0.20,c-0.20,c);
        }
        return bars;
    }

    @Test public void recognizesBuyAtFirstSmallReclaimAfterLowSweep() {
        Candle[] m1=history(100.0,100.1,98.8,98.9);
        SignalResult s=SignalEngine.evaluate(m1,context(),98.89,98.91,98.89,98.91);
        assertEquals("BUY",s.side);
        assertTrue(s.liquiditySweep);
        assertTrue(s.entry>0&&s.entry<98.91);
        assertTrue(s.entry-m1[m1.length-1].l
            <= (Math.min(m1[m1.length-1].o,m1[m1.length-1].c)-m1[m1.length-1].l)*0.10+1e-9);
        assertTrue(s.sl<s.entry&&s.tp1>s.entry&&s.tp2>s.tp1);
        double range=m1[m1.length-1].h-m1[m1.length-1].l;
        assertEquals(m1[m1.length-1].l-range*0.27
            -Math.max(SignalEngine.atr(m1,14)*0.03,(98.91-98.89)*2.0),s.sl,1e-8);
        assertEquals(m1[m1.length-1].l+range*0.618,s.tp1,1e-8);
        assertEquals(m1[m1.length-1].h,s.tp2,1e-8);
        assertTrue(s.setup.contains("EARLY WICK")||s.setup.contains("NEEDLE WICK"));
    }

    @Test public void recognizesSellSymmetricallyAtFirstRecoilAfterHighSweep() {
        Candle[] m1=history(100.0,101.2,99.9,101.1);
        SignalResult s=SignalEngine.evaluate(m1,context(),101.09,101.11,101.09,101.11);
        assertEquals("SELL",s.side);
        assertTrue(s.liquiditySweep);
        assertTrue(s.entry>101.09);
        assertTrue(m1[m1.length-1].h-s.entry
            <= (m1[m1.length-1].h-Math.max(m1[m1.length-1].o,m1[m1.length-1].c))*0.10+1e-9);
        assertTrue(s.sl>s.entry&&s.tp1<s.entry&&s.tp2<s.tp1);
        double range=m1[m1.length-1].h-m1[m1.length-1].l;
        assertEquals(m1[m1.length-1].h+range*0.27
            +Math.max(SignalEngine.atr(m1,14)*0.03,(101.11-101.09)*2.0),s.sl,1e-8);
        assertEquals(m1[m1.length-1].h-range*0.618,s.tp1,1e-8);
        assertEquals(m1[m1.length-1].l,s.tp2,1e-8);
    }
    @Test public void acceptsNearSweepWhenWickRejectionIsValid() {
        Candle[] m1=history(100.0,100.1,98.8,98.9);
        // Previous swing low is 99.80; this wick stops just above it, within the adaptive tolerance.
        m1[m1.length-1]=new Candle(40*60_000L,100.10,100.12,99.81,99.96);
        SignalResult s=SignalEngine.evaluate(m1,context(),99.97,99.99,99.97,99.99);
        assertEquals("BUY",s.side);
        assertTrue(s.liquiditySweep);
        double wickLength=Math.min(m1[m1.length-1].o,m1[m1.length-1].c)-m1[m1.length-1].l;
        assertTrue(s.entry-m1[m1.length-1].l<=wickLength*0.10+1e-9);
    }

    @Test public void doesNotCarryPreviousCandleWickIntoNeutralNewCandle() {
        Candle[] m1=history(100.0,100.1,99.9,100.0);
        long now=System.currentTimeMillis();
        m1[m1.length-2]=new Candle(now-60_000L,100.8,101.6,100.7,100.9);
        // New candle is a small, balanced wick: the old candle's SELL wick must not be replayed.
        m1[m1.length-1]=new Candle(now-2_000L,100.9,100.92,100.88,100.90);
        SignalResult s=SignalEngine.evaluate(m1,context(),100.89,100.91,100.89,100.91);
        assertEquals("WAIT",s.side);
        assertFalse("Prior candle shape alone is not an active-bar rejection",s.wickRejection);
    }

    @Test public void recognizesSellWickWithoutLiquiditySweep() {
        Candle[] m1=history(100.00,100.14,99.95,99.98);
        SignalResult s=SignalEngine.evaluate(m1,context(),99.97,99.99,99.97,99.99);
        assertEquals("SELL",s.side);
        assertTrue("A real upper-wick rejection should qualify without a prior swing sweep",s.wickRejection);
        assertFalse("No prior swing was taken in this fixture",s.liquiditySweep);
        assertTrue(s.entry>99.97);
        assertTrue(s.sl>s.entry&&s.tp1<s.entry);
    }

    @Test public void recognizesBuyWickWithoutLiquiditySweep() {
        Candle[] m1=history(100.00,100.05,99.88,100.02);
        SignalResult s=SignalEngine.evaluate(m1,context(),100.01,100.03,100.01,100.03);
        assertEquals("BUY",s.side);
        assertTrue("A real lower-wick rejection should qualify without a prior swing sweep",s.wickRejection);
        assertFalse("No prior swing was taken in this fixture",s.liquiditySweep);
        assertTrue(s.entry<100.03);
        assertTrue(s.sl<s.entry&&s.tp1>s.entry);
    }

    @Test public void openActionOneFollowsLiveHighWithoutWaitingForSellRejection() {
        Candle[] m1=history(100.00,100.25,99.98,100.21);
        SignalResult s=SignalEngine.evaluate(m1,context(),100.21,100.23,100.21,100.23,true);
        assertEquals("SELL",s.side);
        assertFalse("Open Action 1 must not require a rejection wick",s.wickRejection);
        assertEquals("CANDLE EXHAUSTION • MARKET SELL",s.setup);
        assertEquals("Entry follows the current candle high",m1[m1.length-1].h,s.entry,1e-9);
        assertTrue(s.sl>s.entry&&s.tp1<s.entry&&s.tp2<s.tp1);
    }

    @Test public void openActionOneFollowsLiveLowWithoutWaitingForBuyRejection() {
        Candle[] m1=history(100.00,100.02,99.75,99.79);
        SignalResult s=SignalEngine.evaluate(m1,context(),99.77,99.79,99.77,99.79,true);
        assertEquals("BUY",s.side);
        assertFalse("Open Action 1 must not require a rejection wick",s.wickRejection);
        assertEquals("CANDLE EXHAUSTION • MARKET BUY",s.setup);
        assertEquals("Entry follows the current candle low",m1[m1.length-1].l,s.entry,1e-9);
        assertTrue(s.sl<s.entry&&s.tp1>s.entry&&s.tp2>s.tp1);
    }

    @Test public void openActionOneWaitsUntilExhaustionTravelThresholdIsReached() {
        // Small live candle range remains below the adaptive exhaustion threshold.
        Candle[] m1=history(100.00,100.05,99.95,100.049);
        SignalResult s=SignalEngine.evaluate(m1,context(),100.047,100.049,100.047,100.049,true);
        assertEquals("WAIT",s.side);
    }

}
