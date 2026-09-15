package com.cafeerp.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.PermissionService;
import com.cafeerp.user.UserRepository;

@Configuration
@EnableWebSecurity
// Method-level @PreAuthorize checks back up the URL rules below (defense in
// depth — e.g. the destructive user-delete actions are re-checked on the
// handler method itself, not only by the URL pattern).
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    /**
     * Optional: {@code @WebMvcTest} slices import this configuration but do
     * not scan {@code @Component} handlers, so the audit handler may be
     * absent there. In production it is always present.
     */
    private final org.springframework.beans.factory.ObjectProvider<AuditLogoutSuccessHandler> auditLogoutSuccessHandler;
    /**
     * Optional for the same slice-test reason: {@code @WebMvcTest} slices do
     * not scan {@code @Service} beans, so the login-abuse guard may be absent
     * there. In the full application it is always present and the filter is
     * active.
     */
    private final org.springframework.beans.factory.ObjectProvider<LoginAttemptService> loginAttemptService;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          UserRepository userRepository,
                          org.springframework.beans.factory.ObjectProvider<AuditLogoutSuccessHandler> auditLogoutSuccessHandler,
                          org.springframework.beans.factory.ObjectProvider<LoginAttemptService> loginAttemptService) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.auditLogoutSuccessHandler = auditLogoutSuccessHandler;
        this.loginAttemptService = loginAttemptService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    "/assistant/chat", "/assistant/regenerate", "/assistant/edit",
                    "/assistant/chat/stream", "/assistant/actions/*/stream"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login-error", "/css/**", "/js/**", "/actuator/health",
                        "/build-version", "/health").permitAll()
                // NOTE: SUPER_ADMIN is the root tier; ADMIN is the scoped tier.
                // Admin-tier URL access is additionally narrowed per-module by
                // @permissions.has(...) checks on the controllers (Phase 3).
                .requestMatchers("/categories/**", "/menu/**", "/inventory/**", "/reports/**",
                        "/admin/assistant/**", "/admin/assistant",
                        "/assistant/admin/**", "/assistant/admin", "/users/**", "/settings/**")
                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/kitchen/**").hasAnyRole("KITCHEN", "ADMIN", "SUPER_ADMIN")
                .requestMatchers(HttpMethod.POST, "/orders/*/status")
                .hasAnyRole("STAFF", "ADMIN", "SUPER_ADMIN", "KITCHEN")
                .requestMatchers(HttpMethod.GET, "/orders/*").hasAnyRole("STAFF", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/orders/**", "/", "/account/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/")
                .failureUrl("/login-error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                // Custom handler writes the LOGOUT row to user_session_log
                // (Phase 4 session audit) then redirects to /login?logout.
                .logoutSuccessHandler(auditLogoutSuccessHandler.getIfAvailable(
                        () -> new AuditLogoutSuccessHandler(null, null) {
                            @Override
                            public void onLogoutSuccess(jakarta.servlet.http.HttpServletRequest request,
                                                        jakarta.servlet.http.HttpServletResponse response,
                                                        org.springframework.security.core.Authentication authentication)
                                    throws java.io.IOException {
                                response.sendRedirect("/login?logout");
                            }
                        }))
                .permitAll()
            )
            .userDetailsService(userDetailsService)
            .addFilterBefore(loginAttemptFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(passwordChangeFilter(), UsernamePasswordAuthenticationFilter.class)
            .sessionManagement(session -> session
                // One live session per user: a second sign-in expires the
                // first (which then lands on /login?expired). Kills
                // credential-sharing and stale staff-room sessions.
                .maximumSessions(1)
                .expiredUrl("/login?expired")); // end sessionManagement — logout configured above

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public PasswordChangeFilter passwordChangeFilter() {
        return new PasswordChangeFilter(userRepository);
    }

    /**
     * Login-abuse front gate (see {@link LoginAttemptFilter}): per-IP rolling
     * 429 throttling plus locked-account screening. No-op passthrough in
     * {@code @WebMvcTest} slices where {@link LoginAttemptService} is absent.
     */
    @Bean
    public LoginAttemptFilter loginAttemptFilter() {
        return new LoginAttemptFilter(loginAttemptService.getIfAvailable(() -> null));
    }

    /**
     * Required for {@code maximumSessions(1)} above: publishes session
     * lifecycle events so expired/logged-out sessions are removed from the
     * registry. Without it, stale entries linger and a returning user can be
     * wrongly rejected as "already signed in".
     */
    @Bean
    public org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }

    /**
     * Exposed as the {@code @permissions} bean used by
     * {@code @PreAuthorize("@permissions.has('…')")} expressions. Declared here
     * (rather than component scanning only) so test slices that import
     * {@link SecurityConfig} also get it.
     */
    @Bean
    public PermissionService permissions() {
        return new PermissionService();
    }
}
