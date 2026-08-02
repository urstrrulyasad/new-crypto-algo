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
from .fallback_strategy import FALLBACK_CONFIG, FALLBACK_SOURCE
from .llm import LlmError, generate_strategy
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

    # Up to 3 attempts: static validation + a runtime smoke test on real
    # candles; failures are fed back to the LLM so it can fix its own code.
    feedback: str | None = None
    result: dict = {}
    check: dict = {"valid": False, "errors": ["generation not attempted"]}
    all_attempts: list = []
    llm_exhausted = False
    for _ in range(3):
        goal = req.goal if feedback is None else (
            f"{req.goal}\n\nYour previous strategy failed with this error - "
            f"generate a corrected version:\n{feedback}")
        try:
            result = await generate_strategy(
                providers, goal, timeframe, req.pairs, req.risk_profile, req.market_data)
        except LlmError as e:
            log.warning("LLM chain failed (%s) — will use curated fallback", e)
            llm_exhausted = True
            check = {"valid": False, "errors": [str(e)]}
            break
        except ValueError as e:
            log.warning("LLM parse error (%s) — will use curated fallback", e)
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
            break
        feedback = "; ".join(str(e) for e in check["errors"])
        log.warning("Generated strategy failed checks, retrying: %s", feedback)

    # Last resort: curated RSI/BB template so the pipeline can still paper-trade
    # when the LLM is rate-limited, invalid, or signal-starved.
    if not check["valid"] or llm_exhausted:
        log.warning("Using curated futures fallback template")
        check = validate_strategy_code(FALLBACK_SOURCE)
        if check["valid"]:
            try:
                # Fallback only needs to load + run; thin alts rarely hit 3 signals
                # on a 250-bar smoke window — don't reject the whole coin for that.
                await _smoke_test(
                    FALLBACK_SOURCE, timeframe, req.pairs[0], req.market_type,
                    min_signals=0)
            except Exception as e:  # noqa: BLE001
                check = {"valid": False, "errors": [f"Fallback smoke test failed: {e}"]}
        result = {
            "source_code": FALLBACK_SOURCE,
            "config": {**FALLBACK_CONFIG, "timeframe": timeframe},
            "explanation": "Curated RSI+Bollinger fallback after LLM failure/rate-limit",
            "provider_used": "TEMPLATE",
            "model_used": "rsi-bb-ema-fallback",
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
    }


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
