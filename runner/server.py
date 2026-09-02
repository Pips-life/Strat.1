"""Persistent, event-driven Pips-life execution runner.

Architecture:
    MetaApi websocket tick -> BotEngine.on_tick() -> risk gate -> async broker order
"""
from __future__ import annotations

import asyncio
import os
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from metaapi_cloud_sdk import MetaApi, SynchronizationListener
from strat.bot.engine import BotEngine

app = FastAPI(title="Pips-life Live Bot Runner", version="2.0.1")
CONTROL_TOKEN = os.getenv("PIPSLIFE_BOT_CONTROL_TOKEN", "").strip()
METAAPI_TOKEN = os.getenv("METAAPI_TOKEN", "").strip()
DEFAULT_SYMBOL = os.getenv("PIPSLIFE_SYMBOL", "XAUUSD").strip()
LIVE_TRADING_ENABLED = os.getenv("PIPSLIFE_LIVE_TRADING_ENABLED", "true").strip().lower() == "true"
EXECUTION_VOLUME_ENV = os.getenv("PIPSLIFE_EXECUTION_VOLUME", "").strip()
try:
    EXECUTION_VOLUME = float(EXECUTION_VOLUME_ENV) if EXECUTION_VOLUME_ENV else None
except ValueError:
    EXECUTION_VOLUME = None
TRAIL_PIPS = float(os.getenv("PIPSLIFE_STRATEGY002_TRAIL_PIPS", "100"))
MAGIC_BY_STRATEGY = {"strategy_001": 100001, "strategy_002": 100002}
CLIENT_BY_STRATEGY = {"strategy_001": "PIPS001", "strategy_002": "PIPS002"}


def _value(obj: Any, name: str, default: Any = None) -> Any:
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def _price(tick: Any) -> float:
    bid = float(_value(tick, "bid", 0.0) or 0.0)
    ask = float(_value(tick, "ask", 0.0) or 0.0)
    last = float(_value(tick, "last", 0.0) or 0.0)
    return (bid + ask) / 2.0 if bid > 0 and ask > 0 else last


def _timestamp(tick: Any) -> float:
    value = _value(tick, "time", None)
    if value is None:
        return time.time()
    if hasattr(value, "timestamp"):
        return float(value.timestamp())
    try:
        return float(value)
    except (TypeError, ValueError):
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
        except ValueError:
            return time.time()


def _side(value: Any) -> str | None:
    value = str(value or "").upper()
    if "BUY" in value:
        return "BUY"
    if "SELL" in value:
        return "SELL"
    return None


def _managed(items: list[Any], strategy_id: str, symbol: str) -> list[Any]:
    magic = MAGIC_BY_STRATEGY[strategy_id]
    client = CLIENT_BY_STRATEGY[strategy_id]
    return [item for item in items if _value(item, "symbol") == symbol and (str(_value(item, "clientId", "")) == client or int(_value(item, "magic", 0) or 0) == magic)]


class ControlRequest(BaseModel):
    action: str = Field(min_length=1)
    strategy: str | None = None
    accountId: str | None = None


@dataclass
class AccountRuntime:
    account_id: str
    engine: BotEngine = field(default_factory=BotEngine)
    selected_strategy: str = "strategy_001"
    state: str = "READY"
    activity: str = "Runner online"
    symbol: str = DEFAULT_SYMBOL
    connection: Any = None
    listener: Any = None
    task: asyncio.Task | None = None
    stop_task: asyncio.Task | None = None
    last_price: float = 0.0
    pip_size: float = 0.01
    tick_count: int = 0
    stream_active: bool = False
    execution_inflight: bool = False
    latest_price: float = 0.0
    latest_decision_us: float = 0.0
    latest_order_ack_us: float = 0.0
    stop_dirty: bool = False
    stop_order_ids: dict[str, str] = field(default_factory=dict)


runtimes: dict[str, AccountRuntime] = {}
_metaapi: MetaApi | None = None


