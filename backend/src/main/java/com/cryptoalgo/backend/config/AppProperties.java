package com.cryptoalgo.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Crypto crypto,
        Internal internal,
        Coindcx coindcx,
        StrategyEngine strategyEngine,
        Bootstrap bootstrap,
        Cors cors,
        Pipeline pipeline
) {
    public record Jwt(String secret, long accessTtlMinutes, long refreshTtlDays) {}
    public record Crypto(String masterKey) {}
    public record Internal(String token) {}
    public record Coindcx(String apiBase, String publicBase) {}
    public record StrategyEngine(String baseUrl) {}
    public record Bootstrap(String email, String password) {}
    public record Cors(String allowedOrigins) {}

    public record Pipeline(
            int minPaperTrades,
            double winRateThreshold,
            /** Paper PnL as % of paper stake required for LIVE (OR with win-rate). */
            double minPaperProfitPct,
            int backtestDays,
            java.math.BigDecimal paperStake,
            int maxOpenTrades,
            double defaultStoploss,
            double defaultTargetRoi,
            int futuresLeverage,
            int maxInstruments,
            int minBacktestTrades,
            double maxBacktestDrawdownPct,
            double minBacktestWinRate,
            double minBacktestProfitPct,
            double minBacktestProfitFactor,
            int maxLiveBots,
            double maxWalletPct,
            /** Max share of wallet margin allowed on one futures pair (tenant policy). */
            double maxAssetExposurePct,
            /** Max share of wallet margin allowed for one strategy (tenant policy). */
            double maxStrategyExposurePct,
            /**
             * Conservative SL-vs-leverage buffer when CoinDCX does not return liquidation
             * metadata. Labelled approx only — never treated as exchange liquidation price.
             */
            double liquidationSafetyBuffer,
            long autoGenMs,
            /** Active (non-REJECTED/ARCHIVED) strategies allowed per instrument. */
            int maxStrategiesPerInstrument,
            /**
             * After REJECTED/ARCHIVED, do not regenerate the same instrument+style
             * until this many hours pass (stops identical fallback thrash).
             */
            int regenCooldownHours
    ) {}
}
