"""Persistent Pips-life bot runner.

The Next.js API is the control plane. This service is the long-lived process that
owns the MetaApi streaming connection. Strategy 002 executes directly from
MetaApi tick synchronization events: no polling loop, no HTTP round-trip per
tick, and no blocking strategy work in the quote callback.
"""
from __future__ import annotations

import asyncio
import os
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from metaapi_cloud_sdk import MetaApi, SynchronizationListener

from strat.bot.engine import BotEngine

app = FastAPI(title="Pips-life Live Bot Runner", version="1.1.1")

CONTROL_TOKEN = os.getenv("PIPSLIFE_BOT_CONTROL_TOKEN", "").strip()
METAAPI_TOKEN = os.getenv("METAAPI_TOKEN", "").strip()
DEFAULT_SYMBOL = os.getenv("PIPSLIFE_SYMBOL", "XAUUSD").strip()
LIVE_TRADING_ENABLED = os.getenv("PIPSLIFE_LIVE_TRADING_ENABLED", "false").strip().lower() == "true"
STRATEGY002_VOLUME = float(os.getenv("PIPSLIFE_STRATEGY002_VOLUME", "0.01"))
STRATEGY002_PIPS = float(os.getenv("PIPSLIFE_STRATEGY002_TRAIL_PIPS", "100"))
STRATEGY002_MAGIC = 100002
STRATEGY002_CLIENT_ID = "PIPS002"


def _value(obj: Any, name: str, default: Any = None) -> Any:
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


class ControlRequest(BaseModel):
    action: str = Field(min_length=1)
    strategy: str | None = None
    accountId: str | None = None


@dataclass
class AccountRuntime:
    account_id: str
    engine: BotEngine = field(default_factory=BotEngine)
    selected_strategy: str = "001"
    state: str = "READY"
    activity: str = "Runner online"
    symbol: str = DEFAULT_SYMBOL
    connection: Any = None
    listener: Any = None
    task: asyncio.Task | None = None
    samples: deque[tuple[float, float]] = field(default_factory=lambda: deque(maxlen=64))
    last_price: float = 0.0
    pip_size: float = 0.01
    stop_orders: dict[str, str] = field(default_factory=dict)
    execution_tasks: set[asyncio.Task] = field(default_factory=set)
    tick_count: int = 0


runtimes: dict[str, AccountRuntime] = {}
_metaapi: MetaApi | None = None


class Strategy002Listener(SynchronizationListener):
    """Non-blocking MetaApi tick listener for the Strategy 002 hot path."""

    def __init__(self, runtime: AccountRuntime):
        self.runtime = runtime

    async def on_ticks_updated(self, _instance_index: int, ticks: list[Any], **_kwargs: Any):
        for tick in ticks:
            if _value(tick, "symbol") != self.runtime.symbol:
                continue
            bid = float(_value(tick, "bid", 0.0) or 0.0)
            ask = float(_value(tick, "ask", 0.0) or 0.0)
            last = float(_value(tick, "last", 0.0) or 0.0)
            current = (bid + ask) / 2.0 if bid > 0 and ask > 0 else last
            if current <= 0:
                continue
            previous = self.runtime.last_price
            self.runtime.last_price = current
            if previous <= 0 or current == previous:
                continue
            self.runtime.tick_count += 1
            received = time.perf_counter_ns()
            task = asyncio.create_task(_execute_strategy002_tick(self.runtime, previous, current, received))
            self.runtime.execution_tasks.add(task)
            task.add_done_callback(self.runtime.execution_tasks.discard)


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
    listener = Strategy002Listener(runtime) if runtime.selected_strategy == "002" else None
    if listener is not None:
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
    await connection.subscribe_to_market_data(runtime.symbol, [{'type': 'ticks'}])
    runtime.connection = connection
    runtime.listener = listener


