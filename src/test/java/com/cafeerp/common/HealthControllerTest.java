package com.cafeerp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.UserRepository;

@WebMvcTest(HealthController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void health_anonymous_returns200Immediately() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void health_doesNotCreateSession() throws Exception {
        // An external pinger hits this every few minutes forever — it must
        // never mint (and abandon) server-side sessions.
        MvcResult result = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
