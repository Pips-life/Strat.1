from datetime import datetime, timezone

from strat.zones.options import detect_qof_structure
from strat.zones.models import ZoneRole


def _row(strike, gamma, delta, flow, option_type="CALL"):
    return {"strike": strike, "open_interest": 100, "volume": 100,
            "gamma": gamma, "delta": delta, "flow": flow,
            "iv": 0.25, "option_type": option_type}


def test_negative_signed_pressure_creates_resistance():
    zones = detect_qof_structure([_row(101, -0.8, -0.4, -0.3)], 100, 2, datetime.now(timezone.utc))
    assert zones
    assert zones[0].role == ZoneRole.RESISTANCE


def test_positive_signed_pressure_creates_support():
    zones = detect_qof_structure([_row(99, 0.8, 0.4, 0.3, "PUT")], 100, 2, datetime.now(timezone.utc))
    assert zones
    assert zones[0].role == ZoneRole.SUPPORT


def test_opposite_signed_inputs_do_not_collapse_to_same_direction():
    timestamp = datetime.now(timezone.utc)
    negative = detect_qof_structure([_row(101, -0.8, -0.4, -0.3)], 100, 2, timestamp)
    positive = detect_qof_structure([_row(101, 0.8, 0.4, 0.3)], 100, 2, timestamp)
    assert negative[0].role != positive[0].role
