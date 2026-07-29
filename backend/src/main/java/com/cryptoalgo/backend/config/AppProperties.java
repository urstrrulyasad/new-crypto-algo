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

    /** Autonomous pipeline knobs: paper-trade gate and auto-created bot defaults. */
    public record Pipeline(int minPaperTrades, double winRateThreshold, int backtestDays,
                           java.math.BigDecimal paperStake, int maxOpenTrades,
                           double defaultStoploss, double defaultTargetRoi) {}
}
