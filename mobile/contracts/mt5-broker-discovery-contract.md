# MT5 Broker / Server Discovery Contract

## Request

`GET /api/mt5/brokers?query=<broker name>`

## Response

```json
{
  "brokers": [
    {
      "name": "Example Broker",
      "servers": [
        {"name": "ExampleBroker-MT5Real", "environment": "live"},
        {"name": "ExampleBroker-MT5Demo", "environment": "demo"}
      ]
    }
  ]
}
```

The backend is authoritative for broker/server discovery. The Android client must not hard-code a broker's server list.

## Security

Discovery must not expose `METAAPI_TOKEN` or provider credentials to the client. The client only receives safe broker/server metadata.
