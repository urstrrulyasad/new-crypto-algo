package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.domain.ScalperSettings;
import com.cryptoalgo.backend.repo.ScalperSettingsRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scalper")
public class ScalperController {
    public record UpdateRequest(UUID strategyId, Boolean enabled, String mode, String instruments, String timeframe,
                                BigDecimal stakeAmount, Integer maxOpenTrades, Integer cooldownSeconds,
                                BigDecimal dailyLossLimit, Boolean killSwitch) {}

    private final ScalperSettingsRepository settings;
    public ScalperController(ScalperSettingsRepository settings) { this.settings = settings; }

    @GetMapping
    public Mono<ScalperSettings> get() {
        return CurrentUser.get().flatMap(p -> settings.findByTenantId(p.tenantId())
                .switchIfEmpty(Mono.defer(() -> settings.save(defaults(p.tenantId(), p.userId())))));
    }

    @PutMapping
    public Mono<ScalperSettings> update(@RequestBody UpdateRequest req) {
        return CurrentUser.get().flatMap(p -> settings.findByTenantId(p.tenantId())
                .switchIfEmpty(Mono.defer(() -> settings.save(defaults(p.tenantId(), p.userId()))))
                .flatMap(existing -> {
                    if (req.stakeAmount() != null && req.stakeAmount().signum() < 0)
                        return Mono.error(ApiException.badRequest("stakeAmount must be non-negative"));
                    if (req.maxOpenTrades() != null && (req.maxOpenTrades() < 1 || req.maxOpenTrades() > 20))
                        return Mono.error(ApiException.badRequest("maxOpenTrades must be between 1 and 20"));
                    String mode = req.mode() == null ? existing.mode() : req.mode().toUpperCase();
                    if (!mode.equals("PAPER") && !mode.equals("LIVE"))
                        return Mono.error(ApiException.badRequest("mode must be PAPER or LIVE"));
                    Json instruments = req.instruments() == null ? existing.instruments() : Json.of(req.instruments());
                    ScalperSettings next = new ScalperSettings(existing.id(), existing.tenantId(), existing.userId(),
                            req.strategyId() == null ? existing.strategyId() : req.strategyId(),
                            req.enabled() == null ? existing.enabled() : req.enabled(), mode, instruments,
                            req.timeframe() == null ? existing.timeframe() : req.timeframe(),
                            req.stakeAmount() == null ? existing.stakeAmount() : req.stakeAmount(),
                            req.maxOpenTrades() == null ? existing.maxOpenTrades() : req.maxOpenTrades(),
                            req.cooldownSeconds() == null ? existing.cooldownSeconds() : req.cooldownSeconds(),
                            req.dailyLossLimit() == null ? existing.dailyLossLimit() : req.dailyLossLimit(),
                            req.killSwitch() == null ? existing.killSwitch() : req.killSwitch(),
                            existing.lastDecision(), existing.lastError(), Instant.now(), existing.createdAt(), Instant.now());
                    return settings.save(next);
                }));
    }

    @PostMapping("/kill-switch")
    public Mono<ScalperSettings> kill(@RequestParam boolean enabled) {
        return update(new UpdateRequest(null, false, null, null, null, null, null, null, null, enabled));
    }

    private static ScalperSettings defaults(UUID tenantId, UUID userId) {
        return new ScalperSettings(null, tenantId, userId, null, false, "PAPER", Json.of("[]"), "5m",
                BigDecimal.ZERO, 3, 300, BigDecimal.ZERO, false, "IDLE", null, null, Instant.now(), Instant.now());
    }
}
