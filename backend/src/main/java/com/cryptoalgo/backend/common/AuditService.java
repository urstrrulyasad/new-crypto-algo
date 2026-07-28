package com.cryptoalgo.backend.common;

import com.cryptoalgo.backend.domain.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;

    public AuditService(R2dbcEntityTemplate template, ObjectMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    public Mono<Void> record(UUID tenantId, UUID userId, String action,
                             String entityType, UUID entityId, Map<String, Object> details) {
        try {
            AuditLog row = new AuditLog(UUID.randomUUID(), tenantId, userId, action, entityType,
                    entityId, Json.of(mapper.writeValueAsString(details)), Instant.now());
            return template.insert(row).then()
                    .onErrorResume(e -> {
                        log.error("Audit write failed for action {}", action, e);
                        return Mono.empty();
                    });
        } catch (Exception e) {
            log.error("Audit serialization failed for action {}", action, e);
            return Mono.empty();
        }
    }
}
