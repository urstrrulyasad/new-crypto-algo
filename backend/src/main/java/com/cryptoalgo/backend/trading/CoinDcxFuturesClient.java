package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.market.CandleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * CoinDCX INR-margined futures: public instruments/candles + signed orders/balances.
 * Candles are fetched on demand with no caching.
 */
@Component
public class CoinDcxFuturesClient {

    public record Instrument(String pair, BigDecimal minQuantity, BigDecimal minNotional,
                             BigDecimal maxLeverage, BigDecimal maxNotional) {}

    private final WebClient api;
    private final WebClient publicApi;
    private final ObjectMapper mapper;

    public CoinDcxFuturesClient(WebClient.Builder builder, AppProperties props, ObjectMapper mapper) {
        this.api = builder.clone().baseUrl(props.coindcx().apiBase()).build();
        this.publicApi = builder.clone().baseUrl(props.coindcx().publicBase()).build();
        this.mapper = mapper;
    }

    public Mono<List<String>> activeInstruments(String marginCurrency) {
        return api.get()
                .uri(uri -> uri.path("/exchange/v1/derivatives/futures/data/active_instruments")
                        .queryParam("margin_currency_short_name[]", marginCurrency)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .map(node -> {
                    List<String> out = new ArrayList<>();
                    if (node != null && node.isArray()) {
                        for (JsonNode n : node) out.add(n.asText());
                    }
                    return out;
                })
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures instruments failed: " + e.getMessage()));
    }

    public Mono<Instrument> instrumentDetails(String pair, String marginCurrency) {
        return api.get()
                .uri(uri -> uri.path("/exchange/v1/derivatives/futures/data/instrument")
                        .queryParam("pair", pair)
                        .queryParam("margin_currency_short_name", marginCurrency)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .map(body -> {
                    JsonNode i = body.path("instrument");
                    return new Instrument(
                            i.path("pair").asText(pair),
                            new BigDecimal(i.path("min_quantity").asText("0")),
                            new BigDecimal(i.path("min_notional").asText("0")),
                            new BigDecimal(i.path("max_leverage_long").asText("1")),
                            new BigDecimal(i.path("max_notional").asText("0")));
                })
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures instrument failed: " + e.getMessage()));
    }

    /**
     * Futures candlesticks. Resolution: 1 / 5 / 60 / 1D.
     * from/to are epoch seconds per CoinDCX docs.
     */
    public Flux<CandleService.Candle> candles(String pair, String resolution,
                                              Instant from, Instant to) {
        return publicApi.get()
                .uri(uri -> uri.path("/market_data/candlesticks")
                        .queryParam("pair", pair)
                        .queryParam("from", from.getEpochSecond())
                        .queryParam("to", to.getEpochSecond())
                        .queryParam("resolution", resolution)
                        .queryParam("pcode", "f")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .map(body -> {
                    List<CandleService.Candle> out = new ArrayList<>();
                    JsonNode data = body.path("data");
                    if (data.isArray()) {
                        for (JsonNode c : data) {
                            out.add(new CandleService.Candle(
                                    Instant.ofEpochMilli(c.path("time").asLong()),
                                    new BigDecimal(c.path("open").asText("0")),
                                    new BigDecimal(c.path("high").asText("0")),
                                    new BigDecimal(c.path("low").asText("0")),
                                    new BigDecimal(c.path("close").asText("0")),
                                    new BigDecimal(c.path("volume").asText("0"))));
                        }
                    }
                    out.sort(Comparator.comparing(CandleService.Candle::ts));
                    return out;
                })
                .flatMapMany(Flux::fromIterable)
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures candles failed: " + e.getMessage()));
    }

    public Mono<String> recentCsv(String pair, String resolution, int count) {
        long resMs = resolutionMillis(resolution);
        Instant to = Instant.now();
        Instant from = to.minusMillis(resMs * count);
        return candles(pair, resolution, from, to)
                .collectList()
                .map(list -> {
                    StringBuilder sb = new StringBuilder("date,open,high,low,close,volume\n");
                    for (CandleService.Candle c : list) {
                        sb.append(c.ts()).append(',').append(c.open().toPlainString()).append(',')
                                .append(c.high().toPlainString()).append(',')
                                .append(c.low().toPlainString()).append(',')
                                .append(c.close().toPlainString()).append(',')
                                .append(c.volume().toPlainString()).append('\n');
                    }
                    return sb.toString();
                });
    }

    public Mono<JsonNode> placeOrder(String apiKey, String apiSecret, String pair, String side,
                                     BigDecimal quantity, int leverage, String marginCurrency,
                                     BigDecimal takeProfit, BigDecimal stopLoss) {
        ObjectNode order = mapper.createObjectNode();
        order.put("side", side.toLowerCase());
        order.put("pair", pair);
        order.put("order_type", "market_order");
        order.put("total_quantity", quantity);
        order.put("leverage", leverage);
        order.put("notification", "no_notification");
        order.put("time_in_force", "good_till_cancel");
        order.put("hidden", false);
        order.put("post_only", false);
        ArrayNode margins = order.putArray("margin_currency_short_name");
        margins.add(marginCurrency);
        if (takeProfit != null) order.put("take_profit_price", takeProfit);
        if (stopLoss != null) order.put("stop_loss_price", stopLoss);

        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        body.set("order", order);
        return signedPost("/exchange/v1/derivatives/futures/orders/create", body, apiKey, apiSecret);
    }

    public record WalletBalance(String currency, BigDecimal available) {}

    /**
     * INR futures wallet only. No USDT fallback — LIVE margin is INR-only.
     */
    public Mono<WalletBalance> availableFuturesBalance(String apiKey, String apiSecret) {
        return availableInrBalance(apiKey, apiSecret)
                .map(inr -> new WalletBalance("INR", inr));
    }

    public Mono<BigDecimal> availableInrBalance(String apiKey, String apiSecret) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        return signedGet("/exchange/v1/derivatives/futures/wallets", body, apiKey, apiSecret)
                .map(resp -> {
                    if (resp != null && resp.isArray()) {
                        for (JsonNode w : resp) {
                            if (!"INR".equalsIgnoreCase(w.path("currency_short_name").asText())) continue;
                            BigDecimal bal = new BigDecimal(w.path("balance").asText("0"));
                            BigDecimal locked = new BigDecimal(w.path("locked_balance").asText("0"));
                            BigDecimal avail = bal.subtract(locked);
                            return avail.signum() < 0 ? BigDecimal.ZERO : avail;
                        }
                    }
                    return BigDecimal.ZERO;
                });
    }

    /**
     * Live CoinDCX futures mark/last via latest 1m candle close.
     * Empty if no candle — callers must fail closed (no invented price).
     */
    public Mono<BigDecimal> lastPrice(String pair) {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(180);
        return candles(pair, "1", from, to)
                .collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) return Mono.empty();
                    BigDecimal close = list.get(list.size() - 1).close();
                    if (close == null || close.signum() <= 0) return Mono.empty();
                    return Mono.just(close);
                });
    }

    /** Spot → futures wallet transfer. */
    public Mono<JsonNode> transferSpotToFutures(String apiKey, String apiSecret,
                                                String currency, BigDecimal amount) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        body.put("source_wallet_type", "spot");
        body.put("destination_wallet_type", "futures");
        body.put("currency_short_name", currency);
        body.put("amount", amount.doubleValue());
        return signedPost("/exchange/v1/wallets/transfer", body, apiKey, apiSecret);
    }

    private Mono<JsonNode> signedGet(String path, ObjectNode body, String apiKey, String apiSecret) {
        String json = body.toString();
        return api.method(org.springframework.http.HttpMethod.GET).uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-AUTH-APIKEY", apiKey)
                .header("X-AUTH-SIGNATURE", hmacSha256Hex(apiSecret, json))
                .bodyValue(json)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures API failed: " + e.getMessage()));
    }

    public static String toFuturesResolution(String timeframe) {
        return switch (timeframe) {
            case "1m", "1" -> "1";
            case "5m", "5" -> "5";
            case "1h", "60" -> "60";
            case "1d", "1D" -> "1D";
            case "15m", "30m" -> "5";
            case "4h", "2h" -> "60";
            default -> "60";
        };
    }

    public static String normalizeFuturesTimeframe(String tf) {
        return switch (tf == null ? "1h" : tf) {
            case "1m", "1" -> "1m";
            case "5m", "5" -> "5m";
            case "1h", "60" -> "1h";
            case "1d", "1D" -> "1d";
            case "15m", "30m", "3m" -> "5m";
            default -> "1h";
        };
    }

    private static long resolutionMillis(String resolution) {
        return switch (resolution) {
            case "1" -> 60_000L;
            case "5" -> 300_000L;
            case "60" -> 3_600_000L;
            case "1D" -> 86_400_000L;
            default -> 3_600_000L;
        };
    }

    private Mono<JsonNode> signedPost(String path, ObjectNode body, String apiKey, String apiSecret) {
        String json = body.toString();
        return api.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-AUTH-APIKEY", apiKey)
                .header("X-AUTH-SIGNATURE", hmacSha256Hex(apiSecret, json))
                .bodyValue(json)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures API failed: " + e.getMessage()));
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
