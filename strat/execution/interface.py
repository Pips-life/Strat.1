from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime
from .models import ExecutionResult, Fill, OrderRequest, Position


class ExecutionAdapter(ABC):
    """Environment-independent contract used by replay, demo and live execution."""

    @abstractmethod
    def submit(self, order: OrderRequest) -> ExecutionResult:
        raise NotImplementedError

    @abstractmethod
    def close_position(self, symbol: str, timestamp: datetime, price: float | None = None) -> ExecutionResult:
        raise NotImplementedError

    @abstractmethod
    def positions(self) -> list[Position]:
        raise NotImplementedError

    @abstractmethod
    def on_bar(self, symbol: str, timestamp: datetime, high: float, low: float, close: float) -> list[Fill]:
        """Process market movement and return fills caused by stops/targets."""
        raise NotImplementedError
