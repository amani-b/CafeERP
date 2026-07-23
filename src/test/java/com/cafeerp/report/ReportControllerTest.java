package com.cafeerp.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
import com.cafeerp.order.OrderItemRepository;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.report.ReportService.DateRange;
import com.cafeerp.report.ReportService.ReportData;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.UserRepository;

@WebMvcTest(ReportController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportService reportService;

    @MockBean
    private OrderRepository orderRepository;

    @MockBean
    private OrderItemRepository orderItemRepository;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(roles = "STAFF")
    void reportsPage_whenStaff_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/reports"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reportsPage_whenAdmin_shouldSucceed() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(reportService.resolveDateRange(null, null, null))
                .thenReturn(new DateRange(now.toLocalDate().atStartOfDay(), now.toLocalDate().atTime(23, 59, 59, 999_999_999)));
        when(reportService.generateReport(now.toLocalDate().atStartOfDay(), now.toLocalDate().atTime(23, 59, 59, 999_999_999)))
                .thenReturn(new ReportData(BigDecimal.ZERO, 0L, List.of(), now.toLocalDate().atStartOfDay(), now.toLocalDate().atTime(23, 59, 59, 999_999_999)));

        mockMvc.perform(get("/reports"))
                .andExpect(status().isOk());
    }

    @Test
    void reportsPage_whenAnonymous_shouldRedirectToLogin() throws Exception {
        mockMvc.perform(get("/reports"))
                .andExpect(status().is3xxRedirection());
    }
}