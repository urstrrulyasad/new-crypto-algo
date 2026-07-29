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
 * Watches every OPEN position against the live ticker.
 *
 * Paper: simulates the protective exits - closes at sl_price when price drops
 * through the stop, or at target_price when the target is reached. This makes
 * the paper win-rate gate honest (same exits a live bot would experience).
 *
 * Live: the SL leg rests on the exchange as a stop_limit order; on target the
 * guard cancels the SL leg and market-sells. If the exchange SL was hit, the
 * guard notices the filled SL order and closes the position record.
 */
@Service
public class PositionGuardService {

    private static final Logger log = LoggerFactory.getLogger(PositionGuardService.class);

    private final PositionRepository positions;
    private final BotRepository bots;
    private final TickerService ticker;
    private final ExecutionService execution;
    private final CoinDcxTradeClient trade;

    public PositionGuardService(PositionRepository positions, BotRepository bots,
                                TickerService ticker, ExecutionService execution,
                                CoinDcxTradeClient trade) {
        this.positions = positions;
        this.bots = bots;
        this.ticker = ticker;
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
        TickerService.Tick tick = ticker.last(ExecutionService.pairToMarket(pos.pair()));
        if (tick == null) return Mono.empty();
        BigDecimal price = tick.lastPrice();

        // Spot bots are long-only; SHORT positions only exist on futures (not live yet).
        boolean hitSl = pos.slPrice() != null && price.compareTo(pos.slPrice()) <= 0;
        boolean hitTarget = pos.targetPrice() != null && price.compareTo(pos.targetPrice()) >= 0;
        if (!hitSl && !hitTarget) return Mono.empty();

        return bots.findById(pos.botId()).flatMap(bot -> {
            if ("PAPER".equals(bot.mode())) {
                return hitSl
                        ? execution.closePaperPosition(bot, pos, pos.slPrice(), null, "STOP_LOSS")
                        : execution.closePaperPosition(bot, pos, pos.targetPrice(), null, "TARGET");
            }
            return hitTarget ? liveTarget(bot, pos, price) : liveSlCheck(bot, pos, price);
        });
    }

    /** Target reached on a live position: cancel the SL leg and market-sell. */
    private Mono<Void> liveTarget(Bot bot, Position pos, BigDecimal price) {
        log.info("Live position {} hit target at {}", pos.id(), price);
        return execution.liveExit(bot, pos, price, null, "TARGET");
    }

    /**
     * Price is at/below the stop: the exchange-side stop_limit should have
     * fired. Reconcile - if it filled, close the position at its average
     * price; if there is no SL leg (placement failed earlier), exit at market.
     */
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
                                // SL leg is gone: protect at market
                                return execution.liveExit(bot, pos, price, null, "STOP_LOSS");
                            }
                            return Mono.empty(); // stop triggered but not filled yet; wait
                        }));
    }
}
