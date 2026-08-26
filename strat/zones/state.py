"""Deterministic S/R zone state machine."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Optional

from .models import Zone, ZoneRole, ZoneState


@dataclass(frozen=True)
class ZoneStateConfig:
    break_displacement_atr: float = 0.20
    reclaim_tolerance_atr: float = 0.15
    respect_tolerance_atr: float = 0.10
    invalidate_after_bars: int = 500


class ZoneStateMachine:
    def __init__(self, config: ZoneStateConfig | None = None) -> None:
        self.config = config or ZoneStateConfig()

    def update(self, zone: Zone, price: float, atr: float, timestamp: datetime,
               bars_since_test: Optional[int] = None) -> Zone:
        if atr <= 0:
            raise ValueError("atr must be positive")
        penetration = self.config.break_displacement_atr * atr
        tolerance = self.config.reclaim_tolerance_atr * atr
        inside = zone.contains(price)

        if bars_since_test is not None and bars_since_test > self.config.invalidate_after_bars:
            zone.state = ZoneState.INVALIDATED
            return zone

        zone.last_tested = timestamp if inside else zone.last_tested

        if inside:
            if zone.state == ZoneState.BROKEN:
                if zone.role == ZoneRole.SUPPORT and price >= zone.lower - tolerance:
                    zone.role = ZoneRole.RESISTANCE
                    zone.state = ZoneState.FLIPPED
                    return zone
                if zone.role == ZoneRole.RESISTANCE and price <= zone.upper + tolerance:
                    zone.role = ZoneRole.SUPPORT
                    zone.state = ZoneState.FLIPPED
                    return zone
            if zone.state in {ZoneState.ACTIVE, ZoneState.FORMING, ZoneState.TESTED}:
                zone.state = ZoneState.TESTED
            return zone

        if zone.role == ZoneRole.SUPPORT and price < zone.lower - penetration:
            zone.state = ZoneState.BROKEN
            return zone
        if zone.role == ZoneRole.RESISTANCE and price > zone.upper + penetration:
            zone.state = ZoneState.BROKEN
            return zone

        return zone

    def mark_respected(self, zone: Zone) -> Zone:
        zone.state = ZoneState.RESPECTED
        return zone
