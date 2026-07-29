"""Deterministic futures strategy used when the LLM produces no usable signals."""

# Keep this import-free except pandas/numpy/ta — validation allowlist.
FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.03
    minimal_roi = {"0": 0.015, "60": 0.01, "180": 0.005}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2)
        df["bb_low"] = bb.bollinger_lband()
        df["bb_mid"] = bb.bollinger_mavg()
        df["bb_high"] = bb.bollinger_hband()
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=9).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=21).ema_indicator()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        long_cond = (df["rsi"] < 35) & (df["close"] <= df["bb_low"]) & (df["ema_fast"] >= df["ema_slow"] * 0.998)
        short_cond = (df["rsi"] > 65) & (df["close"] >= df["bb_high"]) & (df["ema_fast"] <= df["ema_slow"] * 1.002)
        df["enter_long"] = long_cond.astype(int)
        df["enter_short"] = short_cond.astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        df["exit_long"] = ((df["rsi"] > 55) | (df["close"] >= df["bb_mid"])).astype(int)
        df["exit_short"] = ((df["rsi"] < 45) | (df["close"] <= df["bb_mid"])).astype(int)
        return df
'''

FALLBACK_CONFIG = {
    "timeframe": "5m",
    "stoploss": -0.03,
    "minimal_roi": {"0": 0.015, "60": 0.01, "180": 0.005},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-fallback",
}
