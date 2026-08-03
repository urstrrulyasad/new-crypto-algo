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
    """Replay recent CoinDCX candles into paper signals (historical closes).

    Matches backtest exit priority: stoploss / ROI on the bar's high-low path,
    then strategy exit signals. Without SL/ROI, paper WR lagged the quality
    backtest and never cleared the 75% LIVE paper gate.
    """
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
    skipped = 0
    for pair in item.get("pairs", []):
        df = await fetch_candles(pair, timeframe, start_ms, now_ms, market_type)
        strategy = strategy_cls()
        stoploss = float(getattr(strategy, "stoploss", -0.018))
        if stoploss > 0:
            stoploss = -stoploss
        roi_map = getattr(strategy, "minimal_roi", {"0": 0.012}) or {"0": 0.012}
        roi = float(roi_map.get("0", next(iter(roi_map.values()), 0.012)))
        df = strategy.populate_indicators(df)
        df = strategy.populate_entry_trend(df)
        df = strategy.populate_exit_trend(df)
        end = len(df) - 1
        pos: str | None = None
        entry_price = 0.0
        for i in range(1, end):
            closed = df.iloc[i]
            prev = df.iloc[i - 1]
            high = float(closed["high"])
            low = float(closed["low"])
            close_px = float(closed["close"])
            ts = closed["date"].isoformat()

            # Intrabar protective exits first (same order as backtest.py).
            if pos == "LONG" and entry_price > 0:
                if low / entry_price - 1.0 <= stoploss:
                    exit_px = entry_price * (1.0 + stoploss)
                    posted += 1
                    result = await _post_signal(
                        item, pair, timeframe, "EXIT_LONG", exit_px, ts,
                        catchup=True, key_tag="sl")
                    if result.get("status") == "ACCEPTED":
                        accepted += 1
                    pos = None
                    entry_price = 0.0
                    await asyncio.sleep(0.01)
                    continue
                if high / entry_price - 1.0 >= roi:
                    exit_px = entry_price * (1.0 + roi)
                    posted += 1
                    result = await _post_signal(
                        item, pair, timeframe, "EXIT_LONG", exit_px, ts,
                        catchup=True, key_tag="roi")
                    if result.get("status") == "ACCEPTED":
                        accepted += 1
                    pos = None
                    entry_price = 0.0
                    await asyncio.sleep(0.01)
                    continue
            elif pos == "SHORT" and entry_price > 0:
                adverse = high / entry_price - 1.0
                low_ratio = (entry_price / low - 1.0) if low > 0 else 0.0
                if adverse >= abs(stoploss):
                    exit_px = entry_price * (1.0 + abs(stoploss))
                    posted += 1
                    result = await _post_signal(
                        item, pair, timeframe, "EXIT_SHORT", exit_px, ts,
                        catchup=True, key_tag="sl")
                    if result.get("status") == "ACCEPTED":
                        accepted += 1
                    pos = None
                    entry_price = 0.0
                    await asyncio.sleep(0.01)
                    continue
                if low_ratio >= roi:
                    exit_px = entry_price * (1.0 - roi)
                    posted += 1
                    result = await _post_signal(
                        item, pair, timeframe, "EXIT_SHORT", exit_px, ts,
                        catchup=True, key_tag="roi")
                    if result.get("status") == "ACCEPTED":
                        accepted += 1
                    pos = None
                    entry_price = 0.0
                    await asyncio.sleep(0.01)
                    continue

            action = _action_for(closed, prev)
            if action is None:
                continue
            if action == "ENTRY_LONG":
                if pos is not None:
                    skipped += 1
                    continue
                pos = "LONG"
                entry_price = close_px
            elif action == "ENTRY_SHORT":
                if pos is not None:
                    skipped += 1
                    continue
                pos = "SHORT"
                entry_price = close_px
            elif action == "EXIT_LONG":
                if pos != "LONG":
                    skipped += 1
                    continue
                pos = None
                entry_price = 0.0
            elif action == "EXIT_SHORT":
                if pos != "SHORT":
                    skipped += 1
                    continue
                pos = None
                entry_price = 0.0
            else:
                skipped += 1
                continue
            posted += 1
            result = await _post_signal(
                item, pair, timeframe, action, close_px, ts, catchup=True)
            if result.get("status") == "ACCEPTED":
                accepted += 1
            await asyncio.sleep(0.01)
    log.info("Catchup strategy %s posted=%s accepted=%s skipped=%s",
             item.get("strategyId"), posted, accepted, skipped)
    return {"ok": True, "posted": posted, "accepted": accepted, "skipped": skipped}


async def _post_signal(item: dict, pair: str, timeframe: str, action: str,
                       price: float, candle_ts: str, *, catchup: bool,
                       key_tag: str = "") -> dict:
    # v4: re-accept catchup after v3 duplicates starved paper (new prefix = new fills).
    if catchup:
        tag = f":{key_tag}" if key_tag else ""
        key_prefix = f"catchup:v4{tag}:"
    else:
        key_prefix = ""
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
