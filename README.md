# XAUUSD Mobile Trading Engine — Rayyan10

Architecture: Android APK → existing LAN Gateway on HP B (port 8787) → Winlator/MT5 → the currently connected MT5 broker account.

## MT5 installation (one EA)

Use only **`mt5/Experts/Rayyan4GatewayEA.mq5`** as the attached EA. It handles trading commands and ACKs (`/next`, `/ack`) plus chart indicators through a separate queue (`/indicator_next`, `/indicator_ack`). Do not attach a second indicator-controller EA.

Compile this EA in MetaEditor, attach it to the MT5 chart, enable Algo Trading, and add the exact gateway base URL under **Tools → Options → Expert Advisors → Allow WebRequest**. The default is `http://192.168.0.112:8787`; update it if HP B's LAN IP changes.

The same EA draws EMA 50 (orange), EMA 200 (purple), Support/Resistance, and Fibonacci levels. The APK payload and MT5 controller use the same EMA 50/200 keys.

## Native live chart synchronization

- The APK chart is rendered natively by `CandleChartView`; MT5 screenshots and Biquote candles are not used as the chart feed.
- The same single EA publishes `CopyRates()` OHLC snapshots to the existing Gateway endpoint `POST /bars/upload`. M1 and M5 histories are sent for signal/EMA calculations, and the active MT5 chart timeframe supplies its own OHLC series. No new port, Gateway URL, or second EA is introduced.
- The Gateway validates symbol, timeframe, timestamps and OHLC consistency before storing each snapshot. The APK reads these snapshots from the same Gateway preferences that store the real MT5 Bid/Ask quote.
- The chart displays the newest **50 candles maximum** (approximately 40–50 fit on screen by default), while retaining up to 200 bars for EMA 50/200 calculations. Bull candles are green; bear candles are red. The current Bid/Ask lines and Entry/SL/TP overlays remain separate from the candle history.
- OHLC snapshots refresh approximately every 2 seconds; the broker quote continues through the existing quote route. If native OHLC is missing or older than 10 seconds, the chart reports stale data and entry readiness is locked rather than silently falling back to a third-party feed.
- Screenshot mirroring is disabled to avoid Winlator screenshot overhead. The trading queue and ACK protocol remain unchanged.

## Rayyan11 unified entry logic

- The Android signal engine uses the last closed M1 candle, M5 EMA direction, ATR and the actual fresh MT5 Bid/Ask. A clearly dominant rejection wick may validate the opposite direction so EMA lag does not automatically discard a reversal.
- The preferred pending entry uses the nearest valid M1 Fibonacci retracement around 38.2%, 50%, 61.8% or 78.6%, with SL beyond the 78.6% level and the rejection wick plus an ATR/spread buffer. TP1 is the swing endpoint; TP2 uses the 127.2% extension. If the retracement level is already passed or does not fit the rejection wick, a validated wick-end LIMIT with 1R / 1.8R targets is used instead of blocking every setup.
- Fibonacci display levels are 0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100% and 127.2%. The spread guard is loosened to 85% of M1 ATR, not removed.
- The quote used for Entry Ready, manual LIMIT validation, Smart Auto and gateway reference Bid/Ask is the same MT5 broker quote. Fresh OHLC candles and a current broker quote remain required; a delayed third-party chart tick alone no longer blocks an otherwise valid signal.
- M5 history can be shorter than 201 candles: the engine keeps the 50/200 EMA when history is sufficient and adapts the slow EMA for 60–200 candles. The gateway URL, /next and /ack protocol are unchanged.
