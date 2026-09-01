package com.cafeerp.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Central permission checker for granular admin permissions (Phase 3).
 * <p>
 * Evaluation rules:
 * <ul>
 *   <li>{@code SUPER_ADMIN} — always true (full access, by definition).</li>
 *   <li>{@code ADMIN} — true only when the authenticated user carries the
 *       {@code PERM_*} granted authority for the requested permission
 *       (loaded from {@code cafe_user_permission} at login by
 *       {@link CustomUserDetailsService}).</li>
 *   <li>{@code STAFF} / {@code KITCHEN} — never true; those tiers are
 *       governed by role rules only.</li>
 * </ul>
 * Referenced from {@code @PreAuthorize} expressions as
 * {@code @permissions.has('INVENTORY')} (registered as the {@code permissions}
 * bean; also declared in {@code SecurityConfig} for WebMvcTest slices) and from
 * templates via
 * {@code sec:authorize="hasRole('SUPER_ADMIN') or hasAuthority('PERM_…')"}.
 */
public class PermissionService {

    /** True when the current authentication holds the given permission. */
    public boolean has(String name) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && has(auth, name);
    }

    /** True when the given authentication holds the given permission. */
    public boolean has(Authentication auth, String name) {
        Permission permission = parseOrNull(name);
        if (permission == null || auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if (auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
            return true;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> permission.authority().equals(a.getAuthority()));
    }

    /**
     * The permissions the current actor is ALLOWED to grant to others.
     * SUPER_ADMIN can grant everything; a scoped admin can only ever grant a
     * subset of what they hold themselves, and never the SUPER_ADMIN-only AI
     * capabilities. Everyone else can grant nothing.
     */
    public Set<Permission> grantablePermissions(Authentication actor) {
        if (actor == null) {
            return Set.of();
        }
        boolean superAdmin = actor.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (superAdmin) {
            return EnumSet.allOf(Permission.class);
        }
        Set<Permission> grantable = EnumSet.noneOf(Permission.class);
        for (GrantedAuthority authority : actor.getAuthorities()) {
            if (authority.getAuthority() != null && authority.getAuthority().startsWith("PERM_")) {
                try {
                    Permission p = Permission.valueOf(authority.getAuthority().substring(5));
                    if (!p.isSuperAdminOnly()) {
                        grantable.add(p);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown PERM_ authority — not a permission, skip.
                }
            }
        }
        return grantable;
    }

    /**
     * Validates a permission grant submitted from the create/edit user form.
     *
     * @param actor          the authenticated admin performing the grant
     * @param requestedNames raw checkbox values from the form
     * @param targetRole     the role being assigned to the target user
     * @throws IllegalArgumentException with a user-facing message when the
     *                                  grant would escalate beyond the actor's
     *                                  own scope
     */
    public void assertCanGrant(Authentication actor, Collection<String> requestedNames, Role targetRole) {
        Set<Permission> grantable = grantablePermissions(actor);
        boolean superAdmin = grantable.size() == Permission.values().length;

        if (targetRole == Role.SUPER_ADMIN && !superAdmin) {
            throw new IllegalArgumentException(
                    "Only the super admin can create or modify SUPER_ADMIN accounts.");
        }
        if (requestedNames == null || requestedNames.isEmpty()) {
            return;
        }
        for (String name : requestedNames) {
            Permission requested = parseOrNull(name);
            if (requested == null) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }
            if (requested.isSuperAdminOnly() && !superAdmin) {
                throw new IllegalArgumentException(
                        "Only the super admin can grant the '" + requested.getLabel() + "' permission.");
            }
            if (!superAdmin && !grantable.contains(requested)) {
                throw new IllegalArgumentException(
                        "You cannot grant the '" + requested.getLabel()
                                + "' permission — you do not hold it yourself.");
            }
        }
    }

    /** Parses a raw form value; returns null when unknown (fail closed). */
    private static Permission parseOrNull(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Permission.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
