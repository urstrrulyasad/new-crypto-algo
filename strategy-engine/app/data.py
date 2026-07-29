"""CoinDCX public candle download — spot and futures — no caching."""
from __future__ import annotations

import httpx
import pandas as pd

from .config import COINDCX_PUBLIC

# Spot public candles
_SPOT_TF_MS = {"1m": 60_000, "15m": 900_000, "1h": 3_600_000, "1d": 86_400_000}
_SPOT_ALIASES = {"3m": "1m", "5m": "1m", "10m": "15m", "30m": "15m",
                 "2h": "1h", "4h": "1h", "6h": "1h", "8h": "1h", "12h": "1h",
                 "3d": "1d", "1w": "1d", "1M": "1d"}

# Futures candlesticks resolutions: 1, 5, 60, 1D
_FUT_RES = {"1m": "1", "5m": "5", "1h": "60", "1d": "1D"}
_FUT_MS = {"1m": 60_000, "5m": 300_000, "1h": 3_600_000, "1d": 86_400_000}
_FUT_ALIASES = {"1": "1m", "5": "5m", "60": "1h", "1D": "1d",
                "3m": "5m", "15m": "5m", "30m": "5m", "2h": "1h", "4h": "1h"}

# Back-compat for runner imports
_TF_MS = _SPOT_TF_MS


def normalize_timeframe(timeframe: str, market_type: str = "SPOT") -> str:
    if market_type.upper() == "FUTURES":
        if timeframe in _FUT_MS:
            return timeframe
        return _FUT_ALIASES.get(timeframe, "1h")
    if timeframe in _SPOT_TF_MS:
        return timeframe
    return _SPOT_ALIASES.get(timeframe, "1h")


async def fetch_candles(pair: str, timeframe: str, start_ms: int, end_ms: int,
                        market_type: str = "SPOT") -> pd.DataFrame:
    if market_type.upper() == "FUTURES":
        return await _fetch_futures(pair, timeframe, start_ms, end_ms)
    return await _fetch_spot(pair, timeframe, start_ms, end_ms)


async def _fetch_spot(pair: str, timeframe: str, start_ms: int, end_ms: int) -> pd.DataFrame:
    timeframe = normalize_timeframe(timeframe, "SPOT")
    window = _SPOT_TF_MS[timeframe] * 1000
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
    return _to_df(rows, pair, timeframe)


async def _fetch_futures(pair: str, timeframe: str, start_ms: int, end_ms: int) -> pd.DataFrame:
    timeframe = normalize_timeframe(timeframe, "FUTURES")
    resolution = _FUT_RES[timeframe]
    rows: list[dict] = []
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{COINDCX_PUBLIC}/market_data/candlesticks",
            params={
                "pair": pair,
                "from": start_ms // 1000,
                "to": end_ms // 1000,
                "resolution": resolution,
                "pcode": "f",
            })
        resp.raise_for_status()
        body = resp.json()
        data = body.get("data", body) if isinstance(body, dict) else body
        if isinstance(data, list):
            rows.extend(data)
    return _to_df(rows, pair, timeframe)


def _to_df(rows: list[dict], pair: str, timeframe: str) -> pd.DataFrame:
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
