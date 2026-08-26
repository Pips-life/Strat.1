"""Strategy registry and runtime selection."""
from __future__ import annotations

from typing import Dict, Type

from .base import Strategy


class StrategyRegistry:
    def __init__(self) -> None:
        self._strategies: Dict[str, Type[Strategy]] = {}

    def register(self, strategy_cls: Type[Strategy]) -> Type[Strategy]:
        if not strategy_cls.id or strategy_cls.id == "base":
            raise ValueError("A strategy must define a unique id")
        if strategy_cls.id in self._strategies:
            raise ValueError(f"Strategy already registered: {strategy_cls.id}")
        self._strategies[strategy_cls.id] = strategy_cls
        return strategy_cls

    def create(self, strategy_id: str, **kwargs) -> Strategy:
        try:
            return self._strategies[strategy_id](**kwargs)
        except KeyError as exc:
            raise KeyError(f"Unknown strategy: {strategy_id}") from exc

    def list(self) -> list[dict]:
        return [
            {"id": cls.id, "name": cls.name, "version": cls.version}
            for cls in self._strategies.values()
        ]


registry = StrategyRegistry()
