from strat.intelligence.precision import PrecisionEntryPlanner


def zone():
    return {"lower": 4599.0, "center": 4605.0, "upper": 4611.0}


def test_precision_accepts_entry_near_favorable_support_edge():
    planner = PrecisionEntryPlanner(minimum_score=70.0)
    result = planner.plan(
        side="LONG",
        price=4600.0,
        zone=zone(),
        atr=20.0,
        stop=4598.0,
        target=4630.0,
        minimum_rr=1.35,
    )
    assert result.accepted
    assert result.precision_score >= 70.0


def test_precision_rejects_poor_long_entry_and_waits_for_better_price():
    planner = PrecisionEntryPlanner(minimum_score=70.0)
    result = planner.plan(
        side="LONG",
        price=4610.5,
        zone=zone(),
        atr=20.0,
        stop=4598.0,
        target=4630.0,
        minimum_rr=1.35,
    )
    assert not result.accepted
    assert "wait" in result.reason
    assert result.preferred_entry < result.entry


def test_precision_accepts_short_near_resistance_edge():
    planner = PrecisionEntryPlanner(minimum_score=70.0)
    result = planner.plan(
        side="SHORT",
        price=4610.0,
        zone=zone(),
        atr=20.0,
        stop=4613.0,
        target=4585.0,
        minimum_rr=1.35,
    )
    assert result.accepted
    assert result.precision_score >= 70.0


def test_precision_never_overrides_reward_risk():
    planner = PrecisionEntryPlanner()
    result = planner.plan(
        side="LONG",
        price=4600.0,
        zone=zone(),
        atr=20.0,
        stop=4598.0,
        target=4601.0,
        minimum_rr=1.35,
    )
    assert not result.accepted
    assert "R" in result.reason
