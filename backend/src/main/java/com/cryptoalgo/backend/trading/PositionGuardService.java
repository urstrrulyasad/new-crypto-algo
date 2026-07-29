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

import java.math.BigDecimal;

/**
 * Watches every OPEN position against live market price.
 *
 * FUTURES: CoinDCX futures last/mark (not spot ticker — symbols do not match).
 * SPOT: CoinDCX spot ticker.
 *
 * Paper: simulates protective exits at sl_price / target_price.
 * Live: SL leg on exchange; target cancels SL and market-exits.
 */
@Service
public class PositionGuardService {

    private static final Logger log = LoggerFactory.getLogger(PositionGuardService.class);

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
                .concatMap(pos -> check(pos).onErrorResume(e -> {
                    log.error("Guard check for position {} failed", pos.id(), e);
                    return Mono.empty();
                }))
                .subscribe(v -> {}, e -> log.error("Guard cycle failed", e));
    }

    private Mono<Void> check(Position pos) {
        if (pos.slPrice() == null && pos.targetPrice() == null) return Mono.empty();
        return bots.findById(pos.botId()).flatMap(bot -> markPrice(bot, pos.pair())
                .flatMap(price -> {
                    boolean isShort = "SHORT".equals(pos.side());
                    boolean hitSl = pos.slPrice() != null && (isShort
                            ? price.compareTo(pos.slPrice()) >= 0
                            : price.compareTo(pos.slPrice()) <= 0);
                    boolean hitTarget = pos.targetPrice() != null && (isShort
                            ? price.compareTo(pos.targetPrice()) <= 0
                            : price.compareTo(pos.targetPrice()) >= 0);
                    if (!hitSl && !hitTarget) return Mono.empty();

                    if ("PAPER".equals(bot.mode())) {
                        return hitSl
                                ? execution.closePaperPosition(bot, pos, pos.slPrice(), null, "STOP_LOSS")
                                : execution.closePaperPosition(bot, pos, pos.targetPrice(), null, "TARGET");
                    }
                    return hitTarget ? liveTarget(bot, pos, price) : liveSlCheck(bot, pos, price);
                }));
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
