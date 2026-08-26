from datetime import datetime, timedelta, timezone

from strat.confluence import ConfluenceEngine
from strat.core.models import PriceBar
from strat.intelligence import delta_pressure, gamma_exposure, gamma_regime, iv_regime, velocity_signal


def test_qof_intelligence_feeds_confluence_as_one_directional_pipeline():
    now = datetime.now(timezone.utc)
    bars = [
        PriceBar(now + timedelta(minutes=i), 100 + i, 101 + i, 99 + i, 100 + i, 1000 + i * 100)
        for i in range(14)
    ]
    options = [
        {"contracts": 100, "multiplier": 100, "delta": 0.60, "gamma": 0.02, "open_interest": 1000, "option_type": "CALL"},
        {"contracts": 20, "multiplier": 100, "delta": 0.40, "gamma": 0.01, "open_interest": 300, "option_type": "PUT"},
    ]
    delta = delta_pressure(options, 114.0)
    gex = gamma_exposure(options, 114.0)
    gamma = gamma_regime(gex)
    iv_change, iv = iv_regime(0.22, 0.18)
    momentum = velocity_signal(bars)

    gamma_score = 75.0 if gamma in {"POSITIVE_GAMMA", "NEGATIVE_GAMMA"} else 50.0
    iv_score = 70.0 if iv == "EXPANDING" else 50.0
    velocity_score = 80.0 if momentum.regime == "EXPANSION" else 60.0
    volume_score = 70.0 if momentum.relative_volume >= 1.0 else 50.0
    long_components = {
        "structure": 90.0,
        "options_flow": 85.0,
        "gamma": gamma_score,
        "delta": 50.0 + delta / 2.0,
        "iv": iv_score,
        "velocity": velocity_score,
        "volume": volume_score,
    }
    short_components = {k: 100.0 - v for k, v in long_components.items()}

    result = ConfluenceEngine().evaluate_directional(
        long_components,
        short_components,
        derived={"gex": gex, "iv_change": iv_change, "velocity_ratio": momentum.velocity_ratio},
    )
    assert result.direction == "LONG"
    assert result.tradable
    assert "gex" in result.derived
