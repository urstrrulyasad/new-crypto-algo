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

/**
 * Live candle access: fetches CoinDCX candles on demand. Nothing is persisted
 * and nothing is cached in memory — every call hits the exchange.
 */
@Service
public class CandleService {

    public record Candle(Instant ts, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, BigDecimal volume) {}

    private final CoinDcxPublicClient client;

    public CandleService(CoinDcxPublicClient client) {
        this.client = client;
    }

    public Flux<Candle> get(String pair, String timeframe, Instant from, Instant to, int limit) {
        return client.candles(pair, timeframe, from.toEpochMilli(), to.toEpochMilli(),
                        Math.min(limit, 1000))
                .map(this::parse)
                .flatMapMany(Flux::fromIterable);
    }

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
