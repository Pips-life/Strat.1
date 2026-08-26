from datetime import datetime, timedelta, timezone

from strat.core.models import PriceBar
from strat.zones import QOFStructureEngine, ZoneRole, ZoneState


def bars():
    start = datetime(2026, 8, 27, tzinfo=timezone.utc)
    closes = [4640, 4642, 4641, 4643, 4644]
    return [
        PriceBar(
            timestamp=start + timedelta(minutes=i),
            open=closes[i - 1] if i else closes[i],
            high=closes[i] + 2,
            low=closes[i] - 2,
            close=closes[i],
            volume=1000,
        )
        for i in range(len(closes))
    ]


def test_qof_can_project_structure_before_price_reaches_it():
    engine = QOFStructureEngine()
    structures = engine.build(
        bars(),
        atr=10.0,
        options=[
            {
                "strike": 4650,
                "option_type": "CALL",
                "open_interest": 1000,
                "volume": 500,
                "gamma": 0.40,
                "delta": 0.60,
                "iv": 20,
                "flow_score": 80,
            }
        ],
    )
    resistance = next(z for z in structures if z.role == ZoneRole.RESISTANCE)
    assert resistance.metadata["qof_primary"] is True
    assert resistance.metadata["predictive"] is True
    assert resistance.metadata["structure_kind"] == "DEALER_RESISTANCE"
    assert resistance.state == ZoneState.FORMING
    assert resistance.center == 4650


def test_qof_target_candidates_are_structure_to_structure():
    engine = QOFStructureEngine()
    structures = engine.build(
        bars(),
        atr=10.0,
        options=[
            {"strike": 4650, "option_type": "CALL", "open_interest": 1000, "volume": 500},
            {"strike": 4625, "option_type": "PUT", "open_interest": 1200, "volume": 600},
        ],
    )
    targets = engine.target_candidates(structures, 4640, "SHORT")
    assert targets
    assert targets[0].role == ZoneRole.SUPPORT
    assert targets[0].center == 4625


def test_qof_structure_can_be_validated_by_price_interaction():
    engine = QOFStructureEngine()
    structures = engine.build(
        bars(),
        atr=10.0,
        options=[
            {"strike": 4650, "option_type": "CALL", "open_interest": 1000, "volume": 500},
        ],
    )
    resistance = next(z for z in structures if z.role == ZoneRole.RESISTANCE)
    engine.update_states(structures, 4650, 10.0, bars()[-1].timestamp)
    assert resistance.state == ZoneState.TESTED
    assert resistance.metadata["validated_by_price"] is True
