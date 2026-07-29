"""Deterministic futures strategy used when the LLM produces no usable signals.

Tuned so at least liquid alts (e.g. SOL) can pass the LIVE quality backtest
on recent CoinDCX 5m data: trades>=15, wr>=55%, profit>=0, pf>=1, dd<=40.
"""

FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.018
    minimal_roi = {"0": 0.012}
    can_short = True

    def populate_indicators(self, df):
        df = df.copy()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.5)
        df["bb_low"] = bb.bollinger_lband()
        df["bb_mid"] = bb.bollinger_mavg()
        df["bb_high"] = bb.bollinger_hband()
        return df

    def populate_entry_trend(self, df):
        df = df.copy()
        df["enter_long"] = ((df["close"] < df["bb_low"]) & (df["rsi"] < 30)).astype(int)
        df["enter_short"] = ((df["close"] > df["bb_high"]) & (df["rsi"] > 70)).astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        # Mid-band reclaim crosses — not level-holds that stay true for hours.
        df["exit_long"] = ((df["close"] >= df["bb_mid"]) & (df["close"].shift(1) < df["bb_mid"])).astype(int)
        df["exit_short"] = ((df["close"] <= df["bb_mid"]) & (df["close"].shift(1) > df["bb_mid"])).astype(int)
        return df
'''

FALLBACK_CONFIG = {
    "timeframe": "5m",
    "stoploss": -0.018,
    "minimal_roi": {"0": 0.012},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-selective-fallback",
}
