from __future__ import annotations

from strat.strategies.strategy_001 import Strategy001
from strat.strategies.strategy_001_volatility import Strategy001Volatility


def _market(*, volatility=None, evidence=None):
    return {
        "price": 100.0,
        "atr": 2.0,
        "flow_direction": "LONG",
        "flow_strength": 80.0,
        "options": [],
        "evidence": evidence or {
            "long": {
                "structure": 85.0,
                "options_flow": 82.0,
                "gamma": 78.0,
                "delta": 80.0,
                "iv": 75.0,
                "velocity": 78.0,
                "volume": 76.0,
            },
            "short": {
                "structure": 45.0,
                "options_flow": 35.0,
                "gamma": 45.0,
                "delta": 35.0,
                "iv": 45.0,
                "velocity": 35.0,
                "volume": 45.0,
            },
        },
        "volatility": volatility or {},
        "qof_market_map": [
            {"id": "support", "center": 99.5, "lower": 98.5, "upper": 100.5,
             "role": "SUPPORT", "state": "ACTIVE", "strength": 90,
             "qof_primary": True, "timeframe": "5m"},
            {"id": "resistance", "center": 105.0, "lower": 104.5, "upper": 105.5,
             "role": "RESISTANCE", "state": "ACTIVE", "strength": 85,
             "qof_primary": True, "timeframe": "5m"},
        ],
    }


def test_replay_baseline_good_qof_trade_is_not_suppressed():
    market = _market()
    base = Strategy001().analyze(market)
    upgraded = Strategy001Volatility().analyze(market)

    assert base["confluence_result"].tradable
    assert upgraded["confluence_result"].tradable
    assert upgraded["confluence_result"].direction == base["confluence_result"].direction


def test_replay_neutral_volatility_data_does_not_remove_a_good_trade():
    market = _market(volatility={})
    result = Strategy001Volatility().analyze(market)["confluence_result"]

    assert result.tradable
    assert result.direction == "LONG"
    assert result.components["volatility"] == 50.0


def test_replay_expanding_volatility_with_velocity_strengthens_context():
    market = _market(
        volatility={"iv": 0.25, "iv_baseline": 0.20, "realized_volatility": 0.17,
                    "iv_rank": 85, "iv_percentile": 90, "skew_change": 0.01},
    )
    market["evidence"]["long"]["velocity"] = 82.0
    result = Strategy001Volatility().analyze(market)["confluence_result"]

    assert result.tradable
    assert result.direction == "LONG"
    assert result.derived["volatility_interaction_bonus"] >= 0.0


def test_replay_extreme_volatility_without_directional_edge_does_not_force_trade():
    market = _market(
        volatility={"iv": 0.35, "iv_baseline": 0.20, "iv_rank": 98,
                    "iv_percentile": 99, "skew_change": 0.05},
        evidence={
            "long": {"structure": 55, "options_flow": 50, "gamma": 50, "delta": 50,
                      "iv": 50, "velocity": 50, "volume": 50},
            "short": {"structure": 55, "options_flow": 50, "gamma": 50, "delta": 50,
                       "iv": 50, "velocity": 50, "volume": 50},
        },
    )
    result = Strategy001Volatility().analyze(market)["confluence_result"]

    assert not result.tradable
    assert result.direction == "NONE"


def test_replay_greek_layer_is_single_volatility_group_not_extra_votes():
    market = _market(volatility={
        "iv": 0.30,
        "iv_baseline": 0.20,
        "vega_exposure": 1000,
        "theta_exposure": -500,
        "vanna_exposure": 300,
        "charm_exposure": -150,
        "skew": 0.04,
    })
    result = Strategy001Volatility().analyze(market)["confluence_result"]

    assert "volatility" in result.components
    assert "vega" not in result.components
    assert "theta" not in result.components
    assert "vanna" not in result.components
    assert "charm" not in result.components
