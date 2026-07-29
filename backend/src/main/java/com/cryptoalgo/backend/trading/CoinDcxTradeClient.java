package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Authenticated CoinDCX REST client. Requests are signed per user:
 * HMAC-SHA256 hex signature of the compact JSON body with
 * X-AUTH-APIKEY / X-AUTH-SIGNATURE headers.
 */
@Component
public class CoinDcxTradeClient {

    private final WebClient client;
    private final ObjectMapper mapper;

    public CoinDcxTradeClient(WebClient.Builder builder, AppProperties props, ObjectMapper mapper) {
        this.client = builder.clone().baseUrl(props.coindcx().apiBase()).build();
        this.mapper = mapper;
    }

    public Mono<JsonNode> placeMarketOrder(String apiKey, String apiSecret, String market,
                                           String side, BigDecimal totalQuantity, String clientOrderId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("side", side.toLowerCase());
        body.put("order_type", "market_order");
        body.put("market", market);
        body.put("total_quantity", totalQuantity);
        body.put("timestamp", System.currentTimeMillis());
        body.put("client_order_id", clientOrderId);
        return signedPost("/exchange/v1/orders/create", body, apiKey, apiSecret);
    }

    /**
     * Stop-limit order (used as the exchange-side stop-loss leg): once
     * stop_price triggers, a limit order at price_per_unit is placed.
     */
    public Mono<JsonNode> placeStopLimitOrder(String apiKey, String apiSecret, String market,
                                              String side, BigDecimal totalQuantity,
                                              BigDecimal stopPrice, BigDecimal limitPrice,
                                              String clientOrderId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("side", side.toLowerCase());
        body.put("order_type", "stop_limit");
        body.put("market", market);
        body.put("total_quantity", totalQuantity);
        body.put("stop_price", stopPrice);
        body.put("price_per_unit", limitPrice);
        body.put("timestamp", System.currentTimeMillis());
        body.put("client_order_id", clientOrderId);
        return signedPost("/exchange/v1/orders/create", body, apiKey, apiSecret);
    }

    public Mono<JsonNode> cancelOrder(String apiKey, String apiSecret, String exchangeOrderId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("id", exchangeOrderId);
        body.put("timestamp", System.currentTimeMillis());
        return signedPost("/exchange/v1/orders/cancel", body, apiKey, apiSecret);
    }

    public Mono<JsonNode> orderStatus(String apiKey, String apiSecret, String exchangeOrderId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("id", exchangeOrderId);
        body.put("timestamp", System.currentTimeMillis());
        return signedPost("/exchange/v1/orders/status", body, apiKey, apiSecret);
    }

    public Mono<JsonNode> balances(String apiKey, String apiSecret) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        return signedPost("/exchange/v1/users/balances", body, apiKey, apiSecret);
    }

    private Mono<JsonNode> signedPost(String path, ObjectNode body, String apiKey, String apiSecret) {
        String json = body.toString();
        return client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-AUTH-APIKEY", apiKey)
                .header("X-AUTH-SIGNATURE", hmacSha256Hex(apiSecret, json))
                .bodyValue(json)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX order API failed: " + e.getMessage()));
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
