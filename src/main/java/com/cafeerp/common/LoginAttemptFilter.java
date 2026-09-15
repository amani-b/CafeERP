package com.cafeerp.common;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Front gate for {@code POST /login}: per-IP throttling plus locked-account
 * screening, <i>before</i> Spring Security attempts authentication.
 * <p>
 * <ul>
 *   <li>IPs over their rolling-minute budget get HTTP 429 ({@code Retry-After:
 *       60}) without a BCrypt check ever running.</li>
 *   <li>Locked accounts are redirected to {@code /login-error} — byte for
 *       byte the same response as a wrong password, so lockout is invisible
 *       to attackers.</li>
 * </ul>
 * Constructed in {@link SecurityConfig} (not component-scanned) so
 * {@code @WebMvcTest} slices stay untouched.
 */
public class LoginAttemptFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptFilter.class);

    private final LoginAttemptService attemptService;

    public LoginAttemptFilter(LoginAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Absent in @WebMvcTest slices (no @Service scan) — stay out of the way.
        return attemptService == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && isLoginPath(request)) {
            String ip = clientIp(request);
            if (!attemptService.tryRecordIpAttempt(ip)) {
                log.warn("Login throttled for IP {} (rolling-minute budget spent)", ip);
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Too many login attempts. Try again in a minute.");
                return;
            }
            String username = request.getParameter("username");
            if (attemptService.isLocked(username)) {
                // Indistinguishable from a bad password — no lockout oracle.
                response.sendRedirect("/login-error");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * {@code getServletPath()} is empty under MockMvc (no servlet mapping) —
     * fall back to the request URI so tests and containers agree.
     */
    private static boolean isLoginPath(HttpServletRequest request) {
        if ("/login".equals(request.getServletPath())) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && (uri.equals("/login") || uri.endsWith("/login"));
    }
    /**
     * Real client IP behind the platform proxy (which sets
     * {@code X-Forwarded-For}; this app already trusts forwarded headers —
     * see {@code server.forward-headers-strategy}). Direct connections fall
     * back to the socket address.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
