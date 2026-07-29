package com.cryptoalgo.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CoinDCX per-market trading rules (min quantity, min notional, quantity
 * precision), fetched from markets_details and cached in memory for 10 min.
 * Used to size live orders correctly against available funds.
 */
@Service
public class MarketRulesService {

    public record Rule(BigDecimal minQuantity, BigDecimal minNotional, int quantityPrecision) {}

    private record Cache(Map<String, Rule> rules, long expiresAtMs) {}

    private static final long TTL_MS = 10 * 60_000;

    private final CoinDcxPublicClient client;
    private final AtomicReference<Cache> cache = new AtomicReference<>();

    public MarketRulesService(CoinDcxPublicClient client) {
        this.client = client;
    }

    /** Rule for an order market symbol, e.g. BTCUSDT. Empty if the market is unknown. */
    public Mono<Rule> rule(String market) {
        Cache c = cache.get();
        if (c != null && c.expiresAtMs() > System.currentTimeMillis()) {
            Rule r = c.rules().get(market);
            return r == null ? Mono.empty() : Mono.just(r);
        }
        return client.marketDetails().map(this::parse)
                .doOnNext(rules -> cache.set(new Cache(rules, System.currentTimeMillis() + TTL_MS)))
                .flatMap(rules -> {
                    Rule r = rules.get(market);
                    return r == null ? Mono.empty() : Mono.just(r);
                });
    }

    private Map<String, Rule> parse(JsonNode array) {
        Map<String, Rule> rules = new HashMap<>();
        if (array == null || !array.isArray()) return rules;
        for (JsonNode m : array) {
            String name = m.path("coindcx_name").asText(null);
            if (name == null) continue;
            rules.put(name, new Rule(
                    new BigDecimal(m.path("min_quantity").asText("0")),
                    new BigDecimal(m.path("min_notional").asText("0")),
                    m.path("target_currency_precision").asInt(8)));
        }
        return rules;
    }
}
