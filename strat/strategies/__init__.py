"""Canonical Strategy 001 plugin package."""

from .base import Signal, Strategy
from .registry import registry
from .strategy_001 import Strategy001 as _BaseStrategy001, Strategy001Config
from .strategy_001_volatility import Strategy001Volatility

# Keep the canonical public Strategy001 name and runtime id while replacing the
# implementation with the volatility-aware extension. The original class remains
# the base implementation so structure/precision/risk boundaries are preserved.
Strategy001 = Strategy001Volatility
registry._strategies[Strategy001.id] = Strategy001

__all__ = ["Signal", "Strategy", "registry", "Strategy001", "Strategy001Config"]
