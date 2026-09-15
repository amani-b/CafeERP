package com.cafeerp.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;

import org.springframework.stereotype.Service;

/**
 * Login-abuse guard: per-IP throttling plus per-account lockout.
 * <p>
 * Two independent counters, both in memory (single instance — no shared
 * state to replicate):
 * <ul>
 *   <li><b>IP window</b> — at most {@value #MAX_LOGINS_PER_MINUTE} {@code POST
 *       /login} attempts per client IP per rolling minute. Excess attempts
 *       are rejected with HTTP 429 by {@link LoginAttemptFilter} before
 *       authentication (and its BCrypt cost) is even attempted. Stops
 *       password spraying across many accounts.</li>
 *   <li><b>Account lockout</b> — {@value #MAX_FAILURES_BEFORE_LOCKOUT}
 *       consecutive bad passwords lock the account for
 *       {@value #LOCKOUT_MINUTES} minutes (persisted in
 *       {@code cafe_user.locked_until}, so restarts don't clear it). Locked
 *       accounts fail exactly like unknown ones — see
 *       {@code CustomUserDetailsService}. A successful login clears the
 *       counter.</li>
 * </ul>
 * Every bad-password attempt against a <i>known</i> account is also written
 * to {@code user_session_log} as {@code LOGIN_FAILED} (best-effort, never
 * breaks the flow). Attempts against unknown usernames are counted but not
 * stored — there is no account to attach them to.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** POST /login attempts allowed per client IP per rolling minute. */
    static final int MAX_LOGINS_PER_MINUTE = 10;

    /** Consecutive bad passwords before an account is locked. */
    static final int MAX_FAILURES_BEFORE_LOCKOUT = 5;

    /** Lockout duration in minutes. */
    static final int LOCKOUT_MINUTES = 15;

    private static final long WINDOW_MILLIS = 60_000L;

    private final UserRepository userRepository;
    private final org.springframework.beans.factory.ObjectProvider<UserSessionLogRepository> sessionLogs;

    /** Client IP → timestamps of recent login POSTs (pruned on access). */
    private final Map<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();

    /** Lower-cased username → consecutive bad-password count. */
    private final Map<String, Integer> userFailures = new ConcurrentHashMap<>();

    public LoginAttemptService(UserRepository userRepository,
                               org.springframework.beans.factory.ObjectProvider<UserSessionLogRepository> sessionLogs) {
        this.userRepository = userRepository;
        this.sessionLogs = sessionLogs;
    }

    /**
     * Records one {@code POST /login} from the given IP. Returns {@code false}
     * when the IP already exhausted its rolling-minute budget (caller answers
     * HTTP 429 without attempting authentication).
     */
    public boolean tryRecordIpAttempt(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long now = Instant.now().toEpochMilli();
        Deque<Long> window = ipAttempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= MAX_LOGINS_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** True while the login-abuse guard is holding the account locked. */
    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return userRepository.findByUsername(username.trim())
                .map(user -> user.getLockedUntil() != null
                        && user.getLockedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
                .orElse(false);
    }

    /** Clears all in-memory counters (tests; lockout rows in the DB are untouched). */
    public void resetForTests() {
        ipAttempts.clear();
        userFailures.clear();
    }

    /** Bad-password attempt: counts, locks at the threshold, audits. */
    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        try {
            String username = usernameOf(event.getAuthentication());
            if (username == null) {
                return;
            }
            String key = username.toLowerCase(java.util.Locale.ROOT);
            int failures = userFailures.merge(key, 1, Integer::sum);
            userRepository.findByUsername(username).ifPresent(user -> {
                if (failures >= MAX_FAILURES_BEFORE_LOCKOUT
                        && (user.getLockedUntil() == null
                                || !user.getLockedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC)))) {
                    user.setLockedUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(LOCKOUT_MINUTES));
                    // Session-scoped demo repo proxies cannot be saved from an
                    // event listener thread (no HTTP session bound); the
                    // in-memory failure counter above still throttles.
                    try {
                        userRepository.save(user);
                    } catch (Exception e) {
                        log.debug("Skipping lockout persistence (no session-bound repo)", e);
                    }
                    log.warn("Account '{}' locked for {} minutes after {} consecutive failures",
                            username, LOCKOUT_MINUTES, failures);
                }
                // Same session-scope caveat for the LOGIN_FAILED audit row.
                try {
                    auditFailedLogin(user);
                } catch (Exception e) {
                    log.debug("Skipping LOGIN_FAILED audit (no session-bound repo)", e);
                }
            });
        } catch (Exception e) {
            // Audit/throttling must never break authentication itself.
            log.error("Failed to record bad-credentials attempt", e);
        }
    }

    /** Successful login clears the account's failure counter and any lockout. */
    @EventListener
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        String username = usernameOf(event.getAuthentication());
        if (username == null) {
            return;
        }
        userFailures.remove(username.toLowerCase(java.util.Locale.ROOT));
        // Clear a previously-set lockout so the correct password always
        // unlocks the account immediately (best-effort, never breaks login;
        // skipped when no session-bound repo is available, e.g. demo event
        // threads).
        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                if (user.getLockedUntil() != null) {
                    user.setLockedUntil(null);
                    try {
                        userRepository.save(user);
                    } catch (Exception e) {
                        log.debug("Skipping lockout clear (no session-bound repo)", e);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to clear lockout after successful login", e);
        }
    }

    private void auditFailedLogin(com.cafeerp.user.User user) {
        try {
            UserSessionLogRepository repo = sessionLogs.getIfAvailable();
            if (repo == null) {
                return;
            }
            repo.save(new UserSessionLog(user, UserSessionLog.Event.LOGIN_FAILED, null));
        } catch (Exception e) {
            log.error("Failed to record LOGIN_FAILED in user_session_log", e);
        }
    }

    private static String usernameOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails details) {
            return details.getUsername();
        }
        if (principal instanceof String name && !name.isBlank() && !"anonymousUser".equals(name)) {
            return name;
        }
        return null;
    }
}
