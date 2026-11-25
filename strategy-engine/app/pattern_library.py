"""Quantitative futures strategy pattern library.

Used to (1) steer the LLM toward proven multi-factor structures with volatility
and regime filters and (2) seed smoke-passing candidates when the LLM chain is
exhausted.
"""
from __future__ import annotations

from typing import Any


PATTERNS: list[dict[str, Any]] = [
    {
        "id": "rsi-bb-mean-reversion",
        "name": "RSI + Bollinger + ATR regime mean reversion",
        "styles": ["mean-reversion"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    # Reward:risk ~1.69:1 — target clears round-trip taker fees with positive expectancy.
    stoploss = -0.013
    minimal_roi = {"0": 0.022, "30": 0.012}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.5)
        df["bb_low"] = bb.bollinger_lband()
        df["bb_mid"] = bb.bollinger_mavg()
        df["bb_high"] = bb.bollinger_hband()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=21).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=55).ema_indicator()
        df["atr_pct"] = ta.volatility.AverageTrueRange(
            df["high"], df["low"], df["close"], window=14
        ).average_true_range() / df["close"].replace(0, np.nan)
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        up = df["ema_fast"] > df["ema_slow"]
        down = df["ema_fast"] < df["ema_slow"]
        tradable_vol = (df["atr_pct"] > 0.0012) & (df["volume"] > df["vol_ma"] * 0.7)
        long_sig = (df["close"] < df["bb_low"]) & (df["rsi"] < 29) & up & tradable_vol
        short_sig = (df["close"] > df["bb_high"]) & (df["rsi"] > 71) & down & tradable_vol
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
            "stoploss": -0.013,
            "minimal_roi": {"0": 0.022, "30": 0.012},
            "can_short": True,
        },
    },
    {
        "id": "ema-adx-trend-pullback",
        "name": "EMA + ADX regime trend pullback",
        "styles": ["trend-following", "mean-reversion"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    # Reward:risk ~1.69:1 with ADX trend-strength gating to avoid sideways chop.
    stoploss = -0.013
    minimal_roi = {"0": 0.022, "60": 0.011}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=12).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=48).ema_indicator()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        df["adx"] = ta.trend.ADXIndicator(df["high"], df["low"], df["close"], window=14).adx()
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        trending = df["adx"] > 18
        liq = df["volume"] > df["vol_ma"] * 0.75
        up = df["ema_fast"] > df["ema_slow"]
        down = df["ema_fast"] < df["ema_slow"]
        long_sig = up & trending & liq & (df["close"] > df["ema_fast"]) & (df["rsi"] > 46) & (df["rsi"] < 66) & (df["close"] > df["close"].shift(1))
        short_sig = down & trending & liq & (df["close"] < df["ema_fast"]) & (df["rsi"] < 54) & (df["rsi"] > 34) & (df["close"] < df["close"].shift(1))
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
            "stoploss": -0.013,
            "minimal_roi": {"0": 0.022, "60": 0.011},
            "can_short": True,
        },
    },
    {
        "id": "keltner-squeeze-breakout",
        "name": "Keltner + Bollinger volatility squeeze breakout",
        "styles": ["breakout-momentum", "trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.016
    minimal_roi = {"0": 0.028, "75": 0.014}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.0)
        kc = ta.volatility.KeltnerChannel(df["high"], df["low"], df["close"], window=20, window_atr=10)
        df["bb_high"] = bb.bollinger_hband()
        df["bb_low"] = bb.bollinger_lband()
        df["kc_high"] = kc.keltner_channel_hband()
        df["kc_low"] = kc.keltner_channel_lband()
        df["kc_mid"] = kc.keltner_channel_mband()
        df["ema_trend"] = ta.trend.EMAIndicator(df["close"], window=50).ema_indicator()
        df["vol_ma"] = df["volume"].rolling(20).mean()
        df["squeeze_on"] = (df["bb_high"] < df["kc_high"]) & (df["bb_low"] > df["kc_low"])
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        squeeze_release = df["squeeze_on"].shift(1).fillna(False) | df["squeeze_on"].shift(2).fillna(False)
        vol_ok = df["volume"] > df["vol_ma"] * 1.1
        long_sig = squeeze_release & vol_ok & (df["close"] > df["bb_high"]) & (df["close"] > df["ema_trend"])
        short_sig = squeeze_release & vol_ok & (df["close"] < df["bb_low"]) & (df["close"] < df["ema_trend"])
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["close"] < df["kc_mid"]) & (df["close"].shift(1) >= df["kc_mid"].shift(1))).astype(int)
        df["exit_short"] = ((df["close"] > df["kc_mid"]) & (df["close"].shift(1) <= df["kc_mid"].shift(1))).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.016,
            "minimal_roi": {"0": 0.028, "75": 0.014},
            "can_short": True,
        },
    },
    {
        "id": "donchian-breakout",
        "name": "Donchian / Turtle-style breakout with ADX filter",
        "styles": ["breakout-momentum", "trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    # Reward:risk 1.6:1 — breakouts use a wider stop, so target scales up too.
    stoploss = -0.02
    minimal_roi = {"0": 0.032, "90": 0.016}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["don_high"] = df["high"].rolling(20).max()
        df["don_low"] = df["low"].rolling(20).min()
        df["ema"] = ta.trend.EMAIndicator(df["close"], window=50).ema_indicator()
        df["adx"] = ta.trend.ADXIndicator(df["high"], df["low"], df["close"], window=14).adx()
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        long_sig = (df["close"] > df["don_high"].shift(1)) & (df["volume"] > df["vol_ma"] * 0.9) & (df["adx"] > 18) & (df["close"] > df["ema"])
        short_sig = (df["close"] < df["don_low"].shift(1)) & (df["volume"] > df["vol_ma"] * 0.9) & (df["adx"] > 18) & (df["close"] < df["ema"])
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
            "stoploss": -0.02,
            "minimal_roi": {"0": 0.032, "90": 0.016},
            "can_short": True,
        },
    },
    {
        "id": "stochrsi-vwap-reversal",
        "name": "StochRSI + rolling VWAP confluence reversal",
        "styles": ["mean-reversion"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "15m"
    stoploss = -0.014
    minimal_roi = {"0": 0.024, "90": 0.012}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        stoch = ta.momentum.StochRSIIndicator(df["close"], window=14, smooth1=3, smooth2=3)
        df["stoch_k"] = stoch.stochrsi_k()
        df["stoch_d"] = stoch.stochrsi_d()
        tp = (df["high"] + df["low"] + df["close"]) / 3.0
        pv = tp * df["volume"]
        df["rvwap"] = pv.rolling(24).sum() / df["volume"].rolling(24).sum().replace(0, np.nan)
        df["ema_macro"] = ta.trend.EMAIndicator(df["close"], window=89).ema_indicator()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        cross_up = (df["stoch_k"] > df["stoch_d"]) & (df["stoch_k"].shift(1) <= df["stoch_d"].shift(1))
        cross_dn = (df["stoch_k"] < df["stoch_d"]) & (df["stoch_k"].shift(1) >= df["stoch_d"].shift(1))
        long_sig = cross_up & (df["stoch_k"] < 0.25) & (df["close"] < df["rvwap"] * 0.996) & (df["close"] > df["ema_macro"] * 0.97)
        short_sig = cross_dn & (df["stoch_k"] > 0.75) & (df["close"] > df["rvwap"] * 1.004) & (df["close"] < df["ema_macro"] * 1.03)
        df["enter_long"] = long_sig.astype(int)
        df["enter_short"] = short_sig.astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["close"] >= df["rvwap"]) & (df["stoch_k"] > 0.72)).astype(int)
        df["exit_short"] = ((df["close"] <= df["rvwap"]) & (df["stoch_k"] < 0.28)).astype(int)
        return df
