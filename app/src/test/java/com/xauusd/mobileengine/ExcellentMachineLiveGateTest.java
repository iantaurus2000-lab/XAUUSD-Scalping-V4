package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExcellentMachineLiveGateTest {
    @Test public void acceptsFreshAlignedXauM1LimitSetup() {
        Candle[] bars=uptrend(360);
        ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate("XAUUSD","M1",bars);
        assertTrue("Synthetic monotonic trend should align: "+signal.reason,signal.ready);
        long now=bars[359].t+30_000L;
        double ask=signal.entry+0.05, bid=ask-0.10;
        ExcellentMachineLiveGate.Decision d=gate("XAUUSDm","M1",bars,signal,bid,ask,now,true);
        assertTrue(d.reason,d.allowed);
    }

    @Test public void refusesNonXauMarketAndNonM1Execution() {
        Candle[] bars=uptrend(360);
        ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate("XAUUSD","M1",bars);
        long now=bars[359].t+30_000L;
        double ask=signal.entry+0.05,bid=ask-0.10;
        assertFalse(ExcellentMachineLiveGate.evaluate("BTCUSD","M1",bars,signal,bid,ask,now-1000,now-1000,now,true).allowed);
        assertFalse(ExcellentMachineLiveGate.evaluate("XAUUSD","M5",bars,signal,bid,ask,now-1000,now-1000,now,true).allowed);
    }

    @Test public void refusesStaleBrokerTick() {
        Candle[] bars=uptrend(360);
        ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate("XAUUSD","M1",bars);
        long now=bars[359].t+30_000L;
        double ask=signal.entry+0.05,bid=ask-0.10;
        ExcellentMachineLiveGate.Decision d=gate("XAUUSD","M1",bars,signal,bid,ask,now-7000,now-7000,now,true);
        assertFalse(d.allowed);
        assertTrue(d.reason.contains("QUOTE"));
    }

    @Test public void refusesSpreadSpikeAndPriceChasing() {
        Candle[] bars=uptrend(360);
        ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate("XAUUSD","M1",bars);
        long now=bars[359].t+30_000L;
        double ask=signal.entry+0.05;
        ExcellentMachineLiveGate.Decision spread=gate("XAUUSD","M1",bars,signal,ask-0.90,ask,now,true);
        assertFalse(spread.allowed);
        assertTrue(spread.reason.contains("SPREAD"));
        ExcellentMachineLiveGate.Decision far=gate("XAUUSD","M1",bars,signal,signal.entry+1.85,signal.entry+1.95,now,true);
        assertFalse(far.allowed);
        assertTrue(far.reason.contains("MENJAUH"));
    }

    @Test public void refusesStaleClosedCandle() {
        Candle[] bars=uptrend(360);
        ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate("XAUUSD","M1",bars);
        long now=bars[359].t+180_000L;
        double ask=signal.entry+0.05,bid=ask-0.10;
        ExcellentMachineLiveGate.Decision d=gate("XAUUSD","M1",bars,signal,bid,ask,now-1000,now-1000,now,true);
        assertFalse(d.allowed);
        assertTrue(d.reason.contains("STALE"));
    }

    private static ExcellentMachineLiveGate.Decision gate(String market,String tf,Candle[] bars,
             ExcellentMachineEngine.Result signal,double bid,double ask,long now,boolean upstreamFresh) {
        return gate(market,tf,bars,signal,bid,ask,now-1000,now-1000,now,upstreamFresh);
    }

    private static ExcellentMachineLiveGate.Decision gate(String market,String tf,Candle[] bars,
             ExcellentMachineEngine.Result signal,double bid,double ask,long receipt,long tick,long now,boolean upstreamFresh) {
        return ExcellentMachineLiveGate.evaluate(market,tf,bars,signal,bid,ask,receipt,tick,now,upstreamFresh);
    }

    private static Candle[] uptrend(int count) {
        Candle[] bars=new Candle[count];
        long start=1_800_000_000_000L;
        for(int i=0;i<count;i++) {
            double close=2200.0+i*0.08;
            bars[i]=new Candle(start+i*60_000L,close-0.03,close+0.50,close-0.50,close);
        }
        return bars;
    }
}
