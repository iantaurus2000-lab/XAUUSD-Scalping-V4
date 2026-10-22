package com.xauusd.mobileengine;

import java.util.Locale;

/**
 * Canonical Android-side market map. Keep this list aligned with GatewayService
 * and mt5/Experts/Rayyan4GatewayEA.mq5, which publish/accept these broker symbols.
 */
public final class MarketSupport {
    public static final String[] SUPPORTED_MARKETS = {"XAUUSD", "BTCUSD", "ETHUSD"};

    private MarketSupport() {}

    /** Normalize APK/feed names and Exness broker names to a base market code. */
    public static String baseSymbol(String market) {
        if (market == null) return "";
        String s = market.trim().toUpperCase(Locale.US);
        if ("XAUUSDM".equals(s)) return "XAUUSD";
        if ("BTCUSDM".equals(s)) return "BTCUSD";
        if ("ETHUSDM".equals(s)) return "ETHUSD";
        return s;
    }

    public static boolean isSupportedMarket(String market) {
        String s = baseSymbol(market);
        return "XAUUSD".equals(s) || "BTCUSD".equals(s) || "ETHUSD".equals(s);
    }

    /** Empty string for an unknown market; never silently route it to another asset. */
    public static String mt5Symbol(String market) {
        switch (baseSymbol(market)) {
            case "XAUUSD": return "XAUUSDm";
            case "BTCUSD": return "BTCUSDm";
            case "ETHUSD": return "ETHUSDm";
            default: return "";
        }
    }

    public static boolean isSupportedMt5Symbol(String symbol) {
        return "XAUUSDm".equalsIgnoreCase(symbol)
                || "BTCUSDm".equalsIgnoreCase(symbol)
                || "ETHUSDm".equalsIgnoreCase(symbol);
    }
}
