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

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          UserRepository userRepository,
                          org.springframework.beans.factory.ObjectProvider<AuditLogoutSuccessHandler> auditLogoutSuccessHandler) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.auditLogoutSuccessHandler = auditLogoutSuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    "/assistant/chat", "/assistant/regenerate", "/assistant/edit"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login-error", "/css/**", "/js/**", "/actuator/health",
                        "/build-version").permitAll()
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
            .addFilterAfter(passwordChangeFilter(), UsernamePasswordAuthenticationFilter.class);

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
