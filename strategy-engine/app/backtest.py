"""Backtest simulator over CoinDCX OHLCV (spot or futures).

Supports long and short, leverage, fees, slippage, and paper liquidation
when margin is wiped. Metrics include win rate, profit factor, max DD, Sharpe.
"""
from __future__ import annotations

import math

import pandas as pd

FEE_RATE = 0.00075  # ~0.075% futures taker
SLIPPAGE = 0.0005


def run_backtest(strategy_cls, df: pd.DataFrame, pair: str, stake: float = 1000.0,
                 leverage: float = 1.0, market_type: str = "SPOT") -> dict:
    strategy = strategy_cls()
    stoploss = float(getattr(strategy, "stoploss", -0.10))
    if stoploss > 0:
        stoploss = -stoploss
    minimal_roi = {int(k): float(v) for k, v in getattr(strategy, "minimal_roi", {"0": 0.05}).items()}
    timeframe = getattr(strategy, "timeframe", "1h")
    lev = max(1.0, float(getattr(strategy, "leverage", leverage) or leverage))

    df = strategy.populate_indicators(df.copy())
    df = strategy.populate_entry_trend(df)
    df = strategy.populate_exit_trend(df)
    for col in ("enter_long", "exit_long", "enter_short", "exit_short"):
        if col not in df.columns:
            df[col] = 0
    df = df.reset_index(drop=True)

    trades: list[dict] = []
    side: str | None = None
    entry_price = 0.0
    entry_time = None
    equity = [1.0]
    is_futures = market_type.upper() == "FUTURES"

    for i in range(1, len(df)):
        row = df.iloc[i]
        prev = df.iloc[i - 1]
        price_open = float(row["open"])

        if side is None:
            if prev.get("enter_long", 0) == 1:
                side, entry_price, entry_time = "long", price_open * (1 + SLIPPAGE), row["date"]
                continue
            if is_futures and prev.get("enter_short", 0) == 1:
                side, entry_price, entry_time = "short", price_open * (1 - SLIPPAGE), row["date"]
                continue
            continue

        minutes_held = (row["date"] - entry_time).total_seconds() / 60
        roi_target = _roi_for(minimal_roi, minutes_held)
        low, high = float(row["low"]), float(row["high"])

        exit_price = None
        reason = None

        if side == "long":
            low_ratio = low / entry_price - 1
            high_ratio = high / entry_price - 1
            if low_ratio <= stoploss:
                exit_price, reason = entry_price * (1 + stoploss), "stoploss"
            elif roi_target is not None and high_ratio >= roi_target:
                exit_price, reason = entry_price * (1 + roi_target), "roi"
            elif prev.get("exit_long", 0) == 1:
                exit_price, reason = price_open * (1 - SLIPPAGE), "exit_signal"
            elif is_futures and low_ratio * lev <= -0.95:
                exit_price, reason = low, "liquidation"
        else:
            # short: profit when price falls
            high_ratio = entry_price / high - 1  # loss when high rises
            low_ratio = entry_price / low - 1
            adverse = high / entry_price - 1
            if adverse >= abs(stoploss):
                exit_price, reason = entry_price * (1 + abs(stoploss)), "stoploss"
            elif roi_target is not None and low_ratio >= roi_target:
                exit_price, reason = entry_price * (1 - roi_target), "roi"
            elif prev.get("exit_short", 0) == 1:
                exit_price, reason = price_open * (1 + SLIPPAGE), "exit_signal"
            elif is_futures and adverse * lev >= 0.95:
                exit_price, reason = high, "liquidation"

        if exit_price is not None:
            if side == "long":
                raw = exit_price / entry_price - 1
            else:
                raw = entry_price / exit_price - 1
            profit_ratio = (1 + raw * lev) * (1 - FEE_RATE) ** 2 - 1
            trades.append({
                "pair": pair,
                "side": side,
                "entry_time": entry_time.isoformat(),
                "exit_time": row["date"].isoformat(),
                "entry_price": round(entry_price, 10),
                "exit_price": round(exit_price, 10),
                "profit_ratio": round(profit_ratio, 6),
                "profit_abs": round(stake * profit_ratio, 4),
                "exit_reason": reason,
                "leverage": lev,
            })
            equity.append(equity[-1] * (1 + profit_ratio))
            side = None

    return {
        "metrics": _metrics(trades, equity, df, timeframe, lev),
        # Cap trade list so HTTP clients don't hit buffer limits; metrics stay full-run.
        "trades": trades[-100:],
    }


def _roi_for(minimal_roi: dict[int, float], minutes: float):
    applicable = [v for k, v in sorted(minimal_roi.items()) if minutes >= k]
    return applicable[-1] if applicable else (minimal_roi.get(0) if 0 in minimal_roi else None)


def _metrics(trades: list[dict], equity: list[float], df: pd.DataFrame,
             timeframe: str, leverage: float) -> dict:
    total = len(trades)
    wins = sum(1 for t in trades if t["profit_ratio"] > 0)
    losses = total - wins
    profit_total = equity[-1] - 1

    gross_win = sum(t["profit_ratio"] for t in trades if t["profit_ratio"] > 0)
    gross_loss = abs(sum(t["profit_ratio"] for t in trades if t["profit_ratio"] <= 0))
    profit_factor = round(gross_win / gross_loss, 4) if gross_loss > 0 else None

    peak, max_dd = 1.0, 0.0
    for e in equity:
        peak = max(peak, e)
        max_dd = max(max_dd, (peak - e) / peak)

    returns = [t["profit_ratio"] for t in trades]
    sharpe = None
    if len(returns) > 1:
        mean = sum(returns) / len(returns)
        var = sum((r - mean) ** 2 for r in returns) / (len(returns) - 1)
        if var > 0:
            sharpe = round(mean / math.sqrt(var) * math.sqrt(len(returns)), 4)

    return {
        "timeframe": timeframe,
        "leverage": leverage,
        "start": df["date"].iloc[0].isoformat() if len(df) else None,
        "end": df["date"].iloc[-1].isoformat() if len(df) else None,
        "candles": len(df),
        "trades": total,
        "wins": wins,
        "losses": losses,
        "win_rate": round(wins / total, 4) if total else 0,
        "profit_total_pct": round(profit_total * 100, 4),
        "profit_factor": profit_factor,
        "max_drawdown_pct": round(max_dd * 100, 4),
        "sharpe": sharpe,
        "best_trade_pct": round(max((t["profit_ratio"] for t in trades), default=0) * 100, 4),
        "worst_trade_pct": round(min((t["profit_ratio"] for t in trades), default=0) * 100, 4),
    }
