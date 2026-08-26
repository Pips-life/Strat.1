"""Strategy platform public package surface.

QOF options intelligence is canonical. The legacy GoldOptionsEngine is no longer
exported because it contained an invalid OI->dealer-position assumption.
"""
from .intelligence.greeks import delta_pressure, gamma_exposure, gamma_regime, iv_regime

__all__ = [
    "delta_pressure",
    "gamma_exposure",
    "gamma_regime",
    "iv_regime",
]