async def _execute_strategy002_tick(runtime: AccountRuntime, previous: float, current: float, received_ns: int) -> None:
    """Execute from an already-received tick with only in-memory work before the order call."""
    if runtime.selected_strategy != "002" or runtime.state != "RUNNING":
        return
    direction = "BUY" if current > previous else "SELL"
    decision_ns = time.perf_counter_ns()
    decision_us = (decision_ns - received_ns) / 1_000.0

    if not LIVE_TRADING_ENABLED:
        runtime.activity = f"002 {direction} {current:.2f} reaction={decision_us:.0f}us — execution gated"
        return

    try:
        options = {
            "comment": "PipsLife002",
            "clientId": STRATEGY002_CLIENT_ID,
            "magic": STRATEGY002_MAGIC,
        }
        order_start_ns = time.perf_counter_ns()
        if direction == "BUY":
            await runtime.connection.create_market_buy_order(runtime.symbol, STRATEGY002_VOLUME, None, None, options)
        else:
            await runtime.connection.create_market_sell_order(runtime.symbol, STRATEGY002_VOLUME, None, None, options)
        ack_us = (time.perf_counter_ns() - order_start_ns) / 1_000.0
        runtime.activity = f"002 {direction} {runtime.symbol} reaction={decision_us:.0f}us order_ack={ack_us:.0f}us"
        asyncio.create_task(_maintain_strategy002_stops(runtime, current))
    except Exception as exc:
        runtime.activity = f"002 {direction} execution error: {exc}"


async def _maintain_strategy002_stops(runtime: AccountRuntime, current_price: float) -> None:
    """Maintain the opposite 100-pip stop outside the tick decision hot path."""
    try:
        terminal = runtime.connection.terminal_state
        positions = terminal.positions or []
        distance = STRATEGY002_PIPS * runtime.pip_size
        for position in positions:
            symbol = _value(position, "symbol")
            client_id = _value(position, "clientId")
            magic = _value(position, "magic")
            if symbol != runtime.symbol or (client_id != STRATEGY002_CLIENT_ID and int(magic or 0) != STRATEGY002_MAGIC):
                continue
            position_id = _value(position, "id")
            volume = float(_value(position, "volume", STRATEGY002_VOLUME) or STRATEGY002_VOLUME)
            position_type = str(_value(position, "type", "")).upper()
            if not position_id:
                continue
            if "BUY" in position_type:
                stop_price = current_price - distance
                if stop_price <= 0:
                    continue
                existing_id = runtime.stop_orders.get(position_id)
                if existing_id:
                    await runtime.connection.modify_order(existing_id, stop_price, None, None)
                else:
                    result = await runtime.connection.create_stop_sell_order(
                        runtime.symbol, volume, stop_price, None, None,
                        {"comment": f"PipsLife002:{position_id}", "clientId": STRATEGY002_CLIENT_ID, "magic": STRATEGY002_MAGIC},
                    )
                    runtime.stop_orders[position_id] = _value(result, "orderId", "") or _value(result, "id", "")
            elif "SELL" in position_type:
                stop_price = current_price + distance
                existing_id = runtime.stop_orders.get(position_id)
                if existing_id:
                    await runtime.connection.modify_order(existing_id, stop_price, None, None)
                else:
                    result = await runtime.connection.create_stop_buy_order(
                        runtime.symbol, volume, stop_price, None, None,
                        {"comment": f"PipsLife002:{position_id}", "clientId": STRATEGY002_CLIENT_ID, "magic": STRATEGY002_MAGIC},
                    )
                    runtime.stop_orders[position_id] = _value(result, "orderId", "") or _value(result, "id", "")
    except Exception as exc:
        runtime.activity = f"002 stop maintenance error: {exc}"


