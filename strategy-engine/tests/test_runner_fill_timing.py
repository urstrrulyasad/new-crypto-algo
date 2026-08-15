"""Fill-timing regression tests for runner.catchup_strategy.

Locks the Fix 6 behaviour: catchup replay must mirror backtest.py — entries and
signal exits fill at the NEXT bar's open (+/- slippage), never the signal bar's
own close, while SL/ROI fill at their trigger prices. Runs under pytest or as a
plain script (no async plugin needed): each test wraps the coroutine in
asyncio.run.
"""
from __future__ import annotations

import asyncio
import math
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import runner  # noqa: E402
from app.backtest import SLIPPAGE  # noqa: E402

COLS = ["date", "open", "high", "low", "close",
        "enter_long", "enter_short", "exit_long", "exit_short"]


def _df(rows: list[tuple]) -> pd.DataFrame:
    base = pd.Timestamp("2024-01-01T00:00:00Z")
    data = []
    for i, (o, h, low, c, el, es, xl, xs) in enumerate(rows):
        data.append({
            "date": base + pd.Timedelta(minutes=5 * i),
            "open": o, "high": h, "low": low, "close": c,
            "enter_long": el, "enter_short": es,
            "exit_long": xl, "exit_short": xs,
        })
    return pd.DataFrame(data, columns=COLS)


class _StubStrategy:
    stoploss = -0.05
    minimal_roi = {"0": 0.10}
    timeframe = "5m"

    def populate_indicators(self, df):
        return df

    def populate_entry_trend(self, df):
        return df

    def populate_exit_trend(self, df):
        return df


def _run_catchup(monkeypatched_df: pd.DataFrame) -> list[tuple]:
    """Drive catchup_strategy against a fixed candle frame, capturing posts."""
    posts: list[tuple] = []

    async def fake_post(item, pair, timeframe, action, price, candle_ts,
                        *, catchup, key_tag=""):
        posts.append((action, price, key_tag))
        return {"status": "ACCEPTED"}

    async def fake_fetch(pair, timeframe, start_ms, end_ms, market_type="SPOT"):
        return monkeypatched_df.copy()

    orig = (runner._post_signal, runner.fetch_candles,
            runner.validate_strategy_code, runner.load_strategy_class)
    runner._post_signal = fake_post
    runner.fetch_candles = fake_fetch
    runner.validate_strategy_code = lambda src: {"valid": True, "errors": []}
    runner.load_strategy_class = lambda src: _StubStrategy
    try:
        item = {
            "tenantId": "t1", "strategyId": "s1", "sourceCode": "stub",
            "marketType": "FUTURES", "timeframe": "5m", "pairs": ["B-BTC_USDT"],
        }
        asyncio.run(runner.catchup_strategy(item, bars=50))
    finally:
        (runner._post_signal, runner.fetch_candles,
         runner.validate_strategy_code, runner.load_strategy_class) = orig
    return posts


def test_long_entry_and_signal_exit_fill_next_open():
    # i1 flags enter_long -> entry fills at i2 open. i3 flags exit_long ->
    # exit fills at i4 open. Prices kept away from SL/ROI so the signal wins.
    df = _df([
        (100, 100, 100, 100, 0, 0, 0, 0),
        (100, 101, 99, 100, 1, 0, 0, 0),   # enter_long flagged
        (110, 111, 109, 110, 0, 0, 0, 0),  # ENTRY fills here @ open
        (112, 113, 111, 112, 0, 0, 1, 0),  # exit_long flagged
        (115, 116, 114, 115, 0, 0, 0, 0),  # EXIT fills here @ open
        (116, 116, 116, 116, 0, 0, 0, 0),  # filler so i4 is processed
    ])
    posts = _run_catchup(df)

    assert len(posts) == 2, posts
    entry_action, entry_price, _ = posts[0]
    exit_action, exit_price, exit_tag = posts[1]

    assert entry_action == "ENTRY_LONG"
    assert math.isclose(entry_price, 110 * (1 + SLIPPAGE), rel_tol=1e-9)
    # Guard against the old look-ahead behaviour (filling at the signal close).
    assert not math.isclose(entry_price, 100.0, rel_tol=1e-9)

    assert exit_action == "EXIT_LONG"
    assert exit_tag == ""  # signal exit, not sl/roi
    assert math.isclose(exit_price, 115 * (1 - SLIPPAGE), rel_tol=1e-9)


def test_long_stoploss_fills_at_trigger_price():
    df = _df([
        (100, 100, 100, 100, 0, 0, 0, 0),
        (100, 101, 99, 100, 1, 0, 0, 0),   # enter_long flagged
        (110, 111, 109, 110, 0, 0, 0, 0),  # ENTRY fills @ open (110.055)
        (108, 109, 100, 104, 0, 0, 0, 0),  # low 100 breaches SL -> trigger price
        (105, 105, 105, 105, 0, 0, 0, 0),  # filler so i3 is processed
    ])
    posts = _run_catchup(df)

    assert len(posts) == 2, posts
    entry_price = posts[0][1]
    exit_action, exit_price, exit_tag = posts[1]

    expected_entry = 110 * (1 + SLIPPAGE)
    assert math.isclose(entry_price, expected_entry, rel_tol=1e-9)
    assert exit_action == "EXIT_LONG"
    assert exit_tag == "sl"
    # SL fills at entry*(1+stoploss), independent of the bar's open/close.
    assert math.isclose(exit_price, expected_entry * (1 + _StubStrategy.stoploss),
                        rel_tol=1e-9)


if __name__ == "__main__":
    test_long_entry_and_signal_exit_fill_next_open()
    test_long_stoploss_fills_at_trigger_price()
    print("ALL TESTS PASSED")
