# RAYYAN EA Build Notes

This file is the handover ledger for every EA build. Append a new entry for each authorized build; do not create a new branch for a build.

## Current locked entry specification — Build #128

- Branch: `RAYYAN`
- Editable source: `mt5/Experts/RayyanD1Standalone_127_BACKUP.mq5`
- Protected original: `mt5/Experts/RayyanD1Standalone.mq5` — do not modify.
- Activation distance: 1,000 broker points from the current M5 Open to the live Low (BUY) or live High (SELL).
- Adaptive weakening: live M5 range × 1.0%, converted to tradable ticks and clamped between 2 and 8 ticks (inputs are configurable).
- The trail follows the current candle's live extreme. A newer extreme supersedes the prior candidate before weakening confirmation.
- Layer 1 and layers 2–5 use the same extreme-trailing entry process. Distinct qualifying extremes may produce multiple entries in the same M5 candle.
- Preserve market execution, existing M5/H1 direction filters, 40-second M5 close blackout, one-direction basket, maximum layer cap, SL, and all position/profit management.
- No ATR, no completed-candle range, no one-entry-per-candle restriction.
- **Authorization lock:** no one may alter, remove, add, or reinterpret POWERFULL entry conditions without the user's explicit permission. Any approved change must be documented here with a new build number, commit, and workflow run. No silent changes.

## Build history

### Build #128 — Adaptive Extreme Trailing
- Date: 2026-11-27
- Branch: `RAYYAN`
- Commit: `772884d5c9d002053ae4dc3d00819879c87f5d65`
- Workflow run: [46009449316](https://github.com/iantaurus2000-lab/XAUUSD-Scalping-V4/actions/runs/46009449316)
- Source: `mt5/Experts/RayyanD1Standalone_127_BACKUP.mq5`
- Changes: 1,000-point activation; adaptive 2–8 tick weakening confirmation using 1% of current M5 range; all layers use the same trailing process.
- Compile result: verified successful; both Gateway and Standalone EA reported 0 errors, 0 warnings.
- Artifact ID: `15202736534`
- Artifact SHA256: `016840263c93bdfca6489cf41b21a4d9ef4d75df4ce79a50557e302585884971`
- Artifact: [Download RAYYAN-127-BACKUP-EA](https://github.com/iantaurus2000-lab/XAUUSD-Scalping-V4/actions/runs/46009449316/artifacts/15202736534)

### Build #127 — Dashboard live quote placement
- Date: 2026-11-27
- Branch: `RAYYAN`
- Commit: `de430774e6296fbf3804fd82321d396a9afbba09`
- Workflow run: [46000490694](https://github.com/iantaurus2000-lab/XAUUSD-Scalping-V4/actions/runs/46000490694)
- Change: move the realtime MT5 quote below ALGO status, right aligned, larger bold font.
- Compile result: verified successful, 0 errors and 0 warnings.
- Artifact ID: `15198256079`
- Artifact SHA256: `0c49e8823e62b46c2dcc8209e142c709b67ef25065e651d1d3eaf73dc2299298`
