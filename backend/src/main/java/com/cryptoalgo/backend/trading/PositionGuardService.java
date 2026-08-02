package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.market.TickerService;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Watches every OPEN position against live market price.
 *
 * FUTURES: CoinDCX futures last/mark (not spot ticker — symbols do not match).
 * SPOT: CoinDCX spot ticker.
 *
 * Paper: simulates protective exits at sl_price / target_price, plus a max-hold
 * safety exit so positions cannot sit forever between SL and target.
 * Live: SL leg on exchange; target cancels SL and market-exits.
 */
@Service
public class PositionGuardService {

    private static final Logger log = LoggerFactory.getLogger(PositionGuardService.class);

    /** Paper positions older than this are force-closed at mark (signal starvation). */
    private static final Duration PAPER_MAX_HOLD = Duration.ofHours(2);

    private final PositionRepository positions;
    private final BotRepository bots;
    private final TickerService ticker;
    private final CoinDcxFuturesClient futures;
    private final ExecutionService execution;
    private final CoinDcxTradeClient trade;

    public PositionGuardService(PositionRepository positions, BotRepository bots,
                                TickerService ticker, CoinDcxFuturesClient futures,
                                ExecutionService execution, CoinDcxTradeClient trade) {
        this.positions = positions;
        this.bots = bots;
        this.ticker = ticker;
        this.futures = futures;
        this.execution = execution;
        this.trade = trade;
    }

    @Scheduled(fixedDelayString = "${app.guard-ms:5000}")
    public void guard() {
        positions.findByStatus("OPEN")
                .collectList()
                .flatMapMany(list -> {
                    if (!list.isEmpty()) {
                        log.info("Guard scanning {} OPEN position(s)", list.size());
                    }
                    return reactor.core.publisher.Flux.fromIterable(list);
                })
                .concatMap(pos -> check(pos).onErrorResume(e -> {
                    log.error("Guard check for position {} failed: {}", pos.id(), e.toString());
                    return Mono.empty();
                }))
                .then()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {}, e -> log.error("Guard cycle failed", e));
    }

    private Mono<Void> check(Position pos) {
        return bots.findById(pos.botId())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Guard skip {} — bot {} missing", pos.id(), pos.botId());
                    return Mono.empty();
                }))
                .flatMap(bot -> markPrice(bot, pos.pair())
                .flatMap(price -> {
                    boolean isShort = "SHORT".equals(pos.side());
                    boolean hitSl = pos.slPrice() != null && (isShort
                            ? price.compareTo(pos.slPrice()) >= 0
                            : price.compareTo(pos.slPrice()) <= 0);
                    boolean hitTarget = pos.targetPrice() != null && (isShort
                            ? price.compareTo(pos.targetPrice()) <= 0
                            : price.compareTo(pos.targetPrice()) >= 0);
                    boolean maxHold = "PAPER".equals(bot.mode())
                            && pos.openedAt() != null
                            && pos.openedAt().isBefore(Instant.now().minus(PAPER_MAX_HOLD));

                    if (!hitSl && !hitTarget && !maxHold) {
                        log.debug("Guard hold {} {} mark={} sl={} tgt={} opened={} mode={}",
                                pos.pair(), pos.side(), price, pos.slPrice(), pos.targetPrice(),
                                pos.openedAt(), bot.mode());
                        return Mono.empty();
                    }

                    if ("PAPER".equals(bot.mode())) {
                        String reason = hitSl ? "STOP_LOSS" : hitTarget ? "TARGET" : "MAX_HOLD";
                        BigDecimal exitPx = hitSl && pos.slPrice() != null ? pos.slPrice()
                                : hitTarget && pos.targetPrice() != null ? pos.targetPrice()
                                : price;
                        log.info("Paper guard {} {} {} entry={} mark={} sl={} target={} ageHours={}",
                                reason, pos.pair(), pos.side(), pos.entryPrice(), price,
                                pos.slPrice(), pos.targetPrice(),
                                pos.openedAt() == null ? -1
                                        : Duration.between(pos.openedAt(), Instant.now()).toHours());
                        return execution.closePaperPosition(bot, pos, exitPx, null, reason);
                    }
                    if (maxHold) return Mono.empty();
                    return hitTarget ? liveTarget(bot, pos, price) : liveSlCheck(bot, pos, price);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // No mark price — still force-close ancient paper so catchup can refill.
                    if (!"PAPER".equals(bot.mode()) || pos.openedAt() == null) return Mono.empty();
                    if (!pos.openedAt().isBefore(Instant.now().minus(PAPER_MAX_HOLD))) return Mono.empty();
                    log.warn("Paper guard MAX_HOLD without mark for {} open since {}",
                            pos.pair(), pos.openedAt());
                    return execution.closePaperPosition(bot, pos,
                            pos.entryPrice(), null, "MAX_HOLD");
                })));
    }

    private Mono<BigDecimal> markPrice(Bot bot, String pair) {
        if ("FUTURES".equals(bot.marketType())) {
            return futures.lastPrice(pair);
        }
        TickerService.Tick tick = ticker.last(ExecutionService.pairToMarket(pair));
        return tick == null ? Mono.empty() : Mono.just(tick.lastPrice());
    }

    private Mono<Void> liveTarget(Bot bot, Position pos, BigDecimal price) {
        log.info("Live position {} hit target at {}", pos.id(), price);
        return execution.liveExit(bot, pos, price, null, "TARGET");
    }

    private Mono<Void> liveSlCheck(Bot bot, Position pos, BigDecimal price) {
        if (pos.slOrderId() == null) {
            log.warn("Live position {} has no SL leg; exiting at market {}", pos.id(), price);
            return execution.liveExit(bot, pos, price, null, "STOP_LOSS");
        }
        return execution.withKey(bot).flatMap(key ->
                trade.orderStatus(key.apiKey(), key.apiSecret(), pos.slOrderId())
                        .flatMap(status -> {
                            String s = status.path("status").asText("open").toLowerCase();
                            if (s.contains("filled") && !s.contains("partially")) {
                                BigDecimal avg = new BigDecimal(
                                        status.path("avg_price").asText(pos.slPrice().toPlainString()));
                                log.info("Live position {} stopped out at {}", pos.id(), avg);
                                return execution.closePosition(pos, avg);
                            }
                            if (s.contains("cancelled") || s.contains("rejected")) {
                                return execution.liveExit(bot, pos, price, null, "STOP_LOSS");
                            }
                            return Mono.empty();
                        }));
    }
}
