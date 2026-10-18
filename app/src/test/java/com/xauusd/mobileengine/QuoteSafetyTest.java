package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuoteSafetyTest {
    @Test public void rejectsInvalidAndCrossedQuotes(){
        assertFalse(QuoteSafety.valid(0,1));
        assertFalse(QuoteSafety.valid(Double.NaN,2));
        assertFalse(QuoteSafety.valid(10,9));
        assertTrue(QuoteSafety.valid(10,10.01));
    }

    @Test public void requiresFreshReceiptAndBrokerTick(){
        long now=1_800_000_000_000L;
        assertTrue(QuoteSafety.isFresh(now-1000,now-2000,now));
        assertFalse(QuoteSafety.isFresh(now-6000,now-2000,now));
        assertFalse(QuoteSafety.isFresh(now-1000,now-16000,now));
        assertFalse(QuoteSafety.isFresh(now-1000,now+7000,now));
        assertFalse(QuoteSafety.isFresh(0,now,now));
    }

    @Test public void staleOrFutureCandlesBlockEntry(){
        long now=1_800_000_000_000L;
        assertTrue(QuoteSafety.isFreshBar(now-60000,now,120000));
        assertFalse(QuoteSafety.isFreshBar(now-180000,now,120000));
        assertFalse(QuoteSafety.isFreshBar(now+1000,now,120000));
        assertFalse(QuoteSafety.isFreshBar(0,now,120000));
    }

    @Test public void spreadGateScalesToAtrAndInstrumentPrice(){
        assertTrue(QuoteSafety.spreadAcceptable(2000.00,2000.02,1.0));
        assertFalse(QuoteSafety.spreadAcceptable(2000.00,2000.50,0.10));
        assertFalse(QuoteSafety.spreadAcceptable(2000.00,2000.10,0.0));
        assertFalse(QuoteSafety.spreadAcceptable(10.0,9.0,1.0));
    }
}
