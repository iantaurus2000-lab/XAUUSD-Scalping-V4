package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrokerOrderLinePolicyTest {
    @Test public void activeEntryIsTranslatedToChartPriceScale(){
        assertEquals(2000.25,BrokerOrderLinePolicy.displayPrice("PENDING","XAUUSDm","XAUUSDm",
            2000.25,2000.00,2000.02,2000.00,2000.02),0.000001);
        assertEquals(2010.25,BrokerOrderLinePolicy.displayPrice("POSITION","XAUUSDm","XAUUSDm",
            2000.25,2010.00,2010.02,2000.00,2000.02),0.000001);
    }

    @Test public void cancelledClosedOrMismatchedSymbolClearsLine(){
        assertEquals(0.0,BrokerOrderLinePolicy.displayPrice("NONE","XAUUSDm","XAUUSDm",
            2000.25,2000,2000.02,2000,2000.02),0.0);
        assertEquals(0.0,BrokerOrderLinePolicy.displayPrice("PENDING","XAUUSDm","BTCUSDm",
            2000.25,2000,2000.02,2000,2000.02),0.0);
        assertEquals(0.0,BrokerOrderLinePolicy.displayPrice("POSITION","XAUUSDm","XAUUSDm",
            0,2000,2000.02,2000,2000.02),0.0);
    }
}
