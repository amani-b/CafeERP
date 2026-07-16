package com.cafeerp.common;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cafeerp.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Redirects any authenticated user who has {@code mustChangePassword == true}
 * to {@code /account/password} so they are forced to set a new password.
 * <p>
 * Bypass paths (no redirect even if flag is set):
 * <ul>
 *   <li>{@code /account/password} — the change-password form itself</li>
 *   <li>{@code /logout} — logout must always work</li>
 *   <li>Static assets ({@code /css/**}, {@code /js/**}, etc.)</li>
 * </ul>
 */
public class PasswordChangeFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public PasswordChangeFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {

            String username = auth.getName();

            userRepository.findByUsername(username).ifPresent(user -> {
                if (user.isMustChangePassword()) {
                    String path = request.getServletPath();
                    if (!shouldBypass(path)) {
                        try {
                            response.sendRedirect("/account/password");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
        }

        filterChain.doFilter(request, response);
    }

    private static boolean shouldBypass(String path) {
        return path.equals("/account/password")
                || path.equals("/logout")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/webjars/")
                || path.startsWith("/actuator/");
    }
}