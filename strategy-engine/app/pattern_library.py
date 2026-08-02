"""Famous / commonly used futures strategy patterns (classic rule packs).

Used to (1) steer the LLM toward proven structures and (2) seed smoke-passing
candidates when the LLM chain is exhausted — never invent fictional trader IP,
only well-known quantitative patterns.
"""
from __future__ import annotations

from typing import Any


PATTERNS: list[dict[str, Any]] = [
    {
        "id": "rsi-bb-mean-reversion",
        "name": "RSI + Bollinger mean reversion",
        "styles": ["mean-reversion"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.014
    minimal_roi = {"0": 0.012, "30": 0.006}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.6)
        df["bb_low"] = bb.bollinger_lband()
        df["bb_mid"] = bb.bollinger_mavg()
        df["bb_high"] = bb.bollinger_hband()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=21).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=55).ema_indicator()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        up = df["ema_fast"] > df["ema_slow"]
        down = df["ema_fast"] < df["ema_slow"]
        # Selective extremes only — keeps smoke alive and drawdown under gate.
        long_sig = (df["close"] < df["bb_low"]) & (df["rsi"] < 28) & up
        short_sig = (df["close"] > df["bb_high"]) & (df["rsi"] > 72) & down
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["close"] >= df["bb_mid"]) & (df["close"].shift(1) < df["bb_mid"])).astype(int)
        df["exit_short"] = ((df["close"] <= df["bb_mid"]) & (df["close"].shift(1) > df["bb_mid"])).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.014,
            "minimal_roi": {"0": 0.012, "30": 0.006},
            "can_short": True,
        },
    },
    {
        "id": "ema-trend-pullback",
        "name": "EMA trend pullback",
        "styles": ["trend-following", "mean-reversion"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.02
    minimal_roi = {"0": 0.016, "60": 0.008}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=12).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=48).ema_indicator()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        up = df["ema_fast"] > df["ema_slow"]
        down = df["ema_fast"] < df["ema_slow"]
        long_sig = up & (df["close"] > df["ema_fast"]) & (df["rsi"] > 45) & (df["rsi"] < 65) & (df["close"] > df["close"].shift(1))
        short_sig = down & (df["close"] < df["ema_fast"]) & (df["rsi"] < 55) & (df["rsi"] > 35) & (df["close"] < df["close"].shift(1))
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["ema_fast"] < df["ema_slow"]) & (df["ema_fast"].shift(1) >= df["ema_slow"].shift(1))).astype(int)
        df["exit_short"] = ((df["ema_fast"] > df["ema_slow"]) & (df["ema_fast"].shift(1) <= df["ema_slow"].shift(1))).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.02,
            "minimal_roi": {"0": 0.016, "60": 0.008},
            "can_short": True,
        },
    },
    {
        "id": "donchian-breakout",
        "name": "Donchian / Turtle-style breakout",
        "styles": ["breakout-momentum", "trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.022
    minimal_roi = {"0": 0.02, "90": 0.01}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["don_high"] = df["high"].rolling(20).max()
        df["don_low"] = df["low"].rolling(20).min()
        df["ema"] = ta.trend.EMAIndicator(df["close"], window=50).ema_indicator()
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        long_sig = (df["close"] > df["don_high"].shift(1)) & (df["volume"] > df["vol_ma"] * 0.8)
        short_sig = (df["close"] < df["don_low"].shift(1)) & (df["volume"] > df["vol_ma"] * 0.8)
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        mid = (df["don_high"] + df["don_low"]) / 2
        df["exit_long"] = ((df["close"] < mid) & (df["close"].shift(1) >= mid.shift(1))).astype(int)
        df["exit_short"] = ((df["close"] > mid) & (df["close"].shift(1) <= mid.shift(1))).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.022,
            "minimal_roi": {"0": 0.02, "90": 0.01},
            "can_short": True,
        },
    },
    {
        "id": "dual-ma-crossover",
        "name": "Dual MA crossover",
        "styles": ["trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.02
    minimal_roi = {"0": 0.015, "75": 0.008}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["sma_fast"] = ta.trend.SMAIndicator(df["close"], window=10).sma_indicator()
        df["sma_slow"] = ta.trend.SMAIndicator(df["close"], window=30).sma_indicator()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        cross_up = (df["sma_fast"] > df["sma_slow"]) & (df["sma_fast"].shift(1) <= df["sma_slow"].shift(1))
        cross_dn = (df["sma_fast"] < df["sma_slow"]) & (df["sma_fast"].shift(1) >= df["sma_slow"].shift(1))
        df["enter_long"] = (cross_up & (df["rsi"] < 70)).astype(int)
        df["enter_short"] = (cross_dn & (df["rsi"] > 30)).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["sma_fast"] < df["sma_slow"]) & (df["sma_fast"].shift(1) >= df["sma_slow"].shift(1))).astype(int)
        df["exit_short"] = ((df["sma_fast"] > df["sma_slow"]) & (df["sma_fast"].shift(1) <= df["sma_slow"].shift(1))).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.02,
            "minimal_roi": {"0": 0.015, "75": 0.008},
            "can_short": True,
        },
    },
    {
        "id": "macd-momentum",
        "name": "MACD momentum",
        "styles": ["breakout-momentum", "trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.02
    minimal_roi = {"0": 0.015, "60": 0.007}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        macd = ta.trend.MACD(df["close"], window_slow=26, window_fast=12, window_sign=9)
        df["macd"] = macd.macd()
        df["macd_signal"] = macd.macd_signal()
        df["macd_hist"] = macd.macd_diff()
        df["ema"] = ta.trend.EMAIndicator(df["close"], window=50).ema_indicator()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        long_sig = (df["macd_hist"] > 0) & (df["macd_hist"].shift(1) <= 0) & (df["close"] > df["ema"])
        short_sig = (df["macd_hist"] < 0) & (df["macd_hist"].shift(1) >= 0) & (df["close"] < df["ema"])
        df["enter_long"] = long_sig.astype(int)
        df["enter_short"] = short_sig.astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["macd_hist"] < 0) & (df["macd_hist"].shift(1) >= 0)).astype(int)
        df["exit_short"] = ((df["macd_hist"] > 0) & (df["macd_hist"].shift(1) <= 0)).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.02,
            "minimal_roi": {"0": 0.015, "60": 0.007},
            "can_short": True,
        },
    },
]


def patterns_for_style(style: str | None) -> list[dict[str, Any]]:
    style = (style or "mean-reversion").strip().lower()
    matched = [p for p in PATTERNS if style in p["styles"]]
    return matched or list(PATTERNS)


def style_hint_block(style: str | None) -> str:
    """Prompt appendix so the LLM invents *new* variants of famous patterns."""
    names = [p["name"] for p in patterns_for_style(style)]
    return (
        "Base your NEW strategy on well-known quant patterns "
        f"(vary parameters, filters, and exits — do not copy verbatim): {', '.join(names)}. "
        "Produce original thresholds/windows so each generation is distinct. "
        "Must fire both long and short entries on typical 5m crypto futures."
    )


def infer_style_from_goal(goal: str) -> str:
    g = (goal or "").lower()
    if "breakout" in g or "momentum" in g:
        return "breakout-momentum"
    if "trend-following" in g or "trend following" in g:
        return "trend-following"
    return "mean-reversion"
