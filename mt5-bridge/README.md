# MT5 Bridge Contract

The Android app's primary phone-only execution path is the Exness Public Trader API when the account/API key is eligible.

For a standard MT5 account, native MT5 execution requires a terminal running an EA. This folder contains the starting EA template.

## Suggested bridge endpoint

GET /mt5/next

Response:
{
  "id": "xauusd-...",
  "side": "buy",
  "symbol": "XAUUSD",
  "volume": "0.01",
  "entry": "4000.00",
  "sl": "3995.00",
  "tp": "4010.00"
}

The bridge must authenticate requests, validate order geometry and idempotency, and never duplicate a command.

The EA template intentionally does not execute arbitrary remote JSON as a live order until a validated JSON parser and a real bridge endpoint are configured.
