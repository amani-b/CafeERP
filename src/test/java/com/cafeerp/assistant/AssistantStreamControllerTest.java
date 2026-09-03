package com.cafeerp.assistant;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.common.SecurityConfig;
import com.cafeerp.user.CustomUserDetailsService;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

/**
 * Phase 5 — SSE streaming chat endpoint. Verifies auth, validation, CSRF
 * exemption and that the stream emits the step + reply events the UI's
 * trace consumes.
 */
@WebMvcTest(AssistantStreamController.class)
@Import(SecurityConfig.class)
class AssistantStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssistantService assistantService;

    @MockBean
    private AssistantTitleService titleService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void stream_whenUnauthenticated_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hi\"}"))
                .andExpect(status().is3xxRedirection()); // form-login redirect
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void stream_withBlankMessage_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/assistant/chat/stream")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().is4xxClientError()); // handler maps the rejection to 4xx
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void stream_shouldEmitStepsThenReply() throws Exception {
        User staff = new User("staff1", "pass", Role.STAFF);
        when(userRepository.findByUsername("staff1")).thenReturn(java.util.Optional.of(staff));

        // The real service fires the trace listener per tool call; simulate
        // that contract so the endpoint's SSE wiring is exercised end-to-end.
        when(assistantService.processMessage(eq(staff), eq("status of order 482?"),
                ArgumentMatchers.<Long>isNull(), any(AgenticAutonomy.class), any()))
                .thenAnswer(invocation -> {
                    AssistantTraceListener trace = invocation.getArgument(4);
                    trace.onStep("Looking up order #482…", "start");
                    trace.onStep("Done — PENDING, 2 items.", "done");
                    return new AssistantReply("Order #482 is PENDING.", List.of(), 1L, List.of());
                });
        // This turn triggered the conversation's first summary title.
        when(titleService.titleFutureFor(1L))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        when(titleService.latestTitleOf(1L))
                .thenReturn(java.util.Optional.of("Checking order 482 status"));

        MvcResult result = mockMvc.perform(post("/assistant/chat/stream")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"status of order 482?\",\"mode\":\"chat\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:step"),
                "stream should contain step events: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Looking up order #482"),
                "stream should contain the human-readable step: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:reply"),
                "stream should end with a reply event: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Order #482 is PENDING."),
                "reply event should carry the full reply JSON: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:title"),
                "stream should deliver the fresh summary title: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Checking order 482 status"),
                "title event should carry the AI summary: " + body);
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void streamAction_confirm_shouldEmitStepsThenReply() throws Exception {
        User staff = new User("staff1", "pass", Role.STAFF);
        when(userRepository.findByUsername("staff1")).thenReturn(java.util.Optional.of(staff));

        when(assistantService.confirmPendingAction(eq(staff), eq(7L), ArgumentMatchers.<Long>isNull(),
                any()))
                .thenAnswer(invocation -> {
                    AssistantTraceListener trace = invocation.getArgument(3);
                    trace.onStep("Running: Change status of order #12 to READY.", "start");
                    trace.onStep("Done — status is now READY.", "done");
                    return new AssistantReply("✅ Done — status changed.", List.of());
                });

        MvcResult result = mockMvc.perform(post("/assistant/actions/7/stream")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verb\":\"confirm\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:step"),
                "stream should contain step events: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Running: Change status"),
                "stream should describe the action in plain language: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:reply"),
                "stream should end with a reply event: " + body);
    }

    @Test
    @WithMockUser(username = "staff1", roles = "STAFF")
    void streamAction_whenActionGone_shouldEmitErrorEvent() throws Exception {
        User staff = new User("staff1", "pass", Role.STAFF);
        when(userRepository.findByUsername("staff1")).thenReturn(java.util.Optional.of(staff));
        when(assistantService.confirmPendingAction(eq(staff), eq(99L), ArgumentMatchers.<Long>isNull(),
                any()))
                .thenThrow(new IllegalArgumentException("Pending action not found"));

        MvcResult result = mockMvc.perform(post("/assistant/actions/99/stream")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verb\":\"confirm\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:error"),
                "gone action should emit an error event: " + body);
    }
}
