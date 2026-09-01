package com.cafeerp.user;

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

@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    // -------------------------------------------------------
    //  404 on nonexistent user edit
    // -------------------------------------------------------
    @Test
    @WithMockUser(username = "root", roles = "SUPER_ADMIN")
    void editForm_withNonexistentId_shouldReturn404() throws Exception {
        when(userService.findById(999L))
                .thenThrow(new IllegalArgumentException("User not found"));

        mockMvc.perform(get("/users/edit/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    //  Authorization — STAFF gets 403, ADMIN gets 200
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "STAFF")
    void usersList_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void usersList_whenKitchen_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "root", roles = "SUPER_ADMIN")
    void usersList_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "root", roles = "SUPER_ADMIN")
    void usersNew_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/users/new"))
                .andExpect(status().isOk());
    }
}