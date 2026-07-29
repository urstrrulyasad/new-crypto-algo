package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
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
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
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

    /** Replay recent CoinDCX candles into paper signals (catch-up for LIVE gate). */
    public Mono<JsonNode> paperCatchup(Map<String, Object> request) {
        return post("/paper-catchup", request, Duration.ofMinutes(10));
    }

    private Mono<JsonNode> post(String path, Object body, Duration timeout) {
        return client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable e) {
        if (e instanceof ApiException) return e;
        if (e instanceof WebClientResponseException w) {
            String detail = extractDetail(w);
            HttpStatus status = HttpStatus.resolve(w.getStatusCode().value());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            // Surface rate-limit / auth errors with their real status so the UI can react
            if (w.getStatusCode().value() == 429)
                return new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail);
            if (w.getStatusCode().value() == 401 || w.getStatusCode().value() == 403)
                return new ApiException(HttpStatus.UNAUTHORIZED, detail);
            return new ApiException(status.is4xxClientError() ? status : HttpStatus.BAD_GATEWAY, detail);
        }
        return ApiException.upstream("Strategy engine call failed: " + e.getMessage());
    }

    private String extractDetail(WebClientResponseException w) {
        String body = w.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) return w.getMessage();
        try {
            // FastAPI errors: {"detail": "..."} or {"detail":[{...}]}
            if (body.contains("\"detail\"")) {
                int start = body.indexOf("\"detail\"");
                String rest = body.substring(start + 8);
                int colon = rest.indexOf(':');
                String value = rest.substring(colon + 1).trim();
                if (value.startsWith("\"")) {
                    int end = value.indexOf('"', 1);
                    if (end > 0) return value.substring(1, end);
                }
                return value.replaceAll("[\\[\\]{}\"]", "").trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 400 ? body.substring(0, 400) : body;
    }
}
