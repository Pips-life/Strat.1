"""Strategy 001: Gold options-flow/GEX strategy.

The strategy uses the reusable Confluence Engine. It is intentionally
opportunity-preserving: missing secondary evidence is neutral, while explicit
contradictions are penalized. Entry rules remain conservative until validated
with replay/backtesting data.
"""
from __future__ import annotations

from typing import Any, Dict

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from .base import Signal, Strategy
from .registry import registry


@registry.register
class OptionsFlowStrategy(Strategy):
    id = "options_flow_001"
    name = "Gold Options Flow"
    version = "1.1.0"

    def __init__(
        self,
        min_confidence: float = 75.0,
        confluence_minimum: float = 62.0,
        confluence_strong: float = 78.0,
    ) -> None:
        self.min_confidence = min_confidence
        self.confluence = ConfluenceEngine(
            ConfluenceConfig(
                minimum_score=confluence_minimum,
                strong_score=confluence_strong,
            )
        )

    def analyze(self, market: Any) -> Dict[str, Any]:
        """Calculate confluence from a normalized options-map result.

        Expected optional market keys:
          confluence: {options, structure, liquidity, volatility}
          contradictions: list[str]
          levels: six-level options map

        Components may be omitted. The confluence engine renormalizes the
        available evidence instead of rejecting a setup merely because a
        secondary feed is unavailable.
        """
        if not isinstance(market, dict):
            raise TypeError("market must be the options-map result dictionary")

        components = market.get("confluence", {}) or {}
        contradictions = market.get("contradictions", []) or []
        result = self.confluence.evaluate(
            components=components,
            contradictions=contradictions,
        )

        return {
            **market,
            "confluence_result": result,
        }

    def generate_signal(self, analysis: Dict[str, Any]) -> Signal:
        """Return a normalized signal without directly executing a trade.

        A tradable confluence score is required, but the current implementation
        still defaults to WAIT because precise entry/exit triggers must first be
        validated against historical/replay data. This prevents premature live
        trading while preserving the scoring framework for future rules.
        """
        result = analysis["confluence_result"]
        levels = analysis.get("levels", {})

        action = "WAIT"
        reason = (
            f"Confluence {result.score:.1f}/100 ({result.grade}); "
            "entry trigger pending validation."
        )

        return Signal(
            action=action,
            confidence=result.score,
            reason=reason,
            metadata={
                "levels": levels,
                "confluence": {
                    "score": result.score,
                    "grade": result.grade,
                    "tradable": result.tradable,
                    "components": result.components,
                    "missing": result.missing,
                    "contradictions": result.contradictions,
                },
            },
        )

    def risk_parameters(self) -> Dict[str, Any]:
        return {
            "min_confidence": self.min_confidence,
            "max_positions": 1,
            "confluence_minimum": self.confluence.config.minimum_score,
            "confluence_strong": self.confluence.config.strong_score,
        }
