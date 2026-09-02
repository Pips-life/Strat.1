"""Persistent live bot runner for Pips-life.

This service is deliberately separate from the Next.js/Vercel API because a
trading engine needs a long-lived process and websocket connection. The API
forwards /select and /start commands here; this process owns BotEngine and the
MetaApi streaming connection.
"""
from __future__ import annotations

import asyncio
import os
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from metaapi_cloud_sdk import MetaApi

from strat.bot.engine import BotEngine
from strat.bot.velocity_expansion import VelocityExpansionController
from strat.execution.interface import ExecutionAdapter
from strat.execution.models import ExecutionResult, Fill, OrderRequest, Position
from strat.strategies.strategy_002 import Strategy002

app = FastAPI(title="Pips-life Live Bot Runner", version="1.0.0")

CONTROL_TOKEN = os.getenv("PIPSLIFE_BOT_CONTROL_TOKEN", "").strip()
METAAPI_TOKEN = os.getenv("METAAPI_TOKEN", "").strip()
DEFAULT_SYMBOL = os.getenv("PIPSLIFE_SYMBOL", "XAUUSD").strip()


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
    activity: str = "Runner connected"
    running: bool = False
    symbol: str = DEFAULT_SYMBOL
    connection: Any = None
    task: asyncio.Task | None = None
    samples: deque[tuple[float, float]] = field(default_factory=lambda: deque(maxlen=64))
    controller: VelocityExpansionController | None = None


runtimes: dict[str, AccountRuntime] = {}
api: MetaApi | None = None


class MetaApiExecutionAdapter(ExecutionAdapter):
    """Bridge the canonical execution interface to a MetaApi streaming connection."""

    def __init__(self, connection: Any, symbol: str):
        self.connection = connection
        self.symbol = symbol
        self._fills: list[Fill] = []

    def submit(self, order: OrderRequest) -> ExecutionResult:
        return asyncio.run_coroutine_threadsafe(self._submit(order), _loop()).result()

    async def _submit(self, order: OrderRequest) -> ExecutionResult:
        try:
            options = {
                "comment": f"Pips-life {order.metadata.get('strategy_id', 'unknown')}",
                "clientId": order.client_order_id or f"PIPSLIFE-{order.symbol}-{int(order.timestamp.timestamp()) if order.timestamp else 0}",
            }
            if order.order_type == "MARKET":
                if order.side == "BUY":
                    result = await self.connection.create_market_buy_order(order.symbol, order.quantity, options=options)
                else:
                    result = await self.connection.create_market_sell_order(order.symbol, order.quantity, options=options)
            elif order.order_type == "STOP":
                if order.side == "BUY":
                    result = await self.connection.create_stop_buy_order(order.symbol, order.quantity, order.price, options=options)
                else:
                    result = await self.connection.create_stop_sell_order(order.symbol, order.quantity, order.price, options=options)
            else:
                return ExecutionResult("REJECTED", "", reason=f"unsupported live order type: {order.order_type}")

            status = "FILLED" if order.order_type == "MARKET" else "NEW"
            order_id = str(result.get("orderId") or result.get("positionId") or result.get("id") or "")
            fill = None
            if status == "FILLED":
                price = float(result.get("price") or order.price or 0.0)
                fill = Fill(order_id=order_id, symbol=order.symbol, side=order.side, quantity=order.quantity,
                            price=price, timestamp=order.timestamp or datetime.now(timezone.utc))
            return ExecutionResult(status, order_id, fill=fill)
        except Exception as exc:
            return ExecutionResult("REJECTED", "", reason=str(exc))

    def cancel_order(self, order_id: str) -> ExecutionResult:
        return asyncio.run_coroutine_threadsafe(self._cancel(order_id), _loop()).result()

    async def _cancel(self, order_id: str) -> ExecutionResult:
        try:
            await self.connection.cancel_order(order_id)
            return ExecutionResult("CANCELLED", order_id)
        except Exception as exc:
            return ExecutionResult("REJECTED", order_id, reason=str(exc))

    def close_position(self, symbol: str, timestamp: datetime, price: float | None = None) -> ExecutionResult:
        return asyncio.run_coroutine_threadsafe(self._close(symbol), _loop()).result()

    async def _close(self, symbol: str) -> ExecutionResult:
        try:
            await self.connection.close_positions_by_symbol(symbol)
            return ExecutionResult("FILLED", symbol)
        except Exception as exc:
            return ExecutionResult("REJECTED", symbol, reason=str(exc))

    def positions(self) -> list[Position]:
        return asyncio.run_coroutine_threadsafe(self._positions(), _loop()).result()

    async def _positions(self) -> list[Position]:
        positions = self.connection.terminal_state.positions or []
        return []

    def on_bar(self, symbol: str, timestamp: datetime, high: float, low: float, close: float) -> list[Fill]:
        return []


def _loop() -> asyncio.AbstractEventLoop:
    loop = asyncio.get_running_loop()
    return loop


