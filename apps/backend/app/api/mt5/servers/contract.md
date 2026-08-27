# MT5 server discovery contract

GET `/api/mt5/servers?q=<broker>`

The route proxies MetaApi's known MT5 server search endpoint using the server-side `METAAPI_TOKEN`.

Response:
```json
{
  "brokers": [
    { "broker": "Broker Name", "servers": ["Broker-Demo", "Broker-Real"] }
  ]
}
```

The mobile client must never contain `METAAPI_TOKEN`. It calls this backend route over HTTPS.
