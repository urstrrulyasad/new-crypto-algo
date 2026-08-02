package com.cryptoalgo.backend.strategy;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cooldown so REJECTED/ARCHIVED instrument+style pairs are not
 * immediately regenerated (identical fallback thrash).
 */
@Component
public class StrategyRegenCooldown {

    private final ConcurrentHashMap<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public void block(String instrument, String style, Duration duration) {
        if (instrument == null || style == null || duration.isZero() || duration.isNegative()) return;
        Instant until = Instant.now().plus(duration);
        blockedUntil.merge(key(instrument, style), until,
                (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Seed a cooldown only if none is active (used for existing terminal rows on boot). */
    public void touchIfAbsent(String instrument, String style, Duration duration) {
        if (instrument == null || style == null) return;
        blockedUntil.putIfAbsent(key(instrument, style), Instant.now().plus(duration));
    }

    public boolean isBlocked(String instrument, String style) {
        Instant until = blockedUntil.get(key(instrument, style));
        if (until == null) return false;
        if (until.isAfter(Instant.now())) return true;
        blockedUntil.remove(key(instrument, style), until);
        return false;
    }

    private static String key(String instrument, String style) {
        return instrument + "|" + style;
    }
}
