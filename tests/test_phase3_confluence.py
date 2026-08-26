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


def test_cross_factor_confirmation_is_applied():
    engine = ConfluenceEngine(ConfluenceConfig(minimum_directional_edge=0.0))
    result = engine.evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 90, "delta": 90, "iv": 90, "velocity": 90, "volume": 90},
        {"structure": 50, "options_flow": 50, "gamma": 50, "delta": 50, "iv": 50, "velocity": 50, "volume": 50},
    )
    assert result.derived["long_interaction_bonus"] > 0
    assert result.derived["short_interaction_bonus"] == 0


def test_conflicting_cross_factor_evidence_is_penalized():
    engine = ConfluenceEngine()
    clean = engine.evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 70, "delta": 90, "iv": 70, "velocity": 70, "volume": 70},
        {"structure": 40, "options_flow": 40, "gamma": 50, "delta": 40, "iv": 50, "velocity": 50, "volume": 50},
    )
    conflict = engine.evaluate_directional(
        {"structure": 90, "options_flow": 90, "gamma": 70, "delta": 20, "iv": 70, "velocity": 70, "volume": 70},
        {"structure": 40, "options_flow": 40, "gamma": 50, "delta": 80, "iv": 50, "velocity": 50, "volume": 50},
    )
    assert "structure_delta_conflict" in conflict.contradictions
    assert conflict.long_score < clean.long_score


def test_missing_evidence_is_neutral_not_a_failure():
    engine = ConfluenceEngine()
    result = engine.evaluate_directional(
        {"structure": 80, "options_flow": 80},
        {"structure": 40, "options_flow": 40},
    )
    assert "gamma" in result.missing
    assert result.direction == "LONG"
