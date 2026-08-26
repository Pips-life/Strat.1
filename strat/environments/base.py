from __future__ import annotations

from abc import ABC, abstractmethod
from strat.execution.interface import ExecutionAdapter


class TradingEnvironment(ABC):
    """Container for the data source and execution adapter used by the bot."""

    name: str

    def __init__(self, execution: ExecutionAdapter):
        self.execution = execution

    @property
    @abstractmethod
    def is_live(self) -> bool:
        raise NotImplementedError


class ReplayEnvironment(TradingEnvironment):
    name = "replay"

    @property
    def is_live(self) -> bool:
        return False


class DemoEnvironment(TradingEnvironment):
    name = "demo"

    @property
    def is_live(self) -> bool:
        return False


class LiveEnvironment(TradingEnvironment):
    name = "live"

    @property
    def is_live(self) -> bool:
        return True
