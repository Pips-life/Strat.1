from __future__ import annotations

from collections.abc import Iterable, Iterator
from .models import Bar


class ReplayEngine:
    """Deterministic chronological market-data replay."""

    def __init__(self, bars: Iterable[Bar]):
        self._bars = sorted(bars, key=lambda b: b.timestamp)

    def stream(self) -> Iterator[Bar]:
        yield from self._bars

    def __len__(self) -> int:
        return len(self._bars)
