from datetime import datetime, timedelta, timezone

from strat.core.models import PriceBar
from strat.zones import ZoneEngine, ZoneRole, ZoneState


def make_bars():
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    closes = [100, 102, 104, 108, 104, 101, 103, 105, 101, 98, 101, 103, 100]
    bars = []
    for i, close in enumerate(closes):
        prev = closes[i - 1] if i else close
        bars.append(PriceBar(
            timestamp=start + timedelta(minutes=i),
            open=prev,
            high=max(prev, close) + 1,
            low=min(prev, close) - 1,
            close=close,
            volume=1000,
        ))
    return bars


def test_structural_zones_are_causal_and_typed():
    bars = make_bars()
    engine = ZoneEngine()
    zones = engine.build(bars, atr=3.0)
    assert zones
    assert all(z.role in {ZoneRole.SUPPORT, ZoneRole.RESISTANCE} for z in zones)
    assert all(z.lower <= z.center <= z.upper for z in zones)


def test_zone_state_break_and_flip():
    bars = make_bars()
    engine = ZoneEngine()
    zones = engine.build(bars, atr=3.0)
    support = next(z for z in zones if z.role == ZoneRole.SUPPORT)
    ts = bars[-1].timestamp
    engine.update_states([support], support.lower - 1.0, 3.0, ts)
    assert support.state == ZoneState.BROKEN
    engine.update_states([support], support.center, 3.0, ts)
    assert support.role == ZoneRole.RESISTANCE
    assert support.state == ZoneState.FLIPPED


def test_nearest_zone_is_available():
    bars = make_bars()
    engine = ZoneEngine()
    zones = engine.build(bars, atr=3.0)
    nearest = engine.nearest(zones, bars[-1].close)
    assert nearest is not None
    assert nearest.distance(bars[-1].close) >= 0
