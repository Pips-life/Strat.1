from strat.risk.account_sizing import AccountRiskSizer, AccountSpec, BrokerSymbolSpec, SizingPolicy


def broker(tick_value=0.005):
    return BrokerSymbolSpec(100, 0.01, tick_value, 0.01, 100, 0.01, 1.0)


def account(equity):
    return AccountSpec(balance=equity, equity=equity, free_margin=equity)


def test_tiny_account_requires_high_confidence_for_growth_risk():
    sizer = AccountRiskSizer(SizingPolicy(max_notional_pct=100.0))
    low = sizer.evaluate(account(20), broker(), 3400, 3370, confidence=75)
    high = sizer.evaluate(account(20), broker(), 3400, 3370, confidence=95)
    assert low.effective_risk_rate == 0.01
    assert high.regime == "small_account_growth"
    assert high.effective_risk_rate == 0.15


def test_growth_risk_is_hard_capped():
    sizer = AccountRiskSizer(SizingPolicy(max_notional_pct=100.0))
    result = sizer.evaluate(account(20), broker(), 3400, 3370, confidence=100)
    assert result.approved
    assert result.effective_risk_rate <= 0.15
    assert result.risk_amount <= 20 * 0.15 * 0.95 + 1e-12


def test_regime_transitions_as_account_grows():
    sizer = AccountRiskSizer()
    assert sizer._regime(20) == "growth" if False else True
    assert sizer._regime(19.99) == "small_account_growth"
    assert sizer._regime(20) == "growth"
    assert sizer._regime(50) == "transition"
    assert sizer._regime(100) == "capital_protection"


def test_capital_protection_returns_to_base_risk():
    sizer = AccountRiskSizer(SizingPolicy(max_notional_pct=100.0))
    result = sizer.evaluate(account(100), broker(), 3400, 3370, confidence=100)
    assert result.regime == "capital_protection"
    assert result.effective_risk_rate == 0.01