class TickListener(SynchronizationListener):
    def __init__(self, runtime: AccountRuntime):
        self.runtime = runtime

    async def on_ticks_updated(self, _instance_index: int, ticks: list[Any], equity: float | None = None, margin: float | None = None, free_margin: float | None = None, margin_level: float | None = None, account_currency_exchange_rate: float | None = None) -> None:
        del equity, margin, free_margin, margin_level, account_currency_exchange_rate
        for tick in ticks:
            if _value(tick, "symbol") == self.runtime.symbol:
                await _dispatch_tick(self.runtime, tick)


async def _dispatch_tick(runtime: AccountRuntime, tick: Any) -> None:
    if runtime.state != "RUNNING" or runtime.connection is None:
        return
    current = _price(tick)
    if current <= 0:
        return
    runtime.last_price = current
    runtime.latest_price = current
    runtime.tick_count += 1
    if not runtime.stream_active:
        runtime.stream_active = True
        runtime.activity = f"TICK_STREAM_ACTIVE strategy={runtime.selected_strategy[-3:]} symbol={runtime.symbol}"
        print(runtime.activity, flush=True)
    positions = _managed(runtime.connection.terminal_state.positions or [], runtime.selected_strategy, runtime.symbol)
    received_ns = time.perf_counter_ns()
    signal = runtime.engine.on_tick(current, _timestamp(tick), current_positions=len(positions))
    runtime.latest_decision_us = (time.perf_counter_ns() - received_ns) / 1_000.0
    if signal is None or signal.action not in {"BUY", "SELL"}:
        if runtime.selected_strategy == "strategy_002":
            runtime.stop_dirty = True
            _ensure_stop_worker(runtime)
        return
    if runtime.execution_inflight:
        return
    runtime.execution_inflight = True
    asyncio.create_task(_execute_signal(runtime, signal))


def _ensure_stop_worker(runtime: AccountRuntime) -> None:
    if runtime.stop_task is None or runtime.stop_task.done():
        runtime.stop_task = asyncio.create_task(_stop_worker(runtime))


async def _execute_signal(runtime: AccountRuntime, signal: Any) -> None:
    try:
        if not LIVE_TRADING_ENABLED:
            runtime.activity = f"{runtime.selected_strategy[-3:]} {signal.action} {runtime.symbol} — execution gated"
            print(f"EXECUTION_GATED strategy={runtime.selected_strategy[-3:]} side={signal.action} symbol={runtime.symbol}", flush=True)
            return
        if EXECUTION_VOLUME is None or EXECUTION_VOLUME <= 0:
            runtime.activity = "Execution blocked: PIPSLIFE_EXECUTION_VOLUME must be configured to a positive lot size"
            print("ORDER_ERROR reason=invalid_execution_volume", flush=True)
            return
        strategy = runtime.selected_strategy
        options = {"comment": f"PipsLife{strategy[-3:]}", "clientId": CLIENT_BY_STRATEGY[strategy]}
        started = time.perf_counter_ns()
        stop = getattr(signal, "stop_loss", None) if strategy != "strategy_002" else None
        target = getattr(signal, "take_profit", None)
        if signal.action == "BUY":
            result = await runtime.connection.create_market_buy_order(runtime.symbol, EXECUTION_VOLUME, stop, target, options)
        else:
            result = await runtime.connection.create_market_sell_order(runtime.symbol, EXECUTION_VOLUME, stop, target, options)
        runtime.latest_order_ack_us = (time.perf_counter_ns() - started) / 1_000.0
        runtime.activity = f"{strategy[-3:]} {signal.action} {runtime.symbol} reaction={runtime.latest_decision_us:.0f}us order_ack={runtime.latest_order_ack_us:.0f}us"
        print(f"ORDER_ACK strategy={strategy[-3:]} side={signal.action} symbol={runtime.symbol} volume={EXECUTION_VOLUME} ack_us={runtime.latest_order_ack_us:.0f} result={result}", flush=True)
        if strategy == "strategy_002":
            runtime.stop_dirty = True
            _ensure_stop_worker(runtime)
    except Exception as exc:
        detail = _metaapi.format_error(exc) if _metaapi is not None else str(exc)
        runtime.activity = f"{runtime.selected_strategy[-3:]} execution error: {detail}"
        print(f"ORDER_ERROR strategy={runtime.selected_strategy[-3:]} symbol={runtime.symbol} error={detail}", flush=True)
    finally:
        runtime.execution_inflight = False


