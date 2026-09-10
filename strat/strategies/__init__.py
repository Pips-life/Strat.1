"""Canonical strategy plugin package."""

from .base import Signal, Strategy
from .registry import registry
from .strategy_001 import Strategy001 as _BaseStrategy001, Strategy001Config
from .strategy_001_volatility import Strategy001Volatility
from .strategy_002 import Strategy002, Strategy002Config
from .strategy_003_smc import Strategy003, Strategy003Config

# Strategy 001 remains the canonical QOF implementation with its volatility-aware extension.
Strategy001 = Strategy001Volatility
registry._strategies[Strategy001.id] = Strategy001
registry._strategies[Strategy002.id] = Strategy002
registry._strategies[Strategy003.id] = Strategy003

__all__ = [
    "Signal",
    "Strategy",
    "registry",
    "Strategy001",
    "Strategy001Config",
    "Strategy002",
    "Strategy002Config",
    "Strategy003",
    "Strategy003Config",
]
