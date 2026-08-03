package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal")
public class InternalController {

    public record ActiveStrategy(UUID tenantId, UUID strategyId, String sourceCode,
                                 String timeframe, List<String> pairs, String marketType) {}

    private final AppProperties props;
    private final BotRepository bots;
    private final StrategyRepository strategies;
    private final ExchangeKeyRepository keys;
    private final CoinDcxFuturesClient futures;
    private final SecretCrypto crypto;
    private final ObjectMapper mapper;

    public InternalController(AppProperties props, BotRepository bots,
                              StrategyRepository strategies, ExchangeKeyRepository keys,
                              CoinDcxFuturesClient futures, SecretCrypto crypto,
                              ObjectMapper mapper) {
        this.props = props;
        this.bots = bots;
        this.strategies = strategies;
        this.keys = keys;
        this.futures = futures;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /** Ops: list active CoinDCX INR futures positions for the first ACTIVE key. */
    @GetMapping("/exchange-positions")
    public Mono<Map<String, Object>> exchangePositions(@RequestHeader("X-Internal-Token") String token,
                                                      @RequestParam(defaultValue = "INR") String margin) {
        if (!props.internal().token().equals(token))
            return Mono.error(ApiException.unauthorized("Bad internal token"));
        return keys.findAll()
                .filter(k -> "ACTIVE".equals(k.status()))
                .next()
                .switchIfEmpty(Mono.error(ApiException.notFound("No active exchange key")))
                .flatMap(key -> {
                    String apiKey = crypto.decrypt(key.apiKeyEnc());
                    String apiSecret = crypto.decrypt(key.apiSecretEnc());
                    return Mono.zip(
                            futures.availableInrBalance(apiKey, apiSecret).defaultIfEmpty(java.math.BigDecimal.ZERO),
                            futures.listPositions(apiKey, apiSecret, margin).defaultIfEmpty(mapper.createArrayNode())
                    ).map(t -> {
                        List<Map<String, Object>> active = new ArrayList<>();
                        JsonNode resp = t.getT2();
                        if (resp != null && resp.isArray()) {
                            for (JsonNode p : resp) {
                                double ap = p.path("active_pos").asDouble(0);
                                if (ap == 0) continue;
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("pair", p.path("pair").asText());
                                row.put("active_pos", ap);
                                row.put("avg_price", p.path("avg_price").asDouble(0));
                                row.put("leverage", p.path("leverage").asDouble(0));
                                row.put("locked_margin", p.path("locked_margin").asText(
                                        p.path("margin").asText("")));
                                active.add(row);
                            }
                        }
                        return Map.<String, Object>of(
                                "availableInr", t.getT1(),
                                "activeCount", active.size(),
                                "active", active);
                    });
                });
    }

    @GetMapping("/active-strategies")
    public Flux<ActiveStrategy> activeStrategies(@RequestHeader("X-Internal-Token") String token) {
        if (!props.internal().token().equals(token))
            return Flux.error(ApiException.unauthorized("Bad internal token"));
        return bots.findByStatus("RUNNING")
                .filter(bot -> !bot.killSwitch())
                .filter(bot -> "FUTURES".equals(bot.marketType()))
                .groupBy(bot -> bot.strategyId())
                .flatMap(group -> group.collectList()
                        .flatMap(botList -> strategies.findById(group.key())
                                .filter(s -> "FUTURES".equals(s.marketType()))
                                .filter(s -> !"ARCHIVED".equals(s.status()) && !"REJECTED".equals(s.status()))
                                .map(strategy -> {
                                    var pairs = new java.util.LinkedHashSet<String>();
                                    String timeframe = "1h";
                                    for (var bot : botList) {
                                        pairs.addAll(readPairs(bot.pairs()));
                                    }
                                    if (strategy.instrument() != null) pairs.add(strategy.instrument());
                                    try {
                                        var cfg = mapper.readTree(strategy.config().asString());
                                        if (cfg.hasNonNull("timeframe")) timeframe = cfg.get("timeframe").asText();
                                    } catch (Exception ignored) {
                                    }
                                    return new ActiveStrategy(strategy.tenantId(), strategy.id(),
                                            strategy.sourceCode(), timeframe, List.copyOf(pairs),
                                            "FUTURES");
                                })));
    }

    private List<String> readPairs(io.r2dbc.postgresql.codec.Json json) {
        try {
            return mapper.readValue(json.asString(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
