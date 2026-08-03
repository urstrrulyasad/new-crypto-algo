package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.market.CandleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CoinDcxFuturesClient.class);

    public record Instrument(
            String pair,
            BigDecimal minQuantity,
            BigDecimal minNotional,
            BigDecimal maxLeverage,
            BigDecimal maxNotional,
            BigDecimal quantityStep,
            BigDecimal priceTick,
            BigDecimal maintenanceMarginRate
    ) {
        /** Required fields for fail-closed LIVE sizing — all from live CoinDCX payload. */
        public boolean hasRequiredSizingFields() {
            return minQuantity != null && minQuantity.signum() > 0
                    && minNotional != null && minNotional.signum() > 0
                    && maxLeverage != null && maxLeverage.signum() > 0;
        }
    }

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
                    if (i == null || i.isMissingNode() || i.isNull()) {
                        throw ApiException.upstream("CoinDCX instrument payload missing for " + pair);
                    }
                    // CoinDCX often leaves max_leverage_long/short null and only exposes
                    // dynamic_position_leverage_details keys (e.g. 2,3,5,10,20,30).
                    BigDecimal maxLev = firstDecimalOrNull(i, "max_leverage_long", "max_leverage_short",
                            "max_leverage");
                    if (maxLev == null || maxLev.signum() <= 0) {
                        maxLev = maxLeverageFromDynamic(i.path("dynamic_position_leverage_details"));
                    }
                    BigDecimal minQty = firstDecimalOrNull(i, "min_quantity", "min_trade_size");
                    BigDecimal qtyStep = firstDecimalOrNull(i, "quantity_increment", "qty_step",
                            "step_size", "unit_contract_value");
                    return new Instrument(
                            i.path("pair").asText(pair),
                            minQty,
                            decimalOrNull(i, "min_notional"),
                            maxLev,
                            decimalOrNull(i, "max_notional"),
                            qtyStep,
                            firstDecimalOrNull(i, "price_increment", "tick_size", "price_tick"),
                            firstDecimalOrNull(i, "maintenance_margin_rate", "maint_margin_rate",
                                    "maintenance_margin"));
                })
                .onErrorMap(e -> !(e instanceof ApiException),
                        e -> ApiException.upstream("CoinDCX futures instrument failed: " + e.getMessage()));
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()
                || node.get(field).asText("").isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(node.get(field).asText());
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal firstDecimalOrNull(JsonNode node, String... fields) {
        for (String f : fields) {
            BigDecimal v = decimalOrNull(node, f);
            if (v != null) return v;
        }
        return null;
    }

    /** Highest leverage tier key from CoinDCX dynamic_position_leverage_details. */
    private static BigDecimal maxLeverageFromDynamic(JsonNode dyn) {
        if (dyn == null || !dyn.isObject() || dyn.isEmpty()) return null;
        BigDecimal max = null;
        var names = dyn.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            try {
                BigDecimal lev = new BigDecimal(key.trim());
                if (lev.signum() > 0 && (max == null || lev.compareTo(max) > 0)) {
                    max = lev;
                }
            } catch (Exception ignored) {
                // skip non-numeric keys
            }
        }
        return max;
    }

    /**
     * Keep each public candlestick response under WebClient's 256KB buffer.
     * 1m is densest (~480 bars/8h); 5m fits ~2d; coarser TFs use 3d.
     */
    private static Duration candleChunk(String resolution) {
        return switch (resolution) {
            case "1" -> Duration.ofHours(8);
            case "5" -> Duration.ofDays(2);
            default -> Duration.ofDays(3);
        };
    }

    /**
     * Futures candlesticks. Resolution: 1 / 5 / 60 / 1D.
     * from/to are epoch seconds per CoinDCX docs.
     * Long ranges are fetched in resolution-sized chunks (1m/5m can exceed 256KB).
     */
    public Flux<CandleService.Candle> candles(String pair, String resolution,
                                              Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return Flux.empty();
        }
        Duration chunk = candleChunk(resolution);
        List<Instant[]> windows = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(to)) {
            Instant end = cursor.plus(chunk);
            if (end.isAfter(to)) end = to;
            windows.add(new Instant[]{cursor, end});
            cursor = end;
        }
        return Flux.fromIterable(windows)
                .concatMap(w -> fetchCandleChunk(pair, resolution, w[0], w[1]))
                .distinct(CandleService.Candle::ts)
                .sort(Comparator.comparing(CandleService.Candle::ts));
    }

    private Flux<CandleService.Candle> fetchCandleChunk(String pair, String resolution,
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

    /**
     * Spot USDT→INR rate for sizing INR-margined futures (notional is USDT-quoted).
     */
    public Mono<BigDecimal> usdtInrRate() {
        return api.get().uri("/exchange/ticker")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .map(node -> {
                    if (node != null && node.isArray()) {
                        for (JsonNode t : node) {
                            if ("USDTINR".equalsIgnoreCase(t.path("market").asText())) {
                                return new BigDecimal(t.path("last_price").asText("0"));
                            }
                        }
                    }
                    return BigDecimal.ZERO;
                })
                .filter(r -> r.signum() > 0)
                .switchIfEmpty(Mono.error(ApiException.upstream("USDTINR rate unavailable")));
    }

    public Mono<JsonNode> placeOrder(String apiKey, String apiSecret, String pair, String side,
                                     BigDecimal quantity, int leverage, String marginCurrency,
                                     BigDecimal takeProfit, BigDecimal stopLoss) {
        return placeOrder(apiKey, apiSecret, pair, side, quantity, leverage, marginCurrency,
                takeProfit, stopLoss, null);
    }

    public Mono<JsonNode> placeOrder(String apiKey, String apiSecret, String pair, String side,
                                     BigDecimal quantity, int leverage, String marginCurrency,
                                     BigDecimal takeProfit, BigDecimal stopLoss, String clientOrderId) {
        // CoinDCX: do NOT send time_in_force on market orders (docs: causes 400).
        // Quantity scale must come from live instrument step when available — callers round first.
        if (quantity == null || quantity.signum() <= 0) {
            return Mono.error(ApiException.badRequest("futures qty must be positive"));
        }
        ObjectNode order = mapper.createObjectNode();
        order.put("side", side.toLowerCase());
        order.put("pair", pair);
        order.put("order_type", "market_order");
        // Prefer integer when scale is 0 (common CoinDCX INR contracts); else plain decimal.
        if (quantity.stripTrailingZeros().scale() <= 0) {
            order.put("total_quantity", quantity.longValueExact());
        } else {
            order.put("total_quantity", quantity.doubleValue());
        }
        order.put("leverage", leverage);
        order.put("notification", "no_notification");
        order.put("hidden", false);
        order.put("post_only", false);
        order.put("margin_currency_short_name", marginCurrency);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            order.put("client_order_id", clientOrderId);
        }
        if (takeProfit != null) order.put("take_profit_price", takeProfit);
        if (stopLoss != null) order.put("stop_loss_price", stopLoss);

        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        body.set("order", order);
        return signedPost("/exchange/v1/derivatives/futures/orders/create", body, apiKey, apiSecret)
                .doOnNext(resp -> log.info("CoinDCX futures create response clientId={}: {}",
                        clientOrderId, resp));
    }

    /** Look up futures orders and find one matching client_order_id (for ambiguous timeout reconcile). */
    public Mono<JsonNode> findOrderByClientOrderId(String apiKey, String apiSecret,
                                                   String pair, String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return Mono.empty();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        body.put("pair", pair);
        body.put("page", "1");
        body.put("size", "50");
        // Docs List Orders / Positions: margin_currency_short_name is an array.
        body.putArray("margin_currency_short_name").add("INR");
        return signedPost("/exchange/v1/derivatives/futures/orders", body, apiKey, apiSecret)
                .flatMap(resp -> {
                    // Docs: list/create often return a bare JSON array.
                    JsonNode list = resp != null && resp.isArray() ? resp : resp.path("orders");
                    if (!list.isArray()) list = resp.path("data");
                    if (!list.isArray()) {
                        return Mono.empty();
                    }
                    for (JsonNode o : list) {
                        String cid = o.path("client_order_id").asText("");
                        if (clientOrderId.equals(cid)) {
                            return Mono.just(o);
                        }
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("findOrderByClientOrderId failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Open/closed futures positions. CoinDCX returns a JSON array.
     * marginCurrency: INR or USDT.
     */
    public Mono<JsonNode> listPositions(String apiKey, String apiSecret, String marginCurrency) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        body.put("page", "1");
        body.put("size", "50");
        var margins = body.putArray("margin_currency_short_name");
        margins.add(marginCurrency == null || marginCurrency.isBlank() ? "INR" : marginCurrency);
        return signedPost("/exchange/v1/derivatives/futures/positions", body, apiKey, apiSecret);
    }

    public record WalletBalance(String currency, BigDecimal available) {}

    /**
     * CoinDCX futures INR wallet fields.
     * Docs/socket: {@code balance} is already usable/available; {@code locked_balance}
     * is margin locked in open orders/positions — do NOT subtract locked from balance.
     */
    public record InrWallet(BigDecimal available, BigDecimal locked) {
        public BigDecimal walletEquity() {
            BigDecimal a = available == null ? BigDecimal.ZERO : available;
            BigDecimal l = locked == null ? BigDecimal.ZERO : locked;
            return a.add(l);
        }
    }

    /**
     * INR futures wallet only. No USDT fallback — LIVE margin is INR-only.
     */
    public Mono<WalletBalance> availableFuturesBalance(String apiKey, String apiSecret) {
        return availableInrBalance(apiKey, apiSecret)
                .map(inr -> new WalletBalance("INR", inr));
    }

    public Mono<BigDecimal> availableInrBalance(String apiKey, String apiSecret) {
        return inrWallet(apiKey, apiSecret).map(InrWallet::available);
    }

    public Mono<InrWallet> inrWallet(String apiKey, String apiSecret) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        return signedGet("/exchange/v1/derivatives/futures/wallets", body, apiKey, apiSecret)
                .map(resp -> {
                    if (resp != null && resp.isArray()) {
                        for (JsonNode w : resp) {
                            if (!"INR".equalsIgnoreCase(w.path("currency_short_name").asText())) continue;
                            BigDecimal available = decimalOrZero(w, "balance");
                            BigDecimal locked = decimalOrZero(w, "locked_balance");
                            if (available.signum() < 0) available = BigDecimal.ZERO;
                            if (locked.signum() < 0) locked = BigDecimal.ZERO;
                            return new InrWallet(available, locked);
                        }
                    }
                    return new InrWallet(BigDecimal.ZERO, BigDecimal.ZERO);
                });
    }

    private static BigDecimal decimalOrZero(JsonNode node, String field) {
        try {
            String t = node.path(field).asText("0");
            if (t == null || t.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(t);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * INR-margined futures PnL: USDT price move × qty × side, converted by USDTINR.
     * Do not multiply by leverage — leverage only sets margin, not PnL notional.
     */
    public static BigDecimal pnlUsdt(BigDecimal entry, BigDecimal markOrExit,
                                    BigDecimal quantity, String side) {
        if (entry == null || markOrExit == null || quantity == null) return BigDecimal.ZERO;
        BigDecimal dir = "SHORT".equalsIgnoreCase(side) ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        return markOrExit.subtract(entry).multiply(quantity).multiply(dir);
    }

    public Mono<BigDecimal> pnlInr(BigDecimal entry, BigDecimal markOrExit,
                                   BigDecimal quantity, String side) {
        return usdtInrRate().map(rate -> pnlUsdt(entry, markOrExit, quantity, side).multiply(rate));
    }

    /**
     * Live CoinDCX futures mark/last via latest candle close.
     * Tries 1m (15m window) then 5m — empty only if both fail.
     * Callers must fail closed (no invented price).
     */
    public Mono<BigDecimal> lastPrice(String pair) {
        Instant to = Instant.now();
        return candleClose(pair, "1", to.minus(Duration.ofMinutes(15)), to)
                .switchIfEmpty(candleClose(pair, "5", to.minus(Duration.ofHours(2)), to));
    }

    private Mono<BigDecimal> candleClose(String pair, String resolution, Instant from, Instant to) {
        return candles(pair, resolution, from, to)
                .collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) return Mono.empty();
                    BigDecimal close = list.get(list.size() - 1).close();
                    if (close == null || close.signum() <= 0) return Mono.empty();
                    return Mono.just(close);
                })
                .onErrorResume(e -> {
                    log.warn("Futures candle close failed for {} res={}: {}", pair, resolution, e.getMessage());
                    return Mono.empty();
                });
    }

    /** Spot → futures wallet transfer (INR or USDT). */
    public Mono<JsonNode> transferSpotToFutures(String apiKey, String apiSecret,
                                                String currency, BigDecimal amount) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timestamp", System.currentTimeMillis());
        // deposit = spot → derivatives futures wallet
        body.put("transfer_type", "deposit");
        body.put("amount", amount.doubleValue());
        body.put("currency_short_name", currency);
        return signedPost("/exchange/v1/derivatives/futures/wallets/transfer", body, apiKey, apiSecret);
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
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(b -> ApiException.upstream(
                                        "CoinDCX futures API " + resp.statusCode().value()
                                                + " " + path + ": " + b)))
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
