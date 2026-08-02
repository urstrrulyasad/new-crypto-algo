"""Deterministic futures strategy used when the LLM produces no usable signals.

Selective RSI/BB + EMA filter to keep trade count and drawdown inside the
paper smoke gate (trades>=20, dd<=40) on liquid INR alts.
"""

FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.015
    minimal_roi = {"0": 0.01}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.8)
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
        df["enter_long"] = (
            (df["close"] < df["bb_low"]) & (df["rsi"] < 28) & up
        ).astype(int)
        df["enter_short"] = (
            (df["close"] > df["bb_high"]) & (df["rsi"] > 72) & down
        ).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        # Mid-band reclaim crosses — not level-holds that stay true for hours.
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
    "stoploss": -0.015,
    "minimal_roi": {"0": 0.01},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-ema-fallback",
}
