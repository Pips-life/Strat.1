"""Compatibility facade for the canonical QOF options intelligence path.

This module intentionally contains no independent dealer-position model. The
legacy GoldOptionsEngine implementation previously inferred dealer positions
from open interest alone; that assumption is invalid because OI does not reveal
which side a dealer holds. New QOF code should use strat.intelligence and
strat.zones directly.
"""
from __future__ import annotations

from strat.intelligence.greeks import black76_d1, black76_gamma, delta_pressure, gamma_exposure, gamma_regime

__all__ = [
    "black76_d1",
    "black76_gamma",
    "delta_pressure",
    "gamma_exposure",
    "gamma_regime",
]

# Legacy names are intentionally not re-exported. Keeping a second GoldOptions
# calculation path would allow contradictory dealer-GEX assumptions to return.
