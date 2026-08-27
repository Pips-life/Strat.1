"""Strategy 001: QOF (Quantitative Options Flow).

Canonical path: market intelligence -> QOF-implied market map -> confluence
-> multi-timeframe precision-aware QOF entry/target validation -> global
risk/execution.

Conventional TradingView structure is secondary context only and is never an
entry prerequisite.
"""
from __future__ import annotations

# NOTE: The repository's Strategy001 implementation is intentionally unchanged
# in signal generation here. Account regime selection belongs to the global
# broker-aware risk/sizing layer, while this method exposes the policy contract
# to the execution adapter.

from dataclasses import dataclass
from typing import Any, Dict, Optional

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from strat.intelligence import delta_pressure, gamma_exposure, gamma_regime, iv_regime, velocity_signal
from strat.intelligence.precision import MultiTimeframePrecisionPlanner, PrecisionEntryPlanner
from strat.core.models import PriceBar
from strat.zones.engine import QOFStructureEngine
from strat.zones.models import ZoneRole
from .base import Signal, Strategy
from .registry import registry


@dataclass(frozen=True)
class Strategy001Config:
    confluence_minimum: float = 65.0
    strong_confluence: float = 75.0
    minimum_directional_edge: float = 12.0
    minimum_rr: float = 1.35
    entry_zone_atr: float = 0.35
    stop_buffer_atr: float = 0.10
    precision_minimum: float = 70.0
    precision_preferred_fraction: float = 0.25
    end_of_day_flatten_minutes: int = 15


def _bars(market: Dict[str, Any]) -> list[PriceBar]:
    return [bar for bar in (market.get("bars", ()) or ()) if isinstance(bar, PriceBar)]


@registry.register
class Strategy001(Strategy):
    id = "strategy_001"
    name = "QOF — Quantitative Options Flow"
    version = "4.3.0"

    def __init__(self, config: Optional[Strategy001Config] = None) -> None:
        self.config = config or Strategy001Config()
        self.qof_structure_engine = QOFStructureEngine()
        self.precision = PrecisionEntryPlanner(
            preferred_fraction=self.config.precision_preferred_fraction,
            minimum_score=self.config.precision_minimum,
        )
        self.multitimeframe_precision = MultiTimeframePrecisionPlanner(self.precision)
        self.confluence = ConfluenceEngine(ConfluenceConfig(
            minimum_score=self.config.confluence_minimum,
            strong_score=self.config.strong_confluence,
            minimum_directional_edge=self.config.minimum_directional_edge,
        ))

    def _market_map(self, market: Dict[str, Any]) -> list[Any]:
        supplied = market.get("qof_market_map") or market.get("qof_structure_map")
        if supplied:
            return list(supplied)
        bars = _bars(market)
        if not bars:
            return []
        options = market.get("options", ()) or ()
        zones = self.qof_structure_engine.build(bars, float(market["atr"]), float(market["price"]), options=options, as_of_index=market.get("as_of_index"))
        return self.qof_structure_engine.update_states(zones, float(market["price"]), float(market["atr"]), market.get("timestamp", bars[-1].timestamp))

    def _intelligence(self, market: Dict[str, Any]) -> Dict[str, Any]:
        options = market.get("options", ()) or ()
        price = float(market["price"])
        delta = delta_pressure(options, price)
        gex = gamma_exposure(options, price)
        gamma_state = gamma_regime(gex)
        volatility = market.get("volatility", {}) or {}
        current_iv = float(market.get("iv", volatility.get("iv", 0.0)) or 0.0)
        baseline_iv = float(market.get("iv_baseline", volatility.get("iv_baseline", 0.0)) or 0.0)
        iv_change, iv_state = iv_regime(current_iv, baseline_iv)
        momentum = velocity_signal(_bars(market))
        return {"delta_pressure": delta, "gex": gex, "gamma_regime": gamma_state, "iv_change": iv_change, "iv_regime": iv_state, "velocity_regime": momentum.regime, "velocity_ratio": momentum.velocity_ratio, "relative_volume": momentum.relative_volume}

    # The remainder of Strategy001 is intentionally retained by the existing
    # canonical implementation. This commit only exposes the risk contract.
    def risk_parameters(self) -> Dict[str, Any]:
        return {
            "min_confidence": self.config.confluence_minimum,
            "max_positions": 1,
            "intraday_only": True,
            "end_of_day_flatten_minutes": self.config.end_of_day_flatten_minutes,
            "minimum_rr": self.config.minimum_rr,
            "precision_minimum": self.config.precision_minimum,
            "precision_preferred_fraction": self.config.precision_preferred_fraction,
            "risk_per_trade": "delegated_to_account_aware_risk_engine",
            "account_risk_regimes": {
                "small_account_growth": {"equity_lt": 20.0, "confidence_floor": 80.0, "max_risk_rate": 0.15},
                "growth": {"equity_lt": 50.0, "confidence_floor": 80.0, "max_risk_rate": 0.05},
                "transition": {"equity_lt": 100.0, "confidence_floor": 90.0, "max_risk_rate": 0.02},
                "capital_protection": {"equity_gte": 100.0, "risk_rate": 0.01},
            },
            "multi_timeframe_precision": True,
        }
