"""Strategy 002: Velocity Expansion.

Detects accelerating directional price velocity and enters immediately. The
strategy does not own broker execution; it emits an entry plus the parameters
needed by the execution controller to maintain the 100-pip opposite stop loop.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import floor
from statistics import median
from typing import Any, Iterable

from .base import Signal, Strategy


@dataclass(frozen=True)
class Strategy002Config:
    pip_size: float = 0.01
    trail_pips: float = 100.0
    baseline_window: int = 20
    min_velocity_ratio: float = 1.50
    min_acceleration_ratio: float = 1.05
    min_velocity: float = 0.0
    risk_per_trade: float = 0.01
    quantity_step: float = 0.01
    min_quantity: float = 0.01
    min_confidence: float = 70.0

    @property
    def trail_distance(self) -> float:
        return self.pip_size * self.trail_pips


class Strategy002(Strategy):
    id = "strategy_002"
    name = "Velocity Expansion"
    version = "1.0.0"

    def __init__(self, config: Strategy002Config | None = None) -> None:
        self.config = config or Strategy002Config()

    @staticmethod
    def _samples(market: Any) -> list[tuple[float, float]]:
        """Return [(timestamp_seconds, price), ...] in chronological order."""
        if isinstance(market, dict):
            raw = market.get("ticks") or market.get("prices") or market.get("samples") or []
        else:
            raw = market
        result: list[tuple[float, float]] = []
        for item in raw:
            if isinstance(item, dict):
                ts = item.get("timestamp", item.get("time"))
                price = item.get("price", item.get("close"))
            else:
                ts, price = item
            if ts is None or price is None:
                continue
            if hasattr(ts, "timestamp"):
                ts = ts.timestamp()
            result.append((float(ts), float(price)))
        return result

    def analyze(self, market: Any) -> dict[str, Any]:
        samples = self._samples(market)
        needed = self.config.baseline_window + 2
        if len(samples) < needed:
            return {"velocity_expanding": False, "reason": "insufficient samples", "sample_count": len(samples)}

        velocities: list[float] = []
        for (t0, p0), (t1, p1) in zip(samples[-(self.config.baseline_window + 2):], samples[-(self.config.baseline_window + 1):]):
            dt = t1 - t0
            velocities.append((p1 - p0) / dt if dt > 0 else 0.0)

        current = velocities[-1]
        previous = velocities[-2]
        baseline = median(abs(v) for v in velocities[:-1])
        baseline = max(baseline, 1e-12)
        current_abs = abs(current)
        expansion_ratio = current_abs / baseline
        acceleration_ratio = current_abs / max(abs(previous), 1e-12)
        expanding = (
            current_abs >= self.config.min_velocity
            and expansion_ratio >= self.config.min_velocity_ratio
            and acceleration_ratio >= self.config.min_acceleration_ratio
        )
        direction = "BUY" if current > 0 else "SELL" if current < 0 else "WAIT"
        confidence = min(99.0, 50.0 + 20.0 * max(0.0, expansion_ratio - 1.0) + 20.0 * max(0.0, acceleration_ratio - 1.0))
        return {
            "velocity_expanding": expanding,
            "direction": direction,
            "velocity": current,
            "previous_velocity": previous,
            "baseline_velocity": baseline,
            "expansion_ratio": expansion_ratio,
            "acceleration_ratio": acceleration_ratio,
            "confidence": confidence,
            "price": samples[-1][1],
            "timestamp": samples[-1][0],
            "sample_count": len(samples),
        }

    def generate_signal(self, analysis: dict[str, Any]) -> Signal:
        if not analysis.get("velocity_expanding"):
            return Signal(action="WAIT", reason=analysis.get("reason", "velocity is not expanding"), metadata=analysis)

        side = analysis["direction"]
        entry = float(analysis["price"])
        distance = self.config.trail_distance
        opposite_stop = entry - distance if side == "BUY" else entry + distance
        confidence = float(analysis.get("confidence", 0.0))
        return Signal(
            action=side,
            confidence=confidence,
            entry=entry,
            stop_loss=opposite_stop,
            reason="velocity expansion detected; immediate directional entry",
            metadata={
                **analysis,
                "strategy": self.id,
                "trail_pips": self.config.trail_pips,
                "trail_distance": distance,
                "opposite_stop_side": "SELL" if side == "BUY" else "BUY",
                "loop": "on opposite stop trigger: close source position, promote stop to running position, place new opposite stop",
            },
        )

    def calculate_quantity(self, *, balance: float, entry: float, tick_size: float, tick_value: float) -> float:
        """Size from account balance using the configured 100-pip reversal distance.

        tick_value is the account-currency value of one tick for one lot.
        """
        if balance <= 0 or entry <= 0 or tick_size <= 0 or tick_value <= 0:
            return 0.0
        risk_budget = balance * self.config.risk_per_trade
        ticks_at_risk = self.config.trail_distance / tick_size
        risk_per_lot = ticks_at_risk * tick_value
        if risk_per_lot <= 0:
            return 0.0
        raw = risk_budget / risk_per_lot
        qty = floor(raw / self.config.quantity_step) * self.config.quantity_step
        return round(qty, 10) if qty >= self.config.min_quantity else 0.0

    def risk_parameters(self) -> dict[str, Any]:
        return {
            "risk_per_trade": self.config.risk_per_trade,
            "trail_pips": self.config.trail_pips,
            "pip_size": self.config.pip_size,
            "trail_distance": self.config.trail_distance,
            "sizing": "balance * risk_per_trade / (trail_distance / tick_size * tick_value)",
        }
