# Pips-life Mobile UI Release

## Release 0.2.0 (2)

This release establishes the mobile dashboard UI foundation for Pips-life.

### Included

- Dark professional trading dashboard.
- No purple in the application palette.
- Animated START BOT / STOP BOT control.
- Dynamic bot-state activity display.
- Backend connection indicator.
- Active-position card structure.
- Strategy 001 / QOF as the primary strategy.
- Reserved Strategy 002 and Strategy 003 slots.
- Dedicated Positions screen.
- Existing MT5 broker/server/account workflow retained.
- Existing MT5 preference persistence retained.
- Existing MetaApi client integration retained.

### Architecture rule

The UI is a presentation/control layer. Strategy 001 engine and its QOF/confluence/risk responsibilities remain outside the mobile UI and must not be reimplemented here.

### Versioning rule

`0.2.0 (2)` is the baseline for the next mobile build. Future releases should increment the version code monotonically and preserve the strategy-engine boundary.

### Current limitation

The dashboard's position/status values are UI placeholders until the backend live-state endpoint is wired into the mobile state layer. The START/STOP control currently demonstrates UI state transitions and is not a replacement for backend execution control.
