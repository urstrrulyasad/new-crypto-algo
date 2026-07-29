"""CoinDCX public candle download with range paging (max 1000 candles/call)."""
from __future__ import annotations

import httpx
import pandas as pd

from .config import COINDCX_PUBLIC

# CoinDCX public candles only serve these intervals (verified against the live API).
_TF_MS = {"1m": 60_000, "15m": 900_000, "1h": 3_600_000, "1d": 86_400_000}

# Anything else (e.g. an LLM emitting "5m") is coerced to the nearest supported interval.
_TF_ALIASES = {"3m": "1m", "5m": "1m", "10m": "15m", "30m": "15m",
               "2h": "1h", "4h": "1h", "6h": "1h", "8h": "1h", "12h": "1h",
               "3d": "1d", "1w": "1d", "1M": "1d"}


def normalize_timeframe(timeframe: str) -> str:
    if timeframe in _TF_MS:
        return timeframe
    return _TF_ALIASES.get(timeframe, "1h")


async def fetch_candles(pair: str, timeframe: str, start_ms: int, end_ms: int) -> pd.DataFrame:
    timeframe = normalize_timeframe(timeframe)
    window = _TF_MS[timeframe] * 1000  # 1000 candles per request
    rows: list[dict] = []
    async with httpx.AsyncClient(timeout=30) as client:
        cursor = start_ms
        while cursor < end_ms:
            chunk_end = min(cursor + window, end_ms)
            resp = await client.get(
                f"{COINDCX_PUBLIC}/market_data/candles",
                params={"pair": pair, "interval": timeframe,
                        "startTime": cursor, "endTime": chunk_end, "limit": 1000})
            resp.raise_for_status()
            data = resp.json()
            if isinstance(data, list):
                rows.extend(data)
            cursor = chunk_end
    if not rows:
        raise ValueError(f"No candle data returned for {pair} {timeframe} in range")
    df = pd.DataFrame(rows)
    df["date"] = pd.to_datetime(df["time"], unit="ms", utc=True)
    df = (df[["date", "open", "high", "low", "close", "volume"]]
          .astype({"open": float, "high": float, "low": float, "close": float, "volume": float})
          .drop_duplicates(subset="date")
          .sort_values("date")
          .reset_index(drop=True))
    return df
