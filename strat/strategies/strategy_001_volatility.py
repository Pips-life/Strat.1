from __future__ import annotations

from typing import Any, Dict

from strat.confluence import ConfluenceConfig, ConfluenceEngine
from strat.intelligence import build_volatility_snapshot
from .strategy_001 import Strategy001 as BaseStrategy001


class VolatilityConfluenceEngine(ConfluenceEngine):
    def _interactions(self, components):
        bonus, conflicts, derived = super()._interactions(components)
        vol = components.get("volatility")
        velocity = components.get("velocity")
        gamma = components.get("gamma")
        if vol is not None and velocity is not None:
            v, m = self._normalize(vol), self._normalize(velocity)
            if v >= 70.0 and m >= 70.0:
                bonus += min(v, m) / 100.0 * 2.0
            elif v >= 75.0 and m <= 30.0:
                conflicts.append("volatility_velocity_conflict")
        if vol is not None and gamma is not None:
            v, g = self._normalize(vol), self._normalize(gamma)
            if v >= 70.0 and g >= 70.0:
                bonus += min(v, g) / 100.0 * 2.0
        bonus = min(self.config.interaction_bonus_cap, bonus)
        derived["volatility_interaction_bonus"] = round(max(0.0, bonus), 3)
        return bonus, conflicts, derived


class Strategy001Volatility(BaseStrategy001):
    version = "4.4.0"

    def __init__(self, config=None) -> None:
        super().__init__(config)
        self.confluence = VolatilityConfluenceEngine(ConfluenceConfig(
            weights={
                "structure": 0.25,
                "options_flow": 0.20,
                "gamma": 0.15,
                "delta": 0.10,
                "volatility": 0.10,
                "velocity": 0.10,
                "volume": 0.10,
            },
            minimum_score=self.config.confluence_minimum,
            strong_score=self.config.strong_confluence,
            minimum_directional_edge=self.config.minimum_directional_edge,
        ))

    def _intelligence(self, market: Dict[str, Any]) -> Dict[str, Any]:
        intelligence = super()._intelligence(market)
        volatility = market.get("volatility", {}) or {}
        snapshot = build_volatility_snapshot(
            market.get("options", ()) or (),
            volatility,
            current_iv=float(market.get("iv", volatility.get("iv", 0.0)) or 0.0),
            baseline_iv=float(market.get("iv_baseline", volatility.get("iv_baseline", 0.0)) or 0.0),
            velocity_regime=str(intelligence.get("velocity_regime", "UNKNOWN")),
        )
        intelligence["volatility_snapshot"] = snapshot
        intelligence.update({
            "iv": snapshot.iv,
            "iv_change": snapshot.iv_change,
            "iv_regime": snapshot.iv_regime,
            "iv_rank": snapshot.iv_rank,
            "iv_percentile": snapshot.iv_percentile,
            "realized_volatility": snapshot.realized_volatility,
            "iv_rv_spread": snapshot.iv_rv_spread,
            "iv_rv_ratio": snapshot.iv_rv_ratio,
            "put_iv": snapshot.put_iv,
            "call_iv": snapshot.call_iv,
            "atm_iv": snapshot.atm_iv,
            "iv_skew": snapshot.skew,
            "iv_skew_change": snapshot.skew_change,
            "term_structure_slope": snapshot.term_structure_slope,
            "term_structure_regime": snapshot.term_structure_regime,
            "vega_exposure": snapshot.vega_exposure,
            "theta_exposure": snapshot.theta_exposure,
            "vanna_exposure": snapshot.vanna_exposure,
            "charm_exposure": snapshot.charm_exposure,
            "volatility_score": snapshot.volatility_score,
        })
        return intelligence

    def _component_scores(self, market, intelligence, zones):
        long_scores, short_scores = super()._component_scores(market, intelligence, zones)
        volatility_score = float(intelligence.get("volatility_score", 50.0))
        supplied = market.get("evidence", {}) or {}
        if supplied.get("long") or supplied.get("short"):
            for scores in (long_scores, short_scores):
                if "volatility" not in scores:
                    scores["volatility"] = scores.get("iv", volatility_score)
        else:
            long_scores.pop("iv", None)
            short_scores.pop("iv", None)
            long_scores["volatility"] = volatility_score
            short_scores["volatility"] = volatility_score
        return long_scores, short_scores
