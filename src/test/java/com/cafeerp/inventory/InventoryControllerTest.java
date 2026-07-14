package com.cafeerp.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cafeerp.common.GlobalExceptionHandler;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.user.CustomUserDetailsService;

@WebMvcTest(InventoryController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryService inventoryService;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    // -------------------------------------------------------
    //  Authorization — STAFF gets 403, ADMIN gets 200/302
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "STAFF")
    void inventoryList_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/inventory"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void inventoryList_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void inventoryUpdate_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(post("/inventory/update/{id}", 1L)
                        .with(csrf())
                        .param("trackInventory", "true")
                        .param("stockQuantity", "10")
                        .param("lowStockThreshold", "3"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void inventoryUpdate_whenAdmin_shouldRedirect() throws Exception {
        mockMvc.perform(post("/inventory/update/{id}", 1L)
                        .with(csrf())
                        .param("trackInventory", "true")
                        .param("stockQuantity", "10")
                        .param("lowStockThreshold", "3"))
                .andExpect(status().is3xxRedirection());
    }
}