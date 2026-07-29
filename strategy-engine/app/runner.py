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


def _on(row, col: str) -> bool:
    if row is None:
        return False
    try:
        return int(row.get(col, 0) or 0) == 1
    except (TypeError, ValueError):
        return False


def _rise(closed, prev, col: str) -> bool:
    return _on(closed, col) and not _on(prev, col)


def _action_for(closed, prev) -> str | None:
    if _rise(closed, prev, "enter_long"):
        return "ENTRY_LONG"
    if _rise(closed, prev, "enter_short"):
        return "ENTRY_SHORT"
    if _rise(closed, prev, "exit_long"):
        return "EXIT_LONG"
    if _rise(closed, prev, "exit_short"):
        return "EXIT_SHORT"
    return None


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
        action = _action_for(closed, prev)
        if action is None:
            continue

        await _post_signal(item, pair, timeframe, action, float(closed["close"]),
                           closed["date"].isoformat(), catchup=False)


async def catchup_strategy(item: dict, bars: int = 800) -> dict:
    """Replay recent CoinDCX candles into paper signals (historical closes)."""
    source = item["sourceCode"]
    market_type = item.get("marketType", "FUTURES")
    timeframe = normalize_timeframe(item.get("timeframe", "5m"), market_type)
    check = validate_strategy_code(source)
    if not check["valid"]:
        return {"ok": False, "error": check["errors"]}
    strategy_cls = load_strategy_class(source)
    tf_map = _FUT_MS if market_type.upper() == "FUTURES" else _TF_MS
    tf_ms = tf_map.get(timeframe, 300_000)
    now_ms = int(time.time() * 1000)
    start_ms = now_ms - tf_ms * max(50, bars)

    posted = 0
    accepted = 0
    for pair in item.get("pairs", []):
        df = await fetch_candles(pair, timeframe, start_ms, now_ms, market_type)
        strategy = strategy_cls()
        df = strategy.populate_indicators(df)
        df = strategy.populate_entry_trend(df)
        df = strategy.populate_exit_trend(df)
        # Walk closed candles only (skip forming last bar).
        end = len(df) - 1
        for i in range(1, end):
            closed = df.iloc[i]
            prev = df.iloc[i - 1]
            action = _action_for(closed, prev)
            if action is None:
                continue
            posted += 1
            result = await _post_signal(
                item, pair, timeframe, action, float(closed["close"]),
                closed["date"].isoformat(), catchup=True)
            if result.get("status") == "ACCEPTED":
                accepted += 1
            # Yield so backend can fill before the next signal in the sequence.
            await asyncio.sleep(0.05)
    log.info("Catchup strategy %s posted=%s accepted=%s", item.get("strategyId"), posted, accepted)
    return {"ok": True, "posted": posted, "accepted": accepted}


async def _post_signal(item: dict, pair: str, timeframe: str, action: str,
                       price: float, candle_ts: str, *, catchup: bool) -> dict:
    key_prefix = "catchup:" if catchup else ""
    signal = {
        "tenantId": item["tenantId"],
        "strategyId": item["strategyId"],
        "idempotencyKey": f"{key_prefix}{item['strategyId']}:{pair}:{timeframe}:{candle_ts}:{action}",
        "pair": pair,
        "timeframe": timeframe,
        "action": action,
        "price": price,
        "candleTs": candle_ts,
        "payload": {"catchup": True} if catchup else {},
    }
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{BACKEND_URL}/api/v1/internal/signals",
            headers={"X-Internal-Token": INTERNAL_TOKEN},
            json=signal)
        resp.raise_for_status()
        body = resp.json()
        log.info("Signal %s %s for strategy %s -> %s%s",
                 action, pair, item["strategyId"], body,
                 " (catchup)" if catchup else "")
        return body
