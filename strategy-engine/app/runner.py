"""Signal runner: evaluates active FUTURES strategies on each closed candle."""
from __future__ import annotations

import asyncio
import logging
import time

import httpx

from .backtest import SLIPPAGE, _roi_for
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

    Mirrors the backtest that gates LIVE promotion: entries and signal exits
    fill at the NEXT bar's open (+/- slippage) — no look-ahead — while SL/ROI
    trigger at their price levels intrabar. The ROI ladder decays with
    minutes-held exactly like backtest._roi_for.
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
        roi_map = {int(k): float(v) for k, v in
                   (getattr(strategy, "minimal_roi", {"0": 0.012}) or {"0": 0.012}).items()}
        df = strategy.populate_indicators(df)
        df = strategy.populate_entry_trend(df)
        df = strategy.populate_exit_trend(df)
        end = len(df) - 1
        pos: str | None = None
        entry_price = 0.0
        entry_time = None
        for i in range(1, end):
            closed = df.iloc[i]
            prev = df.iloc[i - 1]
            price_open = float(closed["open"])
            high = float(closed["high"])
            low = float(closed["low"])
            ts = closed["date"].isoformat()


            if pos is None:
                # Entry fires when the PREVIOUS bar flagged it, filled at THIS
                # bar's open (next open) + slippage — same convention as the
                # backtest promotion gate. Level-based, not rising-edge, so it
                # survives repeated frames.
                if _on(prev, "enter_long"):
                    pos = "LONG"
                    entry_price = price_open * (1 + SLIPPAGE)
                    entry_time = closed["date"]
                elif _on(prev, "enter_short"):
                    pos = "SHORT"
                    entry_price = price_open * (1 - SLIPPAGE)
                    entry_time = closed["date"]
                else:
                    continue
                posted += 1
                result = await _post_signal(
                    item, pair, timeframe, "ENTRY_LONG" if pos == "LONG" else "ENTRY_SHORT",
                    entry_price, ts, catchup=True)
                if result.get("status") == "ACCEPTED":
                    accepted += 1
                await asyncio.sleep(0.01)
                continue

            minutes_held = (closed["date"] - entry_time).total_seconds() / 60
            roi_target = _roi_for(roi_map, minutes_held)

            # Intrabar protective exits first (same order as backtest.py).
            if pos == "LONG":
                low_ratio = low / entry_price - 1.0
                high_ratio = high / entry_price - 1.0
                if low_ratio <= stoploss:
                    exit_px = entry_price * (1.0 + stoploss)
                    action = "EXIT_LONG"
                    key_tag = "sl"
                elif roi_target is not None and high_ratio >= roi_target:
                    exit_px = entry_price * (1.0 + roi_target)
                    action = "EXIT_LONG"
                    key_tag = "roi"
                elif _on(prev, "exit_long"):
                    exit_px = price_open * (1 - SLIPPAGE)
                    action = "EXIT_LONG"
                    key_tag = ""
                else:
                    continue
            else:  # SHORT
                adverse = high / entry_price - 1.0
                low_ratio = (entry_price / low - 1.0) if low > 0 else 0.0
                if adverse >= abs(stoploss):
                    exit_px = entry_price * (1.0 + abs(stoploss))
                    action = "EXIT_SHORT"
                    key_tag = "sl"
                elif roi_target is not None and low_ratio >= roi_target:
                    exit_px = entry_price * (1.0 - roi_target)
                    action = "EXIT_SHORT"
                    key_tag = "roi"
                elif _on(prev, "exit_short"):
                    exit_px = price_open * (1 + SLIPPAGE)
                    action = "EXIT_SHORT"
                    key_tag = ""
                else:
                    continue
            posted += 1
            result = await _post_signal(
                item, pair, timeframe, action, exit_px, ts,
                catchup=True, key_tag=key_tag)
            if result.get("status") == "ACCEPTED":
                accepted += 1
            pos = None
            entry_price = 0.0
            entry_time = None
            await asyncio.sleep(0.01)

    log.info("Catchup strategy %s posted=%s accepted=%s skipped=%s",
             item.get("strategyId"), posted, accepted, skipped)
    return {"ok": True, "posted": posted, "accepted": accepted, "skipped": skipped}


async def _post_signal(item: dict, pair: str, timeframe: str, action: str,
                       price: float, candle_ts: str, *, catchup: bool,
                       key_tag: str = "") -> dict:
    # v7: fills moved to next-bar open (+/- slippage) to mirror the backtest
    # gate; bumped so corrected signals replace the old look-ahead v6 keys.
    if catchup:
        tag = f":{key_tag}" if key_tag else ""
        key_prefix = f"catchup:v7{tag}:"
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
