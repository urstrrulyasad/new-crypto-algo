package com.cryptoalgo.backend.market;

import com.cryptoalgo.backend.trading.CoinDcxFuturesClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Public market data for charts: candles, markets, live ticker stream (SSE). */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final CandleService candles;
    private final TickerService ticker;
    private final CoinDcxPublicClient client;
    private final CoinDcxFuturesClient futures;

    public MarketController(CandleService candles, TickerService ticker, CoinDcxPublicClient client,
                            CoinDcxFuturesClient futures) {
        this.candles = candles;
        this.ticker = ticker;
        this.client = client;
        this.futures = futures;
    }

    @GetMapping("/candles")
    public Flux<CandleService.Candle> candles(@RequestParam String pair,
                                              @RequestParam(defaultValue = "1h") String timeframe,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                              @RequestParam(defaultValue = "2000") int limit,
                                              @RequestParam(defaultValue = "SPOT") String marketType) {
        if ("FUTURES".equalsIgnoreCase(marketType)) {
            String resolution = CoinDcxFuturesClient.toFuturesResolution(timeframe);
            // Prefer newest candles when over limit (chunked fetch can exceed default 2000).
            return futures.candles(pair, resolution, from, to).takeLast(Math.min(limit, 10_000));
        }
        return candles.get(pair, timeframe, from, to, Math.min(limit, 10_000));
    }

    @GetMapping("/markets")
    public Mono<JsonNode> markets() {
        return client.marketDetails();
    }

    /** Active INR-margined futures instruments (on demand, no cache). */
    @GetMapping("/futures/instruments")
    public Mono<Map<String, Object>> futuresInstruments(
            @RequestParam(defaultValue = "INR") String marginCurrency) {
        return futures.activeInstruments(marginCurrency)
                .map(list -> Map.of(
                        "marginCurrency", marginCurrency,
                        "count", list.size(),
                        "instruments", list));
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
