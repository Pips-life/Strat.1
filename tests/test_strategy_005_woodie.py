from strat.strategies.strategy_005 import Strategy005


def test_woodie_formula_uses_previous_closed_4h():
    s = Strategy005()
    p = s.woodie({"high": 110.0, "low": 100.0, "close": 108.0})
    assert p["pp"] == 106.5
    assert p["r1"] == 113.0
    assert p["s1"] == 103.0
    assert p["r2"] == 116.5
    assert p["s2"] == 96.5


def test_buy_rejection_at_s1_targets_pp():
    s = Strategy005()
    market = {
        "timeframes": {
            "4h": [
                {"open": 95, "high": 110, "low": 100, "close": 108},
                {"open": 108, "high": 109, "low": 104, "close": 105},
            ],
            "5m": [
                {"open": 104.5, "high": 105, "low": 102.0, "close": 103.5},
                {"open": 103.2, "high": 104.0, "low": 101.5, "close": 103.8},
            ],
        }
    }
    a = s.analyze(market)
    assert a["side"] == "BUY"
    sig = s.generate_signal(a)
    assert sig.action == "BUY"
    assert sig.take_profit == 106.5
    assert sig.stop_loss < sig.entry


def test_no_entry_from_forming_4h_pivot():
    s = Strategy005()
    source = {"high": 110, "low": 100, "close": 108}
    current = {"high": 130, "low": 90, "close": 100}
    market = {"timeframes": {"4h": [source, current], "5m": [
        {"open": 103, "high": 104, "low": 101, "close": 103},
        {"open": 103, "high": 104, "low": 101, "close": 103},
    ]}}
    assert s.analyze(market)["pivots"]["pp"] == 106.5


def test_buy_pp_retest_uses_5m_price_action_and_targets_r1():
    s = Strategy005()
    market = {"timeframes": {
        "4h": [
            {"open": 95, "high": 110, "low": 100, "close": 108},
            {"open": 108, "high": 109, "low": 104, "close": 107},
        ],
        "5m": [
            {"open": 106.8, "high": 107.2, "low": 106.6, "close": 106.9},
            {"open": 106.4, "high": 107.2, "low": 106.3, "close": 106.8},
        ],
    }}
    a = s.analyze(market)
    assert a["side"] == "BUY"
    assert a["trigger"] == "PP_RETEST_BUY"
    assert a["target"] == 113.0
    assert a["stop"] < a["entry"]


def test_sell_pp_retest_uses_bearish_4h_direction_and_5m_price_action():
    s = Strategy005()
    market = {"timeframes": {
        "4h": [
            {"open": 108, "high": 110, "low": 100, "close": 105},
            {"open": 105, "high": 106, "low": 102, "close": 103},
        ],
        "5m": [
            {"open": 104.8, "high": 104.9, "low": 104.4, "close": 104.7},
            {"open": 104.9, "high": 105.2, "low": 104.3, "close": 104.7},
        ],
    }}
    a = s.analyze(market)
    assert a["side"] == "SELL"
    assert a["trigger"] == "PP_RESISTANCE_SELL"
    assert a["target"] == 100.0
    assert a["stop"] > a["entry"]