async def _ensure_runtime(account_id: str) -> AccountRuntime:
    global api
    if not METAAPI_TOKEN:
        raise HTTPException(status_code=503, detail="METAAPI_TOKEN is not configured")
    if api is None:
        api = MetaApi(METAAPI_TOKEN)
    runtime = runtimes.get(account_id)
    if runtime is None:
        runtime = AccountRuntime(account_id=account_id)
        runtimes[account_id] = runtime
    if runtime.connection is None:
        account = await api.metatrader_account_api.get_account(account_id)
        if account.state != "DEPLOYED":
            await account.deploy()
        if account.connection_status != "CONNECTED":
            await account.wait_connected()
        connection = account.get_streaming_connection()
        await connection.connect()
        await connection.wait_synchronized()
        await connection.subscribe_to_market_data(runtime.symbol)
        runtime.connection = connection
    return runtime


async def _strategy_loop(runtime: AccountRuntime) -> None:
    runtime.running = True
    runtime.state = "RUNNING"
    runtime.activity = f"Trading {runtime.selected_strategy} on {runtime.symbol}"
    try:
        if runtime.selected_strategy != "002":
            runtime.activity = "Strategy 001 selected; live execution adapter not enabled in this runner"
            return

        adapter = MetaApiExecutionAdapter(runtime.connection, runtime.symbol)
        runtime.controller = VelocityExpansionController(adapter, Strategy002())
        runtime.engine.select_strategy("002")

        while runtime.running:
            price_obj = runtime.connection.terminal_state.price(runtime.symbol)
            if not price_obj:
                await asyncio.sleep(0.25)
                continue
            bid = float(_value(price_obj, "bid", 0.0))
            ask = float(_value(price_obj, "ask", 0.0))
            price = (bid + ask) / 2.0 if bid and ask else float(_value(price_obj, "last", 0.0))
            if price <= 0:
                await asyncio.sleep(0.25)
                continue
            now = datetime.now(timezone.utc)
            runtime.samples.append((now.timestamp(), price))

            if not runtime.controller.pairs:
                signal = runtime.engine.evaluate({"ticks": list(runtime.samples)})
                if signal.action in {"BUY", "SELL"}:
                    info = runtime.connection.terminal_state.account_information
                    spec = runtime.connection.terminal_state.specification(runtime.symbol)
                    balance = float(_value(info, "balance", 0.0))
                    tick_size = float(_value(spec, "tickSize", _value(spec, "point", 0.01)))
                    tick_value = float(_value(spec, "tickValue", 1.0))
                    result = runtime.controller.enter(runtime.symbol, signal, balance=balance,
                                                      tick_size=tick_size, tick_value=tick_value, timestamp=now)
                    runtime.activity = f"{signal.action}: {result.status}"

            await asyncio.sleep(0.25)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        runtime.state = "ERROR"
        runtime.activity = str(exc)
    finally:
        runtime.running = False
        if runtime.state != "ERROR":
            runtime.state = "STOPPED"


async def _stop(runtime: AccountRuntime) -> None:
    runtime.running = False
    if runtime.task and not runtime.task.done():
        runtime.task.cancel()
        try:
            await runtime.task
        except asyncio.CancelledError:
            pass
    runtime.task = None
    runtime.state = "STOPPED"
    runtime.activity = "Trading stopped"


def _check_token(authorization: str | None) -> None:
    if CONTROL_TOKEN and authorization != f"Bearer {CONTROL_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid runner control token")


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"ok": True, "runner": "online", "accounts": len(runtimes)}


@app.get("/")
async def state(accountId: str | None = None) -> dict[str, Any]:
    runtime = runtimes.get(accountId) if accountId else next(iter(runtimes.values()), None)
    if runtime is None:
        return {"configured": True, "state": "READY", "strategy": "001", "activity": "Runner online"}
    return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy,
            "activity": runtime.activity}


@app.post("/")
async def control(body: ControlRequest, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _check_token(authorization)
    action = body.action.strip().lower()
    account_id = (body.accountId or os.getenv("METAAPI_ACCOUNT_ID", "")).strip()
    if not account_id:
        raise HTTPException(status_code=400, detail="accountId is required")
    runtime = await _ensure_runtime(account_id)

    if action == "select":
        if body.strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        if runtime.task and not runtime.task.done():
            await _stop(runtime)
        runtime.engine.select_strategy(body.strategy)
        runtime.selected_strategy = body.strategy
        runtime.state = "SELECTED"
        runtime.activity = f"Strategy {body.strategy} selected in BotEngine"
        return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy, "activity": runtime.activity}

    if action == "start":
        strategy = body.strategy or runtime.selected_strategy
        if strategy not in {"001", "002"}:
            raise HTTPException(status_code=400, detail="strategy must be 001 or 002")
        if runtime.task and not runtime.task.done():
            await _stop(runtime)
        runtime.engine.select_strategy(strategy)
        runtime.selected_strategy = strategy
        runtime.task = asyncio.create_task(_strategy_loop(runtime))
        await asyncio.sleep(0)
        return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy, "activity": runtime.activity}

    if action == "stop":
        await _stop(runtime)
        return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy, "activity": runtime.activity}

    raise HTTPException(status_code=400, detail=f"unsupported action: {action}")
