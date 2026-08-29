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

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          UserRepository userRepository) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/assistant/chat"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login-error", "/css/**", "/js/**", "/actuator/health",
                        "/build-version").permitAll()
                .requestMatchers("/categories/**", "/menu/**", "/inventory/**", "/reports/**",
                        "/admin/assistant/**", "/admin/assistant",
                        "/assistant/admin/**", "/assistant/admin", "/users/**").hasRole("ADMIN")
                .requestMatchers("/kitchen/**").hasAnyRole("KITCHEN", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/orders/*/status").hasAnyRole("STAFF", "ADMIN", "KITCHEN")
                .requestMatchers(HttpMethod.GET, "/orders/*").hasAnyRole("STAFF", "ADMIN")
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
                .logoutSuccessUrl("/login?logout")
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
}
