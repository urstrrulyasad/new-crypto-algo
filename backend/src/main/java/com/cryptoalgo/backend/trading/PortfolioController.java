package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.market.TickerService;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PositionRepository positions;
    private final TradeOrderRepository orders;
    private final TickerService ticker;

    public PortfolioController(PositionRepository positions, TradeOrderRepository orders,
                               TickerService ticker) {
        this.positions = positions;
        this.orders = orders;
        this.ticker = ticker;
    }

    @GetMapping("/positions")
    public Flux<Position> positions() {
        return CurrentUser.get()
                .flatMapMany(p -> positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(p.tenantId(), p.userId()));
    }

    @GetMapping("/orders")
    public Flux<TradeOrder> orders() {
        return CurrentUser.get()
                .flatMapMany(p -> orders.findByTenantIdAndUserIdOrderByCreatedAtDesc(p.tenantId(), p.userId()));
    }

    /** Aggregate PnL summary: realized (closed positions) + unrealized (mark-to-ticker). */
    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary() {
        return CurrentUser.get().flatMap(p ->
                positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(p.tenantId(), p.userId())
                        .collectList()
                        .map(this::summarize));
    }

    private Map<String, Object> summarize(List<Position> all) {
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        int open = 0, closed = 0, wins = 0;
        for (Position pos : all) {
            if ("CLOSED".equals(pos.status())) {
                closed++;
                if (pos.realizedPnl() != null) {
                    realized = realized.add(pos.realizedPnl());
                    if (pos.realizedPnl().signum() > 0) wins++;
                }
            } else {
                open++;
                TickerService.Tick tick = ticker.last(ExecutionService.pairToMarket(pos.pair()));
                if (tick != null) {
                    BigDecimal dir = "SHORT".equals(pos.side()) ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
                    unrealized = unrealized.add(tick.lastPrice().subtract(pos.entryPrice())
                            .multiply(pos.quantity()).multiply(dir).multiply(pos.leverage()));
                }
            }
        }
        return Map.of(
                "openPositions", open,
                "closedPositions", closed,
                "realizedPnl", realized,
                "unrealizedPnl", unrealized,
                "winRate", closed == 0 ? 0 : (double) wins / closed);
    }
}
