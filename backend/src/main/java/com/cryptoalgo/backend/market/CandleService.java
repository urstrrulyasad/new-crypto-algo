package com.cryptoalgo.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Candle store: persists CoinDCX candles into Postgres (shared market data)
 * and serves them for charts, backtest data export and paper-fill pricing.
 */
@Service
public class CandleService {

    public record Candle(Instant ts, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, BigDecimal volume) {}

    private static final Logger log = LoggerFactory.getLogger(CandleService.class);

    private final CoinDcxPublicClient client;
    private final DatabaseClient db;

    public CandleService(CoinDcxPublicClient client, DatabaseClient db) {
        this.client = client;
        this.db = db;
    }

    /** Fetch latest candles from CoinDCX and upsert them into the store. */
    public Mono<Integer> refresh(String pair, String timeframe, Long startMs, Long endMs) {
        return client.candles(pair, timeframe, startMs, endMs, 1000)
                .flatMap(json -> upsertAll(pair, timeframe, json));
    }

    private Mono<Integer> upsertAll(String pair, String timeframe, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) return Mono.just(0);
        List<Mono<Long>> inserts = new ArrayList<>();
        for (JsonNode c : array) {
            inserts.add(db.sql("""
                            INSERT INTO candles(pair, timeframe, ts, open, high, low, close, volume)
                            VALUES (:pair, :tf, :ts, :o, :h, :l, :c, :v)
                            ON CONFLICT (pair, timeframe, ts) DO UPDATE
                              SET open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
                                  close = EXCLUDED.close, volume = EXCLUDED.volume
                            """)
                    .bind("pair", pair)
                    .bind("tf", timeframe)
                    .bind("ts", Instant.ofEpochMilli(c.get("time").asLong()))
                    .bind("o", new BigDecimal(c.get("open").asText()))
                    .bind("h", new BigDecimal(c.get("high").asText()))
                    .bind("l", new BigDecimal(c.get("low").asText()))
                    .bind("c", new BigDecimal(c.get("close").asText()))
                    .bind("v", new BigDecimal(c.get("volume").asText()))
                    .fetch().rowsUpdated());
        }
        return Flux.concat(inserts).count().map(Long::intValue)
                .doOnNext(n -> log.debug("Upserted {} candles for {} {}", n, pair, timeframe));
    }

    /** Serve candles from the store; if empty, backfill once from CoinDCX first. */
    public Flux<Candle> get(String pair, String timeframe, Instant from, Instant to, int limit) {
        Flux<Candle> query = db.sql("""
                        SELECT ts, open, high, low, close, volume FROM candles
                        WHERE pair = :pair AND timeframe = :tf AND ts >= :from AND ts <= :to
                        ORDER BY ts ASC LIMIT :limit
                        """)
                .bind("pair", pair).bind("tf", timeframe)
                .bind("from", from).bind("to", to).bind("limit", limit)
                .map(this::mapRow).all();

        return query.collectList().flatMapMany(rows -> {
            if (!rows.isEmpty()) return Flux.fromIterable(rows);
            return refresh(pair, timeframe, from.toEpochMilli(), to.toEpochMilli())
                    .thenMany(query);
        });
    }

    /** Latest close price from the store (used for paper fills as a fallback to live ticker). */
    public Mono<BigDecimal> latestClose(String pair) {
        return db.sql("""
                        SELECT close FROM candles WHERE pair = :pair
                        ORDER BY ts DESC LIMIT 1
                        """)
                .bind("pair", pair)
                .map(row -> row.get("close", BigDecimal.class))
                .one();
    }

    private Candle mapRow(io.r2dbc.spi.Readable row) {
        return new Candle(
                row.get("ts", Instant.class),
                row.get("open", BigDecimal.class),
                row.get("high", BigDecimal.class),
                row.get("low", BigDecimal.class),
                row.get("close", BigDecimal.class),
                row.get("volume", BigDecimal.class));
    }

    public Mono<Map<String, Object>> backfill(String pair, String timeframe, Instant from, Instant to) {
        // CoinDCX returns max 1000 candles per call; walk the range forward.
        long stepMs = timeframeMillis(timeframe) * 1000L;
        List<long[]> windows = new ArrayList<>();
        for (long cursor = from.toEpochMilli(); cursor < to.toEpochMilli(); cursor += stepMs) {
            windows.add(new long[]{cursor, Math.min(cursor + stepMs, to.toEpochMilli())});
        }
        return Flux.fromIterable(windows)
                .concatMap(w -> refresh(pair, timeframe, w[0], w[1]))
                .reduce(0, Integer::sum)
                .map(total -> Map.of("pair", pair, "timeframe", timeframe, "candles", total));
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
