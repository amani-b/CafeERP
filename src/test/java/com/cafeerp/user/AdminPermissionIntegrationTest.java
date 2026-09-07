package com.cafeerp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Phase 3 — granular admin permissions, verified end-to-end against the real
 * MVC + Security + Flyway stack (see test method docs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPermissionIntegrationTest extends AbstractIntegrationTest {

    private static final String SCOPE_ADMIN = "scoped_admin_p3";
    private static final String SCOPE_PW = "scopepass123";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedScopedAdmin() {
        txTemplate.executeWithoutResult(tx -> {
            if (userRepository.findByUsername(SCOPE_ADMIN).isEmpty()) {
                User admin = new User();
                admin.setUsername(SCOPE_ADMIN);
                admin.setPassword(passwordEncoder.encode(SCOPE_PW));
                admin.setRole(Role.ADMIN);
                admin.setMustChangePassword(false);
                // Only INVENTORY — nothing else (explicit opt-in).
                admin.setPermissions(Set.of(Permission.INVENTORY));
                userRepository.save(admin);
            }
        });
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    private MockHttpSession loginAsSuperAdmin() throws Exception {
        return login("admin", PLACEHOLDER_PASSWORD);
    }

    // ---------------------------------------------------------------
    //  1. Without the permission → rejected server-side, direct API hit
    // ---------------------------------------------------------------

    @Test
    void scopedAdmin_withoutPermission_isRejectedEvenOnDirectApiHit() throws Exception {
        txTemplate.executeWithoutResult(tx ->
                findUser(SCOPE_ADMIN).setPermissions(Set.of(Permission.INVENTORY)));
        MockHttpSession session = login(SCOPE_ADMIN, SCOPE_PW);

        // INVENTORY is granted → allowed
        mockMvc.perform(get("/inventory").session(session))
                .andExpect(status().isOk());

        // MENU, REPORTS, CATEGORIES, USERS — not granted → 403 on direct GETs
        mockMvc.perform(get("/menu").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/categories").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users").session(session)).andExpect(status().isForbidden());

        // Direct POST (e.g. curl) also rejected — not just UI hiding
        mockMvc.perform(post("/categories").session(session).with(csrf())
                        .param("name", "Sneaky"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    //  2. With the permission → succeeds
    // ---------------------------------------------------------------

    @Test
    void scopedAdmin_withPermission_succeeds() throws Exception {
        txTemplate.executeWithoutResult(tx ->
                findUser(SCOPE_ADMIN).setPermissions(Set.of(Permission.INVENTORY, Permission.MENU)));
        MockHttpSession session = login(SCOPE_ADMIN, SCOPE_PW);

        mockMvc.perform(get("/inventory").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/menu").session(session)).andExpect(status().isOk());
        // Still no REPORT permission → 403
        mockMvc.perform(get("/reports").session(session)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    //  3. Only SUPER_ADMIN can assign the AI permissions
    // ---------------------------------------------------------------

    @Test
    void superAdmin_canAssignAiPermissions_andEverythingElse() throws Exception {
        MockHttpSession session = loginAsSuperAdmin();

        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "p3_ai_admin")
                        .param("password", "aipass12345")
                        .param("role", "ADMIN")
                        .param("permissions", "INVENTORY", "AI_AGENTIC_ACTIONS"))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername("p3_ai_admin").orElseThrow();
        assertThat(created.getPermissions()).containsExactlyInAnyOrder(
                Permission.INVENTORY, Permission.AI_AGENTIC_ACTIONS);
    }

    @Test
    void scopedAdmin_cannotAssignAiPermissions_evenOnesTheyHold_themselves() throws Exception {
        txTemplate.executeWithoutResult(tx -> findUser(SCOPE_ADMIN)
                .setPermissions(Set.of(Permission.INVENTORY, Permission.USER_MANAGEMENT,
                        Permission.AI_AGENTIC_ACTIONS)));
        MockHttpSession session = login(SCOPE_ADMIN, SCOPE_PW);

        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "p3_escalation")
                        .param("password", "escalate123")
                        .param("role", "ADMIN")
                        .param("permissions", "INVENTORY", "AI_AGENTIC_ACTIONS"))
                .andExpect(status().isOk()); // form re-rendered with error

        // Nothing persisted — fail closed
        assertThat(userRepository.findByUsername("p3_escalation")).isEmpty();
    }

    @Test
    void scopedAdmin_cannotGrantPermissionsTheyDoNotHold() throws Exception {
        txTemplate.executeWithoutResult(tx -> findUser(SCOPE_ADMIN)
                .setPermissions(Set.of(Permission.INVENTORY, Permission.USER_MANAGEMENT)));
        MockHttpSession session = login(SCOPE_ADMIN, SCOPE_PW);

        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "p3_overgrant")
                        .param("password", "overpass123")
                        .param("role", "ADMIN")
                        .param("permissions", "INVENTORY", "MENU"))
                .andExpect(status().isOk()); // rejected, form re-rendered

        assertThat(userRepository.findByUsername("p3_overgrant")).isEmpty();
    }

    @Test
    void superAdmin_createdAdmin_endsWithNoPermissionsByDefault() throws Exception {
        MockHttpSession session = loginAsSuperAdmin();

        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "p3_blank_admin")
                        .param("password", "blankpass123")
                        .param("role", "ADMIN"))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername("p3_blank_admin").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.ADMIN);
        assertThat(created.getPermissions()).isEmpty(); // explicit opt-in, none by default

        // And that blank admin can't reach any module
        MockHttpSession blank = login("p3_blank_admin", "blankpass123");
        mockMvc.perform(get("/inventory").session(blank)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users").session(blank)).andExpect(status().isForbidden());
    }

    @Test
    void scopedAdmin_cannotCreateSuperAdmin() throws Exception {
        txTemplate.executeWithoutResult(tx ->
                findUser(SCOPE_ADMIN).setPermissions(Set.of(Permission.USER_MANAGEMENT)));
        MockHttpSession session = login(SCOPE_ADMIN, SCOPE_PW);

        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "p3_sneaky_super")
                        .param("password", "sneakypass123")
                        .param("role", "SUPER_ADMIN"))
                .andExpect(status().isOk()); // rejected

        assertThat(userRepository.findByUsername("p3_sneaky_super")).isEmpty();
    }
}
