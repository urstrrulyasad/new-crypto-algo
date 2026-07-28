package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** HTTP client for the Python strategy-engine sidecar (freqtrade wrapper). */
@Component
public class StrategyEngineClient {

    private final WebClient client;

    public StrategyEngineClient(WebClient.Builder builder, AppProperties props) {
        this.client = builder.clone()
                .baseUrl(props.strategyEngine().baseUrl())
                .defaultHeader("X-Internal-Token", props.internal().token())
                .build();
    }

    /** Generate a freqtrade strategy via the configured LLM provider. */
    public Mono<JsonNode> generateStrategy(Map<String, Object> request) {
        return post("/generate", request, Duration.ofMinutes(3));
    }

    /** Validate strategy code (AST allowlist + sandbox load + lookahead check). */
    public Mono<JsonNode> validateStrategy(Map<String, Object> request) {
        return post("/validate", request, Duration.ofMinutes(2));
    }

    /** Run a backtest; returns metrics + trades. Long-running. */
    public Mono<JsonNode> runBacktest(Map<String, Object> request) {
        return post("/backtest", request, Duration.ofMinutes(15));
    }

    private Mono<JsonNode> post(String path, Object body, Duration timeout) {
        return client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("Strategy engine call failed: " + e.getMessage()));
    }
}
