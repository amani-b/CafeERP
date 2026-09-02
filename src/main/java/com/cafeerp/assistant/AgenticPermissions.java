package com.cafeerp.assistant;

import java.util.EnumSet;
import java.util.Set;

import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;

/**
 * Effective-permission resolution for the agentic AI tool set (Phase 4).
 * <p>
 * A tool call made through the assistant must never surface data the asking
 * user could not see themselves: every tool — read or write — is scoped to
 * the caller's OWN effective permissions. {@code SUPER_ADMIN} implicitly holds
 * everything; a scoped {@code ADMIN} holds exactly their persisted
 * {@code cafe_user_permission} rows; STAFF/KITCHEN hold none of the granular
 * permissions.
 */
public final class AgenticPermissions {

    private AgenticPermissions() {
    }

    /** The user's effective granular permissions (SUPER_ADMIN = all). */
    public static Set<Permission> effective(User user) {
        if (user == null) {
            return Set.of();
        }
        if (user.getRole() == Role.SUPER_ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        return user.getPermissions() == null ? Set.of() : user.getPermissions();
    }

    public static boolean holds(User user, Permission permission) {
        return effective(user).contains(permission);
    }

    /** True when the user may use write-capable agentic tools at all. */
    public static boolean isAgentic(User user) {
        return holds(user, Permission.AI_AGENTIC_ACTIONS);
    }
}