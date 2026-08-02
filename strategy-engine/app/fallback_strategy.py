"""Deterministic futures strategies used when the LLM is unavailable.

Designed to clear the smoke gate (enough trades, controlled drawdown) without
the hyperactive BB template that produced 200+ trades / 70% DD.
"""

FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.016
    minimal_roi = {"0": 0.012, "60": 0.006}
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
        # Rising-edge into extreme — avoids holding enter_*=1 for many bars.
        long_sig = (df["close"] < df["bb_low"]) & (df["rsi"] < 30) & up
        short_sig = (df["close"] > df["bb_high"]) & (df["rsi"] > 70) & down
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = (
            (df["close"] >= df["bb_mid"]) & (df["close"].shift(1) < df["bb_mid"])
        ).astype(int)
        df["exit_short"] = (
            (df["close"] <= df["bb_mid"]) & (df["close"].shift(1) > df["bb_mid"])
        ).astype(int)
        return df
'''

FALLBACK_CONFIG = {
    "timeframe": "5m",
    "stoploss": -0.016,
    "minimal_roi": {"0": 0.012, "60": 0.006},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-ema-fallback",
}

# Slightly looser — only if selective fails the minimum-signal smoke check.
ACTIVE_FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.018
    minimal_roi = {"0": 0.014, "45": 0.007}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.4)
        df["bb_low"] = bb.bollinger_lband()
        df["bb_mid"] = bb.bollinger_mavg()
        df["bb_high"] = bb.bollinger_hband()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=13).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=34).ema_indicator()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        up = df["ema_fast"] > df["ema_slow"]
        down = df["ema_fast"] < df["ema_slow"]
        long_sig = (df["close"] < df["bb_low"]) & (df["rsi"] < 32) & up
        short_sig = (df["close"] > df["bb_high"]) & (df["rsi"] > 68) & down
        df["enter_long"] = (long_sig & (~long_sig.shift(1).fillna(False))).astype(int)
        df["enter_short"] = (short_sig & (~short_sig.shift(1).fillna(False))).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = (
            (df["close"] >= df["bb_mid"]) & (df["close"].shift(1) < df["bb_mid"])
        ).astype(int)
        df["exit_short"] = (
            (df["close"] <= df["bb_mid"]) & (df["close"].shift(1) > df["bb_mid"])
        ).astype(int)
        return df
'''

ACTIVE_FALLBACK_CONFIG = {
    "timeframe": "5m",
    "stoploss": -0.018,
    "minimal_roi": {"0": 0.014, "45": 0.007},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-active-fallback",
}
