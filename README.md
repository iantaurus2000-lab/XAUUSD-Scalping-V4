# XAUUSD Mobile Trading Engine — Rayyan10

Architecture: Android APK → LAN gateway on HP B → Winlator/MT5 → Exness demo account.

## MT5 installation (one EA)

Use only **`mt5/Experts/Rayyan4GatewayEA.mq5`** as the attached EA. It handles trading commands and ACKs (`/next`, `/ack`) plus chart indicators through a separate queue (`/indicator_next`, `/indicator_ack`). Do not attach a second indicator-controller EA.

Compile this EA in MetaEditor, attach it to the MT5 chart, enable Algo Trading, and add the exact gateway base URL under **Tools → Options → Expert Advisors → Allow WebRequest**. The default is `http://192.168.0.112:8787`; update it if HP B's LAN IP changes.

The same EA draws EMA 50 (orange), EMA 200 (purple), Support/Resistance, and Fibonacci levels. The APK payload and MT5 controller use the same EMA 50/200 keys.

## Rayyan11 unified entry logic

- The Android signal engine uses the last closed M1 candle, M5 EMA direction, ATR and the actual fresh MT5 Bid/Ask. A clearly dominant rejection wick may validate the opposite direction so EMA lag does not automatically discard a reversal.
- The preferred pending entry uses the nearest valid M1 Fibonacci retracement around 38.2%, 50%, 61.8% or 78.6%, with SL beyond the 78.6% level and the rejection wick plus an ATR/spread buffer. TP1 is the swing endpoint; TP2 uses the 127.2% extension. If the retracement level is already passed or does not fit the rejection wick, a validated wick-end LIMIT with 1R / 1.8R targets is used instead of blocking every setup.
- Fibonacci display levels are 0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100% and 127.2%. The spread guard is loosened to 85% of M1 ATR, not removed.
- The quote used for Entry Ready, manual LIMIT validation, Smart Auto and gateway reference Bid/Ask is the same MT5 broker quote. Fresh OHLC candles and a current broker quote remain required; a delayed third-party chart tick alone no longer blocks an otherwise valid signal.
- M5 history can be shorter than 201 candles: the engine keeps the 50/200 EMA when history is sufficient and adapts the slow EMA for 60–200 candles. The gateway URL, /next and /ack protocol are unchanged.
