package com.cafeerp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Full-stack Phase 3 tests for granular admin permissions: real logins, real
 * filter chain, real Flyway schema. Verifies that
 * <ul>
 *   <li>a scoped admin WITHOUT a permission is rejected server-side even when
 *       hitting the endpoint directly (not just hidden in the UI),</li>
 *   <li>a scoped admin WITH the permission succeeds,</li>
 *   <li>only the super admin can grant {@code AI_CODING_TOOL} /
 *       {@code AI_AGENTIC_ACTIONS}, and a scoped admin can never grant more
 *       than they hold themselves.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionEnforcementIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;

    private static final String SCOPED_INVENTORY = "scoped_inv";
    private static final String SCOPED_NO_PERMS = "scoped_none";
    private static final String SCOPED_USER_MGMT = "scoped_um";

    @BeforeAll
    void seedScopedAdmins() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String[] u : new String[][] {
                {SCOPED_INVENTORY, "password123"},
                {SCOPED_NO_PERMS, "password123"},
                {SCOPED_USER_MGMT, "password123"}}) {
            if (userRepository.findByUsername(u[0]).isEmpty()) {
                User user = new User();
                user.setUsername(u[0]);
                user.setPassword(u[1]);
                user.setRole(Role.ADMIN);
                userService.createUser(user);
            }
        }
        jdbc.update("UPDATE cafe_user SET must_change_password = FALSE");
        grant(SCOPED_INVENTORY, Permission.INVENTORY);
        grant(SCOPED_USER_MGMT, Permission.USER_MANAGEMENT);
    }

    private void grant(String username, Permission... permissions) {
        User user = userRepository.findByUsername(username).orElseThrow();
        user.setPermissions(Set.of(permissions));
        userService.updateUser(user);
    }

    private long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    // ---------------------------------------------------------------
    //  Scoped admin WITHOUT the permission -> rejected server-side
    // ---------------------------------------------------------------

    @Test
    void scopedAdminWithoutInventoryPermission_isRejectedOnInventoryEndpoint() throws Exception {
        MockHttpSession session = login(SCOPED_NO_PERMS, "password123");
        mockMvc.perform(get("/inventory").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedAdminWithoutUserManagementPermission_isRejectedOnUsersEndpoint() throws Exception {
        MockHttpSession session = login(SCOPED_INVENTORY, "password123");
        mockMvc.perform(get("/users").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedAdminWithoutMenuPermission_isRejectedOnMenuEndpoint() throws Exception {
        MockHttpSession session = login(SCOPED_NO_PERMS, "password123");
        mockMvc.perform(get("/menu").session(session))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    //  Scoped admin WITH the permission -> succeeds
    // ---------------------------------------------------------------

    @Test
    void scopedAdminWithInventoryPermission_canAccessInventory() throws Exception {
        MockHttpSession session = login(SCOPED_INVENTORY, "password123");
        mockMvc.perform(get("/inventory").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void scopedAdminWithUserManagementPermission_canAccessUsers() throws Exception {
        MockHttpSession session = login(SCOPED_USER_MGMT, "password123");
        mockMvc.perform(get("/users").session(session))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    //  Super admin retains full access
    // ---------------------------------------------------------------

    @Test
    void superAdmin_retainsFullAccessToAllModules() throws Exception {
        MockHttpSession session = login("admin", PLACEHOLDER_PASSWORD);
        for (String url : new String[] {"/inventory", "/menu", "/categories", "/reports", "/users", "/orders"}) {
            mockMvc.perform(get(url).session(session)).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------
    //  Grant rules on the create/update endpoints
    // ---------------------------------------------------------------

    @Test
    void scopedAdmin_cannotGrantPermissionTheyDoNotHold() throws Exception {
        MockHttpSession session = login(SCOPED_USER_MGMT, "password123");
        // Holds USER_MANAGEMENT only - REPORT must be refused even though the
        // form was POSTed directly with that checkbox value.
        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "victim1")
                        .param("role", "ADMIN")
                        .param("password", "password123")
                        .param("permissions", "REPORT"))
                .andExpect(status().isOk())
                .andExpect(view().name("users/create"));

        assertThat(userRepository.findByUsername("victim1")).isEmpty();
    }

    @Test
    void scopedAdmin_canGrantPermissionTheyHold() throws Exception {
        MockHttpSession session = login(SCOPED_USER_MGMT, "password123");
        // Holds USER_MANAGEMENT - granting it to a new admin is allowed.
        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", "scoped_ok")
                        .param("role", "ADMIN")
                        .param("password", "password123")
                        .param("permissions", "USER_MANAGEMENT"))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername("scoped_ok").orElseThrow();
        assertThat(created.getPermissions()).containsExactly(Permission.USER_MANAGEMENT);
    }

    @Test
    void scopedAdmin_cannotGrantAiPermissions() throws Exception {
        for (Permission aiPermission : new Permission[] {Permission.AI_CODING_TOOL, Permission.AI_AGENTIC_ACTIONS}) {
            String username = "ai_refused_" + aiPermission.name().toLowerCase();
            MockHttpSession session = login(SCOPED_USER_MGMT, "password123");
            mockMvc.perform(post("/users").session(session).with(csrf())
                            .param("username", username)
                            .param("role", "ADMIN")
                            .param("password", "password123")
                            .param("permissions", aiPermission.name()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("users/create"));
            assertThat(userRepository.findByUsername(username)).isEmpty();
        }
    }

    @Test
    void onlySuperAdmin_canGrantAiPermissions() throws Exception {
        String username = "ai_granted_by_root";
        MockHttpSession session = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users").session(session).with(csrf())
                        .param("username", username)
                        .param("role", "ADMIN")
                        .param("password", "password123")
                        .param("permissions", "AI_CODING_TOOL", "AI_AGENTIC_ACTIONS", "INVENTORY"))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername(username).orElseThrow();
        assertThat(created.getPermissions()).containsExactlyInAnyOrder(
                Permission.AI_CODING_TOOL, Permission.AI_AGENTIC_ACTIONS, Permission.INVENTORY);
    }

    @Test
    void createAdminForm_showsNoPermissionsSelectedByDefault() throws Exception {
        MockHttpSession session = login("admin", PLACEHOLDER_PASSWORD);
        MvcResult page = mockMvc.perform(get("/users/new").session(session))
                .andExpect(status().isOk()).andReturn();
        String html = page.getResponse().getContentAsString();
        // Every checkbox renders, none is pre-checked (explicit opt-in only).
        for (Permission p : Permission.values()) {
            assertThat(html).contains("value=\"" + p.name() + "\"");
            assertThat(html).doesNotContain("value=\"" + p.name() + "\" checked");
        }
    }

    @Test
    void permissionsAreStoredInJoinTable_notHardcodedOnUserRow() {
        Long id = userId(SCOPED_INVENTORY);
        Map<String, Object> columns = new JdbcTemplate(dataSource)
                .queryForMap("SELECT * FROM cafe_user WHERE id = ?", id);
        // No permission column exists on the user row...
        assertThat(columns.keySet().stream().map(String::toLowerCase))
                .noneMatch(c -> c.contains("permission"));
        // ...the grant lives in the join table instead.
        Integer count = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM cafe_user_permission WHERE user_id = ? AND permission = ?",
                Integer.class, id, Permission.INVENTORY.name());
        assertThat(count).isEqualTo(1);
    }
}

