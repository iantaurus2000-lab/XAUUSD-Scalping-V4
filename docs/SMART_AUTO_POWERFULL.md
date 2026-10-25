# SMART AUTO-POWERFULL — Design and Review Notes

## Isolation contract

- New app preferences use `powerfull_*` keys, separated by broker symbol.
- The EA uses `POWERFULL_MAGIC = 4104451` and its own `CTrade powerTrade` instance.
- Its configuration/status route is `/power_config` and `/power_state`; it does not consume or clear the legacy `/next`, `/ack`, `/state`, or indicator queue.
- Legacy `RAYYAN_MAGIC = 4104407`, the Fibo/wick signal engine, and its order queue remain separate.
- This feature branch is not the base/build branch. Do not run the existing build workflow until explicitly instructed.

## Forecast model

- Symbols: `XAUUSDm`, `BTCUSDm`, and `ETHUSDm`.
- Timeframe: M5; source is MT5 `CopyRates`, not an external price feed.
- Each symbol uses its own configured price movement per pip. A pip setting is the price delta represented by one pip, not the MT5 point by assumption.
- The model filters completed M5 bars to the last 365 calendar days, sorts High-Low range sizes, selects configured P50/P60/P70/P80/P90 quantile, and scales by the ratio between the last 14-bar average and the year-window average (bounded 0.50–2.00).
- At least 2,000 valid candles and 350 days of history are required. If history is shorter, the model fails closed and reports the actual loaded day coverage.
- Projected High/Low use the new candle's Open as anchor and a historical average upper-range share, clamped between 35% and 65%.
- This is a transparent baseline/heuristic model, not a trained or statistically validated predictor. Its forward performance remains unverified until a tick-based backtest and demo test are performed.

## Entry and order lifecycle

- At the start of a new M5 candle, attempt a BUY LIMIT at 1% of forecast range above projected Low and a SELL LIMIT at 99% of forecast range above projected Low.
- The first pair must be sent within the opening 5 seconds of that M5 bar. Replacements can be sent later only after a material forecast revision, provided the prior pending-order count is confirmed zero and more than five seconds remain before candle close.
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
6. Confirm initial pair is sent only in first five seconds; both pending orders expire at M5 close.
7. Force a rejected second order and verify rollback/partial-uncertain status without duplicate pair.
8. Exercise both upward and under-pace reforecast; verify old pending count is zero before replacement.
9. Test each market's actual pip/tick/point/volume/stops/expiry specifications before enabling it.
10. Test profitable tick close, ~3-pip software guard plus server SL, late-candle hold, next-bar confirmation, momentum reversal, terminal disconnect, stale quote and order rejection.
11. Verify legacy `/next` + `/ack` orders and the original Fibo/wick pathway behave as before.

No APK build, MetaEditor compile, unit test, backtest, or terminal/demo execution has been run for these changes yet.
