from __future__ import annotations

from datetime import datetime
from typing import Any
import json
from urllib.request import Request, urlopen

from .interface import ExecutionAdapter
from .models import ExecutionResult, Fill, OrderRequest, Position


class RemoteExecutionAdapter(ExecutionAdapter):
    """Provider-neutral engine adapter for the Vercel/MetaApi execution backend.

    The engine never receives MetaApi credentials and never knows broker-specific APIs.
    """

    def __init__(self, base_url: str, api_key: str, account_id: str):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.account_id = account_id

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        body = json.dumps(payload).encode() if payload is not None else None
        req = Request(
            f"{self.base_url}{path}",
            data=body,
            method=method,
            headers={"x-api-key": self.api_key, "content-type": "application/json"},
        )
        with urlopen(req, timeout=15) as response:
            return json.loads(response.read().decode())

    def submit(self, order: OrderRequest) -> ExecutionResult:
        result = self._request("POST", "/api/trade", {
            "accountId": self.account_id,
            "action": order.side,
            "symbol": order.symbol,
            "volume": order.quantity,
            "stopLoss": order.stop_loss,
            "takeProfit": order.take_profit,
            "clientId": order.client_order_id,
        })
        response = result.get("response", result)
        status = "FILLED" if response.get("numericCode") == 10009 else "REJECTED"
        return ExecutionResult(status=status, order_id=str(response.get("orderId", "")), reason=response.get("message"))

    def close_position(self, symbol: str, timestamp: datetime, price: float | None = None) -> ExecutionResult:
        positions = self._request("GET", f"/api/accounts/{self.account_id}/state").get("positions", [])
        matching = [p for p in positions if p.get("symbol") == symbol]
        if not matching:
            return ExecutionResult(status="REJECTED", order_id="", reason="No open position for symbol")
        result = self._request("POST", "/api/trade", {
            "accountId": self.account_id,
            "action": "CLOSE_POSITION",
            "positionId": str(matching[0]["id"]),
        })
        response = result.get("response", result)
        status = "FILLED" if response.get("numericCode") == 10009 else "REJECTED"
        return ExecutionResult(status=status, order_id=str(response.get("orderId", "")), reason=response.get("message"))

    def positions(self) -> list[Position]:
        data = self._request("GET", f"/api/accounts/{self.account_id}/state")
        result: list[Position] = []
        for p in data.get("positions", []):
            result.append(Position(
                symbol=p["symbol"],
                side="BUY" if "BUY" in p["type"] else "SELL",
                quantity=float(p["volume"]),
                entry_price=float(p["openPrice"]),
                opened_at=datetime.fromisoformat(p["time"].replace("Z", "+00:00")),
                stop_loss=p.get("stopLoss"),
                take_profit=None,
                metadata={"broker_position_id": p.get("id")},
            ))
        return result

    def on_bar(self, symbol: str, timestamp: datetime, high: float, low: float, close: float) -> list[Fill]:
        # Broker-side SL/TP management is authoritative in demo/live modes.
        return []
