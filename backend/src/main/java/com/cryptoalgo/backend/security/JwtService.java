package com.cryptoalgo.backend.security;

import com.cryptoalgo.backend.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(props.jwt().secret()));
        this.accessTtl = Duration.ofMinutes(props.jwt().accessTtlMinutes());
    }

    public String issueAccessToken(AuthPrincipal p) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(p.userId().toString())
                .claim("tenantId", p.tenantId().toString())
                .claim("email", p.email())
                .claim("role", p.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException if invalid/expired */
    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthPrincipal(
                UUID.fromString(c.getSubject()),
                UUID.fromString(c.get("tenantId", String.class)),
                c.get("email", String.class),
                c.get("role", String.class));
    }
}
