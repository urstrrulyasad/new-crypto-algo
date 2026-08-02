"""Strategy engine API: generation (LLM), validation, backtesting + signal runner."""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

import time

from . import runner
from .backtest import run_backtest
from .config import INTERNAL_TOKEN
from .data import fetch_candles, normalize_timeframe, _TF_MS, _FUT_MS
from .llm import LlmError, generate_strategy
from .pattern_library import (
    infer_style_from_goal,
    patterns_for_style,
    style_hint_block,
)
from .validation import load_strategy_class, validate_strategy_code

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("engine")


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(runner.run_forever())
    yield
    task.cancel()


app = FastAPI(title="Crypto Algo Strategy Engine", lifespan=lifespan)


def check_token(token: str | None) -> None:
    if token != INTERNAL_TOKEN:
        raise HTTPException(status_code=401, detail="Bad internal token")


class ProviderConfig(BaseModel):
    provider_type: str
    dialect: str
    base_url: str
    api_key: str
    models: list[str]


class GenerateRequest(BaseModel):
    providers: list[ProviderConfig]
    goal: str
    timeframe: str = "1h"
    pairs: list[str] = ["B-BTC_USDT"]
    risk_profile: str = "balanced"
    market_data: str | None = None
    market_type: str = "FUTURES"


class ValidateRequest(BaseModel):
    source_code: str


class BacktestRequest(BaseModel):
    source_code: str
    pairs: list[str]
    timeframe: str = "1h"
    start: datetime
    end: datetime
    stake: float = 1000.0
    leverage: float = 3.0
    market_type: str = "FUTURES"


class PaperCatchupRequest(BaseModel):
    tenant_id: str
    strategy_id: str
    source_code: str
    pairs: list[str]
    timeframe: str = "5m"
    market_type: str = "FUTURES"
    bars: int = 800


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/generate")
async def generate(req: GenerateRequest, x_internal_token: str | None = Header(None)):
    check_token(x_internal_token)
    if not req.providers:
        raise HTTPException(status_code=400, detail="No AI providers supplied")
    providers = [p.model_dump() for p in req.providers]
    timeframe = normalize_timeframe(req.timeframe, req.market_type)
    style = infer_style_from_goal(req.goal)
    pattern_hint = style_hint_block(style)

    # Stricter fix loop: validation + smoke feedback back into the LLM.
    feedback: str | None = None
    result: dict = {}
    check: dict = {"valid": False, "errors": ["generation not attempted"]}
    all_attempts: list = []
    llm_exhausted = False
    max_llm_rounds = 5
    for round_i in range(max_llm_rounds):
        base_goal = f"{req.goal}\n\n{pattern_hint}"
        goal = base_goal if feedback is None else (
            f"{base_goal}\n\nYour previous strategy failed with this error — "
            f"generate a corrected NEW version (change thresholds/windows):\n{feedback}")
        try:
            result = await generate_strategy(
                providers, goal, timeframe, req.pairs, req.risk_profile, req.market_data)
        except LlmError as e:
            log.warning("LLM chain exhausted (%s)", e)
            llm_exhausted = True
            check = {"valid": False, "errors": [str(e)]}
            break
        except ValueError as e:
            log.warning("LLM parse error (%s)", e)
            llm_exhausted = True
            check = {"valid": False, "errors": [f"LLM response parse error: {e}"]}
            break
        all_attempts.extend(result.get("attempts", []))
        source = result.get("source_code", "")
        check = validate_strategy_code(source)
        if check["valid"]:
            try:
                await _smoke_test(source, timeframe, req.pairs[0], req.market_type)
            except Exception as e:  # noqa: BLE001
                check = {"valid": False,
                         "errors": [f"Runtime smoke test on live candles failed: {e}"]}
        if check["valid"]:
            log.info("LLM strategy passed smoke on round %s (%s/%s)",
                     round_i + 1, result.get("provider_used"), result.get("model_used"))
            break
        feedback = "; ".join(str(e) for e in check["errors"])
        log.warning("Generated strategy failed checks (round %s/%s): %s",
                    round_i + 1, max_llm_rounds, feedback)

    # If LLM is down: try famous pattern library (smoke must pass).
    # If patterns also fail: pause — do not return a failing template for the
    # backend to persist as REJECTED.
    paused = False
    if not check["valid"]:
        if llm_exhausted:
            log.warning("LLM exhausted — trying famous pattern library for style=%s", style)
            picked = await _try_pattern_library(
                style, timeframe, req.pairs[0], req.market_type)
            if picked is not None:
                result, check = picked
            else:
                paused = True
                check = {
                    "valid": False,
                    "errors": [
                        "LLM providers rate-limited/unavailable and no famous pattern "
                        "cleared smoke — auto-gen paused until providers recover"
                    ],
                }
                result = {
                    "source_code": "",
                    "config": {"timeframe": timeframe},
                    "explanation": "Paused: LLM exhausted and pattern library smoke failed",
                    "provider_used": None,
                    "model_used": None,
                    "attempts": all_attempts,
                }
        else:
            # LLM answered but never cleared smoke after retries — pause persist.
            paused = True
            result = {
                "source_code": result.get("source_code", ""),
                "config": result.get("config", {"timeframe": timeframe}),
                "explanation": result.get("explanation", ""),
                "provider_used": result.get("provider_used"),
                "model_used": result.get("model_used"),
                "attempts": all_attempts,
            }

    return {
        "valid": check["valid"],
        "errors": check.get("errors", []),
        "source_code": result.get("source_code", ""),
        "config": result.get("config", {}),
        "explanation": result.get("explanation", ""),
        "provider_used": result.get("provider_used"),
        "model_used": result.get("model_used"),
        "attempts": all_attempts,
        "llm_exhausted": llm_exhausted,
        "paused": paused,
    }


