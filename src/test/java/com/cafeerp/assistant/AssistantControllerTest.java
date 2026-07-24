package com.cafeerp.assistant;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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