package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal")
public class InternalController {

    public record ActiveStrategy(UUID tenantId, UUID strategyId, String sourceCode,
                                 String timeframe, List<String> pairs, String marketType) {}

    private final AppProperties props;
    private final BotRepository bots;
    private final StrategyRepository strategies;
    private final ObjectMapper mapper;

    public InternalController(AppProperties props, BotRepository bots,
                              StrategyRepository strategies, ObjectMapper mapper) {
        this.props = props;
        this.bots = bots;
        this.strategies = strategies;
        this.mapper = mapper;
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
