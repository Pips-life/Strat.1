"""Provider-neutral core market models.

Phase 1 intentionally contains no vendor or execution concerns. Replay, demo,
and live adapters should all normalize into these models before strategy logic.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Mapping


@dataclass(frozen=True)
class PriceBar:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    timeframe: str = "1m"

    def __post_init__(self) -> None:
        if self.high < max(self.open, self.close):
            raise ValueError("high must be >= open and close")
        if self.low > min(self.open, self.close):
            raise ValueError("low must be <= open and close")
        if self.low > self.high:
            raise ValueError("low must be <= high")
        if self.volume < 0:
            raise ValueError("volume cannot be negative")


@dataclass(frozen=True)
class MarketSnapshot:
    """A point-in-time normalized market state."""

    timestamp: datetime
    price: float
    atr: float
    bars: tuple[PriceBar, ...] = ()
    options: Mapping[str, Any] = field(default_factory=dict)
    volume: Mapping[str, Any] = field(default_factory=dict)
    volatility: Mapping[str, Any] = field(default_factory=dict)
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.price <= 0:
            raise ValueError("price must be positive")
        if self.atr <= 0:
            raise ValueError("atr must be positive")
