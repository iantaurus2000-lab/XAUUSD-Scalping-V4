# SMART AUTO-POWERFULL — Design and Review Notes

## Isolation contract

- New app preferences use `powerfull_*` keys, separated by broker symbol.
- The EA uses `POWERFULL_MAGIC = 4104451` and its own `CTrade powerTrade` instance.
- The strategy is permanently demo-only and requires an MT5 hedging account with opposite positions allowed. This prevents two pending directions from netting against one another or a legacy position.
- Its configuration/status route is `/power_config` and `/power_state`; it does not consume or clear the legacy `/next`, `/ack`, `/state`, or indicator queue.
- The EA uses a 100 ms timer for cross-symbol tick checks and retains the legacy trade/chart/indicator work in its original `PollSeconds` gated sequence before Powerfull gateway calls. If the millisecond timer cannot be installed, MT5 falls back to the original timer cadence.
- Legacy `RAYYAN_MAGIC = 4104407`, the Fibo/wick signal engine, and its order queue remain separate.
- This feature branch is not the base/build branch. Do not run the existing build workflow until explicitly instructed.

## Forecast model

- Symbols: `XAUUSDm`, `BTCUSDm`, and `ETHUSDm`.
- Timeframe: M5; source is MT5 `CopyRates`, not an external price feed.
- Each symbol uses its own configured price movement per pip. A pip setting is the price delta represented by one pip, not the MT5 point by assumption.
- The model filters completed M5 bars to the last 365 calendar days, sorts High-Low range sizes, selects configured P50/P60/P70/P80/P90 quantile, and scales by the ratio between the last 14-bar average and the year-window average (bounded 0.50–2.00).
- At least 2,000 valid candles and 350 days of history are required. If history is shorter, the model fails closed and reports the actual loaded day coverage.
- Projected High/Low use the new candle's Open as anchor and a historical average upper-range share, clamped between 35% and 65%.
- No third-party indicator is used. This is a transparent baseline/heuristic model, not a trained or statistically validated predictor. Percentile (P50-P90) describes historical range distribution; it is not the probability that price will touch an entry level. Forward performance remains unverified until tick-based out-of-sample and demo tests are performed.

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

- At the start of a new M5 candle, attempt a BUY LIMIT at 1% of forecast range above projected Low and a SELL LIMIT at 99% of forecast range above projected Low.
- The initial BUY LIMIT + SELL LIMIT pair must be submitted immediately within the first 1-2 seconds of each new M5 candle (EA hard deadline: 2 seconds, aiming for the first available tick near 1 second). If that window is missed, do not make a late initial entry; wait for the next M5 candle. Forecast replacements are a separate path and require the old pending-order count to be confirmed zero before replacement.
- Pending orders use specified expiration at the M5 candle close. A broker which does not support specified expiration is not eligible for this strategy until a safe expiry mechanism is agreed.
- A separate magic number scopes all Powerfull order queries/deletes. If one side of a pair is rejected, the EA attempts to roll back the other still-pending side and prevents duplicate seeding in that bar.
- A live Powerfull position blocks a new pair for that symbol while the position is open; no unlimited stacking.
- When current M5 range materially exceeds the forecast, or the realized range falls materially below square-root-time pace after at least 60 seconds, a bounded reforecast can cancel/re-price pending orders. At most two re-plans are attempted per candle. This is a heuristic, not proof that a target range is impossible.

## Position management and risk

- Server-side SL is placed about 3 configured pips from the pending entry; a software guard also closes when executable price movement reaches the configured loss threshold.
- Because price can gap, a broker can reject close/modify requests, or slippage can occur, the realised loss is not guaranteed to cap at exactly 3 pips.
- Exit logic estimates net floating profit with swap and an estimate of exit commission and attempts to close when estimated net is positive. Realised net profit can still differ due to slippage, fees, quote movement and broker execution.
- In the final 100 ms of an M5 bar, a profitable position may arm a cross-bar hold if tick direction is aligned. The next bar must confirm momentum; if not confirmed, the position is exited when the short confirmation window passes. If the terminal receives no tick in the final 100 ms, no hold is armed.
- Fresh-tick, terminal permission, demo-account, symbol specification, spread, stop-level, lot step, entry geometry, pending expiry, and order return-code checks are required before creating a pair.

## Manual acceptance checklist — NOT RUN

1. Verify Android menu shows SMART AUTO-POWERFULL separately from legacy Smart Auto Entry.
2. Verify save/load of each market's enabled, lot, pip-size and percentile keys; ensure old auto preferences do not change.
3. Verify the three EA config requests are round-robin and status fields are scoped by symbol.
4. Verify fresh M5 history reports actual days/samples; missing history blocks entry.
5. In MT5 Strategy Tester/demo, confirm only `POWERFULL_MAGIC` orders are touched by cancel/re-price code.
6. Test the initial pair inside the first 1-2 seconds of a new M5 candle; confirm per-side ON/OFF, pending expiry at M5 close, and server SL on each enabled order.
7. Force a rejected second order and verify rollback/partial-uncertain status without duplicate pair.
8. Exercise both upward and under-pace reforecast; verify old pending count is zero before replacement.
9. Test each market's actual pip/tick/point/volume/stops/expiry specifications before enabling it.
10. Test each feature toggle independently, including profitable tick close, mandatory ~3-pip software guard plus server SL, late-candle hold, next-bar confirmation, momentum reversal, terminal disconnect, stale quote and order rejection.
11. Verify legacy `/next` + `/ack` orders and the original Fibo/wick pathway behave as before.

Build / test status for this branch is recorded in the associated GitHub Actions run; compiling does not replace the still-required MT5 demo acceptance checks.
