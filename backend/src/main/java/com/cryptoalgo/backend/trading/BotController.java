package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bots")
@Validated
public class BotController {

    public record CreateBotRequest(@NotBlank String name, @NotNull UUID strategyId,
                                   UUID exchangeKeyId, String mode, String marketType,
                                   @NotNull List<String> pairs, String stakeCurrency,
                                   @NotNull BigDecimal stakeAmount, Integer maxOpenTrades,
                                   BigDecimal leverage) {}

    private final BotRepository bots;
    private final StrategyRepository strategies;
    private final ExchangeKeyRepository keys;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public BotController(BotRepository bots, StrategyRepository strategies, ExchangeKeyRepository keys,
                         R2dbcEntityTemplate template, ObjectMapper mapper, AuditService audit) {
        this.bots = bots;
        this.strategies = strategies;
        this.keys = keys;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
    }

    @GetMapping
    public Flux<Bot> list() {
        return CurrentUser.get()
                .flatMapMany(p -> bots.findByTenantIdAndUserIdOrderByCreatedAtDesc(p.tenantId(), p.userId()));
    }

    @PostMapping
    public Mono<Bot> create(@RequestBody CreateBotRequest req) {
        String mode = req.mode() == null ? "PAPER" : req.mode();
        if (!mode.equals("PAPER") && !mode.equals("LIVE"))
            return Mono.error(ApiException.badRequest("mode must be PAPER or LIVE"));
        return CurrentUser.get().flatMap(p ->
                strategies.findByIdAndTenantId(req.strategyId(), p.tenantId())
                        .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                        .flatMap(strategy -> {
                            if (!"APPROVED".equals(strategy.status()))
                                return Mono.error(ApiException.badRequest("Strategy must be APPROVED before running a bot"));
                            Mono<UUID> keyCheck = mode.equals("LIVE")
                                    ? keys.findByIdAndTenantId(req.exchangeKeyId(), p.tenantId())
                                        .filter(k -> k.userId().equals(p.userId()))
                                        .map(k -> k.id())
                                        .switchIfEmpty(Mono.error(ApiException.badRequest(
                                                "LIVE bots need one of your CoinDCX API keys")))
                                    : Mono.just(new UUID(0, 0));
                            return keyCheck.flatMap(keyId -> {
                                Bot bot = new Bot(UUID.randomUUID(), p.tenantId(), p.userId(),
                                        strategy.id(), mode.equals("LIVE") ? req.exchangeKeyId() : null,
                                        req.name(), mode,
                                        req.marketType() == null ? "SPOT" : req.marketType(),
                                        toJson(req.pairs()),
                                        req.stakeCurrency() == null ? "USDT" : req.stakeCurrency(),
                                        req.stakeAmount(),
                                        req.maxOpenTrades() == null ? 3 : req.maxOpenTrades(),
                                        req.leverage() == null ? BigDecimal.ONE : req.leverage(),
                                        "STOPPED", false, Instant.now(), Instant.now());
                                return template.insert(bot)
                                        .then(audit.record(p.tenantId(), p.userId(), "BOT_CREATED",
                                                "BOT", bot.id(), Map.of("name", req.name(), "mode", mode)))
                                        .thenReturn(bot);
                            });
                        }));
    }

    @PostMapping("/{id}/start")
    public Mono<Bot> start(@PathVariable UUID id) {
        return setStatus(id, "RUNNING");
    }

    @PostMapping("/{id}/stop")
    public Mono<Bot> stop(@PathVariable UUID id) {
        return setStatus(id, "STOPPED");
    }

    @PostMapping("/{id}/kill-switch")
    public Mono<Bot> killSwitch(@PathVariable UUID id, @RequestParam boolean enabled) {
        return owned(id).flatMap(bot -> bots.save(withStatus(bot, bot.status(), enabled)));
    }

    private Mono<Bot> setStatus(UUID id, String status) {
        return owned(id).flatMap(bot -> bots.save(withStatus(bot, status, bot.killSwitch())));
    }

    private Mono<Bot> owned(UUID id) {
        return CurrentUser.get().flatMap(p -> bots.findByIdAndTenantId(id, p.tenantId())
                .filter(b -> b.userId().equals(p.userId()) || p.isTenantAdmin())
                .switchIfEmpty(Mono.error(ApiException.notFound("Bot not found"))));
    }

    private Bot withStatus(Bot b, String status, boolean killSwitch) {
        return new Bot(b.id(), b.tenantId(), b.userId(), b.strategyId(), b.exchangeKeyId(), b.name(),
                b.mode(), b.marketType(), b.pairs(), b.stakeCurrency(), b.stakeAmount(),
                b.maxOpenTrades(), b.leverage(), status, killSwitch, b.createdAt(), Instant.now());
    }

    private Json toJson(Object value) {
        try {
            return Json.of(mapper.writeValueAsString(value));
        } catch (Exception e) {
            throw ApiException.badRequest("Serialization failed");
        }
    }
}
