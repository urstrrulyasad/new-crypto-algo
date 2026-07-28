"""Provider-agnostic LLM client for strategy generation.

Supports Anthropic, Gemini, Grok (xAI) and any OpenAI-compatible endpoint.
Provider config (base URL, model, API key, optional request template) is
supplied per call by the backend from the admin-level configuration.
"""
from __future__ import annotations

import json
import re

import httpx

SYSTEM_PROMPT = """You are an expert quantitative trading strategy developer.
Generate a Python trading strategy class with EXACTLY this contract (freqtrade-style):

- One class with attributes: timeframe (str), stoploss (negative float, e.g. -0.05),
  minimal_roi (dict mapping minutes-held string to ROI ratio, e.g. {"0": 0.04, "60": 0.02}).
- Methods (each takes and returns a pandas DataFrame with columns
  date, open, high, low, close, volume):
    def populate_indicators(self, df): ...
    def populate_entry_trend(self, df): ...   # set df["enter_long"] = 1 on entry candles
    def populate_exit_trend(self, df): ...    # set df["exit_long"] = 1 on exit candles
- Only import pandas, numpy and ta (the 'ta' technical-analysis package, e.g.
  ta.momentum.RSIIndicator, ta.trend.EMAIndicator, ta.volatility.BollingerBands).
- No I/O, no network, no file access, no exec/eval. Vectorized pandas only.

Respond with ONLY a JSON object: {"source_code": "<python code>", "config": {"timeframe": "...", "stoploss": ..., "minimal_roi": {...}}, "explanation": "<one paragraph>"}"""


async def generate_strategy(provider_type: str, base_url: str, model: str, api_key: str,
                            goal: str, timeframe: str, pairs: list[str],
                            risk_profile: str, request_template: dict | None = None) -> dict:
    user_prompt = (
        f"Goal: {goal}\nTimeframe: {timeframe}\nPairs: {', '.join(pairs)}\n"
        f"Risk profile: {risk_profile}\n"
        "Generate the strategy now.")

    async with httpx.AsyncClient(timeout=180) as client:
        if provider_type == "ANTHROPIC":
            resp = await client.post(
                f"{base_url.rstrip('/')}/v1/messages",
                headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
                json={"model": model, "max_tokens": 8000,
                      "system": SYSTEM_PROMPT,
                      "messages": [{"role": "user", "content": user_prompt}]})
            resp.raise_for_status()
            text = resp.json()["content"][0]["text"]
        elif provider_type == "GEMINI":
            resp = await client.post(
                f"{base_url.rstrip('/')}/v1beta/models/{model}:generateContent",
                headers={"x-goog-api-key": api_key},
                json={"system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                      "contents": [{"parts": [{"text": user_prompt}]}]})
            resp.raise_for_status()
            text = resp.json()["candidates"][0]["content"]["parts"][0]["text"]
        else:  # GROK and OPENAI_COMPATIBLE both speak the OpenAI chat API
            resp = await client.post(
                f"{base_url.rstrip('/')}/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json={"model": model,
                      "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                                   {"role": "user", "content": user_prompt}]})
            resp.raise_for_status()
            text = resp.json()["choices"][0]["message"]["content"]

    return _extract_json(text)


def _extract_json(text: str) -> dict:
    text = text.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.DOTALL)
    if fenced:
        text = fenced.group(1)
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        raise ValueError("LLM response did not contain JSON")
    return json.loads(text[start:end + 1])
