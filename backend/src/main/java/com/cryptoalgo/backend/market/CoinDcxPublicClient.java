package com.cryptoalgo.backend.market;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/** CoinDCX public (unauthenticated) endpoints: ticker, market details, candles. */
@Component
public class CoinDcxPublicClient {

    private final WebClient api;
    private final WebClient publicApi;

    public CoinDcxPublicClient(WebClient.Builder builder, AppProperties props) {
        this.api = builder.clone().baseUrl(props.coindcx().apiBase()).build();
        this.publicApi = builder.clone().baseUrl(props.coindcx().publicBase()).build();
    }

    public Mono<JsonNode> ticker() {
        return get(api, u -> u.path("/exchange/ticker").build());
    }

    public Mono<JsonNode> marketDetails() {
        return get(api, u -> u.path("/exchange/v1/markets_details").build());
    }

    /**
     * Candles for a pair (e.g. B-BTC_USDT), interval 1m..1M.
     * Returns newest-first array of {open,high,low,close,volume,time}.
     */
    public Mono<JsonNode> candles(String pair, String interval, Long startTimeMs, Long endTimeMs, Integer limit) {
        return get(publicApi, u -> {
            UriBuilder b = u.path("/market_data/candles")
                    .queryParam("pair", pair)
                    .queryParam("interval", interval);
            if (startTimeMs != null) b = b.queryParam("startTime", startTimeMs);
            if (endTimeMs != null) b = b.queryParam("endTime", endTimeMs);
            if (limit != null) b = b.queryParam("limit", limit);
            return b.build();
        });
    }

    private Mono<JsonNode> get(WebClient client, java.util.function.Function<UriBuilder, java.net.URI> uri) {
        return client.get().uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX request failed: " + e.getMessage()));
    }
}
