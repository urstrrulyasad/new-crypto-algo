import pandas as pd
import ta


class EmaCrossStrategy:
    timeframe = "1h"
    stoploss = -0.05
    minimal_roi = {"0": 0.04, "120": 0.02, "360": 0.01}

    def populate_indicators(self, df):
        df["ema_fast"] = ta.trend.EMAIndicator(df["close"], window=12).ema_indicator()
        df["ema_slow"] = ta.trend.EMAIndicator(df["close"], window=26).ema_indicator()
        df["rsi"] = ta.momentum.RSIIndicator(df["close"], window=14).rsi()
        return df

    def populate_entry_trend(self, df):
        df["enter_long"] = 0
        cross_up = (df["ema_fast"] > df["ema_slow"]) & (df["ema_fast"].shift(1) <= df["ema_slow"].shift(1))
        df.loc[cross_up & (df["rsi"] < 70), "enter_long"] = 1
        return df

    def populate_exit_trend(self, df):
        df["exit_long"] = 0
        cross_down = (df["ema_fast"] < df["ema_slow"]) & (df["ema_fast"].shift(1) >= df["ema_slow"].shift(1))
        df.loc[cross_down, "exit_long"] = 1
        return df
