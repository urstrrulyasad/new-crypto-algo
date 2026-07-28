package com.cryptoalgo.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live prices: polls the CoinDCX ticker (generated once per second upstream)
 * and fans out through an in-JVM sink to SSE subscribers; also caches last prices
 * for the execution engine's paper fills.
 */
@Service
public class TickerService {

    public record Tick(String market, BigDecimal lastPrice, BigDecimal high, BigDecimal low,
                       BigDecimal volume, String change24h, long timestamp) {}

    private static final Logger log = LoggerFactory.getLogger(TickerService.class);

    private final CoinDcxPublicClient client;
    private final Map<String, Tick> lastTicks = new ConcurrentHashMap<>();
    private final Sinks.Many<Tick> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);

    public TickerService(CoinDcxPublicClient client) {
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${app.ticker-poll-ms:3000}")
    public void poll() {
        client.ticker().subscribe(this::ingest,
                e -> log.warn("Ticker poll failed: {}", e.getMessage()));
    }

    private void ingest(JsonNode array) {
        if (array == null || !array.isArray()) return;
        for (JsonNode t : array) {
            try {
                Tick tick = new Tick(
                        t.get("market").asText(),
                        new BigDecimal(t.get("last_price").asText()),
                        new BigDecimal(t.get("high").asText()),
                        new BigDecimal(t.get("low").asText()),
                        new BigDecimal(t.get("volume").asText()),
                        t.get("change_24_hour").asText(),
                        t.get("timestamp").asLong());
                lastTicks.put(tick.market(), tick);
                sink.tryEmitNext(tick);
            } catch (Exception ignored) {
                // individual malformed entries are skipped; upstream format is stable
            }
        }
    }

    public Flux<Tick> stream() {
        return sink.asFlux();
    }

    public Tick last(String market) {
        return lastTicks.get(market);
    }

    public Map<String, Tick> snapshot() {
        return Map.copyOf(lastTicks);
    }
}
