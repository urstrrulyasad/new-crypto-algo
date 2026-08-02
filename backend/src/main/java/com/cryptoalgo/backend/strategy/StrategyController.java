package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.repo.AuditLogRepository;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * Read-only strategy registry. Strategies are auto-generated for FUTURES;
 * there is no manual create/generate endpoint.
 */
@RestController
@RequestMapping("/api/v1/strategies")
@Validated
public class StrategyController {

    public record PaperProgress(long closedTrades, long wins, double winRate,
                                BigDecimal totalPnl, int requiredTrades, double requiredWinRate,
                                long openPositions) {}
    public record StrategyView(UUID id, UUID tenantId, String name, int version, String status,
                               String origin, String sourceCode, JsonNode config, String prompt,
                               String marketType, String instrument, String marginCurrency,
                               Instant createdAt, PaperProgress paper) {}

    public record StrategyTrade(UUID id, UUID botId, String mode, String pair, String side,
                                BigDecimal quantity, BigDecimal entryPrice, BigDecimal exitPrice,
                                String status, BigDecimal realizedPnl, Instant openedAt,
                                Instant closedAt) {}

    public record StrategyOrder(UUID id, UUID botId, String mode, String pair, String side,
                                String orderType, String status, BigDecimal price,
                                BigDecimal quantity, BigDecimal filledQty, String error,
                                Instant createdAt) {}

    public record LiveSkip(String action, JsonNode details, Instant createdAt) {}

