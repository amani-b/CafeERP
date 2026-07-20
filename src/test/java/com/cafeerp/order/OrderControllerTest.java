package com.cafeerp.order;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.cafeerp.common.GlobalExceptionHandler;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.menu.MenuService;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.UserRepository;

@WebMvcTest(OrderController.class)
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
    //  Receipt: 200 for ADMIN and STAFF
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "ADMIN")
    void receipt_whenAdminAndOrderExists_shouldSucceed() throws Exception {
        Order order = new Order();
        order.setId(1L);
        when(orderService.findById(1L)).thenReturn(order);

        mockMvc.perform(get("/orders/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(view().name("orders/receipt"))
                .andExpect(model().attributeExists("order"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void receipt_whenStaffAndOrderExists_shouldSucceed() throws Exception {
        Order order = new Order();
        order.setId(1L);
        when(orderService.findById(1L)).thenReturn(order);

        mockMvc.perform(get("/orders/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(view().name("orders/receipt"))
                .andExpect(model().attributeExists("order"));
    }

    // -------------------------------------------------------
    //  Receipt: 404 when order does not exist
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "ADMIN")
    void receipt_whenAdminAndOrderMissing_shouldReturn404() throws Exception {
        when(orderService.findById(999L))
                .thenThrow(new IllegalArgumentException("Order not found"));

        mockMvc.perform(get("/orders/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void receipt_whenStaffAndOrderMissing_shouldReturn404() throws Exception {
        when(orderService.findById(999L))
                .thenThrow(new IllegalArgumentException("Order not found"));

        mockMvc.perform(get("/orders/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    //  List: accessible by both ADMIN and STAFF
    // -------------------------------------------------------
    @Test
    @WithMockUser(roles = "ADMIN")
    void ordersList_whenAdmin_shouldSucceed() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void ordersList_whenStaff_shouldSucceed() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    //  Unauthenticated access → redirect to login
    // -------------------------------------------------------
    @Test
    void receipt_whenUnauthenticated_shouldRedirectToLogin() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().is3xxRedirection());
    }
}