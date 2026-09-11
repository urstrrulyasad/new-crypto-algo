package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.domain.ScalperSettings;
import com.cryptoalgo.backend.repo.ScalperSettingsRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;

/** Keeps the independent scalper control plane alive; entry decisions remain fail-closed until enabled. */
@Service
public class ScalperHeartbeatService {
    private final ScalperSettingsRepository settings;
    public ScalperHeartbeatService(ScalperSettingsRepository settings) { this.settings = settings; }

    @Scheduled(fixedDelayString = "${app.scalper-heartbeat-ms:10000}")
    public void heartbeat() {
        settings.findAll()
                .filter(s -> s.enabled() && !s.killSwitch())
                .flatMap(s -> settings.save(new ScalperSettings(s.id(), s.tenantId(), s.userId(), s.strategyId(), s.enabled(),
                        s.mode(), s.instruments(), s.timeframe(), s.stakeAmount(), s.maxOpenTrades(),
                        s.cooldownSeconds(), s.dailyLossLimit(), s.killSwitch(), "READY", s.lastError(),
                        Instant.now(), s.createdAt(), Instant.now())))
                .onErrorResume(e -> Flux.empty())
                .subscribe();
    }
}
