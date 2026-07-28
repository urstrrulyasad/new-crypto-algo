package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.RefreshToken;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, UUID> {
    Mono<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);
}
