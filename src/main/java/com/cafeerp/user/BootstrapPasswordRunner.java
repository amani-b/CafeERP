package com.cafeerp.user;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-boot bootstrap for the seeded accounts.
 * <p>
 * Migration {@code V17} locks the publicly documented {@code changeme123}
 * logins to an unguessable placeholder — password login is impossible until
 * this runner issues each still-locked seeded account (admin/staff/kitchen) a
 * cryptographically random password and logs it <b>once</b> at WARN for the
 * deployer to copy from the platform logs. {@code must_change_password} stays
 * enforced, so the first login still forces an immediate change.
 * <p>
 * Idempotent once the migration has run: accounts holding a real password (no
 * locked placeholder) are untouched, so from the second boot onwards the runner
 * is a no-op and logs nothing. Mind the one-time exception — {@code V17} itself
 * re-locks the three seeded accounts unconditionally the first time it is
 * applied, so a deployment upgrading to this version does get fresh one-time
 * passwords logged even if its admin password had already been customised.
 * Runs in every non-demo profile (prod <i>and</i> dev share the same migrations
 * and seeded logins).
 */
@Component
@Profile("!demo")
@Order(50)
public class BootstrapPasswordRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapPasswordRunner.class);

    /** Placeholder written by V17 — matches nothing, login impossible. */
    public static final String LOCKED_PLACEHOLDER = "{locked}bootstrap-required";

    private static final String[] SEEDED_ACCOUNTS = { "admin", "staff", "kitchen" };

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapPasswordRunner(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String username : SEEDED_ACCOUNTS) {
            userRepository.findByUsername(username).ifPresent(user -> {
                if (LOCKED_PLACEHOLDER.equals(user.getPassword())) {
                    String fresh = randomPassword();
                    user.setPassword(passwordEncoder.encode(fresh));
                    user.setMustChangePassword(true);
                    user.setLockedUntil(null);
                    userRepository.save(user);
                    log.warn("INITIAL {} PASSWORD (shown once, then never again): {}"
                            + " — log in and change it immediately via /account/password.",
                            username.toUpperCase(), fresh);
                }
            });
        }
    }

    /** 24 random bytes, URL-safe Base64 (32 chars) — no ambiguous shell quoting. */
    public static String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
