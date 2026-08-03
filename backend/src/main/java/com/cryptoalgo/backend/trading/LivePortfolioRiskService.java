package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-layer LIVE portfolio risk beyond maxLiveBots (tenant-scoped).
 * Exposure measured in INR using each bot's stakeAmount per open LIVE position.
 */
@Service
public class LivePortfolioRiskService {

    public record ExposureSnapshot(
            long liveBotCount,
            BigDecimal openMarginInr,
            BigDecimal assetMarginInr,
            BigDecimal strategyMarginInr,
            long botOpenCount,
            long pendingOrders
    ) {}

    public record RiskDecision(boolean ok, String reason, ExposureSnapshot snap) {
        static RiskDecision fail(String reason, ExposureSnapshot snap) {
            return new RiskDecision(false, reason, snap);
        }

        static RiskDecision pass(ExposureSnapshot snap) {
            return new RiskDecision(true, null, snap);
        }
    }

    private final BotRepository bots;
    private final PositionRepository positions;
    private final TradeOrderRepository orders;
    private final AppProperties props;

    public LivePortfolioRiskService(BotRepository bots, PositionRepository positions,
                                    TradeOrderRepository orders, AppProperties props) {
        this.bots = bots;
        this.positions = positions;
        this.orders = orders;
        this.props = props;
    }

    public Mono<RiskDecision> evaluateEntry(Bot bot, String pair, BigDecimal additionalMarginInr,
                                            BigDecimal walletAvailableInr, BigDecimal reservedInr) {
        return bots.countByTenantIdAndMarketTypeAndModeAndStatus(
                        bot.tenantId(), "FUTURES", "LIVE", "RUNNING")
                .flatMap(liveCount -> Mono.zip(
                        openStakeForTenant(bot.tenantId()),
                        assetStake(bot.tenantId(), pair),
                        strategyStake(bot.tenantId(), bot.strategyId()),
                        positions.countByBotIdAndStatus(bot.id(), "OPEN"),
                        orders.findByTenantIdAndModeAndStatusIn(
                                        bot.tenantId(), "LIVE",
                                        Set.of("SUBMITTING", "PENDING_RECONCILE", "UNKNOWN", "PENDING"))
                                .count()
                ).map(t -> {
                    BigDecimal portfolio = t.getT1();
                    BigDecimal asset = t.getT2();
                    BigDecimal strategy = t.getT3();
                    long botOpen = t.getT4();
                    long pending = t.getT5();
                    ExposureSnapshot snap = new ExposureSnapshot(
                            liveCount, portfolio, asset, strategy, botOpen, pending);

                    if (Boolean.TRUE.equals(bot.killSwitch())) {
                        return RiskDecision.fail("bot kill switch active", snap);
                    }
                    if (liveCount > props.pipeline().maxLiveBots()) {
                        return RiskDecision.fail("maxLiveBots exceeded", snap);
                    }
                    // walletAvailableInr is free Cash (exchange already deducted locked margin).
                    // Cap new entries against free cash only — portfolio/openStake is informational.
                    BigDecimal wallet = nz(walletAvailableInr);
                    BigDecimal reserved = nz(reservedInr);
                    BigDecimal add = nz(additionalMarginInr);
                    BigDecimal walletCap = wallet.multiply(
                            BigDecimal.valueOf(props.pipeline().maxWalletPct()));
                    if (reserved.add(add).compareTo(walletCap) > 0) {
                        return RiskDecision.fail("portfolio wallet cap exceeded after fill", snap);
                    }
                    BigDecimal assetCap = wallet.multiply(
                            BigDecimal.valueOf(props.pipeline().maxAssetExposurePct()));
                    // asset/strategy open stakes are already locked on exchange; only gate the new add.
                    if (add.compareTo(assetCap) > 0) {
                        return RiskDecision.fail("asset exposure cap exceeded", snap);
                    }
                    BigDecimal stratCap = wallet.multiply(
                            BigDecimal.valueOf(props.pipeline().maxStrategyExposurePct()));
                    if (add.compareTo(stratCap) > 0) {
                        return RiskDecision.fail("strategy exposure cap exceeded", snap);
                    }
                    if (botOpen >= bot.maxOpenTrades()) {
                        return RiskDecision.fail("bot maxOpenTrades reached", snap);
                    }
                    // stakeAmount is preferred margin only — LiveFuturesSizingService may bump
                    // above it up to wallet/asset/strategy caps to clear CoinDCX min notional.
                    if (pending > 20) {
                        return RiskDecision.fail("too many pending LIVE orders", snap);
                    }
                    return RiskDecision.pass(snap);
                }));
    }

    public Mono<BigDecimal> openStakeForTenant(UUID tenantId) {
        return positions.findByTenantIdAndStatus(tenantId, "OPEN")
                .collectList()
                .flatMap(openPos -> bots.findByTenantIdAndMarketTypeAndModeAndStatus(
                                tenantId, "FUTURES", "LIVE", "RUNNING")
                        .collectMap(Bot::id, b -> b)
                        .map(botMap -> sumStake(openPos, botMap, null, null)));
    }

    private Mono<BigDecimal> assetStake(UUID tenantId, String pair) {
        return positions.findByTenantIdAndStatus(tenantId, "OPEN")
                .collectList()
                .flatMap(openPos -> bots.findByTenantIdAndMarketTypeAndModeAndStatus(
                                tenantId, "FUTURES", "LIVE", "RUNNING")
                        .collectMap(Bot::id, b -> b)
                        .map(botMap -> sumStake(openPos, botMap, pair, null)));
    }

    private Mono<BigDecimal> strategyStake(UUID tenantId, UUID strategyId) {
        return positions.findByTenantIdAndStatus(tenantId, "OPEN")
                .collectList()
                .flatMap(openPos -> bots.findByTenantIdAndMarketTypeAndModeAndStatus(
                                tenantId, "FUTURES", "LIVE", "RUNNING")
                        .collectMap(Bot::id, b -> b)
                        .map(botMap -> sumStake(openPos, botMap, null, strategyId)));
    }

    private static BigDecimal sumStake(java.util.List<Position> openPos,
                                       java.util.Map<UUID, Bot> botMap,
                                       String pairFilter, UUID strategyFilter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Position p : openPos) {
            Bot owner = botMap.get(p.botId());
            if (owner == null || owner.stakeAmount() == null) continue;
            if (pairFilter != null && !pairFilter.equals(p.pair())) continue;
            if (strategyFilter != null && !strategyFilter.equals(owner.strategyId())) continue;
            sum = sum.add(owner.stakeAmount());
        }
        return sum;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
