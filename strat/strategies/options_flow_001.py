"""Strategy 001: Gold options-flow/GEX strategy.

The analytical engine remains provider-independent. This plugin converts its
six-level map into a normalized trading signal. Execution and global risk are
handled elsewhere.
"""
from __future__ import annotations

from typing import Any, Dict

from .base import Signal, Strategy
from .registry import registry


@registry.register
class OptionsFlowStrategy(Strategy):
    id = "options_flow_001"
    name = "Gold Options Flow"
    version = "1.0.0"

    def __init__(self, min_confidence: float = 75.0) -> None:
        self.min_confidence = min_confidence

    def analyze(self, market: Any) -> Dict[str, Any]:
        """Expect market to expose an already-calculated options map."""
        if not isinstance(market, dict):
            raise TypeError("market must be the options-map result dictionary")
        return market

    def generate_signal(self, analysis: Dict[str, Any]) -> Signal:
        """Conservative first implementation: map levels to WAIT by default.

        We deliberately do not invent entry/exit rules until the strategy is
        backtested. The six-level analytics are available in metadata for the
        eventual rule set.
        """
        levels = analysis.get("levels", {})
        return Signal(
            action="WAIT",
            confidence=0.0,
            reason="Strategy 001 analytics ready; entry rules pending validation.",
            metadata={"levels": levels},
        )

    def risk_parameters(self) -> Dict[str, Any]:
        return {
            "min_confidence": self.min_confidence,
            "max_positions": 1,
        }
