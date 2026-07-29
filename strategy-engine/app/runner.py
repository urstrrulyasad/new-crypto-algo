"""Signal runner: evaluates active FUTURES strategies on each closed candle."""
from __future__ import annotations

import asyncio
import logging
import time

import httpx

from .config import BACKEND_URL, INTERNAL_TOKEN, SIGNAL_POLL_SECONDS
from .data import fetch_candles, normalize_timeframe, _TF_MS, _FUT_MS
from .validation import validate_strategy_code, load_strategy_class

log = logging.getLogger("runner")


async def run_forever() -> None:
    while True:
        try:
            await evaluate_all()
        except Exception:  # noqa: BLE001
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
    market_type = item.get("marketType", "FUTURES")
    timeframe = normalize_timeframe(item.get("timeframe", "1h"), market_type)
    check = validate_strategy_code(source)
    if not check["valid"]:
        log.error("Active strategy %s failed validation: %s", item["strategyId"], check["errors"])
        return
    strategy_cls = load_strategy_class(source)

    tf_map = _FUT_MS if market_type.upper() == "FUTURES" else _TF_MS
    tf_ms = tf_map.get(timeframe, 3_600_000)
    now_ms = int(time.time() * 1000)
    start_ms = now_ms - tf_ms * 300

    for pair in item.get("pairs", []):
        df = await fetch_candles(pair, timeframe, start_ms, now_ms, market_type)
        strategy = strategy_cls()
        df = strategy.populate_indicators(df)
        df = strategy.populate_entry_trend(df)
        df = strategy.populate_exit_trend(df)

        # Last fully closed candle. Rising-edge only — level-based exit flags
        # stay 1 for many bars and would otherwise spam EXIT every poll.
        if len(df) < 2:
            continue
        closed = df.iloc[-2]
        prev = df.iloc[-3] if len(df) >= 3 else None

        def _on(row, col: str) -> bool:
            if row is None:
                return False
            try:
                return int(row.get(col, 0) or 0) == 1
            except (TypeError, ValueError):
                return False

        def _rise(col: str) -> bool:
            return _on(closed, col) and not _on(prev, col)

        action = None
        if _rise("enter_long"):
            action = "ENTRY_LONG"
        elif _rise("enter_short"):
            action = "ENTRY_SHORT"
        elif _rise("exit_long"):
            action = "EXIT_LONG"
        elif _rise("exit_short"):
            action = "EXIT_SHORT"
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
