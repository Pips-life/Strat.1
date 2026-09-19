"""Strategy 005: independent Woodie 4H pivots with 5M price action."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Any
from .base import Signal, Strategy

@dataclass(frozen=True)
class Strategy005Config:
    pivot_timeframe: str = "4h"
    entry_timeframe: str = "5m"
    risk_cap: float = 0.05
    touch_tolerance_fraction: float = 0.15
    stop_buffer_fraction: float = 0.10
    min_reward_risk: float = 1.0

class Strategy005(Strategy):
    id = "strategy_005"
    name = "Woodie 4H Pivot / 5M Price Action"
    version = "1.0.0"

    def __init__(self, config: Strategy005Config | None = None) -> None:
        self.config = config or Strategy005Config()

    @staticmethod
    def _bars(raw: Any) -> list[dict[str, float]]:
        if not isinstance(raw, list):
            return []
        out = []
        for b in raw:
            if not isinstance(b, dict):
                continue
            try:
                h, l, c = (float(b[k]) for k in ("high", "low", "close"))
                o = float(b.get("open", c))
            except (KeyError, TypeError, ValueError):
                continue
            if min(o, h, l, c) <= 0 or h < l:
                continue
            out.append({"open": o, "high": h, "low": l, "close": c})
        return out

    @staticmethod
    def woodie(previous_4h: dict[str, float]) -> dict[str, float]:
        h, l, c = previous_4h["high"], previous_4h["low"], previous_4h["close"]
        p = (h + l + 2.0 * c) / 4.0
        return {"pp": p, "r1": 2*p-l, "s1": 2*p-h, "r2": p+h-l, "s2": p-h+l,
                "r3": h+2*(p-l), "s3": l-2*(h-p)}

    def _series(self, market: Any, key: str) -> list[dict[str, float]]:
        if not isinstance(market, dict):
            return []
        tf = market.get("timeframes")
        for source in (tf if isinstance(tf, dict) else {}, market):
            for k in (key, key.upper(), key.lower()):
                if k in source:
                    return self._bars(source[k])
        return []

    @staticmethod
    def _rejection(candle, side, level, tolerance):
        o,h,l,c = candle["open"],candle["high"],candle["low"],candle["close"]
        rng=max(h-l,1e-12)
        if side=="BUY":
            return l <= level+tolerance and c>level and c>o and (min(o,c)-l)/rng >= .25
        return h >= level-tolerance and c<level and c<o and (h-max(o,c))/rng >= .25

    def analyze(self, market: Any) -> dict[str, Any]:
        four_h=self._series(market,self.config.pivot_timeframe)
        five_m=self._series(market,self.config.entry_timeframe)
        if len(four_h)<2 or len(five_m)<2:
            return {"ready":False,"reason":"waiting for previous closed 4H candle and 5M price action"}
        pivot_source=four_h[-2]
        levels=self.woodie(pivot_source)
        candle,prior=five_m[-1],five_m[-2]
        price=candle["close"]
        tolerance=max(pivot_source["high"]-pivot_source["low"],1e-12)*self.config.touch_tolerance_fraction
        side,trigger,target_level="WAIT",None,None
        # Primary model: support/resistance rejection at S1/S2 or R1/R2.
        for s, names in (("BUY",(("s1","S1_REJECTION"),("s2","S2_REJECTION"))),
                         ("SELL",(("r1","R1_REJECTION"),("r2","R2_REJECTION")))):
            for level,name in names:
                if self._rejection(candle,s,levels[level],tolerance):
                    side,trigger,target_level=s,name,levels["pp"]
                    break
            if side!="WAIT": break
        # PP retest model: a completed 5M candle must retest PP from the
        # correct side and close back away from it with directional confirmation.
        if side=="WAIT":
            o,h,l,c=candle["open"],candle["high"],candle["low"],candle["close"]
            if (prior["close"] > levels["pp"] and l <= levels["pp"]+tolerance
                    and c > levels["pp"] and c > o):
                side,trigger,target_level="BUY","PP_RETEST_BUY",levels["r1"]
            elif (prior["close"] < levels["pp"] and h >= levels["pp"]-tolerance
                  and c < levels["pp"] and c < o):
                side,trigger,target_level="SELL","PP_RETEST_SELL",levels["s1"]
        stop=None
        if side=="BUY": stop=candle["low"]-abs(candle["close"]-candle["low"])*self.config.stop_buffer_fraction
        elif side=="SELL": stop=candle["high"]+abs(candle["high"]-candle["close"])*self.config.stop_buffer_fraction
        risk=abs(price-stop) if stop is not None else 0
        rr=abs(target_level-price)/risk if risk>0 and target_level is not None else 0
        return {"ready":True,"side":side,"price":price,"stop":stop,"target":target_level,
                "reward_risk":rr,"trigger":trigger,"pivot_source":pivot_source,"pivots":levels,
                "entry_timeframe":"5m","pivot_timeframe":"4h","risk_cap":self.config.risk_cap}

    def generate_signal(self, analysis):
        if not analysis.get("ready"):
            return Signal(action="WAIT",reason=analysis.get("reason","Strategy 005 waiting"),metadata=analysis)
        side=analysis.get("side","WAIT")
        if side not in {"BUY","SELL"}:
            return Signal(action="WAIT",reason="No confirmed 5M price action at a Woodie S1/S2, R1/R2, or PP retest level.",metadata=analysis)
        if float(analysis.get("reward_risk",0)) < self.config.min_reward_risk:
            return Signal(action="WAIT",confidence=0,reason="Woodie PP target does not provide sufficient room for the 5M setup.",
                          metadata={**analysis,"entry_block":"INSUFFICIENT_REWARD_RISK"})
        return Signal(action=side,confidence=90,entry=float(analysis["price"]),
                      stop_loss=float(analysis["stop"]),take_profit=float(analysis["target"]),
                      reason=f"Woodie 4H {analysis['trigger']} confirmed by 5M price action; exit at 4H PP.",
                      metadata={**analysis,"strategy":self.id,"entry_model":"5M rejection at S1/S2 or R1/R2; PP retest with 5M price action",
                                "exit_model":"4H Woodie PP","risk_model":"maximum 5% account balance"})

    def risk_parameters(self):
        return {"risk_cap":self.config.risk_cap,"risk_basis":"account balance",
                "pivot_formula":"P=(H+L+2C)/4","pivot_source":"previous completed 4H candle",
                "entry_timeframe":"5m","entry_model":"5M rejection/reclaim at S1/S2 or R1/R2; PP retest with 5M price action",
                "exit_model":"current 4H Woodie PP","independent":True,"feed":"MetaApi stream"}
