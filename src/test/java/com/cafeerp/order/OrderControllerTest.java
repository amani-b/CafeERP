package com.cafeerp.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.cafeerp.common.GlobalExceptionHandler;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.menu.MenuService;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.UserRepository;

@WebMvcTest({OrderController.class, KitchenController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private MenuService menuService;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    // -------------------------------------------------------
    //  Kitchen queue — KITCHEN can access, STAFF/ADMIN also
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "KITCHEN")
    void kitchenQueue_whenKitchen_shouldSucceed() throws Exception {
        when(orderService.findActiveOrders()).thenReturn(List.of());
        mockMvc.perform(get("/kitchen"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void kitchenQueue_whenAdmin_shouldSucceed() throws Exception {
        when(orderService.findActiveOrders()).thenReturn(List.of());
        mockMvc.perform(get("/kitchen"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void kitchenQueue_whenStaff_shouldBeForbidden() throws Exception {
        mockMvc.perform(get("/kitchen"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------
    //  Receipt view — STAFF/ADMIN can access, KITCHEN is rejected
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "STAFF")
    void receipt_whenStaff_shouldSucceed() throws Exception {
        Order order = new Order();
        order.setId(1L);
        when(orderService.findById(1L)).thenReturn(order);
        mockMvc.perform(get("/orders/{id}", 1L))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void receipt_whenAdmin_shouldSucceed() throws Exception {
        Order order = new Order();
        order.setId(1L);
        when(orderService.findById(1L)).thenReturn(order);
        mockMvc.perform(get("/orders/{id}", 1L))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void receipt_whenKitchen_shouldBeForbidden() throws Exception {
        mockMvc.perform(get("/orders/{id}", 1L))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------
    //  Status update — STAFF/ADMIN/KITCHEN can all access
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "STAFF")
    void updateStatus_whenStaff_shouldSucceed() throws Exception {
        mockMvc.perform(post("/orders/{id}/status", 1L)
                        .param("status", "PREPARING")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(post("/orders/{id}/status", 1L)
                        .param("status", "PREPARING")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void updateStatus_whenKitchen_shouldSucceed() throws Exception {
        mockMvc.perform(post("/orders/{id}/status", 1L)
                        .param("status", "PREPARING")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // -------------------------------------------------------
    //  Orders list — accessible by any authenticated user
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "KITCHEN")
    void ordersList_whenKitchen_shouldSucceed() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());
    }
}