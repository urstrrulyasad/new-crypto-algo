package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.domain.ScalperSettings;
import com.cryptoalgo.backend.market.CandleService;
import com.cryptoalgo.backend.repo.ScalperSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, closed-candle evaluator. It intentionally produces decisions only;
 * order routing remains behind the existing signal and execution risk gates.
 */
@Service
public class ScalperDecisionService {
    private final ScalperSettingsRepository settings;
    private final CandleService candles;
    private final ObjectMapper mapper;

    public ScalperDecisionService(ScalperSettingsRepository settings, CandleService candles, ObjectMapper mapper) {
        this.settings = settings;
        this.candles = candles;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${app.scalper-poll-ms:30000}")
    public void evaluate() {
        settings.findAll().filter(s -> s.enabled() && !s.killSwitch())
                .flatMap(this::evaluateSettings, 2)
                .onErrorResume(e -> Flux.empty()).subscribe();
    }

    private reactor.core.publisher.Mono<ScalperSettings> evaluateSettings(ScalperSettings current) {
        List<String> pairs = parsePairs(current.instruments());
        if (pairs.isEmpty()) return save(current, "SKIP:no instruments", null);
        Instant to = Instant.now().minus(Duration.ofSeconds(2));
        Instant from = to.minus(Duration.ofMinutes(60));
        return Flux.fromIterable(pairs).flatMap(pair -> candles.get(pair, current.timeframe(), from, to, 120)
                        .collectList().map(rows -> new Result(pair, decide(rows))), 2)
                .collectList()
                .flatMap(results -> {
                    String decision = results.stream().map(r -> r.pair + ":" + r.decision)
                            .reduce((a, b) -> a + ", " + b).orElse("SKIP:no data");
                    return save(current, decision, null);
                }).onErrorResume(e -> save(current, "HALTED:data unavailable", e.getMessage()));
    }

    private reactor.core.publisher.Mono<ScalperSettings> save(ScalperSettings s, String decision, String error) {
        return settings.save(new ScalperSettings(s.id(), s.tenantId(), s.userId(), s.strategyId(), s.enabled(), s.mode(),
                s.instruments(), s.timeframe(), s.stakeAmount(), s.maxOpenTrades(), s.cooldownSeconds(),
                s.dailyLossLimit(), s.killSwitch(), decision, error, Instant.now(), s.createdAt(), Instant.now()));
    }

    private static String decide(List<CandleService.Candle> rows) {
        if (rows.size() < 30) return "SKIP:insufficient candles";
        int last = rows.size() - 2; // exclude the still-forming candle
        if (last < 25) return "SKIP:insufficient closed candles";
        BigDecimal close = rows.get(last).close();
        BigDecimal fast = average(rows, last - 8, last);
        BigDecimal slow = average(rows, last - 24, last);
        BigDecimal volume = averageVolume(rows, last - 19, last - 1);
        BigDecimal lastVolume = rows.get(last).volume();
        if (volume.signum() <= 0 || lastVolume.compareTo(volume.multiply(new BigDecimal("1.2"))) < 0)
            return "SKIP:no volume confirmation";
        if (close.compareTo(fast) > 0 && fast.compareTo(slow) > 0) return "ENTRY_LONG_CANDIDATE";
        if (close.compareTo(fast) < 0 && fast.compareTo(slow) < 0) return "ENTRY_SHORT_CANDIDATE";
        return "SKIP:no trend edge";
    }

    private static BigDecimal average(List<CandleService.Candle> rows, int from, int to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = from; i <= to; i++) sum = sum.add(rows.get(i).close());
        return sum.divide(BigDecimal.valueOf(to - from + 1), 12, java.math.RoundingMode.HALF_UP);
    }
    private static BigDecimal averageVolume(List<CandleService.Candle> rows, int from, int to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = from; i <= to; i++) sum = sum.add(rows.get(i).volume());
        return sum.divide(BigDecimal.valueOf(to - from + 1), 12, java.math.RoundingMode.HALF_UP);
    }
    private List<String> parsePairs(io.r2dbc.postgresql.codec.Json json) {
        try { return mapper.readValue(json.asString(), mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) { return new ArrayList<>(); }
    }
    private record Result(String pair, String decision) {}
}