    private final StrategyRepository strategies;
    private final BotRepository bots;
    private final PositionRepository positions;
    private final TradeOrderRepository tradeOrders;
    private final AuditLogRepository auditLogs;
    private final PaperStatsService paperStats;
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public StrategyController(StrategyRepository strategies, BotRepository bots,
                              PositionRepository positions, TradeOrderRepository tradeOrders,
                              AuditLogRepository auditLogs, PaperStatsService paperStats,
                              DatabaseClient db, ObjectMapper mapper, AppProperties props) {
        this.strategies = strategies;
        this.bots = bots;
        this.positions = positions;
        this.tradeOrders = tradeOrders;
        this.auditLogs = auditLogs;
        this.paperStats = paperStats;
        this.db = db;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping
    public Flux<StrategyView> list(@RequestParam(defaultValue = "FUTURES") String marketType,
                                   @RequestParam(defaultValue = "false") boolean includeCode) {
        // Lightweight list: skip source_code/prompt columns + one grouped paper-stats query.
        return CurrentUser.get().flatMapMany(p -> {
            String cols = includeCode
                    ? "id, tenant_id, name, version, status, origin, source_code, config, prompt, "
                    + "market_type, instrument, margin_currency, created_at"
                    : "id, tenant_id, name, version, status, origin, '' AS source_code, config, "
                    + "NULL AS prompt, market_type, instrument, margin_currency, created_at";
            return db.sql("""
                            SELECT %s
                            FROM strategies
                            WHERE tenant_id = :tid AND market_type = :mt
                              AND status NOT IN ('REJECTED', 'ARCHIVED')
                            ORDER BY created_at DESC
                            """.formatted(cols))
                    .bind("tid", p.tenantId())
                    .bind("mt", marketType)
                    .map((row, meta) -> new Strategy(
                            row.get("id", UUID.class),
                            row.get("tenant_id", UUID.class),
                            null,
                            row.get("name", String.class),
                            row.get("version", Integer.class) == null ? 1 : row.get("version", Integer.class),
                            null,
                            includeCode ? row.get("source_code", String.class) : "",
                            row.get("config", Json.class),
                            row.get("status", String.class),
                            row.get("origin", String.class),
                            null,
                            includeCode ? row.get("prompt", String.class) : null,
                            row.get("market_type", String.class),
                            row.get("instrument", String.class),
                            row.get("margin_currency", String.class),
                            row.get("created_at", Instant.class)))
                    .all()
                    .collectList()
                    .flatMapMany(list -> {
                        java.util.List<UUID> ids = list.stream().map(Strategy::id).toList();
                        return paperStats.forStrategies(ids)
                                .flatMapMany(statsMap -> Flux.fromIterable(list)
                                        .map(s -> toView(s, statsMap.getOrDefault(s.id(),
                                                        new PaperStatsService.PaperStats(
                                                                0, 0, BigDecimal.ZERO, 0)),
                                                includeCode)));
                    });
        });
    }

    @GetMapping("/{id}")
    public Mono<StrategyView> get(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMap(s -> withStats(s, true));
    }

    /** Paper + live position history for a strategy (via its bots). */
    @GetMapping("/{id}/trades")
    public Flux<StrategyTrade> trades(@PathVariable UUID id,
                                      @RequestParam(required = false) String mode) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMapMany(s -> bots.findByStrategyId(s.id())
                        .filter(b -> mode == null || mode.isBlank() || mode.equalsIgnoreCase(b.mode()))
                        .concatMap(b -> positions.findByBotIdOrderByOpenedAtDesc(b.id())
                                .map(pos -> toTrade(pos, b.mode()))))
                .sort(Comparator.comparing(StrategyTrade::openedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
    }

    /** Paper + live order history for a strategy (includes FAILED live attempts). */
    @GetMapping("/{id}/orders")
    public Flux<StrategyOrder> orders(@PathVariable UUID id,
                                      @RequestParam(required = false) String mode) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMapMany(s -> bots.findByStrategyId(s.id())
                        .filter(b -> mode == null || mode.isBlank() || mode.equalsIgnoreCase(b.mode()))
                        .concatMap(b -> tradeOrders.findByBotIdOrderByCreatedAtDesc(b.id())
                                .map(o -> toOrder(o, b.mode()))))
                .sort(Comparator.comparing(StrategyOrder::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
    }

    /** Why LIVE promotion was skipped (balance, max bots, bad backtest, etc.). */
    @GetMapping("/{id}/live-skips")
    public Flux<LiveSkip> liveSkips(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMapMany(s -> auditLogs.findByTenantIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                                s.tenantId(), "STRATEGY", s.id())
                        .filter(a -> a.action() != null && a.action().startsWith("AUTO_LIVE_SKIPPED"))
                        .take(20)
                        .map(a -> new LiveSkip(a.action(), readJson(a.details()), a.createdAt())));
    }

    private StrategyTrade toTrade(Position pos, String mode) {
        return new StrategyTrade(pos.id(), pos.botId(), mode, pos.pair(), pos.side(),
                pos.quantity(), pos.entryPrice(), pos.exitPrice(), pos.status(),
                pos.realizedPnl(), pos.openedAt(), pos.closedAt());
    }

    private StrategyOrder toOrder(TradeOrder o, String mode) {
        return new StrategyOrder(o.id(), o.botId(), mode, o.pair(), o.side(), o.orderType(),
                o.status(), o.price(), o.quantity(), o.filledQty(), o.error(), o.createdAt());
    }

    private Mono<StrategyView> withStats(Strategy s, boolean includeCode) {
        return paperStats.forStrategy(s.id()).map(st -> toView(s, st, includeCode));
    }

    private StrategyView toView(Strategy s, PaperStatsService.PaperStats st, boolean includeCode) {
        return new StrategyView(s.id(), s.tenantId(), s.name(), s.version(), s.status(),
                s.origin(),
                includeCode ? s.sourceCode() : "",
                readJson(s.config()),
                includeCode ? s.prompt() : null,
                s.marketType(), s.instrument(), s.marginCurrency(), s.createdAt(),
                new PaperProgress(st.closedTrades(), st.wins(), st.winRate(), st.totalPnl(),
                        props.pipeline().minPaperTrades(),
                        props.pipeline().winRateThreshold(),
                        st.openPositions()));
    }

    private JsonNode readJson(Json json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json.asString());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
