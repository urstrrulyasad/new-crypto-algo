"""Signal runner: evaluates active strategies on each closed candle.

Polls the backend for strategies that have RUNNING bots, downloads the recent
candles per pair, executes the strategy, and when the last CLOSED candle
carries an entry/exit signal, POSTs it to the backend's internal signal
endpoint with an idempotency key (strategy+pair+candle-timestamp), so repeated
evaluation never causes duplicate orders.
"""
from __future__ import annotations

import asyncio
import logging
import time

import httpx

from .config import BACKEND_URL, INTERNAL_TOKEN, SIGNAL_POLL_SECONDS
from .data import fetch_candles, _TF_MS
from .validation import validate_strategy_code, load_strategy_class

log = logging.getLogger("runner")


async def run_forever() -> None:
    while True:
        try:
            await evaluate_all()
        except Exception:  # noqa: BLE001 - keep the loop alive, log everything
            log.exception("Signal evaluation cycle failed")
        await asyncio.sleep(SIGNAL_POLL_SECONDS)


async def evaluate_all() -> None:
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{BACKEND_URL}/api/v1/internal/active-strategies",
            headers={"X-Internal-Token": INTERNAL_TOKEN})
        resp.raise_for_status()
        strategies = resp.json()

    if not strategies:
        return
    log.info("Evaluating %d active strategies", len(strategies))
    for item in strategies:
        try:
            await evaluate_strategy(item)
        except Exception:  # noqa: BLE001
            log.exception("Strategy %s evaluation failed", item.get("strategyId"))


async def evaluate_strategy(item: dict) -> None:
    source = item["sourceCode"]
    timeframe = item.get("timeframe", "1h")
    check = validate_strategy_code(source)
    if not check["valid"]:
        log.error("Active strategy %s failed validation: %s", item["strategyId"], check["errors"])
        return
    strategy_cls = load_strategy_class(source)

    tf_ms = _TF_MS.get(timeframe, 3_600_000)
    now_ms = int(time.time() * 1000)
    start_ms = now_ms - tf_ms * 300  # 300 candles of context

    for pair in item.get("pairs", []):
        df = await fetch_candles(pair, timeframe, start_ms, now_ms)
        strategy = strategy_cls()
        df = strategy.populate_indicators(df)
        df = strategy.populate_entry_trend(df)
        df = strategy.populate_exit_trend(df)

        # last CLOSED candle: the final row may still be forming, use the one before it
        closed = df.iloc[-2] if len(df) >= 2 else df.iloc[-1]
        action = None
        if closed.get("enter_long", 0) == 1:
            action = "ENTRY_LONG"
        elif closed.get("exit_long", 0) == 1:
            action = "EXIT_LONG"
        if action is None:
            continue

        candle_ts = closed["date"].isoformat()
        signal = {
            "tenantId": item["tenantId"],
            "strategyId": item["strategyId"],
            "idempotencyKey": f"{item['strategyId']}:{pair}:{timeframe}:{candle_ts}:{action}",
            "pair": pair,
            "timeframe": timeframe,
            "action": action,
            "price": float(closed["close"]),
            "candleTs": candle_ts,
            "payload": {},
        }
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{BACKEND_URL}/api/v1/internal/signals",
                headers={"X-Internal-Token": INTERNAL_TOKEN},
                json=signal)
            resp.raise_for_status()
            log.info("Signal %s %s for strategy %s -> %s",
                     action, pair, item["strategyId"], resp.json())
