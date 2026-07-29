package com.cryptoalgo.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live candle access: fetches CoinDCX candles on demand (nothing is persisted)
 * with a short in-memory TTL cache to absorb repeated chart requests.
 */
@Service
public class CandleService {

    public record Candle(Instant ts, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, BigDecimal volume) {}

    private record CacheEntry(List<Candle> candles, long expiresAtMs) {}

    private static final long TTL_MS = 30_000;
    private static final int MAX_ENTRIES = 500;

    private final CoinDcxPublicClient client;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CandleService(CoinDcxPublicClient client) {
        this.client = client;
    }

    /** Candles for a range, ascending by time, straight from CoinDCX (cached briefly). */
    public Flux<Candle> get(String pair, String timeframe, Instant from, Instant to, int limit) {
        String key = pair + "|" + timeframe + "|" + bucket(from) + "|" + bucket(to) + "|" + limit;
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAtMs() > now) return Flux.fromIterable(hit.candles());
        return client.candles(pair, timeframe, from.toEpochMilli(), to.toEpochMilli(),
                        Math.min(limit, 1000))
                .map(this::parse)
                .doOnNext(candles -> {
                    if (cache.size() >= MAX_ENTRIES) cache.clear();
                    cache.put(key, new CacheEntry(candles, System.currentTimeMillis() + TTL_MS));
                })
                .flatMapMany(Flux::fromIterable);
    }

    /** Recent candles as compact CSV (date,open,high,low,close,volume) for LLM prompts. */
    public Mono<String> recentCsv(String pair, String timeframe, int count) {
        long tfMs = timeframeMillis(timeframe);
        long now = System.currentTimeMillis();
        return get(pair, timeframe, Instant.ofEpochMilli(now - tfMs * count),
                Instant.ofEpochMilli(now), count)
                .collectList()
                .map(candles -> {
                    StringBuilder sb = new StringBuilder("date,open,high,low,close,volume\n");
                    for (Candle c : candles) {
                        sb.append(c.ts()).append(',').append(c.open().toPlainString()).append(',')
                                .append(c.high().toPlainString()).append(',')
                                .append(c.low().toPlainString()).append(',')
                                .append(c.close().toPlainString()).append(',')
                                .append(c.volume().toPlainString()).append('\n');
                    }
                    return sb.toString();
                });
    }

    private List<Candle> parse(JsonNode array) {
        List<Candle> out = new ArrayList<>();
        if (array == null || !array.isArray()) return out;
        for (JsonNode c : array) {
            out.add(new Candle(
                    Instant.ofEpochMilli(c.get("time").asLong()),
                    new BigDecimal(c.get("open").asText()),
                    new BigDecimal(c.get("high").asText()),
                    new BigDecimal(c.get("low").asText()),
                    new BigDecimal(c.get("close").asText()),
                    new BigDecimal(c.get("volume").asText())));
        }
        out.sort(Comparator.comparing(Candle::ts));
        return out;
    }

    /** Bucket range boundaries to 30s so near-identical chart requests share a cache slot. */
    private static long bucket(Instant t) {
        return t.toEpochMilli() / TTL_MS;
    }

    static long timeframeMillis(String tf) {
        long unit = switch (tf.substring(tf.length() - 1)) {
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            case "M" -> 2_592_000_000L;
            default -> throw new IllegalArgumentException("Bad timeframe " + tf);
        };
        return unit * Long.parseLong(tf.substring(0, tf.length() - 1));
    }
}
