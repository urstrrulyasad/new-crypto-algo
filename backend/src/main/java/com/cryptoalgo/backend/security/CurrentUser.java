package com.cryptoalgo.backend.security;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/** Helper to fetch the authenticated principal (with tenant) inside reactive flows. */
public final class CurrentUser {

    private CurrentUser() {}

    public static Mono<AuthPrincipal> get() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (AuthPrincipal) ctx.getAuthentication().getPrincipal());
    }
}
