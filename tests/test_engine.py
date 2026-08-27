import math

import pytest

from strat.intelligence.greeks import black76_d1, black76_gamma
from strat.options_engine import black76_gamma as facade_black76_gamma


def test_black76_gamma_positive():
    gamma = black76_gamma(4620, 4620, 0.20, 3 / 365)
    assert gamma > 0
    assert math.isfinite(gamma)
    assert facade_black76_gamma(4620, 4620, 0.20, 3 / 365) == pytest.approx(gamma)


def test_black76_d1_at_the_money_is_positive_for_positive_vol_time():
    d1 = black76_d1(4620, 4620, 0.20, 3 / 365)
    assert d1 > 0
    assert math.isfinite(d1)


def test_black76_rejects_invalid_inputs():
    with pytest.raises(ValueError):
        black76_gamma(4620, 4620, 0.0, 3 / 365)
