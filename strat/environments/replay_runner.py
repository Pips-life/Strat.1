from __future__ import annotations
from dataclasses import dataclass
from datetime import date
from typing import Any, Callable
from strat.backtest.equity import EquityCurve
from strat.backtest.models import Bar
from strat.backtest.replay import ReplayEngine
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import Fill, OrderRequest
from strat.risk.engine import RiskEngine, RiskRequest
from strat.strategies.base import Strategy

@dataclass(frozen=True)
class ReplayEvent:
    timestamp: Any
    kind: str
    payload: dict[str, Any]

@dataclass(frozen=True)
class ClosedReplayTrade:
    symbol: str; side: str; quantity: float; entry_price: float; exit_price: float
    entry_time: Any; exit_time: Any; stop_loss: float; pnl: float; r_multiple: float; reason: str

class ReplayRunner:
    """Run Strategy through RiskEngine/execution and automatically build equity reporting."""
    def __init__(self, bars: list[Bar], execution: ExecutionAdapter, strategy: Strategy,
                 risk: RiskEngine | None = None, market_builder: Callable[[Bar], dict[str, Any]] | None = None,
                 account_equity: float = 0.0, point_value: float = 1.0, quantity_step: float = 0.01) -> None:
        self.replay=ReplayEngine(bars); self.execution=execution; self.strategy=strategy
        self.risk=risk or RiskEngine(); self.market_builder=market_builder or self._default_market_builder
        self.initial_equity=account_equity; self.equity=account_equity; self.point_value=point_value; self.quantity_step=quantity_step
        self.events=[]; self.trades=[]; self._open={}; self._daily_pnl={}; self._daily_trades={}; self.consecutive_losses=0
        self.equity_curve=EquityCurve(account_equity)

    def run(self) -> list[ReplayEvent]:
        for bar in self.replay.stream():
            symbol=self._symbol(bar)
            self._record_fills(self.execution.on_bar(symbol,bar.timestamp,bar.high,bar.low,bar.close),bar,"PROTECTIVE_EXIT")
            if self.risk.should_flatten(bar.timestamp) and self.execution.positions():
                result=self.execution.close_position(symbol,bar.timestamp,bar.close)
                if result.fill: self._record_fills([result.fill],bar,"END_OF_DAY_EXIT")
                self.equity_curve.record(bar.timestamp,self.equity); continue
            market=self.market_builder(bar); market.setdefault("symbol",symbol); market.setdefault("price",bar.close)
            if bar.atr is not None: market.setdefault("atr",bar.atr)
            signal=self.strategy.generate_signal(self.strategy.analyze(market))
            self.events.append(ReplayEvent(bar.timestamp,"SIGNAL",{"strategy":self.strategy.id,"action":signal.action,"confidence":signal.confidence,"reason":signal.reason,"metadata":signal.metadata}))
            if signal.action in {"BUY","SELL"} and signal.entry is not None and signal.stop_loss is not None and signal.take_profit is not None:
                day=bar.timestamp.date()
                decision=self.risk.evaluate(RiskRequest(side=signal.action,entry=signal.entry,stop_loss=signal.stop_loss,take_profit=signal.take_profit,equity=self.equity,current_positions=len(self.execution.positions()),daily_pnl=self._daily_pnl.get(day,0.0),trades_today=self._daily_trades.get(day,0),consecutive_losses=self.consecutive_losses,now=bar.timestamp,point_value=self.point_value,confidence=signal.confidence))
                self.events.append(ReplayEvent(bar.timestamp,"RISK",{"approved":decision.approved,"quantity":decision.quantity,"reason":decision.reason,"risk_amount":decision.risk_amount,"reward_risk":decision.reward_risk}))
                if decision.approved:
                    result=self.execution.submit(OrderRequest(symbol=symbol,side=signal.action,quantity=decision.quantity,price=signal.entry,stop_loss=signal.stop_loss,take_profit=signal.take_profit,timestamp=bar.timestamp,metadata={"strategy":self.strategy.id,"confidence":signal.confidence,**signal.metadata}))
                    self.events.append(ReplayEvent(bar.timestamp,"ORDER",{"status":result.status,"order_id":result.order_id,"reason":result.reason}))
                    if result.fill: self._daily_trades[day]=self._daily_trades.get(day,0)+1; self._record_fills([result.fill],bar,"ENTRY",signal.stop_loss)
            elif signal.action=="CLOSE" and self.execution.positions():
                result=self.execution.close_position(symbol,bar.timestamp,bar.close)
                if result.fill: self._record_fills([result.fill],bar,"STRATEGY_EXIT")
            self.equity_curve.record(bar.timestamp,self.equity)
        return self.events

    def summary(self) -> dict[str,Any]:
        wins=[t for t in self.trades if t.pnl>0]; losses=[t for t in self.trades if t.pnl<0]; gp=sum(t.pnl for t in wins); gl=abs(sum(t.pnl for t in losses)); total_r=sum(t.r_multiple for t in self.trades)
        return {"initial_equity":self.initial_equity,"final_equity":self.equity,"realized_pnl":self.equity-self.initial_equity,"trades":len(self.trades),"win_rate":len(wins)/len(self.trades) if self.trades else 0.0,"profit_factor":gp/gl if gl else (float("inf") if gp else 0.0),"total_r":total_r,"max_drawdown":self.equity_curve.max_drawdown,"equity_curve":self.equity_curve.as_dicts()}

    @staticmethod
    def _default_market_builder(bar: Bar)->dict[str,Any]: return {"symbol":str(bar.metadata.get("symbol","XAUUSD")),"price":bar.close,"atr":bar.atr or 0.0,"confluence":{},"levels":{},"price_action":{},"liquidity":{},"volatility":{}}
    @staticmethod
    def _symbol(bar: Bar)->str: return str(bar.metadata.get("symbol","XAUUSD"))

    def _record_fills(self,fills:list[Fill],bar:Bar,kind:str,stop_loss:float|None=None)->None:
        for fill in fills:
            self.events.append(ReplayEvent(bar.timestamp,kind,{"order_id":fill.order_id,"symbol":fill.symbol,"side":fill.side,"quantity":fill.quantity,"price":fill.price,"commission":fill.commission,"slippage":fill.slippage}))
            if kind=="ENTRY": self._open[fill.symbol]=(fill,stop_loss if stop_loss is not None else fill.price); continue
            opened=self._open.pop(fill.symbol,None)
            if opened is None: continue
            entry,stop=opened; direction=1.0 if entry.side=="BUY" else -1.0
            pnl=(fill.price-entry.price)*fill.quantity*direction*self.point_value-entry.commission-fill.commission; risk_cash=abs(entry.price-stop)*entry.quantity*self.point_value; r=pnl/risk_cash if risk_cash>0 else 0.0
            self.trades.append(ClosedReplayTrade(fill.symbol,entry.side,entry.quantity,entry.price,fill.price,entry.timestamp,fill.timestamp,stop,pnl,r,kind)); self.equity+=pnl
            day=fill.timestamp.date(); self._daily_pnl[day]=self._daily_pnl.get(day,0.0)+pnl; self.consecutive_losses=self.consecutive_losses+1 if pnl<0 else 0
