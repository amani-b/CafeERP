package com.cafeerp.assistant;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cafeerp.common.GlobalExceptionHandler;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

/**
 * Access-control tests for the Assistant admin list + thread detail routes
 * ({@code /admin/assistant} and {@code /admin/assistant/{userId}}).
 *
 * <p>Both endpoints are governed by the ADMIN-only rule in {@link SecurityConfig}
 * for {@code /admin/assistant/**}; these tests exercise the actual Spring
 * Security filter chain to confirm a non-admin role cannot reach either page.</p>
 */
@WebMvcTest(AssistantAdminController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class AssistantAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssistantService assistantService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private final User adminUser = new User("admin1", "pass", Role.ADMIN);

    // -------------------------------------------------------
    //  /admin/assistant — ADMIN only (list page)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "STAFF")
    void adminList_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/assistant"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void adminList_whenKitchen_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/assistant"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminList_whenAdmin_shouldSucceed() throws Exception {
        when(assistantService.getUsersWithMessages()).thenReturn(List.of());
        mockMvc.perform(get("/admin/assistant"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    //  /admin/assistant/{userId} — ADMIN only (thread detail)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "STAFF")
    void adminThread_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/assistant/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void adminThread_whenKitchen_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/admin/assistant/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminThread_whenAdmin_shouldSucceed() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(assistantService.getHistory(adminUser)).thenReturn(List.of());

        mockMvc.perform(get("/admin/assistant/1"))
                .andExpect(status().isOk());
    }
}