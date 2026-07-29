"""Deterministic futures strategy used when the LLM produces no usable signals."""

# Keep this import-free except pandas/numpy/ta — validation allowlist.
FALLBACK_SOURCE = r'''
import pandas as pd
import numpy as np
import ta

class Strategy:
    timeframe = "5m"
    stoploss = -0.025
    minimal_roi = {"0": 0.012, "60": 0.008, "180": 0.004}
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
        # Slightly looser than first cut so paper can accumulate closed trades
        # under the LIVE gate without inventing prices.
        long_cond = (df["rsi"] < 40) & (df["close"] <= df["bb_low"] * 1.002)
        short_cond = (df["rsi"] > 60) & (df["close"] >= df["bb_high"] * 0.998)
        df["enter_long"] = long_cond.astype(int)
        df["enter_short"] = short_cond.astype(int)
        return df

    def populate_exit_trend(self, df):
        df = df.copy()
        # Cross / reclaim exits — not "close >= mid" which stays true for hours.
        rsi_up = (df["rsi"] > 55) & (df["rsi"].shift(1) <= 55)
        rsi_dn = (df["rsi"] < 45) & (df["rsi"].shift(1) >= 45)
        reclaim_mid = (df["close"] >= df["bb_mid"]) & (df["close"].shift(1) < df["bb_mid"])
        lose_mid = (df["close"] <= df["bb_mid"]) & (df["close"].shift(1) > df["bb_mid"])
        df["exit_long"] = (rsi_up | reclaim_mid).astype(int)
        df["exit_short"] = (rsi_dn | lose_mid).astype(int)
        return df
'''

FALLBACK_CONFIG = {
    "timeframe": "5m",
    "stoploss": -0.025,
    "minimal_roi": {"0": 0.012, "60": 0.008, "180": 0.004},
    "can_short": True,
    "provider_used": "TEMPLATE",
    "model_used": "rsi-bb-fallback",
}
