from strat.intelligence.precision import MultiTimeframePrecisionPlanner, PrecisionEntryPlanner


def zone(role, lower, upper, *, timeframe, qof_primary=True):
    return {"role": role, "lower": lower, "upper": upper, "center": (lower + upper) / 2.0, "timeframe": timeframe, "qof_primary": qof_primary}


def test_higher_timeframe_qof_context_confirms_execution_precision():
    planner = MultiTimeframePrecisionPlanner(PrecisionEntryPlanner(minimum_score=70.0))
    execution = zone("SUPPORT", 99.0, 101.0, timeframe="5m")
    context = {"1h": [zone("SUPPORT", 98.0, 102.0, timeframe="1h")]}
    result = planner.plan(side="LONG", price=99.5, execution_zone=execution, execution_timeframe="5m", context_zones_by_timeframe=context, atr=2.0, stop=98.5, target=104.0, minimum_rr=1.35)
    assert result.accepted is True and result.aligned_context is True and result.execution_timeframe == "5m" and "1h" in result.context_timeframes


def test_unaligned_higher_timeframe_qof_context_blocks_entry():
    planner = MultiTimeframePrecisionPlanner()
    execution = zone("SUPPORT", 99.0, 101.0, timeframe="5m")
    context = {"1h": [zone("RESISTANCE", 110.0, 112.0, timeframe="1h")]}
    result = planner.plan(side="LONG", price=99.5, execution_zone=execution, execution_timeframe="5m", context_zones_by_timeframe=context, atr=2.0, stop=98.5, target=104.0, minimum_rr=1.35)
    assert result.accepted is False and result.aligned_context is False


def test_price_only_context_cannot_satisfy_qof_alignment():
    planner = MultiTimeframePrecisionPlanner()
    execution = zone("RESISTANCE", 109.0, 111.0, timeframe="5m")
    context = {"1h": [zone("RESISTANCE", 108.0, 112.0, timeframe="1h", qof_primary=False)]}
    result = planner.plan(side="SHORT", price=110.0, execution_zone=execution, execution_timeframe="5m", context_zones_by_timeframe=context, atr=2.0, stop=112.0, target=104.0, minimum_rr=1.35)
    assert result.accepted is False and result.aligned_context is False


def test_missing_context_does_not_create_direction_or_force_rejection():
    planner = MultiTimeframePrecisionPlanner()
    execution = zone("RESISTANCE", 109.0, 111.0, timeframe="5m")
    # At the preferred short entry the absence of HTF context is neutral; it
    # neither invents directional confirmation nor penalizes a precise entry.
    result = planner.plan(side="SHORT", price=110.5, execution_zone=execution, execution_timeframe="5m", context_zones_by_timeframe=None, atr=2.0, stop=112.0, target=104.0, minimum_rr=1.35)
    assert result.accepted is True
    assert result.aligned_context is False
