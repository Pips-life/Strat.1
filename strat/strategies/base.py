"""Stable interface that every trading strategy must implement."""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict


@dataclass
class Signal:
    action: str = "WAIT"  # BUY, SELL, WAIT, CLOSE
    confidence: float = 0.0
    entry: float | None = None
    stop_loss: float | None = None
    take_profit: float | None = None
    reason: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)


class Strategy(ABC):
    """Strategy plugin contract.

    Strategies analyze market data and return normalized Signals. Risk and
    execution remain outside the strategy so new ideas cannot bypass global
    controls.
    """

    id: str = "base"
    name: str = "Base Strategy"
    version: str = "1.0.0"

    @abstractmethod
    def analyze(self, market: Any) -> Dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    def generate_signal(self, analysis: Dict[str, Any]) -> Signal:
        raise NotImplementedError

    def risk_parameters(self) -> Dict[str, Any]:
        return {}
