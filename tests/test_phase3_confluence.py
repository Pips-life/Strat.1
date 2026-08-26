from strat.confluence import ConfluenceConfig, ConfluenceEngine


def test_directional_confluence_uses_new_seven_factor_weights():
    engine = ConfluenceEngine(ConfluenceConfig(minimum_directional_edge=5.0))
    result = engine.evaluate_directional(
        {"structure": 90, "options_flow": 85, "gamma": 75, "delta": 80, "iv": 70, "velocity": 90, "volume": 80},
        {"structure": 40, "options_flow": 35, "gamma": 50, "delta": 20, "iv": 50, "velocity": 30, "volume": 45},
    )
    assert result.direction == "LONG"
    assert result.long_score > result.short_score
    assert result.tradable is True
    assert result.directional_edge >= 5.0


def test_missing_evidence_is_neutral_not_a_failure():
    engine = ConfluenceEngine()
    result = engine.evaluate_directional(
        {"structure": 80, "options_flow": 80},
        {"structure": 40, "options_flow": 40},
    )
    assert "gamma" in result.missing
    assert result.direction == "LONG"