async def _try_pattern_library(
        style: str, timeframe: str, pair: str, market_type: str
) -> tuple[dict, dict] | None:
    """Return (result, check) for the first famous pattern that passes smoke."""
    for pattern in patterns_for_style(style):
        src = pattern["source"]
        cfg = {**pattern["config"], "timeframe": timeframe}
        check = validate_strategy_code(src)
        if not check["valid"]:
            continue
        try:
            await _smoke_test(src, timeframe, pair, market_type, min_signals=1)
        except Exception as e:  # noqa: BLE001
            log.warning("Pattern %s smoke failed: %s", pattern["id"], e)
            continue
        log.info("Pattern library hit: %s for %s", pattern["id"], pair)
        result = {
            "source_code": src,
            "config": cfg,
            "explanation": f"Famous pattern seed: {pattern['name']} (LLM unavailable)",
            "provider_used": "PATTERN_LIBRARY",
            "model_used": pattern["id"],
            "attempts": [],
        }
        return result, check
    return None


async def _smoke_test(source: str, timeframe: str, pair: str, market_type: str = "FUTURES",
                      min_signals: int = 3) -> None:
    """Run the strategy end-to-end on ~250 real candles to catch runtime bugs."""
    strategy_cls = load_strategy_class(source)
    tf = normalize_timeframe(timeframe, market_type)
    tf_ms = (_FUT_MS if market_type.upper() == "FUTURES" else _TF_MS).get(tf, 3_600_000)
    now_ms = int(time.time() * 1000)
    df = await fetch_candles(pair, tf, now_ms - tf_ms * 250, now_ms, market_type)
    if df is None or len(df) < 50:
        raise ValueError(
            f"CoinDCX returned insufficient live candles for {pair} "
            f"(got {0 if df is None else len(df)}); refusing smoke without real market data"
        )
    strategy = strategy_cls()
    df = strategy.populate_indicators(df)
    df = strategy.populate_entry_trend(df)
    df = strategy.populate_exit_trend(df)
    if "enter_long" not in df.columns and "enter_short" not in df.columns:
        raise ValueError("strategy never sets enter_long or enter_short")
    long_n = int(df["enter_long"].fillna(0).sum()) if "enter_long" in df.columns else 0
    short_n = int(df["enter_short"].fillna(0).sum()) if "enter_short" in df.columns else 0
    if long_n + short_n < min_signals:
        raise ValueError(
            f"strategy produced too few entry signals on smoke candles "
            f"(long={long_n}, short={short_n}); need clearer RSI/BB/EMA rules"
        )


@app.post("/validate")
async def validate(req: ValidateRequest, x_internal_token: str | None = Header(None)):
    check_token(x_internal_token)
    check = validate_strategy_code(req.source_code)
    if check["valid"]:
        try:
            load_strategy_class(req.source_code)
        except Exception as e:  # noqa: BLE001
            check = {"valid": False, "errors": [f"Strategy failed to load: {e}"]}
    return check


@app.post("/backtest")
async def backtest(req: BacktestRequest, x_internal_token: str | None = Header(None)):
    check_token(x_internal_token)
    check = validate_strategy_code(req.source_code)
    if not check["valid"]:
        raise HTTPException(status_code=422, detail=f"Invalid strategy: {check['errors']}")
    strategy_cls = load_strategy_class(req.source_code)

    all_trades: list[dict] = []
    per_pair: dict[str, dict] = {}
    for pair in req.pairs:
        df = await fetch_candles(pair, req.timeframe,
                                 int(req.start.timestamp() * 1000),
                                 int(req.end.timestamp() * 1000),
                                 req.market_type)
        result = run_backtest(strategy_cls, df, pair, req.stake, req.leverage, req.market_type)
        per_pair[pair] = result["metrics"]
        all_trades.extend(result["trades"])

    total_trades = sum(m["trades"] for m in per_pair.values())
    wins = sum(m["wins"] for m in per_pair.values())
    combined = {
        "pairs": per_pair,
        "trades": total_trades,
        "wins": wins,
        "win_rate": round(wins / total_trades, 4) if total_trades else 0,
        "profit_total_pct": round(sum(m["profit_total_pct"] for m in per_pair.values()), 4),
        "max_drawdown_pct": max((m["max_drawdown_pct"] for m in per_pair.values()), default=0),
        "profit_factor": next((m.get("profit_factor") for m in per_pair.values() if m.get("profit_factor")), None),
        "sharpe": next((m.get("sharpe") for m in per_pair.values() if m.get("sharpe")), None),
        "timeframe": req.timeframe,
        "market_type": req.market_type,
    }
    return {"metrics": combined, "trades": sorted(all_trades, key=lambda t: t["entry_time"])}


@app.post("/paper-catchup")
async def paper_catchup(req: PaperCatchupRequest, x_internal_token: str | None = Header(None)):
    """Replay CoinDCX candle history into paper signals for LIVE-gate progress."""
    check_token(x_internal_token)
    item = {
        "tenantId": req.tenant_id,
        "strategyId": req.strategy_id,
        "sourceCode": req.source_code,
        "pairs": req.pairs,
        "timeframe": req.timeframe,
        "marketType": req.market_type,
    }
    return await runner.catchup_strategy(item, bars=req.bars)
