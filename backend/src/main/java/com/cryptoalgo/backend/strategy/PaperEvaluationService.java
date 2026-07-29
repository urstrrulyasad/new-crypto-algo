package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The paper-trade gate. Periodically evaluates every PAPER_TRADING strategy:
 * once it has at least {@code pipeline.min-paper-trades} closed paper trades
 * with a win rate of at least {@code pipeline.win-rate-threshold}, the
 * strategy is auto-approved for live trading - its paper bots are stopped and
 * a LIVE bot is started with the owner's CoinDCX key (no human confirmation,
 * per product decision). Strategies below the bar simply keep paper trading.
 */
@Service
public class PaperEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PaperEvaluationService.class);

    private final StrategyRepository strategies;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final PaperStatsService paperStats;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;
    private final AppProperties props;

    public PaperEvaluationService(StrategyRepository strategies, BotRepository bots,
                                  ExchangeKeyRepository keys, PaperStatsService paperStats,
                                  R2dbcEntityTemplate template, AuditService audit,
                                  AppProperties props) {
        this.strategies = strategies;
        this.bots = bots;
        this.keys = keys;
        this.paperStats = paperStats;
        this.template = template;
        this.audit = audit;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.pipeline.evaluate-ms:60000}")
    public void evaluate() {
        strategies.findByStatus("PAPER_TRADING")
                .concatMap(s -> evaluateOne(s).onErrorResume(e -> {
                    log.error("Paper evaluation for strategy {} failed", s.id(), e);
                    return Mono.empty();
                }))
                .subscribe(v -> {}, e -> log.error("Paper evaluation cycle failed", e));
    }

    Mono<Void> evaluateOne(Strategy strategy) {
        return paperStats.forStrategy(strategy.id()).flatMap(stats -> {
            if (stats.closedTrades() < props.pipeline().minPaperTrades()) return Mono.empty();
            if (stats.winRate() < props.pipeline().winRateThreshold()) {
                // keep paper trading; re-evaluated as more trades close
                return Mono.empty();
            }
            log.info("Strategy {} passed the paper gate: {}/{} wins ({}%)", strategy.id(),
                    stats.wins(), stats.closedTrades(), Math.round(stats.winRate() * 100));
            return promote(strategy, stats);
        });
    }

    private Mono<Void> promote(Strategy strategy, PaperStatsService.PaperStats stats) {
        Mono<Void> stopPaperBots = bots.findByStrategyId(strategy.id())
                .filter(b -> "PAPER".equals(b.mode()) && "RUNNING".equals(b.status()))
                .flatMap(b -> bots.save(withStatus(b, "STOPPED")))
                .then();

        Mono<Void> markApproved = strategies.save(new Strategy(strategy.id(), strategy.tenantId(),
                strategy.userId(), strategy.name(), strategy.version(), strategy.parentId(),
                strategy.sourceCode(), strategy.config(), "LIVE_APPROVED", strategy.origin(),
                strategy.aiProviderId(), strategy.prompt(), strategy.createdAt())).then();

        Mono<Void> startLive = bots.findByStrategyId(strategy.id())
                .filter(b -> "PAPER".equals(b.mode()))
                .next()
                .flatMap(paperBot -> keys.findByTenantIdAndUserId(strategy.tenantId(), strategy.userId())
                        .filter(k -> "ACTIVE".equals(k.status()))
                        .next()
                        .flatMap(key -> {
                            Bot live = new Bot(UUID.randomUUID(), strategy.tenantId(),
                                    strategy.userId(), strategy.id(), key.id(),
                                    strategy.name() + " · live", "LIVE", paperBot.marketType(),
                                    paperBot.pairs(), paperBot.stakeCurrency(), paperBot.stakeAmount(),
                                    paperBot.maxOpenTrades(), paperBot.leverage(), "RUNNING", false,
                                    Instant.now(), Instant.now());
                            return template.insert(live)
                                    .then(audit.record(strategy.tenantId(), strategy.userId(),
                                            "STRATEGY_AUTO_PROMOTED_LIVE", "STRATEGY", strategy.id(),
                                            Map.of("winRate", String.valueOf(stats.winRate()),
                                                    "closedTrades", String.valueOf(stats.closedTrades()),
                                                    "liveBotId", live.id().toString())));
                        })
                        .switchIfEmpty(audit.record(strategy.tenantId(), strategy.userId(),
                                "AUTO_LIVE_SKIPPED_NO_EXCHANGE_KEY", "STRATEGY", strategy.id(),
                                Map.of("reason", "user has no active CoinDCX key")).then(Mono.empty())))
                .then();

        return stopPaperBots.then(markApproved).then(startLive);
    }

    private Bot withStatus(Bot b, String status) {
        return new Bot(b.id(), b.tenantId(), b.userId(), b.strategyId(), b.exchangeKeyId(), b.name(),
                b.mode(), b.marketType(), b.pairs(), b.stakeCurrency(), b.stakeAmount(),
                b.maxOpenTrades(), b.leverage(), status, b.killSwitch(), b.createdAt(), Instant.now());
    }
}
