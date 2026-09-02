"""Persistent Pips-life bot runner.

The Next.js API is a control plane. This service is the long-lived process that
owns BotEngine and the MetaApi streaming connection. Never put MetaApi
credentials in the Android app.
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

app = FastAPI(title="Pips-life Live Bot Runner", version="1.0.1")

CONTROL_TOKEN = os.getenv("PIPSLIFE_BOT_CONTROL_TOKEN", "").strip()
METAAPI_TOKEN = os.getenv("METAAPI_TOKEN", "").strip()
DEFAULT_SYMBOL = os.getenv("PIPSLIFE_SYMBOL", "XAUUSD").strip()
LIVE_TRADING_ENABLED = os.getenv("PIPSLIFE_LIVE_TRADING_ENABLED", "false").strip().lower() == "true"


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
    task: asyncio.Task | None = None
    samples: deque[tuple[float, float]] = field(default_factory=lambda: deque(maxlen=64))


runtimes: dict[str, AccountRuntime] = {}
_metaapi: MetaApi | None = None


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
    await connection.connect()
    await connection.wait_synchronized()
    await connection.subscribe_to_market_data(runtime.symbol)
    runtime.connection = connection


def _check_control_token(authorization: str | None) -> None:
    if CONTROL_TOKEN and authorization != f"Bearer {CONTROL_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid runner control token")


async def _market_loop(runtime: AccountRuntime) -> None:
    """Feed live prices into the canonical BotEngine.

    Strategy evaluation is live here. Actual order placement remains explicitly
    gated until the MT5 position-accounting mode and the Strategy 002 reversal
    adapter are validated for the connected account. This prevents a netting
    account from being treated like a hedging account.
    """
    runtime.state = "RUNNING"
    runtime.activity = f"Strategy {runtime.selected_strategy} running on {runtime.symbol}"
    try:
        runtime.engine.select_strategy(runtime.selected_strategy)
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
                    else:
                        runtime.activity += " — execution adapter not armed"
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
    runtime.task = None
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
            "activity": runtime.activity}


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
        if runtime.task and not runtime.task.done():
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
        await _ensure_connection(runtime)
        if runtime.task and not runtime.task.done():
            await _stop(runtime)
        runtime.engine.select_strategy(strategy)
        runtime.selected_strategy = strategy
        runtime.task = asyncio.create_task(_market_loop(runtime))
        await asyncio.sleep(0)
        return {"configured": True, "state": runtime.state, "strategy": strategy, "activity": runtime.activity}

    if action == "stop":
        await _stop(runtime)
        return {"configured": True, "state": runtime.state, "strategy": runtime.selected_strategy, "activity": runtime.activity}

    raise HTTPException(status_code=400, detail=f"unsupported action: {action}")
