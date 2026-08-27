# MT5 Mobile Contract

## Request

- `brokerQuery`: user-entered broker name/search text
- `server`: exact selected MT5 server
- `accountNumber`: MT5 account number
- `password`: supplied transiently for secure backend authentication

## Response

- `connected`: boolean
- `broker`: normalized broker name
- `server`: normalized server
- `accountNumberMasked`: masked account number
- `accountCurrency`: optional
- `balance`: optional
- `equity`: optional
- `message`: safe user-facing status

## Security rules

- Never ship `METAAPI_TOKEN` in the Android application.
- Never log the MT5 password, MetaApi token, or full account credentials.
- Persist only the minimum preference data required for account selection.
- Password/token storage must use Android secure storage or backend-managed credentials.
- Backend remains responsible for MetaApi connectivity and trading operations.
