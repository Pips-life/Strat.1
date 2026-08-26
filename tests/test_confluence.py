from strat.confluence import ConfluenceEngine


def test_full_confluence_can_trade():
    result = ConfluenceEngine().evaluate({
        "options": 90,
        "structure": 82,
        "liquidity": 75,
        "volatility": 70,
    })
    assert result.tradable
    assert result.score >= 78


def test_missing_secondary_evidence_is_not_automatic_rejection():
    result = ConfluenceEngine().evaluate({
        "options": 92,
        "structure": 82,
        "liquidity": 72,
    })
    assert result.tradable
    assert "volatility" in result.missing


def test_contradiction_is_penalized():
    clean = ConfluenceEngine().evaluate({
        "options": 90,
        "structure": 80,
        "liquidity": 80,
        "volatility": 80,
    })
    conflict = ConfluenceEngine().evaluate(
        {
            "options": 90,
            "structure": 80,
            "liquidity": 80,
            "volatility": 80,
        },
        contradictions=["structure_against_trade"],
    )
    assert conflict.score < clean.score
