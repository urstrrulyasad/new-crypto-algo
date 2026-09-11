package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.ScalperSettings;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ScalperSettingsRepository extends ReactiveCrudRepository<ScalperSettings, UUID> {
    Mono<ScalperSettings> findByTenantId(UUID tenantId);
}
