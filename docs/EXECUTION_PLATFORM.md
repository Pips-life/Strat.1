# Execution Platform Architecture

## Target topology

```text
Android APK
   │ HTTPS + authenticated API
   ▼
Vercel Backend
   │
   ├── Strategy/engine control plane
   ├── account state
   ├── execution audit
   └── mode gate: REPLAY / DEMO / LIVE
   │
   ▼
MetaApi Bridge
   │
   ├── MT5 account deployment
   ├── terminal state
   ├── market/account data
   └── trade execution
   │
   ▼
MT5 Account(s) → Broker
```

## Responsibilities

- **Strategy engine:** decides whether a trade is valid and what order/risk parameters to request.
- **Global risk engine:** determines permitted size and account regime before execution.
- **Vercel backend:** authenticated control plane and API boundary. It never exposes MetaApi credentials to the APK.
- **MetaApi:** provider/bridge for MT4/MT5 account connectivity and trading.
- **MT5 account:** broker-side execution venue.
- **APK:** operator interface. The production UI exposes Demo/Live; Replay is development-only.

MetaApi supports reading terminal state and trading through a common MT4/MT5 API. Its account provisioning API can add and deploy MT5 accounts. See the official MetaApi documentation for current account/provisioning details.

## Execution contract

The engine uses `ExecutionAdapter`. `RemoteExecutionAdapter` sends normalized orders to the Vercel backend. The engine does not import MetaApi SDKs or broker-specific code.

```text
OrderRequest
    ↓
Risk approval
    ↓
RemoteExecutionAdapter
    ↓
POST /api/trade
    ↓
MetaApi trade API
    ↓
MT5
```

## Modes

### REPLAY
Development only. Uses the existing simulated execution path and historical/replay data. No broker credentials and no live order route.

### DEMO
The backend selects an MT5 demo account. Strategy and risk logic are unchanged.

### LIVE
The backend selects an MT5 live account only after explicit user selection and server-side safety checks. Live execution is intentionally not enabled by default.

## Security

- `METAAPI_TOKEN` is server-only.
- `BACKEND_API_KEY` is server-only in this foundation; production should replace this with user authentication and scoped account authorization.
- The APK must never contain MetaApi credentials.
- Account credentials should be entered through a secure server-side provisioning/configuration flow rather than persisted in the APK.
- Every execution request should carry a unique client/execution id and be auditable before live enablement.

## Current implementation status

Implemented foundation:

- Vercel/Next.js backend skeleton.
- MetaApi SDK wrapper for account lookup/deployment.
- Normalized account state endpoint.
- Normalized trade endpoint for buy/sell/close actions.
- Python `RemoteExecutionAdapter` connecting the existing engine execution contract to the backend.
- Android APK shell with Replay, Demo and Live controls.

Not yet enabled until credentials and real accounts are supplied:

- MetaApi account provisioning from the APK.
- Persistent user/account database.
- Production authentication/authorization.
- Historical data provider connection.
- Live-mode kill switch and multi-factor confirmation.
- Signed APK/release pipeline.
