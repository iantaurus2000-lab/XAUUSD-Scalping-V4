package com.xauusd.mobileengine;

/**
 * Converts MT5 order prices back to the chart feed's scale using the same
 * additive offset concept as the EA's tick.ask-refAsk / tick.bid-refBid mapper.
 */
public final class BrokerOrderLinePolicy {
    private BrokerOrderLinePolicy() {}

    public static double displayPrice(String state,String selectedSymbol,String orderSymbol,
            double brokerEntry,double feedBid,double feedAsk,double brokerBid,double brokerAsk){
        if(!("PENDING".equals(state)||"POSITION".equals(state)))return 0.0;
        if(selectedSymbol==null||orderSymbol==null||!selectedSymbol.equals(orderSymbol))return 0.0;
        if(!Double.isFinite(brokerEntry)||brokerEntry<=0.0)return 0.0;
        if(!QuoteSafety.valid(feedBid,feedAsk)||!QuoteSafety.valid(brokerBid,brokerAsk))return 0.0;
        double converted=brokerEntry+((feedBid+feedAsk)-(brokerBid+brokerAsk))/2.0;
        return Double.isFinite(converted)&&converted>0.0?converted:0.0;
    }
}
