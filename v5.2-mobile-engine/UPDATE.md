# XAUUSD Mobile Trading Engine — Update Track

Current release: V5.2.1
Version code: 541

UI CONTRACT
The V5.1.3-style dashboard is the fixed UI structure. Future updates must not replace or redesign the main dashboard.

CURRENT UPDATE
- Realtime XAUUSD feed and M1/M5/M15 OHLC
- H/L display and brighter small text
- Professional CandleChartView adapted to V5.2 engine
- EMA 9/21/50, Support/Resistance, Fibonacci, Bid/Ask, Entry/SL/TP
- Chart pan, vertical move, pinch zoom, LIVE
- Time axis and BUY/SELL signal markers
- Persistent last READY signal and signal history
- Compact copy controls for Entry/SL/TP
- Indicator/Fibonacci settings dialog
- Market scanner based on strategy filters
- Candle pattern classification
- Background ENTRY READY notification + TEST ALARM
- News/session/network ticker
- Exness API preflight before AUTO
- Pending-order manager, cancel-by-ID, modify order, close/partial-close
- Configurable Exness instrument
- Risk guards and existing Auto BUY/SELL LIMIT flow

EXNESS CONNECTION CONTRACT
Use:
- Trading Account ID
- Exness Public Trader API Key
- Ed25519 Secret/Private Key
- API Host / Access Point
MT5 Login + Server + Trading Password are not used by this direct API execution path.

FUTURE APK UPDATE RULE
Increase versionCode for each installable update. Keep applicationId unchanged.
Do not replace the V5.1.3-style dashboard unless explicitly requested.
Keep update notes in this file and update.json.
