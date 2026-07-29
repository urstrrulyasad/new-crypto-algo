package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only bot ops + start/stop/kill. Manual create is disabled — bots are
 * spawned by the auto futures pipeline.
 */
@RestController
@RequestMapping("/api/v1/bots")
public class BotController {

    private final BotRepository bots;
    private final AuditService audit;

    public BotController(BotRepository bots, AuditService audit) {
        this.bots = bots;
        this.audit = audit;
    }

    @GetMapping
    public Flux<Bot> list(@RequestParam(required = false) String marketType,
                          @RequestParam(required = false) String mode) {
        String mt = marketType == null ? "FUTURES" : marketType.toUpperCase();
        return CurrentUser.get().flatMapMany(p -> bots.findByTenantIdAndMarketType(p.tenantId(), mt)
                .filter(b -> mode == null || mode.equalsIgnoreCase(b.mode()))
                .filter(b -> b.userId().equals(p.userId()) || p.isTenantAdmin()));
    }

    @PostMapping
    public Mono<Bot> create() {
        return Mono.error(ApiException.badRequest(
                "Manual bot creation is disabled. Futures bots are created by the auto strategy pipeline."));
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

    /** Stop every RUNNING FUTURES bot for the current tenant (ops kill switch). */
    @PostMapping("/stop-all-futures")
    public Mono<Map<String, Object>> stopAllFutures() {
        return CurrentUser.get().flatMap(p -> bots.findByTenantIdAndMarketType(p.tenantId(), "FUTURES")
                .filter(b -> "RUNNING".equals(b.status()))
                .flatMap(b -> bots.save(withStatus(b, "STOPPED", true))
                        .then(audit.record(p.tenantId(), p.userId(), "FUTURES_STOP_ALL",
                                "BOT", b.id(), Map.of("name", b.name()))))
                .count()
                .flatMap(n -> audit.record(p.tenantId(), p.userId(), "FUTURES_STOP_ALL_DONE",
                                "TENANT", p.tenantId(), Map.of("stopped", String.valueOf(n)))
                        .thenReturn(Map.of("stopped", n))));
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
                b.maxOpenTrades(), b.leverage(), status, killSwitch, b.marginCurrency(),
                b.createdAt(), Instant.now());
    }
}
