"""Strategy engine API: generation (LLM), validation, backtesting + signal runner."""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import runner
from .backtest import run_backtest
from .config import INTERNAL_TOKEN
from .data import fetch_candles
from .llm import generate_strategy
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


class GenerateRequest(BaseModel):
    provider_type: str
    base_url: str
    model: str
    api_key: str
    goal: str
    timeframe: str = "1h"
    pairs: list[str] = ["B-BTC_USDT"]
    risk_profile: str = "balanced"
    request_template: dict | None = None


class ValidateRequest(BaseModel):
    source_code: str


class BacktestRequest(BaseModel):
    source_code: str
    pairs: list[str]
    timeframe: str = "1h"
    start: datetime
    end: datetime
    stake: float = 1000.0


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/generate")
async def generate(req: GenerateRequest, x_internal_token: str | None = Header(None)):
    check_token(x_internal_token)
    result = await generate_strategy(
        req.provider_type, req.base_url, req.model, req.api_key,
        req.goal, req.timeframe, req.pairs, req.risk_profile, req.request_template)
    source = result.get("source_code", "")
    check = validate_strategy_code(source)
    if check["valid"]:
        try:
            load_strategy_class(source)
        except Exception as e:  # noqa: BLE001
            check = {"valid": False, "errors": [f"Strategy failed to load: {e}"]}
    return {
        "valid": check["valid"],
        "errors": check["errors"],
        "source_code": source,
        "config": result.get("config", {}),
        "explanation": result.get("explanation", ""),
    }


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
                                 int(req.end.timestamp() * 1000))
        result = run_backtest(strategy_cls, df, pair, req.stake)
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
        "timeframe": req.timeframe,
    }
    return {"metrics": combined, "trades": sorted(all_trades, key=lambda t: t["entry_time"])}
