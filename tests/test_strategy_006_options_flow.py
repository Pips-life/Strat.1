from strat.strategies.strategy_006_options_flow import OptionsRow, Strategy006Config, calculate_options_map, zone_trade


def test_options_flow_maps_walls_and_flip():
    rows = [
        OptionsRow(2490, "P", open_interest=10000, gamma=0.10),
        OptionsRow(2495, "P", open_interest=8000, gamma=0.08),
        OptionsRow(2505, "C", open_interest=9000, gamma=0.12),
        OptionsRow(2510, "C", open_interest=6000, gamma=0.08),
    ]
    m = calculate_options_map(rows, 2500)
    assert m.data_valid
    assert m.zones.put_wall == 2490
    assert m.zones.call_wall == 2505
    assert m.zones.primary_hedge_floor == 2495
    assert m.zones.immediate_hedge_wall in (2490, 2505)


def test_risk_is_capped_at_five_percent_and_target_is_next_zone():
    rows = [
        OptionsRow(2490, "P", open_interest=10000, gamma=0.10),
        OptionsRow(2505, "C", open_interest=9000, gamma=0.12),
    ]
    m = calculate_options_map(rows, 2490)
    signal = zone_trade(m, 2490, 1000, "BUY", 2485, Strategy006Config(minimum_reward_risk=1.0))
    assert signal.action == "BUY"
    assert signal.take_profit == 2505
    assert signal.metadata["risk_amount"] == 50.0
    assert signal.metadata["risk_fraction"] == 0.05


def test_no_trade_without_valid_zone_to_zone_reward():
    rows = [
        OptionsRow(2490, "P", open_interest=10000, gamma=0.10),
        OptionsRow(2491, "C", open_interest=9000, gamma=0.12),
    ]
    m = calculate_options_map(rows, 2490)
    signal = zone_trade(m, 2490, 1000, "BUY", 2489.5, Strategy006Config(minimum_reward_risk=1.35))
    assert signal.action == "WAIT"
