# XAUUSD Mobile Trading Engine V5.2

Mobile-first XAUUSD scalping engine for Android phones.

## Included
- Realtime XAUUSD market feed from Biquote for chart/analysis.
- M1/M5 multi-timeframe engine.
- Wick rejection, liquidity sweep, BOS/CHOCH-style confirmation.
- EMA 9/21/50, RSI, ATR, MACD, Bollinger Bands, stochastic, support/resistance.
- BUY LIMIT and SELL LIMIT planning with Entry, SL, TP1 and TP2.
- Manual limit order entry.
- Auto LIMIT execution with score threshold, spread guard, cooldown, max-orders/day, daily drawdown guard and one-active-exposure guard.
- Background foreground-service mode with trading notifications.
- Android Keystore encrypted storage for Exness API credentials.
- Exness Public Trader API signed requests (Ed25519), access-point discovery, account snapshot, instrument conditions, pending limit order, and cancel-order building blocks.
- MT5 bridge folder with an MQL5 EA template for a separate terminal/VPS execution path.
- Telegram credential storage hook for alerts.

## Execution reality
This app has two separate execution paths:
1. **Direct Exness API** — the phone can place pending orders directly only when the selected Exness trading account/API key is eligible and the API is available for the user's region/account.
2. **MT5 standard account** — an Android app cannot install/run a custom MQL5 Expert Advisor inside the MT5 mobile terminal. A separate MT5 desktop/VPS/bridge process is required for native MT5 execution.

The Exness API documentation currently requires an Exness API key, account ID and Ed25519 secret and says to resolve the current API access point before trading. It also notes that API availability is region/account dependent.

## Safety
The auto engine is disabled by default and must be started explicitly. The strategy score is a filter, not a guarantee of profitable trades or any fixed win rate. Test with a demo account before live trading.

## GitHub Actions
Workflow: `.github/workflows/build-v52-mobile.yml`

Artifacts:
- `XAUUSD-MOBILE-ENGINE-V5-2-PRO` — debug APK
- `XAUUSD-MOBILE-ENGINE-V5-2-SOURCE` — source ZIP


CI build marker: V5.2 PRO APK validation branch.
