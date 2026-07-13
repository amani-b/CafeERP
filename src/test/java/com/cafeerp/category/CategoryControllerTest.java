package com.cafeerp.category;

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

@WebMvcTest(CategoryController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryService categoryService;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    // -------------------------------------------------------
    //  Group 3: 404 on nonexistent category edit
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "ADMIN")
    void editForm_withNonexistentId_shouldReturn404() throws Exception {
        when(categoryService.findById(999L))
                .thenThrow(new IllegalArgumentException("Category not found"));

        mockMvc.perform(get("/categories/edit/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    //  Group 4: Authorization — STAFF gets 403, ADMIN gets 200
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "STAFF")
    void categoriesList_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/categories"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void categoriesList_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void categoriesNew_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/categories/new"))
                .andExpect(status().isOk());
    }
}