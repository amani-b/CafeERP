package com.cafeerp.user;

/**
 * Granular admin permissions (Phase 3). A {@code SUPER_ADMIN} implicitly holds
 * every permission; a scoped {@code ADMIN} holds exactly the permissions
 * persisted in {@code cafe_user_permission} (see {@code V12__add_user_permissions.sql}).
 * <p>
 * Permissions are an enum persisted as a string in a join table — NOT hardcoded
 * booleans on the user row — so new permissions can be added later with only an
 * enum entry (existing rows are untouched; no destructive migration needed).
 */
public enum Permission {

    INVENTORY("Inventory"),
    MENU("Menu"),
    ORDER_KITCHEN("Orders & Kitchen"),
    REPORT("Reports"),
    USER_MANAGEMENT("User management"),
    CATEGORY("Categories"),
    /** Capability gated to SUPER_ADMIN grantors — the agentic write actions. */
    AI_AGENTIC_ACTIONS("AI agentic actions");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    /** Human-readable label used on the create/edit admin checkboxes. */
    public String getLabel() {
        return label;
    }

    /** Spring Security granted-authority name for this permission. */
    public String authority() {
        return "PERM_" + name();
    }

    /** True when only a SUPER_ADMIN may grant this permission to others. */
    public boolean isSuperAdminOnly() {
        return this == AI_AGENTIC_ACTIONS;
    }
}
