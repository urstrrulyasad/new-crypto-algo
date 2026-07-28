package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.AuditLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, UUID> {
    Flux<AuditLog> findTop100ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
