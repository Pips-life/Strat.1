"""Lock the QOF boundary: GEX is regime, not bullish/bearish direction."""
from strat.zones.options import OptionsStructureConfig, _row_score, _role
from strat.zones.models import ZoneRole


def _row(gamma, delta, flow):
    return {
        "strike": 2350,
        "open_interest": 1000,
        "volume": 100,
        "gamma": gamma,
        "delta": delta,
        "flow": flow,
        "iv": 20,
        "option_type": "CALL",
    }


def test_gamma_sign_alone_cannot_create_direction():
    cfg = OptionsStructureConfig()
    _, _, positive_gamma = _row_score(_row(10.0, 0.0, 0.0), cfg)
    _, _, negative_gamma = _row_score(_row(-10.0, 0.0, 0.0), cfg)
    assert positive_gamma == 0.0
    assert negative_gamma == 0.0
    assert _role(_row(10.0, 0.0, 0.0), 2350, 2350, positive_gamma) is None
    assert _role(_row(-10.0, 0.0, 0.0), 2350, 2350, negative_gamma) is None


def test_signed_delta_and_flow_supply_direction():
    cfg = OptionsStructureConfig()
    _, _, bullish = _row_score(_row(-10.0, 0.8, 80.0), cfg)
    _, _, bearish = _row_score(_row(10.0, -0.8, -80.0), cfg)
    assert bullish > 0
    assert bearish < 0
    assert _role(_row(-10.0, 0.8, 80.0), 2350, 2350, bullish) == ZoneRole.SUPPORT
    assert _role(_row(10.0, -0.8, -80.0), 2350, 2350, bearish) == ZoneRole.RESISTANCE
