# XAUUSD Mobile Trading Engine — Rayyan10

Architecture: Android APK → LAN gateway on HP B → Winlator/MT5 → Exness demo account.

## MT5 installation (one EA)

Use only **`mt5/Experts/Rayyan4GatewayEA.mq5`** as the attached EA. It handles trading commands and ACKs (`/next`, `/ack`) plus chart indicators through a separate queue (`/indicator_next`, `/indicator_ack`). Do not attach a second indicator-controller EA.

Compile this EA in MetaEditor, attach it to the MT5 chart, enable Algo Trading, and add the exact gateway base URL under **Tools → Options → Expert Advisors → Allow WebRequest**. The default is `http://192.168.0.112:8787`; update it if HP B's LAN IP changes.

The same EA draws EMA 50 (orange), EMA 200 (purple), Support/Resistance, and Fibonacci levels. The APK payload and MT5 controller use the same EMA 50/200 keys.
