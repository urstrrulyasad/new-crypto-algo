import json, urllib.request

pairs = ["B-TAG_USDT", "B-ARX_USDT", "B-TA_USDT", "B-VANRY_USDT"]
for pair in pairs:
    url = (
        "https://api.coindcx.com/exchange/v1/derivatives/futures/data/instrument"
        f"?pair={pair}&margin_currency_short_name=INR"
    )
    with urllib.request.urlopen(url, timeout=20) as r:
        i = json.load(r).get("instrument") or {}
    dyn = i.get("dynamic_position_leverage_details") or {}
    dyn_keys = sorted(dyn.keys(), key=lambda x: float(x)) if isinstance(dyn, dict) else dyn
    interesting = {
        k: i.get(k)
        for k in i
        if any(
            s in k.lower()
            for s in (
                "min",
                "lev",
                "notional",
                "qty",
                "quantity",
                "trade",
                "step",
                "size",
                "contract",
                "tick",
                "price",
            )
        )
    }
    print("====", pair)
    print(
        "min_quantity",
        i.get("min_quantity"),
        "min_trade_size",
        i.get("min_trade_size"),
        "min_notional",
        i.get("min_notional"),
        "max_lev_long",
        i.get("max_leverage_long"),
        "dyn",
        dyn_keys,
    )
    print("interesting", json.dumps(interesting, default=str)[:800])
