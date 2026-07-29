package com.cafeerp.assistant;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.common.GlobalExceptionHandler;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

@WebMvcTest(AssistantController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class AssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssistantService assistantService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private final User staffUser = new User("staff1", "pass", Role.STAFF);
    private final User adminUser = new User("admin1", "pass", Role.ADMIN);
    private final User kitchenUser = new User("kitchen1", "pass", Role.KITCHEN);

    // -------------------------------------------------------
    //  /assistant/admin/** — ADMIN only
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "STAFF")
    void adminUsers_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/assistant/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void adminUsers_whenKitchen_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/assistant/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUsers_whenAdmin_shouldSucceed() throws Exception {
        when(assistantService.getUsersWithMessages()).thenReturn(List.of());
        mockMvc.perform(get("/assistant/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void adminUserHistory_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/assistant/admin/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void adminUserHistory_whenKitchen_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/assistant/admin/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUserHistory_whenAdmin_shouldSucceed() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(assistantService.getHistory(adminUser)).thenReturn(List.of());
        mockMvc.perform(get("/assistant/admin/1"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    //  /assistant/chat — any authenticated user
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void chat_whenStaff_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(staffUser));
        when(assistantService.processMessage(any(), anyString()))
                .thenReturn(new AssistantReply("Hello!", List.of()));

        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "kitchen1", roles = "KITCHEN")
    void chat_whenKitchen_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("kitchen1")).thenReturn(Optional.of(kitchenUser));
        when(assistantService.processMessage(any(), anyString()))
                .thenReturn(new AssistantReply("Hello!", List.of()));

        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void chat_whenAdmin_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(adminUser));
        when(assistantService.processMessage(any(), anyString()))
                .thenReturn(new AssistantReply("Hello!", List.of()));

        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void chat_withEmptyMessage_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------
    //  Outer catch-all: unhandled exceptions → graceful fallback
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void chat_whenServiceThrowsUnhandledException_shouldReturnGracefulFallback() throws Exception {
        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(staffUser));
        when(assistantService.processMessage(any(), anyString()))
                .thenThrow(new RuntimeException("Simulated catastrophic failure"));

        // The outer catch-all should return a graceful fallback (200 OK, not 500)
        when(assistantService.getFallbackReply(staffUser))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable. You can still ask me about:", List.of()));

        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"What is the meaning of life?\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void chat_whenFallbackReplyItselfFails_shouldReturnLastResortFallback() throws Exception {
        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(staffUser));
        // processMessage throws (the primary failure)
        when(assistantService.processMessage(any(), anyString()))
                .thenThrow(new RuntimeException("Simulated failure in processMessage"));
        // getFallbackReply also throws (the nested failure)
        when(assistantService.getFallbackReply(staffUser))
                .thenThrow(new RuntimeException("Simulated failure in getFallbackReply"));

        // The outer catch-all should catch both and return the last-resort fallback
        mockMvc.perform(post("/assistant/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    //  CSRF exemption: /assistant/chat must work without CSRF token
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void chat_withoutCsrf_whenStaff_shouldNotBeForbidden() throws Exception {
        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(staffUser));
        when(assistantService.processMessage(any(), anyString()))
                .thenReturn(new AssistantReply("Hello!", List.of()));

        // No .with(csrf()) — the exemption must allow this through
        mockMvc.perform(post("/assistant/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    @Test
    void chat_whenUnauthenticated_shouldRedirectToLogin() throws Exception {
        // No @WithMockUser and no .with(csrf()) — should be rejected by auth, not CSRF
        mockMvc.perform(post("/assistant/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\"}"))
                .andExpect(MockMvcResultMatchers.status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void logout_withoutCsrf_shouldStillBeForbidden() throws Exception {
        // Other POST endpoints must still have CSRF protection
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/logout"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    // -------------------------------------------------------
    //  /assistant/history — any authenticated user, scoped to caller
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void history_whenStaff_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(staffUser));
        when(assistantService.getHistory(staffUser)).thenReturn(List.of());

        mockMvc.perform(get("/assistant/history"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "kitchen1", roles = "KITCHEN")
    void history_whenKitchen_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("kitchen1")).thenReturn(Optional.of(kitchenUser));
        when(assistantService.getHistory(kitchenUser)).thenReturn(List.of());

        mockMvc.perform(get("/assistant/history"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void history_whenAdmin_shouldSucceed() throws Exception {
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(adminUser));
        when(assistantService.getHistory(adminUser)).thenReturn(List.of());

        mockMvc.perform(get("/assistant/history"))
                .andExpect(status().isOk());
    }
}