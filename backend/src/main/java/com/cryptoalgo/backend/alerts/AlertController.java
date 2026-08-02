package com.cryptoalgo.backend.alerts;

import com.cryptoalgo.backend.domain.AuditLog;
import com.cryptoalgo.backend.repo.AuditLogRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant alerts for the web Admin banner and Android push/local notifications:
 * AI rate limits + LIVE buy/sell fills.
 */
@RestController
@RequestMapping("/api/v1")
public class AlertController {

    private static final Set<String> ALERT_ACTIONS = Set.of(
            "AI_RATE_LIMITED",
            "STRATEGY_GENERATE_SKIPPED",
            "LIVE_ORDER_PLACED",
            "LIVE_EXIT",
            "LIVE_FUTURES_EXIT",
            "LIVE_ENTRY_SKIPPED"
    );

    public record AlertView(UUID id, String action, String title, String body,
                            Map<String, String> details, Instant createdAt) {}

    public record AiHealth(boolean rateLimited, String message, Instant lastAt,
                           long recentRateLimitEvents) {}

    private final AuditLogRepository auditLogs;
    private final ObjectMapper mapper;

    public AlertController(AuditLogRepository auditLogs, ObjectMapper mapper) {
        this.auditLogs = auditLogs;
        this.mapper = mapper;
    }

    @GetMapping("/alerts")
    public Flux<AlertView> alerts(@RequestParam(defaultValue = "40") int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return CurrentUser.get().flatMapMany(p ->
                auditLogs.findTop100ByTenantIdOrderByCreatedAtDesc(p.tenantId())
                        .filter(a -> ALERT_ACTIONS.contains(a.action()))
                        .take(lim)
                        .map(this::toView));
    }

    /** Surface rate-limit state so admins can rotate AI keys manually. */
    @GetMapping("/ai/health")
    public Mono<AiHealth> aiHealth() {
        Instant since = Instant.now().minus(2, ChronoUnit.HOURS);
        return CurrentUser.get().flatMap(p ->
                auditLogs.findTop100ByTenantIdOrderByCreatedAtDesc(p.tenantId())
                        .filter(a -> "AI_RATE_LIMITED".equals(a.action())
                                || ("STRATEGY_GENERATE_SKIPPED".equals(a.action())
                                && looksRateLimited(a)))
                        .filter(a -> a.createdAt() != null && a.createdAt().isAfter(since))
                        .collectList()
                        .map(list -> {
                            if (list.isEmpty()) {
                                return new AiHealth(false, "AI providers healthy", null, 0);
                            }
                            AuditLog latest = list.get(0);
                            Map<String, String> d = readDetails(latest.details());
                            String msg = d.getOrDefault("reason", "AI provider rate limited — rotate the API key in Admin.");
                            return new AiHealth(true, msg, latest.createdAt(), list.size());
                        }));
    }

    private AlertView toView(AuditLog a) {
        Map<String, String> d = readDetails(a.details());
        return new AlertView(a.id(), a.action(), titleFor(a.action()), bodyFor(a.action(), d), d, a.createdAt());
    }

    private boolean looksRateLimited(AuditLog a) {
        Map<String, String> d = readDetails(a.details());
        return "true".equalsIgnoreCase(d.get("rateLimited"))
                || com.cryptoalgo.backend.strategy.StrategyPipelineService.isRateLimitError(d.get("reason"));
    }

    private static String titleFor(String action) {
        return switch (action) {
            case "AI_RATE_LIMITED" -> "AI rate limited";
            case "STRATEGY_GENERATE_SKIPPED" -> "Strategy generation paused";
            case "LIVE_ORDER_PLACED" -> "LIVE bought / opened";
            case "LIVE_EXIT", "LIVE_FUTURES_EXIT" -> "LIVE sold / closed";
            case "LIVE_ENTRY_SKIPPED" -> "LIVE entry skipped";
            default -> action;
        };
    }

    private static String bodyFor(String action, Map<String, String> d) {
        return switch (action) {
            case "AI_RATE_LIMITED", "STRATEGY_GENERATE_SKIPPED" ->
                    d.getOrDefault("reason", "Rotate your AI provider key in Admin → AI Providers.");
            case "LIVE_ORDER_PLACED" ->
                    "LIVE " + d.getOrDefault("side", "entry") + " "
                            + d.getOrDefault("pair", d.getOrDefault("market", "pair"))
                            + (d.containsKey("entryPrice") ? " @ " + d.get("entryPrice") : "")
                            + (d.containsKey("qty") ? " · qty " + d.get("qty") : "");
            case "LIVE_EXIT", "LIVE_FUTURES_EXIT" ->
                    "Closed " + d.getOrDefault("pair", d.getOrDefault("market", "pair"))
                            + (d.containsKey("price") ? " @ " + d.get("price") : "")
                            + (d.containsKey("reason") ? " · " + d.get("reason") : "");
            case "LIVE_ENTRY_SKIPPED" ->
                    d.getOrDefault("reason", "LIVE entry was skipped");
            default -> d.toString();
        };
    }

    private Map<String, String> readDetails(Json json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        try {
            JsonNode node = mapper.readTree(json.asString());
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
