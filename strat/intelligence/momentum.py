"""Price velocity and participation features used by the confluence layer."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from strat.core.models import PriceBar


@dataclass(frozen=True)
class MomentumSignal:
    velocity: float = 0.0
    baseline_velocity: float = 0.0
    velocity_ratio: float = 0.0
    regime: str = "UNKNOWN"
    relative_volume: float = 0.0


def velocity_signal(bars: Sequence[PriceBar], short_window: int = 3, baseline_window: int = 12,
                    expansion_ratio: float = 1.35, contraction_ratio: float = 0.75) -> MomentumSignal:
    if len(bars) < max(short_window + 1, baseline_window + 1):
        return MomentumSignal()

    def speed(window: int) -> float:
        segment = bars[-window - 1:]
        return abs(segment[-1].close - segment[0].close) / max(window, 1)

    current = speed(short_window)
    baseline = speed(baseline_window)
    ratio = current / baseline if baseline > 0 else 0.0
    if ratio >= expansion_ratio:
        regime = "EXPANSION"
    elif 0 < ratio <= contraction_ratio:
        regime = "CONTRACTION"
    else:
        regime = "STABLE"

    recent_volume = sum(max(0.0, b.volume) for b in bars[-short_window:]) / short_window
    base_volume = sum(max(0.0, b.volume) for b in bars[-baseline_window:]) / baseline_window
    relative_volume = recent_volume / base_volume if base_volume > 0 else 0.0
    return MomentumSignal(current, baseline, ratio, regime, relative_volume)
