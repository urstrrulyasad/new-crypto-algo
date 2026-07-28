package com.cryptoalgo.backend.security;

import java.util.UUID;

/** Authenticated identity carried through the Reactor context on every request. */
public record AuthPrincipal(UUID userId, UUID tenantId, String email, String role) {

    public boolean isSuperAdmin() { return "SUPER_ADMIN".equals(role); }
    public boolean isTenantAdmin() { return "TENANT_ADMIN".equals(role) || isSuperAdmin(); }
}
