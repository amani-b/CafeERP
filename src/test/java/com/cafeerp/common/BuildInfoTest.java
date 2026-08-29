package com.cafeerp.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.cafeerp.AbstractIntegrationTest;

/**
 * The deploy self-announcement seam: the build stamp must be reachable
 * without authentication (so the banner works even on the login page).
 */
public class BuildInfoTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void buildVersionEndpointIsPublic_andReturnsStamp() throws Exception {
        String server = mockMvc.perform(get("/build-version"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(server).isNotBlank();
    }
}