''',
        "config": {
            "timeframe": "15m",
            "stoploss": -0.014,
            "minimal_roi": {"0": 0.024, "90": 0.012},
            "can_short": True,
        },
    },
    {
        "id": "dual-ma-crossover",
        "name": "Dual EMA crossover with volume confirmation",
        "styles": ["trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    # Reward:risk ~1.64:1.
    stoploss = -0.014
    minimal_roi = {"0": 0.023, "75": 0.012}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=10).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=30).ema_indicator()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        cross_up = (df["ema_fast"] > df["ema_slow"]) & (df["ema_fast"].shift(1) <= df["ema_slow"].shift(1))
        cross_dn = (df["ema_fast"] < df["ema_slow"]) & (df["ema_fast"].shift(1) >= df["ema_slow"].shift(1))
        vol_ok = df["volume"] > df["vol_ma"] * 0.85
        df["enter_long"] = (cross_up & (df["rsi"] < 68) & vol_ok).astype(int)
        df["enter_short"] = (cross_dn & (df["rsi"] > 32) & vol_ok).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["ema_fast"] < df["ema_slow"]) & (df["ema_fast"].shift(1) >= df["ema_slow"].shift(1))).astype(int)
        df["exit_short"] = ((df["ema_fast"] > df["ema_slow"]) & (df["ema_fast"].shift(1) <= df["ema_slow"].shift(1))).astype(int)
        return df
''',
        "config": {
            "timeframe": "5m",
            "stoploss": -0.014,
            "minimal_roi": {"0": 0.023, "75": 0.012},
            "can_short": True,
        },
    },
    {
        "id": "macd-momentum",
        "name": "MACD histogram impulse with ADX + EMA filter",
        "styles": ["breakout-momentum", "trend-following"],
        "source": r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    # Reward:risk ~1.64:1.
    stoploss = -0.014
    minimal_roi = {"0": 0.023, "60": 0.012}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        macd = ta.trend.MACD(df["close"], window_slow=26, window_fast=12, window_sign=9)
        df["macd"] = macd.macd()
        df["macd_signal"] = macd.macd_signal()
        df["macd_hist"] = macd.macd_diff()
        df["ema"] = ta.trend.EMAIndicator(df["close"], window=50).ema_indicator()
        df["adx"] = ta.trend.ADXIndicator(df["high"], df["low"], df["close"], window=14).adx()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        trending = df["adx"] > 17
        long_sig = (df["macd_hist"] > 0) & (df["macd_hist"].shift(1) <= 0) & (df["close"] > df["ema"]) & trending
        short_sig = (df["macd_hist"] < 0) & (df["macd_hist"].shift(1) >= 0) & (df["close"] < df["ema"]) & trending
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
            "stoploss": -0.014,
            "minimal_roi": {"0": 0.023, "60": 0.012},
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
        "Base your NEW strategy on multi-factor quant patterns with regime & volume gating "
        f"(vary parameters, filters, and exits — do not copy verbatim): {', '.join(names)}. "
        "Include ADX or ATR volatility gating to skip low-liquidity chop, and volume confirmation. "
        "Produce original thresholds/windows so each generation is distinct. "
        "Must fire both long and short entries on typical 5m crypto futures."
    )


def infer_style_from_goal(goal: str) -> str:
    g = (goal or "").lower()
    if "breakout" in g or "momentum" in g or "squeeze" in g:
        return "breakout-momentum"
    if "trend-following" in g or "trend following" in g:
        return "trend-following"
    return "mean-reversion"