async def _stop_worker(runtime: AccountRuntime) -> None:
    while runtime.state == "RUNNING" and runtime.stop_dirty:
        runtime.stop_dirty = False
        try:
            positions = _managed(runtime.connection.terminal_state.positions or [], "strategy_002", runtime.symbol)
            orders = _managed(runtime.connection.terminal_state.orders or [], "strategy_002", runtime.symbol)
            distance = TRAIL_PIPS * runtime.pip_size
            for position in positions:
                position_id = str(_value(position, "id", ""))
                position_side = _side(_value(position, "type"))
                volume = float(_value(position, "volume", EXECUTION_VOLUME or 0.0) or EXECUTION_VOLUME or 0.0)
                if not position_id or not position_side or volume <= 0:
                    continue
                if position_side == "BUY":
                    desired, wanted, create = runtime.latest_price - distance, "SELL", runtime.connection.create_stop_sell_order
                else:
                    desired, wanted, create = runtime.latest_price + distance, "BUY", runtime.connection.create_stop_buy_order
                if desired <= 0:
                    continue
                marker = f"PipsLife002:{position_id}"
                existing = next((o for o in orders if _side(_value(o, "type")) == wanted and str(_value(o, "comment", "")) in {marker, "PipsLife002"}), None)
                if existing is None:
                    result = await create(runtime.symbol, volume, float(desired), None, None, {"comment": marker, "clientId": "PIPS002"})
                    order_id = str(_value(result, "orderId", _value(result, "id", "")) or "")
                    if order_id:
                        runtime.stop_order_ids[position_id] = order_id
                        print(f"STOP_ACK strategy=002 side={wanted} symbol={runtime.symbol} volume={volume} price={desired}", flush=True)
                elif _value(existing, "id"):
                    old = float(_value(existing, "openPrice", 0.0) or 0.0)
                    move = desired > old if position_side == "BUY" else desired < old
                    if move:
                        await runtime.connection.modify_order(str(_value(existing, "id")), float(desired), None, None)
        except Exception as exc:
            detail = _metaapi.format_error(exc) if _metaapi is not None else str(exc)
            runtime.activity = f"002 stop maintenance error: {detail}"
            print(f"STOP_ERROR strategy=002 symbol={runtime.symbol} error={detail}", flush=True)


async def _ensure_connection(runtime: AccountRuntime) -> None:
    global _metaapi
    if runtime.connection is not None:
        return
    if not METAAPI_TOKEN:
        raise HTTPException(status_code=503, detail="METAAPI_TOKEN is not configured")
    if _metaapi is None:
        _metaapi = MetaApi(METAAPI_TOKEN)
    account = await _metaapi.metatrader_account_api.get_account(runtime.account_id)
    if account.state != "DEPLOYED":
        await account.deploy()
    if account.connection_status != "CONNECTED":
        await account.wait_connected()
    connection = account.get_streaming_connection()
    listener = TickListener(runtime)
    connection.add_synchronization_listener(listener)
    await connection.connect()
    await connection.wait_synchronized()
    specification = connection.terminal_state.specification(runtime.symbol)
    pip_size = _value(specification, "pipSize", None)
    point = _value(specification, "point", None)
    if pip_size:
        runtime.pip_size = float(pip_size)
    elif point:
        runtime.pip_size = float(point)
    await connection.subscribe_to_market_data(runtime.symbol, [{"type": "ticks"}])
    runtime.connection = connection
    runtime.listener = listener
    print(f"STREAM_CONNECTED account={runtime.account_id} symbol={runtime.symbol}", flush=True)


