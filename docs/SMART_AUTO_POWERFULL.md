# SMART AUTO-POWERFULL — Design and Review Notes

## Isolation contract

- New app preferences use `powerfull_*` keys, separated by broker symbol.
- The EA uses `POWERFULL_MAGIC = 4104451` and its own `CTrade powerTrade` instance.
- The strategy is permanently demo-only and requires an MT5 hedging account with opposite positions allowed. This prevents two pending directions from netting against one another or a legacy position.
- Its configuration/status route is `/power_config` and `/power_state`; it does not consume or clear the legacy `/next`, `/ack`, `/state`, or indicator queue.
- The EA uses a 100 ms timer for cross-symbol tick checks and retains the legacy trade/chart/indicator work in its original `PollSeconds` gated sequence before Powerfull gateway calls. If the millisecond timer cannot be installed, MT5 falls back to the original timer cadence.
- Legacy `RAYYAN_MAGIC = 4104407`, the Fibo/wick signal engine, and its order queue remain separate.
- This feature branch is not the base/build branch. Do not run the existing build workflow until explicitly instructed.

## Simple next-candle forecast

- Symbols: `XAUUSDm`, `BTCUSDm`, and `ETHUSDm`; timeframe M5, using MT5 `CopyRates`.
- Forecast range = arithmetic mean of the last 3–5 completed M5 candle ranges (`High - Low`). Only 3 valid completed candles are required; no 2,000-bar minimum, 350-day history gate, quantile, or distribution scaling.
- The next candle's projected range is centered symmetrically around its observed M5 Open. This is a deliberately simple heuristic, not a reliable guarantee of the future high/low.
- BUY LIMIT = 0.1% above projected Low; SELL LIMIT = 98.9% of the full projected wick-to-wick range above projected Low. Prices are rounded to the symbol's tradable tick size.
- The historical percentile setting is no longer shown in Android and is ignored by the forecast; the old preference is tolerated for compatibility.
- Temporary gateway heartbeat delays do not cancel the last valid config. At startup, entries remain OFF until at least one valid per-symbol config is received.
- This model is not statistically validated. Forward performance remains unverified until tick-based out-of-sample and demo tests are performed.

## Independent feature controls

Each symbol has separately persisted switches in Android, passed through `/power_config`, and applied by the EA:

- **Master POWERFULL ON/OFF** — OFF cancels Powerfull pending orders and blocks new entries. Any already-open Powerfull position remains under the EA's position/risk management until closed.
- **BUY LIMIT ON/OFF** and **SELL LIMIT ON/OFF** — enable either side independently. Turning one side OFF cancels that side's own pending orders after broker confirmation; it does not forcibly close an already-open position.
- **Reforecast / pending replacement ON/OFF** — when OFF, forecast deviations do not cancel and replace a pending order; pending orders retain their candle-close expiration.
- **Fast net-profit close ON/OFF** — when ON, the EA attempts to close as soon as estimated net profit becomes positive. When OFF, this particular profit-close rule is disabled; the mandatory server SL and software loss guard remain active.
- **Cross-candle momentum hold ON/OFF** — when ON, a profitable position can be held across a candle boundary only when the final-tick momentum conditions are met, followed by next-candle confirmation. OFF clears saved hold state.
- The configured ~3-pip hard-risk guard and server-side SL are intentionally **not toggleable** in this UI.

The switches are per symbol (`XAUUSDm`, `BTCUSDm`, and `ETHUSDm`); saving one symbol does not change settings for another symbol or the legacy SMART AUTO-standar / SMART AUTO-EXCELLENT modes.

## Entry and order lifecycle

- When a new M5 candle is detected, attempt the enabled BUY/SELL LIMIT orders using the simple forecast above. The arbitrary first-2-seconds deadline is removed; a late attempt can still fail if price has already crossed a limit level or the broker's minimum-distance rules no longer permit it.
- The strategy no longer re-forecasts or replaces pending orders based on intrabar range/distribution calculations. Pending orders keep their original levels and expire at the end of that M5 candle.
- Broker-compatible specified expiration, valid LIMIT direction, tick/point/volume specifications, demo-account mode, hedging mode, trading permissions, stop distance, and trade return-code checks remain. These are execution/safety requirements, not a guarantee that the broker will accept an order.
- The extra spread threshold that previously blocked entries has been removed. The broker may still reject an order because of its own rules, price movement, unavailable trading, or execution conditions.
- A separate magic number scopes all POWERFULL order queries/deletes. If one side of a pair is rejected, the EA attempts to roll back the other still-pending side and prevents duplicate seeding in that bar.
- A live POWERFULL position blocks a new pair for that symbol while the position is open; no unlimited stacking.

## Position management and risk

- Server-side SL is placed about 3 configured pips from the pending entry; a software guard also closes when executable price movement reaches the configured loss threshold.
- Because price can gap, a broker can reject close/modify requests, or slippage can occur, the realised loss is not guaranteed to cap at exactly 3 pips.
- Exit logic estimates net floating profit with swap and an estimate of exit commission and attempts to close when estimated net is positive. Realised net profit can still differ due to slippage, fees, quote movement and broker execution.
- In the final 100 ms of an M5 bar, a profitable position may arm a cross-bar hold if tick direction is aligned. The next bar must confirm momentum; if not confirmed, the position is exited when the short confirmation window passes. If the terminal receives no tick in the final 100 ms, no hold is armed.
- A valid current tick, terminal permission, demo-account, symbol specification, stop-level, lot step, basic LIMIT direction, pending expiry, and order return-code checks remain before creating a pair. The former extra spread threshold is removed.

## Manual acceptance checklist — NOT RUN

1. Verify Android menu shows SMART AUTO-POWERFULL separately from legacy Smart Auto Entry.
2. Verify save/load of each market's enabled, lot and pip-size settings; percentile is no longer part of the UI/model.
3. Verify the three EA config requests are round-robin and status fields are scoped by symbol.
4. Verify the simple forecast uses 3–5 completed M5 bars and reports the actual sample count.
5. In MT5 Strategy Tester/demo, confirm only `POWERFULL_MAGIC` orders are touched by cancel/re-price code.
6. Test entry after new M5 detection without a 2-second deadline; confirm per-side ON/OFF, pending expiry at M5 close, and server SL on each enabled order.
7. Force a rejected second order and verify rollback/partial-uncertain status without duplicate pair.
8. Verify pending levels remain fixed for the candle and no intrabar reforecast/replacement is attempted.
9. Test each market's actual pip/tick/point/volume/stops/expiry specifications before enabling it.
10. Test each feature toggle independently, including profitable tick close, mandatory ~3-pip software guard plus server SL, late-candle hold, next-bar confirmation, momentum reversal, terminal disconnect, stale quote and order rejection.
11. Verify legacy `/next` + `/ack` orders and the original Fibo/wick pathway behave as before.

Build / test status for this branch is recorded in the associated GitHub Actions run; compiling does not replace the still-required MT5 demo acceptance checks.
