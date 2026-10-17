package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class Mt5FeedClientTest {
    @Test public void freshQuoteIsTradeable() {
        assertTrue(Mt5FeedClient.isQuoteFresh(true,45,4165.10,4165.36));
    }

    @Test public void closedMarketQuoteIsNotTradeable() {
        // Historical OHLC can still be drawn, but a closed/stale quote cannot authorize entries.
        assertFalse(Mt5FeedClient.isQuoteFresh(false,45,4165.10,4165.36));
    }

    @Test public void quoteOlderThanLimitIsNotTradeable() {
        assertFalse(Mt5FeedClient.isQuoteFresh(true,121,4165.10,4165.36));
    }

    @Test public void invalidAgeOrPricesAreNotTradeable() {
        assertFalse(Mt5FeedClient.isQuoteFresh(true,-1,4165.10,4165.36));
        assertFalse(Mt5FeedClient.isQuoteFresh(true,20,0,0.01));
        assertFalse(Mt5FeedClient.isQuoteFresh(true,20,4165.40,4165.36));
        assertFalse(Mt5FeedClient.isQuoteFresh(true,20,Double.NaN,4165.36));
    }
}