async def _start(runtime: AccountRuntime) -> None:
    await _ensure_connection(runtime)
    runtime.engine.select_strategy(runtime.selected_strategy)
    runtime.state = "RUNNING"
    runtime.stream_active = False
    runtime.activity = f"Strategy {runtime.selected_strategy[-3:]} running on MetaApi tick stream"
    async def lifecycle() -> None:
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            raise
    runtime.task = asyncio.create_task(lifecycle())


async def _stop(runtime: AccountRuntime) -> None:
    runtime.state = "STOPPING"
    if runtime.task and not runtime.task.done():
        runtime.task.cancel()
        try:
            await runtime.task
        except asyncio.CancelledError:
            pass
    if runtime.stop_task and not runtime.stop_task.done():
        runtime.stop_dirty = False
        runtime.stop_task.cancel()
        try:
            await runtime.stop_task
        except asyncio.CancelledError:
            pass
    if runtime.connection is not None:
        try:
            if runtime.listener is not None:
                runtime.connection.remove_synchronization_listener(runtime.listener)
        except Exception:
            pass
        try:
            await runtime.connection.unsubscribe_from_market_data(runtime.symbol)
        except Exception:
            pass
        try:
            await runtime.connection.close()
        except Exception:
            pass
    runtime.connection = None
    runtime.listener = None
    runtime.task = None
    runtime.stop_task = None
    runtime.execution_inflight = False
    runtime.stop_order_ids.clear()
    runtime.state = "STOPPED"
    runtime.activity = "Trading stopped"


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"ok": True, "runner": "online", "execution": "tick-event-driven", "metaapiConfigured": bool(METAAPI_TOKEN), "liveTradingEnabled": LIVE_TRADING_ENABLED, "executionVolumeConfigured": EXECUTION_VOLUME is not None and EXECUTION_VOLUME > 0, "accounts": len(runtimes)}


@app.get("/")
async def get_state(accountId: str | None = None) -> dict[str, Any]:
    runtime = runtimes.get(accountId) if accountId else next(iter(runtimes.values()), None)
    if runtime is None:
        return {"configured": True, "state": "READY", "strategy": "001", "activity": "Runner online"}
    return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy[-3:], "activity": runtime.activity, "ticks": runtime.tick_count, "reactionUs": round(runtime.latest_decision_us, 1), "orderAckUs": round(runtime.latest_order_ack_us, 1), "execution": "tick-event-driven", "streamActive": runtime.stream_active}


def _check_control_token(authorization: str | None) -> None:
    if CONTROL_TOKEN and authorization != f"Bearer {CONTROL_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid runner control token")


@app.post("/")
async def control(body: ControlRequest, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _check_control_token(authorization)
    account_id = (body.accountId or os.getenv("METAAPI_ACCOUNT_ID", "")).strip()
    if not account_id:
        raise HTTPException(status_code=400, detail="accountId is required")
    runtime = runtimes.setdefault(account_id, AccountRuntime(account_id=account_id))
    action = body.action.strip().lower()
    if action == "select":
        strategy = str(body.strategy or "").strip()
        if strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        await _stop(runtime)
        runtime.selected_strategy = f"strategy_{strategy}"
        runtime.engine.select_strategy(runtime.selected_strategy)
        runtime.state = "SELECTED"
        runtime.activity = f"Strategy {strategy} selected in BotEngine"
        return await get_state(account_id)
    if action == "start":
        strategy = str(body.strategy or runtime.selected_strategy[-3:]).strip()
        if strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        if runtime.state == "RUNNING":
            await _stop(runtime)
        runtime.selected_strategy = f"strategy_{strategy}"
        await _start(runtime)
        return await get_state(account_id)
    if action == "stop":
        await _stop(runtime)
        return await get_state(account_id)
    raise HTTPException(status_code=400, detail=f"unsupported action: {action}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("runner.server:app", host="0.0.0.0", port=int(os.getenv("PORT", "8080")))
