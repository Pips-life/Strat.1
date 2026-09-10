"""FlashAlpha API adapter for Strategy 001.

The API key is read only from FLASHALPHA_API_KEY; it is never stored in the
repository. The adapter is deliberately fail-soft: if the key is absent, a
symbol is unsupported, the plan does not expose an endpoint, or the API is
unavailable, Strategy 001 continues using its native market inputs.
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


@dataclass
class _CacheEntry:
    expires_at: float
    value: dict


class FlashAlphaClient:
    def __init__(self) -> None:
        self.api_key = os.getenv("FLASHALPHA_API_KEY", "").strip()
        self.base_url = os.getenv("FLASHALPHA_BASE_URL", "https://lab.flashalpha.com").rstrip("/")
        self.timeout = max(0.5, float(os.getenv("FLASHALPHA_TIMEOUT_SECONDS", "2.5")))
        self.cache_ttl = max(0.0, float(os.getenv("FLASHALPHA_CACHE_TTL_SECONDS", "5")))
        self.cache: dict[str, _CacheEntry] = {}

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def _get(self, path: str) -> dict | None:
        if not self.api_key:
            return None
        request = Request(
            f"{self.base_url}{path}",
            headers={"X-Api-Key": self.api_key, "Accept": "application/json"},
            method="GET",
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                if response.status < 200 or response.status >= 300:
                    return None
                payload = json.loads(response.read().decode("utf-8"))
                return payload if isinstance(payload, dict) else None
        except (HTTPError, URLError, TimeoutError, ValueError, OSError):
            return None

    def snapshot(self, symbol: str) -> dict | None:
        symbol = str(symbol or "").strip().upper()
        if not self.configured or not symbol:
            return None
        cached = self.cache.get(symbol)
        now = time.monotonic()
        if cached and cached.expires_at > now:
            return cached.value

        encoded = quote(symbol, safe="")
        gex = self._get(f"/v1/exposure/gex/{encoded}") or {}
        levels_response = self._get(f"/v1/exposure/levels/{encoded}") or {}
        flow = self._get(f"/v1/flow/summary/{encoded}") or {}
        levels = levels_response.get("levels") if isinstance(levels_response.get("levels"), dict) else {}

        if not gex and not levels and not flow:
            return None

        value = {
            "symbol": symbol,
            "as_of": flow.get("as_of") or gex.get("as_of") or levels_response.get("as_of"),
            "underlying_price": flow.get("underlying_price") or gex.get("underlying_price") or levels_response.get("underlying_price"),
            "net_gex": gex.get("net_gex"),
            "live_gex": flow.get("live_gex"),
            "gamma_flip": gex.get("gamma_flip") if gex.get("gamma_flip") is not None else levels.get("gamma_flip"),
            "regime": gex.get("regime") or levels_response.get("regime"),
            "call_wall": levels.get("call_wall"),
            "put_wall": levels.get("put_wall"),
            "zero_dte_magnet": levels.get("zero_dte_magnet"),
            "flow_direction": flow.get("flow_direction"),
            "intraday_oi_delta": flow.get("intraday_oi_delta"),
            "flow_gex_pct_shift": flow.get("flow_gex_pct_shift"),
        }
        self.cache[symbol] = _CacheEntry(now + self.cache_ttl, value)
        return value


_client = FlashAlphaClient()


def enrich_market(market: dict) -> dict:
    """Return market data enriched with an optional FlashAlpha snapshot."""
    symbol = market.get("flashalpha_symbol") or market.get("ticker") or market.get("symbol")
    snapshot = _client.snapshot(str(symbol)) if symbol else None
    if not snapshot:
        return market
    enriched = dict(market)
    enriched["flashalpha"] = snapshot
    return enriched


def client_configured() -> bool:
    return _client.configured
