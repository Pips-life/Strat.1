from strat.risk.account_sizing import AccountRiskSizer, AccountSpec, BrokerSymbolSpec, SizingPolicy


def broker(*, tick_value=1.0, tick_size=0.01, min_volume=0.01, max_volume=100.0, step=0.01, leverage=100, contract_size=1.0):
    return BrokerSymbolSpec(leverage, tick_size, tick_value, min_volume, max_volume, step, contract_size)


def test_small_account_can_trade_when_minimum_volume_is_within_risk_budget():
    sizer = AccountRiskSizer(SizingPolicy(risk_per_trade=0.01, max_notional_pct=100.0))
    result = sizer.evaluate(AccountSpec(balance=20.0, equity=20.0, free_margin=20.0), broker(tick_value=0.005), 3400.0, 3370.0)
    assert result.approved
    assert result.volume >= 0.01
    assert result.risk_amount <= 0.20


def test_small_account_rejects_unsafe_minimum_volume():
    sizer = AccountRiskSizer(SizingPolicy(risk_per_trade=0.01, max_notional_pct=100.0))
    result = sizer.evaluate(AccountSpec(balance=20.0, equity=20.0, free_margin=20.0), broker(tick_value=1.0), 3400.0, 3399.80)
    assert not result.approved
    assert "minimum volume" in result.reason


def test_wider_precise_stop_can_make_minimum_volume_executable_without_increasing_risk_budget():
    sizer = AccountRiskSizer(SizingPolicy(risk_per_trade=0.01, max_notional_pct=100.0))
    result = sizer.evaluate(AccountSpec(balance=20.0, equity=20.0, free_margin=20.0), broker(tick_value=0.005), 3400.0, 3370.0)
    assert result.approved
    assert result.risk_amount <= 0.20


def test_sizer_never_uses_leverage_to_increase_stop_loss_risk():
    sizer = AccountRiskSizer(SizingPolicy(risk_per_trade=0.01, max_notional_pct=100.0))
    low = sizer.evaluate(AccountSpec(20.0, 20.0, 20.0), broker(tick_value=1.0, leverage=50), 3400.0, 3399.50)
    high = sizer.evaluate(AccountSpec(20.0, 20.0, 20.0), broker(tick_value=1.0, leverage=500), 3400.0, 3399.50)
    assert low.risk_amount <= 0.20 or not low.approved
    assert high.risk_amount <= 0.20 or not high.approved
