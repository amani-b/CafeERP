package com.cafeerp.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trivial liveness probe for external uptime pingers (e.g. hitting the demo
 * deployment every few minutes).
 * <p>
 * Deliberately dependency-free: no database, no repositories (in particular
 * none of the demo profile's session-scoped stores), no AI, no session
 * access — just an immediate HTTP 200. Permitted anonymously in
 * {@link SecurityConfig} and safe to ping forever without accumulating any
 * server-side state.
 */
@RestController
public class HealthController {

    /** Liveness probe — always 200, no side effects. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
