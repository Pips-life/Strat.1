from strat.confluence import ConfluenceEngine


def test_canonical_qof_factors_are_tradable_when_aligned():
    result = ConfluenceEngine().evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 80, "delta": 85, "iv": 70, "velocity": 85, "volume": 80},
        {"structure": 35, "options_flow": 35, "gamma": 45, "delta": 25, "iv": 50, "velocity": 30, "volume": 40},
    )
    assert result.tradable
    assert result.direction == "LONG"


def test_missing_factors_are_neutral():
    result = ConfluenceEngine().evaluate_directional(
        {"structure": 82, "options_flow": 82},
        {"structure": 40, "options_flow": 40},
    )
    assert "gamma" in result.missing
    assert result.direction == "LONG"


def test_contradiction_reduces_edge():
    clean = ConfluenceEngine().evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 80, "delta": 85, "iv": 70, "velocity": 80, "volume": 80},
        {"structure": 40, "options_flow": 40, "gamma": 50, "delta": 35, "iv": 50, "velocity": 40, "volume": 45},
    )
    conflict = ConfluenceEngine().evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 80, "delta": 20, "iv": 70, "velocity": 80, "volume": 80},
        {"structure": 40, "options_flow": 40, "gamma": 50, "delta": 85, "iv": 50, "velocity": 40, "volume": 45},
    )
    assert conflict.long_score < clean.long_score
