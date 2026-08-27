from __future__ import annotations

from strat.intelligence.volatility import build_volatility_snapshot


def test_complete_snapshot_exposes_all_volatility_greeks():
    snapshot = build_volatility_snapshot(
        options=[
            {"option_type": "PUT", "iv": 0.22, "open_interest": 100, "vega": 0.4, "theta": -0.08, "vanna": 0.03, "charm": -0.02},
            {"option_type": "CALL", "iv": 0.18, "open_interest": 100, "vega": 0.5, "theta": -0.06, "vanna": 0.02, "charm": -0.01},
        ],
        volatility={
            "iv": 0.20,
            "iv_baseline": 0.18,
            "iv_rank": 82,
            "iv_percentile": 88,
            "realized_volatility": 0.15,
            "skew_change": 0.01,
            "term_structure": [{"days": 7, "iv": 0.23}, {"days": 30, "iv": 0.19}],
        },
    )

    assert snapshot.iv == 0.20
    assert snapshot.iv_regime == "EXPANDING"
    assert snapshot.iv_rank == 82
    assert snapshot.iv_percentile == 88
    assert snapshot.iv_rv_ratio > 1.0
    assert snapshot.put_iv > snapshot.call_iv
    assert snapshot.skew > 0
    assert snapshot.term_structure_regime == "INVERTED"
    assert snapshot.vega_exposure != 0
    assert snapshot.theta_exposure != 0
    assert snapshot.vanna_exposure != 0
    assert snapshot.charm_exposure != 0


def test_missing_volatility_data_is_neutral_not_bearish_or_bullish():
    snapshot = build_volatility_snapshot()

    assert snapshot.iv_regime == "UNKNOWN"
    assert snapshot.iv == 0
    assert snapshot.skew == 0
    assert snapshot.vega_exposure == 0
    assert snapshot.theta_exposure == 0
    assert snapshot.vanna_exposure == 0
    assert snapshot.charm_exposure == 0
    assert snapshot.volatility_score == 50.0


def test_iv_expansion_and_velocity_are_reflected_as_contextual_strength():
    snapshot = build_volatility_snapshot(
        volatility={"iv": 0.25, "iv_baseline": 0.20, "realized_volatility": 0.17},
        velocity_regime="EXPANSION",
    )

    assert snapshot.iv_regime == "EXPANDING"
    assert snapshot.volatility_score > 70


def test_greeks_do_not_create_direction_without_directional_evidence():
    snapshot = build_volatility_snapshot(
        volatility={
            "iv": 0.30,
            "iv_baseline": 0.20,
            "iv_rank": 95,
            "iv_percentile": 96,
            "skew": 0.08,
            "skew_change": 0.03,
            "term_structure_slope": -0.002,
            "vega_exposure": 1000,
            "theta_exposure": -500,
            "vanna_exposure": 250,
            "charm_exposure": -125,
        }
    )

    # The snapshot is deliberately unsigned: directional meaning belongs to
    # the options-flow/Delta/GEX evidence and Strategy 001's confluence layer.
    assert snapshot.volatility_score >= 50
    assert not hasattr(snapshot, "direction")
