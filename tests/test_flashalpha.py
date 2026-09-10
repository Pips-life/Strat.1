from strat.intelligence import flashalpha


def test_flashalpha_enrichment_uses_snapshot(monkeypatch):
    monkeypatch.setenv("FLASHALPHA_API_KEY", "test-key")
    client = flashalpha.FlashAlphaClient()

    responses = {
        "/v1/exposure/gex/SPY": {"net_gex": -100.0, "gamma_flip": 600.0, "underlying_price": 590.0},
        "/v1/exposure/levels/SPY": {"levels": {"call_wall": 610.0, "put_wall": 575.0}},
        "/v1/flow/summary/SPY": {"flow_direction": "amplifying", "live_gex": -120.0, "underlying_price": 590.0},
        "/v1/strategies/flow-anomaly/SPY": {"decision": "candidate", "score": 74, "confidence": 0.8, "regime": "bullish_flow_imbalance", "data_quality": {"score": 90}},
    }
    monkeypatch.setattr(client, "_get", lambda path: responses.get(path))

    snapshot = client.snapshot("SPY")
    assert snapshot["net_gex"] == -100.0
    assert snapshot["live_gex"] == -120.0
    assert snapshot["call_wall"] == 610.0
    assert snapshot["put_wall"] == 575.0
    assert snapshot["flow_signal"]["score"] == 74


def test_flashalpha_is_fail_soft_without_key(monkeypatch):
    monkeypatch.delenv("FLASHALPHA_API_KEY", raising=False)
    client = flashalpha.FlashAlphaClient()
    assert client.snapshot("SPY") is None
