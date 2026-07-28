package com.cryptoalgo.backend.auth;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.RefreshToken;
import com.cryptoalgo.backend.domain.User;
import com.cryptoalgo.backend.repo.RefreshTokenRepository;
import com.cryptoalgo.backend.repo.UserRepository;
import com.cryptoalgo.backend.security.AuthPrincipal;
import com.cryptoalgo.backend.security.JwtService;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    public record TokenPair(String accessToken, String refreshToken, UserView user) {}
    public record UserView(UUID id, UUID tenantId, String email, String displayName, String role) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final R2dbcEntityTemplate template;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, JwtService jwt,
                       PasswordEncoder encoder, R2dbcEntityTemplate template, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.encoder = encoder;
        this.template = template;
        this.refreshTtl = Duration.ofDays(props.jwt().refreshTtlDays());
    }

    public Mono<TokenPair> login(String email, String password) {
        return users.findByEmail(email.toLowerCase().trim())
                .filter(u -> "ACTIVE".equals(u.status()))
                .filter(u -> encoder.matches(password, u.passwordHash()))
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Invalid credentials")))
                .flatMap(this::issueTokens);
    }

    public Mono<TokenPair> refresh(String refreshToken) {
        return refreshTokens.findByTokenHashAndRevokedFalse(sha256(refreshToken))
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Invalid refresh token")))
                .flatMap(t -> refreshTokens.save(new RefreshToken(t.id(), t.userId(), t.tokenHash(),
                                t.expiresAt(), true, t.createdAt()))
                        .then(users.findById(t.userId())))
                .filter(u -> "ACTIVE".equals(u.status()))
                .switchIfEmpty(Mono.error(ApiException.unauthorized("User disabled")))
                .flatMap(this::issueTokens);
    }

    private Mono<TokenPair> issueTokens(User u) {
        AuthPrincipal p = new AuthPrincipal(u.id(), u.tenantId(), u.email(), u.role());
        String access = jwt.issueAccessToken(p);
        byte[] raw = new byte[48];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RefreshToken row = new RefreshToken(UUID.randomUUID(), u.id(), sha256(refresh),
                Instant.now().plus(refreshTtl), false, Instant.now());
        return template.insert(row).thenReturn(new TokenPair(access, refresh,
                new UserView(u.id(), u.tenantId(), u.email(), u.displayName(), u.role())));
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
