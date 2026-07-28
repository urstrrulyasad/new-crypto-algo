package com.cryptoalgo.backend.admin;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.domain.User;
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
import java.util.Set;
import java.util.UUID;

/** Tenant-admin: user management within the caller's own tenant. */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
@Validated
public class UserAdminController {

    public record CreateUserRequest(@Email @NotBlank String email,
                                    @NotBlank @Size(min = 8) String password,
                                    @NotBlank String displayName, @NotBlank String role) {}
    public record UserView(UUID id, String email, String displayName, String role,
                           String status, Instant createdAt) {
        static UserView of(User u) {
            return new UserView(u.id(), u.email(), u.displayName(), u.role(), u.status(), u.createdAt());
        }
    }

    private static final Set<String> ASSIGNABLE_ROLES = Set.of("TENANT_ADMIN", "TRADER");

    private final UserRepository users;
    private final R2dbcEntityTemplate template;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminController(UserRepository users, R2dbcEntityTemplate template,
                               PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.template = template;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping
    public Flux<UserView> list() {
        return CurrentUser.get().flatMapMany(p -> users.findByTenantId(p.tenantId())).map(UserView::of);
    }

    @PostMapping
    public Mono<UserView> create(@RequestBody CreateUserRequest req) {
        if (!ASSIGNABLE_ROLES.contains(req.role()))
            return Mono.error(ApiException.badRequest("role must be TENANT_ADMIN or TRADER"));
        return CurrentUser.get().flatMap(actor ->
                users.findByTenantIdAndEmail(actor.tenantId(), req.email().toLowerCase())
                        .flatMap(u -> Mono.<UserView>error(ApiException.conflict("Email already exists in tenant")))
                        .switchIfEmpty(Mono.defer(() -> {
                            User u = new User(UUID.randomUUID(), actor.tenantId(),
                                    req.email().toLowerCase(), encoder.encode(req.password()),
                                    req.displayName(), req.role(), "ACTIVE", Instant.now(), Instant.now());
                            return template.insert(u)
                                    .then(audit.record(actor.tenantId(), actor.userId(), "USER_CREATED",
                                            "USER", u.id(), Map.of("email", u.email(), "role", u.role())))
                                    .thenReturn(UserView.of(u));
                        })));
    }

    @PatchMapping("/{id}/status")
    public Mono<UserView> setStatus(@PathVariable UUID id, @RequestParam String status) {
        if (!status.equals("ACTIVE") && !status.equals("DISABLED"))
            return Mono.error(ApiException.badRequest("status must be ACTIVE or DISABLED"));
        return CurrentUser.get().flatMap(actor -> users.findById(id)
                .filter(u -> u.tenantId().equals(actor.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("User not found")))
                .flatMap(u -> users.save(new User(u.id(), u.tenantId(), u.email(), u.passwordHash(),
                        u.displayName(), u.role(), status, u.createdAt(), Instant.now())))
                .map(UserView::of));
    }
}
