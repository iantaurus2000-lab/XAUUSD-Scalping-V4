# XAUUSD Mobile Trading Engine — Rayyan10

Architecture: Android APK → LAN gateway on HP B → Winlator/MT5 → Exness demo account.

## MT5 installation (one EA)

Use only **`mt5/Experts/Rayyan4GatewayEA.mq5`** as the attached EA. It handles trading commands and ACKs (`/next`, `/ack`) plus chart indicators through a separate queue (`/indicator_next`, `/indicator_ack`). Do not attach a second indicator-controller EA.

Compile this EA in MetaEditor, attach it to the MT5 chart, enable Algo Trading, and add the exact gateway base URL under **Tools → Options → Expert Advisors → Allow WebRequest**. The default is `http://192.168.0.112:8787`; update it if HP B's LAN IP changes.

The same EA draws EMA 50 (orange), EMA 200 (purple), Support/Resistance, and Fibonacci levels. The APK payload and MT5 controller use the same EMA 50/200 keys.

## Supported markets and strategy routing

The APK market selector supports **XAUUSD → XAUUSDm**, **BTCUSD → BTCUSDm**, and **ETHUSD → ETHUSDm**. The single Gateway EA publishes broker quotes for these three MT5 symbols. Both Smart Auto strategies use the currently selected market: Standard Wick + Fibonacci uses the saved Fibonacci levels with M1/M5 OHLC, while Excellent Machine uses its separate 360-candle analysis history (Donchian 64, EMA 150, and a 5-neighbour prototype) with the live quote/spread/risk gate. Excellent Machine auto-entry remains M1-only. Enabling the model for BTC/ETH is a routing/eligibility change, not evidence of profitable or runtime-tested behavior.
