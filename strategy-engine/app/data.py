"""CoinDCX public candle download with range paging (max 1000 candles/call)."""
from __future__ import annotations

import httpx
import pandas as pd

from .config import COINDCX_PUBLIC

_TF_MS = {"1m": 60_000, "5m": 300_000, "15m": 900_000, "30m": 1_800_000,
          "1h": 3_600_000, "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
          "8h": 28_800_000, "1d": 86_400_000, "3d": 259_200_000,
          "1w": 604_800_000, "1M": 2_592_000_000}


async def fetch_candles(pair: str, timeframe: str, start_ms: int, end_ms: int) -> pd.DataFrame:
    if timeframe not in _TF_MS:
        raise ValueError(f"Unsupported timeframe {timeframe}")
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
