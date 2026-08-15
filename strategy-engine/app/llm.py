"""Provider-agnostic LLM client with automatic rate-limit failover.

The backend supplies an ordered chain of provider configs (preset dialect,
base URL, decrypted API key and an ordered model list). Generation walks the
chain: on a 429 (or transient upstream failure) it tries the next model of the
same provider first; when a provider's models are exhausted (or its key is
rejected) it moves to the next provider. It only fails once the whole chain
is exhausted.
"""
from __future__ import annotations

import json
import logging
import re

import httpx

log = logging.getLogger("llm")

SYSTEM_PROMPT = """You are an expert quantitative trading strategy developer for CoinDCX INR-margined crypto futures.
Generate a Python trading strategy class with EXACTLY this contract (freqtrade-style):

- One class with attributes:
  timeframe (one of "1m", "5m", "1h", "1d"),
  stoploss (negative float, e.g. -0.05),
  minimal_roi (dict mapping minutes-held string to ROI ratio, e.g. {"0": 0.04, "60": 0.02}),
  can_short = True.
- Methods (each takes and returns a pandas DataFrame with columns
  date, open, high, low, close, volume):
    def populate_indicators(self, df): ...
    def populate_entry_trend(self, df): ...   # set df["enter_long"]=1 and/or df["enter_short"]=1
    def populate_exit_trend(self, df): ...    # set df["exit_long"]=1 and/or df["exit_short"]=1
- Only import pandas, numpy and ta (the 'ta' technical-analysis package, e.g.
  ta.momentum.RSIIndicator, ta.trend.EMAIndicator, ta.volatility.BollingerBands).
- Use ONLY real, documented 'ta' method names (e.g. BollingerBands exposes
  bollinger_mavg/bollinger_hband/bollinger_lband; RSIIndicator exposes rsi();
  EMAIndicator exposes ema_indicator()). Never invent method names.
- No I/O, no network, no file access, no exec/eval. Vectorized pandas only.
- Design for leveraged INR futures on 5m (preferred) or 1m:
  prioritize POSITIVE expectancy over raw signal count.
  Require selective entries (filters + confirmation), not constant flipping.
  Prefer RSI mean-reversion with BB/EMA confirmation, or EMA trend with
  pullback entries. Keep stoploss tight (≤5%). Set minimal_roi["0"] to at least
  1.5× the stoploss magnitude so the first target clears round-trip taker fees
  (~0.15%) with positive expectancy (e.g. stoploss -0.014 with minimal_roi
  {"0": 0.022}); never let the target sit below the stop. Target win_rate ≥55%
  and profit_factor > 1. Always set both long and short entry rules when can_short
  is true. Avoid strategies that overtrade every candle.


Respond with ONLY a JSON object: {"source_code": "<python code>", "config": {"timeframe": "...", "stoploss": ..., "minimal_roi": {...}, "can_short": true}, "explanation": "<one paragraph>"}"""


class LlmError(Exception):
    """Raised when the upstream LLM provider rejects or fails the request."""

    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


async def generate_strategy(providers: list[dict], goal: str, timeframe: str,
                            pairs: list[str], risk_profile: str,
                            market_data: str | None = None) -> dict:
    """Walk the provider/model failover chain until one model answers."""
    user_prompt = (
        f"Goal: {goal}\nTimeframe: {timeframe}\nPairs: {', '.join(pairs)}\n"
        f"Risk profile: {risk_profile}\n")
    if market_data:
        user_prompt += (
            "\nRecent live market data (CSV: date,open,high,low,close,volume) "
            "for the primary pair - use it to tune indicator periods and thresholds:\n"
            f"{market_data}\n")
    user_prompt += "\nGenerate the strategy now."

    attempts: list[dict] = []
    async with httpx.AsyncClient(timeout=180) as client:
        for prov in providers:
            ptype = prov["provider_type"]
            for model in prov.get("models", []):
                try:
                    text = await _call_model(client, prov["dialect"], prov["base_url"],
                                             model, prov["api_key"], user_prompt)
                    result = _extract_json(text)
                    result["provider_used"] = ptype
                    result["model_used"] = model
                    result["attempts"] = attempts
                    return result
                except LlmError as e:
                    attempts.append({"provider": ptype, "model": model,
                                     "status": e.status_code, "error": str(e)[:300]})
                    log.warning("LLM attempt failed (%s/%s): %s", ptype, model, e)
                    if e.status_code in (401, 403):
                        break  # bad key: no point trying other models of this provider
                    continue  # 429 / 5xx / timeout: next model, then next provider
                except (ValueError, KeyError) as e:
                    attempts.append({"provider": ptype, "model": model,
                                     "status": 422, "error": f"parse error: {e}"[:300]})
                    log.warning("LLM response parse failed (%s/%s): %s", ptype, model, e)
                    continue

    all_rate_limited = attempts and all(a["status"] == 429 for a in attempts)
    raise LlmError(
        "All configured AI providers/models failed. Attempts: "
        + json.dumps(attempts),
        429 if all_rate_limited else 502)


async def _call_model(client: httpx.AsyncClient, dialect: str, base_url: str,
                      model: str, api_key: str, user_prompt: str) -> str:
    try:
        if dialect == "GEMINI":
            resp = await client.post(
                f"{base_url.rstrip('/')}/v1beta/models/{model}:generateContent",
                headers={"x-goog-api-key": api_key},
                json={"system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                      "contents": [{"parts": [{"text": user_prompt}]}]})
            _raise_for_llm(resp, f"Gemini/{model}")
            body = resp.json()
            if "candidates" not in body or not body["candidates"]:
                raise LlmError(f"Gemini returned no candidates: {str(body)[:300]}", 502)
            return body["candidates"][0]["content"]["parts"][0]["text"]
        if dialect == "ANTHROPIC":
            resp = await client.post(
                f"{base_url.rstrip('/')}/v1/messages",
                headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
                json={"model": model, "max_tokens": 8000,
                      "system": SYSTEM_PROMPT,
                      "messages": [{"role": "user", "content": user_prompt}]})
            _raise_for_llm(resp, f"Anthropic/{model}")
            return resp.json()["content"][0]["text"]
        # OPENAI dialect: Groq, OpenRouter, Mistral, Cerebras and any compatible API
        resp = await client.post(
            f"{base_url.rstrip('/')}/v1/chat/completions",
            headers={"Authorization": f"Bearer {api_key}"},
            json={"model": model,
                  "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                               {"role": "user", "content": user_prompt}]})
        _raise_for_llm(resp, f"{dialect}/{model}")
        return resp.json()["choices"][0]["message"]["content"]
    except httpx.TimeoutException as e:
        raise LlmError(f"LLM request timed out: {e}", 504) from e
    except httpx.RequestError as e:
        raise LlmError(f"LLM request failed: {e}", 502) from e


def _raise_for_llm(resp: httpx.Response, provider: str) -> None:
    if resp.is_success:
        return
    detail = resp.text[:500]
    if resp.status_code == 429:
        raise LlmError(f"{provider} rate limit hit (429): {detail}", 429)
    if resp.status_code in (401, 403):
        raise LlmError(f"{provider} rejected the API key ({resp.status_code}): {detail}", 401)
    raise LlmError(f"{provider} error {resp.status_code}: {detail}", 502)


def _extract_json(text: str) -> dict:
    text = text.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.DOTALL)
    if fenced:
        text = fenced.group(1)
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        raise ValueError("LLM response did not contain JSON")
    return json.loads(text[start:end + 1])
