package com.cryptoalgo.backend.auth;

import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Tenant;
import com.cryptoalgo.backend.domain.User;
import com.cryptoalgo.backend.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** First-run bootstrap: creates the platform tenant + super admin if no users exist. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UserRepository users;
    private final R2dbcEntityTemplate template;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public BootstrapRunner(UserRepository users, R2dbcEntityTemplate template,
                           PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.template = template;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.count().flatMap(count -> {
            if (count > 0) return reactor.core.publisher.Mono.empty();
            UUID tenantId = UUID.randomUUID();
            Tenant tenant = new Tenant(tenantId, "Platform", "platform", "ACTIVE", Instant.now());
            User admin = new User(UUID.randomUUID(), tenantId,
                    props.bootstrap().email().toLowerCase(), encoder.encode(props.bootstrap().password()),
                    "Super Admin", "SUPER_ADMIN", "ACTIVE", Instant.now(), Instant.now());
            log.info("Bootstrapping platform tenant and super admin '{}'", props.bootstrap().email());
            return template.insert(tenant).then(template.insert(admin));
        }).subscribe(v -> {}, e -> log.error("Bootstrap failed", e));
    }
}
