package com.cafeerp.user;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // NOTE: looks up ACTIVE accounts only. A soft-deleted (deactivated) user
        // is treated exactly like an unknown username, so they cannot log in —
        // while their row (and every FK pointing at it) stays intact.
        User user = userRepository.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                authoritiesFor(user)
        );
    }

    /**
     * Granted authorities = role + one PERM_* authority per persisted
     * permission. SUPER_ADMIN needs no PERM_* rows — PermissionService grants
     * it everything implicitly.
     */
    private static List<SimpleGrantedAuthority> authoritiesFor(User user) {
        java.util.List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        for (Permission permission : user.getPermissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }
        return authorities;
    }
}