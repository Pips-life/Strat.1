from strat.intelligence.precision import MultiTimeframePrecisionPlanner, PrecisionEntryPlanner


def zone(role, lower, upper, *, timeframe, qof_primary=True, **extra):
    return {
        "role": role,
        "lower": lower,
        "upper": upper,
        "center": (lower + upper) / 2.0,
        "timeframe": timeframe,
        "qof_primary": qof_primary,
        **extra,
    }


def test_ltf_respect_of_htf_qof_zone_counts_as_valid_confirmation():
    planner = MultiTimeframePrecisionPlanner(PrecisionEntryPlanner(minimum_score=70.0))
    execution = zone("SUPPORT", 99.0, 101.0, timeframe="5m")
    context = {
        "1h": [zone(
            "SUPPORT", 110.0, 112.0, timeframe="1h",
            ltf_respect=True, respect_direction="LONG",
        )]
    }
    result = planner.plan(
        side="LONG", price=99.5, execution_zone=execution,
        execution_timeframe="5m", context_zones_by_timeframe=context,
        atr=2.0, stop=98.5, target=104.0, minimum_rr=1.35,
    )
    assert result.accepted is True
    assert result.aligned_context is True
    assert result.htf_respected is True
    assert result.reason == "LTF respect validated the higher-timeframe QOF zone"


def test_ltf_respect_direction_must_match_setup():
    planner = MultiTimeframePrecisionPlanner()
    execution = zone("SUPPORT", 99.0, 101.0, timeframe="5m")
    context = {"1h": [zone(
        "SUPPORT", 110.0, 112.0, timeframe="1h",
        ltf_respect=True, respect_direction="SHORT",
    )]}
    result = planner.plan(
        side="LONG", price=99.5, execution_zone=execution,
        execution_timeframe="5m", context_zones_by_timeframe=context,
        atr=2.0, stop=98.5, target=104.0, minimum_rr=1.35,
    )
    assert result.accepted is False
    assert result.htf_respected is False


def test_non_qof_zone_cannot_validate_ltf_respect():
    planner = MultiTimeframePrecisionPlanner()
    execution = zone("SUPPORT", 99.0, 101.0, timeframe="5m")
    context = {"1h": [zone(
        "SUPPORT", 110.0, 112.0, timeframe="1h",
        qof_primary=False, ltf_respect=True, respect_direction="LONG",
    )]}
    result = planner.plan(
        side="LONG", price=99.5, execution_zone=execution,
        execution_timeframe="5m", context_zones_by_timeframe=context,
        atr=2.0, stop=98.5, target=104.0, minimum_rr=1.35,
    )
    assert result.accepted is False
    assert result.htf_respected is False
