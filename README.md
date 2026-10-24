# XAUUSD Mobile Trading Engine — Rayyan10

Architecture: Android APK → existing LAN Gateway on HP B (port 8787) → Winlator/MT5 → the currently connected MT5 broker account.

## MT5 installation (one EA)

Use only **`mt5/Experts/Rayyan4GatewayEA.mq5`** as the attached EA. It handles trading commands and ACKs (`/next`, `/ack`) plus chart indicators through a separate queue (`/indicator_next`, `/indicator_ack`). Do not attach a second indicator-controller EA.

Compile this EA in MetaEditor, attach it to the MT5 chart, enable Algo Trading, and add the exact gateway base URL under **Tools → Options → Expert Advisors → Allow WebRequest**. The default is `http://192.168.0.112:8787`; update it if HP B's LAN IP changes.

The same EA draws EMA 50 (orange), EMA 200 (purple), Support/Resistance, and Fibonacci levels. The APK payload and MT5 controller use the same EMA 50/200 keys.

## Native live chart synchronization

- The APK chart is rendered natively by `CandleChartView`; MT5 screenshots and Biquote candles are not used as the chart feed.
- The same single EA publishes `CopyRates()` OHLC snapshots for all three supported broker symbols (`XAUUSDm`, `BTCUSDm`, `ETHUSDm`) and all six APK timeframes (`M1`, `M5`, `M15`, `H1`, `H4`, `D1`) to the existing Gateway endpoint `POST /bars/upload` in one batch. M1 and M5 include up to 200 bars for analysis; higher timeframes include up to 50 bars. The APK can switch market/timeframe without depending on the symbol currently open in the MT5 desktop. No new port, Gateway URL, or second EA is introduced.
- The Gateway validates each symbol/timeframe series, ascending candle timestamps and OHLC consistency before storing the batch. The EA publishes real `SymbolInfoTick()` Bid/Ask plus `tick.time_msc` for all three symbols; the APK selects the quote by its active market and maps `XAUUSD`→`XAUUSDm`, `BTCUSD`→`BTCUSDm`, and `ETHUSD`→`ETHUSDm`. The displayed price, spread and entry quote use the same selected-symbol MT5 quote. Tick snapshots are uploaded together through `POST /quote/upload` in one short request, reducing the sequential-request lag of the previous per-symbol polling path. The original `/quote` endpoint remains available for compatibility.
- The chart displays the newest **50 candles maximum** (approximately 40–50 fit on screen by default), while retaining up to 200 bars for EMA 50/200 calculations. Bull candles are green; bear candles are red. The current Bid/Ask lines and Entry/SL/TP overlays remain separate from the candle history.
- OHLC snapshots refresh approximately every 2 seconds and the EA publishes the latest real MT5 tick snapshot on its existing timer cycle (normally 1 second). The APK checks both Gateway receive age and `tick.time_msc` age; a stale tick is never treated as live. If the selected timeframe's OHLC is missing or older than 10 seconds, the chart reports stale data. If the selected chart is live but M1/M5 signal history is incomplete, the chart remains visible while Entry Ready/Auto entry stay locked.
- Screenshot mirroring is disabled to avoid Winlator screenshot overhead. The trading queue and ACK protocol remain unchanged.

## Rayyan11 unified entry logic

- The Android signal engine uses the last closed M1 candle, M5 EMA direction, ATR and the actual fresh MT5 Bid/Ask. A clearly dominant rejection wick may validate the opposite direction so EMA lag does not automatically discard a reversal.
- The preferred pending entry uses the nearest valid M1 Fibonacci retracement around 38.2%, 50%, 61.8% or 78.6%, with SL beyond the 78.6% level and the rejection wick plus an ATR/spread buffer. TP1 is the swing endpoint; TP2 uses the 127.2% extension. If the retracement level is already passed or does not fit the rejection wick, a validated wick-end LIMIT with 1R / 1.8R targets is used instead of blocking every setup.
- Fibonacci display levels are 0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100% and 127.2%. The spread guard is loosened to 85% of M1 ATR, not removed.
- The quote used for Entry Ready, manual LIMIT validation, Smart Auto and gateway reference Bid/Ask is the same MT5 broker quote. Fresh OHLC candles and a current broker quote remain required; a delayed third-party chart tick alone no longer blocks an otherwise valid signal.
- M5 history can be shorter than 201 candles: the engine keeps the 50/200 EMA when history is sufficient and adapts the slow EMA for 60–200 candles. The gateway URL, /next and /ack protocol are unchanged.


Build checkpoint: native MT5 multi-market chart and tick synchronization revisions are queued for validation on this branch. The APK CI build must pass before its artifact is distributed; the EA source must be compiled separately with MetaEditor for an `.ex5` binary.
