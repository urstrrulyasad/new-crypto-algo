package com.cryptoalgo.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;

/** Public market data for charts: candles, markets, live ticker stream (SSE). */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final CandleService candles;
    private final TickerService ticker;
    private final CoinDcxPublicClient client;

    public MarketController(CandleService candles, TickerService ticker, CoinDcxPublicClient client) {
        this.candles = candles;
        this.ticker = ticker;
        this.client = client;
    }

    @GetMapping("/candles")
    public Flux<CandleService.Candle> candles(@RequestParam String pair,
                                              @RequestParam(defaultValue = "1h") String timeframe,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                              @RequestParam(defaultValue = "2000") int limit) {
        return candles.get(pair, timeframe, from, to, Math.min(limit, 10_000));
    }

    @GetMapping("/markets")
    public Mono<JsonNode> markets() {
        return client.marketDetails();
    }

    @GetMapping("/ticker")
    public Collection<TickerService.Tick> tickerSnapshot() {
        return ticker.snapshot().values();
    }

    @GetMapping(value = "/ticker/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TickerService.Tick> tickerStream(@RequestParam(required = false) String markets) {
        Flux<TickerService.Tick> stream = ticker.stream();
        if (markets == null || markets.isBlank()) return stream;
        var wanted = java.util.Set.of(markets.split(","));
        return stream.filter(t -> wanted.contains(t.market()));
    }
}
