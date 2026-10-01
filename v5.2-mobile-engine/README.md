# XAUUSD Mobile Trading Engine V5.2-A

Mobile-first foundation. No Windows PC or VPS is required for the signal/data layer.

## V5.2-A
- XAUUSD realtime price from biquote
- M1 and M5 OHLC
- Mobile candlestick chart
- Wick rejection detection
- Liquidity sweep detection
- BOS detection
- M5 bias
- BUY/SELL signal lamp
- Confidence score
- Signal-only mode: no automatic orders

Data source: https://biquote.io/api/XAUUSD and OHLC endpoints. The feed is market data only; it is not an order-execution API.

Next stage: validate the signal engine on the phone, then add a broker-supported execution path separately.
