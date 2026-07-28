package com.cryptoalgo.backend.admin;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.domain.Tenant;
import com.cryptoalgo.backend.domain.User;
import com.cryptoalgo.backend.repo.TenantRepository;
import com.cryptoalgo.backend.repo.UserRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Super-admin: tenant lifecycle. Tenant onboarding is pure data configuration. */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Validated
public class TenantAdminController {

    public record CreateTenantRequest(@NotBlank String name, @NotBlank String slug,
                                      @Email @NotBlank String adminEmail,
                                      @NotBlank @Size(min = 8) String adminPassword,
                                      @NotBlank String adminName) {}

    private final TenantRepository tenants;
    private final UserRepository users;
    private final R2dbcEntityTemplate template;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public TenantAdminController(TenantRepository tenants, UserRepository users,
                                 R2dbcEntityTemplate template, PasswordEncoder encoder, AuditService audit) {
        this.tenants = tenants;
        this.users = users;
        this.template = template;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping
    public Flux<Tenant> list() {
        return tenants.findAll();
    }

    @PostMapping
    public Mono<Tenant> create(@RequestBody CreateTenantRequest req) {
        return tenants.findBySlug(req.slug())
                .flatMap(t -> Mono.<Tenant>error(ApiException.conflict("Slug already in use")))
                .switchIfEmpty(Mono.defer(() -> CurrentUser.get().flatMap(actor -> {
                    Tenant tenant = new Tenant(UUID.randomUUID(), req.name(), req.slug(),
                            "ACTIVE", Instant.now());
                    User admin = new User(UUID.randomUUID(), tenant.id(), req.adminEmail().toLowerCase(),
                            encoder.encode(req.adminPassword()), req.adminName(), "TENANT_ADMIN",
                            "ACTIVE", Instant.now(), Instant.now());
                    return template.insert(tenant)
                            .then(template.insert(admin))
                            .then(audit.record(actor.tenantId(), actor.userId(), "TENANT_CREATED",
                                    "TENANT", tenant.id(), Map.of("slug", req.slug())))
                            .thenReturn(tenant);
                })));
    }

    @PatchMapping("/{id}/status")
    public Mono<Tenant> setStatus(@PathVariable UUID id, @RequestParam String status) {
        if (!status.equals("ACTIVE") && !status.equals("SUSPENDED"))
            return Mono.error(ApiException.badRequest("status must be ACTIVE or SUSPENDED"));
        return tenants.findById(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Tenant not found")))
                .flatMap(t -> tenants.save(new Tenant(t.id(), t.name(), t.slug(), status, t.createdAt())));
    }
}
