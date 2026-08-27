import pytest

from strat.intelligence import delta_pressure, gamma_exposure, gamma_regime, iv_regime


def test_delta_pressure_is_directional_and_normalized():
    options = [
        {"option_type": "CALL", "contracts": 100, "delta": 0.5, "multiplier": 1},
        {"option_type": "PUT", "contracts": 50, "delta": 0.4, "multiplier": 1},
    ]
    assert 0 < delta_pressure(options, 100.0) < 100


def test_gamma_and_iv_regimes():
    options = [{"gamma": 0.02, "open_interest": 100, "multiplier": 1, "gex_sign": 1}]
    gex = gamma_exposure(options, 100.0)
    assert gex > 0
    assert gamma_regime(gex) == "POSITIVE_GAMMA"
    change, regime = iv_regime(1.10, 1.00)
    assert change == pytest.approx(0.10)
    assert regime == "EXPANDING"