async def _market_loop(runtime: AccountRuntime) -> None:
    """Strategy 001 remains on the canonical engine; Strategy 002 is event-driven."""
    runtime.state = "RUNNING"
    runtime.activity = f"Strategy {runtime.selected_strategy} running on {runtime.symbol}"
    try:
        runtime.engine.select_strategy(runtime.selected_strategy)
        if runtime.selected_strategy == "002":
            while True:
                await asyncio.sleep(3600)
        while True:
            price_obj = runtime.connection.terminal_state.price(runtime.symbol)
            bid = float(_value(price_obj, "bid", 0.0)) if price_obj else 0.0
            ask = float(_value(price_obj, "ask", 0.0)) if price_obj else 0.0
            last = float(_value(price_obj, "last", 0.0)) if price_obj else 0.0
            price = (bid + ask) / 2.0 if bid and ask else last
            if price > 0:
                now = datetime.now(timezone.utc)
                runtime.samples.append((now.timestamp(), price))
                signal = runtime.engine.evaluate({"ticks": list(runtime.samples)})
                if signal.action in {"BUY", "SELL"}:
                    runtime.activity = f"{runtime.selected_strategy} signal {signal.action} at {price}"
                    if not LIVE_TRADING_ENABLED:
                        runtime.activity += " — execution gated"
            await asyncio.sleep(0.25)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        runtime.state = "ERROR"
        runtime.activity = str(exc)
    finally:
        if runtime.state != "ERROR":
            runtime.state = "STOPPED"


async def _stop(runtime: AccountRuntime) -> None:
    if runtime.task and not runtime.task.done():
        runtime.task.cancel()
        try:
            await runtime.task
        except asyncio.CancelledError:
            pass
    for task in list(runtime.execution_tasks):
        task.cancel()
    runtime.execution_tasks.clear()
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
    runtime.stop_orders.clear()
    runtime.state = "STOPPED"
    runtime.activity = "Trading stopped"


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"ok": True, "runner": "online", "metaapiConfigured": bool(METAAPI_TOKEN),
            "liveTradingEnabled": LIVE_TRADING_ENABLED, "accounts": len(runtimes)}


@app.get("/")
async def get_state(accountId: str | None = None) -> dict[str, Any]:
    runtime = runtimes.get(accountId) if accountId else next(iter(runtimes.values()), None)
    if runtime is None:
        return {"configured": True, "state": "READY", "strategy": "001", "activity": "Runner online"}
    return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy,
            "activity": runtime.activity, "ticks": runtime.tick_count}


def _check_control_token(authorization: str | None) -> None:
    if CONTROL_TOKEN and authorization != f"Bearer {CONTROL_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid runner control token")


@app.post("/")
async def control(body: ControlRequest, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _check_control_token(authorization)
    action = body.action.strip().lower()
    account_id = (body.accountId or os.getenv("METAAPI_ACCOUNT_ID", "")).strip()
    if not account_id:
        raise HTTPException(status_code=400, detail="accountId is required")
    runtime = runtimes.setdefault(account_id, AccountRuntime(account_id=account_id))

    if action == "select":
        strategy = body.strategy
        if strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        await _stop(runtime)
        runtime.engine.select_strategy(strategy)
        runtime.selected_strategy = strategy
        runtime.state = "SELECTED"
        runtime.activity = f"Strategy {strategy} selected in BotEngine"
        return {"configured": True, "state": runtime.state, "strategy": strategy, "activity": runtime.activity}

    if action == "start":
        strategy = body.strategy or runtime.selected_strategy
        if strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        if runtime.task and not runtime.task.done():
            await _stop(runtime)
        runtime.selected_strategy = strategy
        await _ensure_connection(runtime)
        runtime.engine.select_strategy(strategy)
        runtime.task = asyncio.create_task(_market_loop(runtime))
        await asyncio.sleep(0)
        return {"configured": True, "state": runtime.state, "strategy": strategy, "activity": runtime.activity}

    if action == "stop":
        await _stop(runtime)
        return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy, "activity": runtime.activity}

    raise HTTPException(status_code=400, detail=f"unsupported action: {action}")
