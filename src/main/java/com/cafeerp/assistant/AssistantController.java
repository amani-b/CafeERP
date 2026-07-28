package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;
    private final UserRepository userRepository;

    public AssistantController(AssistantService assistantService,
                               UserRepository userRepository) {
        this.assistantService = assistantService;
        this.userRepository = userRepository;
    }

    /**
     * POST /assistant/chat — any authenticated user can chat with the assistant.
     * <p>
     * This method has a hard outer catch-all: any unhandled exception anywhere
     * in the chat flow (Tier 2 matching, AI provider calls, tool dispatch,
     * persistence, etc.) will be caught here, logged at ERROR level with full
     * detail, and result in a graceful fallback message returned to the user.
     * The user will never see a bare error or stack trace.
     */
    @PostMapping("/chat")
    public ResponseEntity<AssistantReply> chat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            User user = userRepository.findByUsername(userDetails.getUsername())
                    .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

            AssistantReply reply = assistantService.processMessage(user, message);
            return ResponseEntity.ok(reply);

        } catch (Exception e) {
            // Hard outer catch-all: ANY unhandled exception in the chat path
            // is caught here, logged in full, and degrades to a graceful fallback.
            log.error("UNHANDLED EXCEPTION in /assistant/chat for user '{}': {}",
                    userDetails.getUsername(), e.toString(), e);

            // Best-effort: try to look up the User entity for a role-scoped fallback
            try {
                User user = userRepository.findByUsername(userDetails.getUsername()).orElse(null);
                if (user != null) {
                    AssistantReply fallback = assistantService.getFallbackReply(user);
                    return ResponseEntity.ok(fallback);
                }
            } catch (Exception lookupFailure) {
                log.error("Failed to look up user for fallback in outer catch-all", lookupFailure);
            }

            // Last-resort fallback if even user lookup failed
            AssistantReply lastResort = new AssistantReply(
                    "I'm sorry, the assistant is temporarily unavailable due to an unexpected error. "
                    + "Please try again later.",
                    List.of());
            return ResponseEntity.ok(lastResort);
        }
    }

    /**
     * GET /assistant/history — current user's own message thread.
     */
    @GetMapping("/history")
    public ResponseEntity<List<AssistantMessage>> history(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

        List<AssistantMessage> messages = assistantService.getHistory(user);
        return ResponseEntity.ok(messages);
    }

    /**
     * GET /assistant/admin — admin only. Lists users who have assistant activity.
     */
    @GetMapping("/admin")
    public ResponseEntity<List<User>> adminUsers() {
        List<User> users = assistantService.getUsersWithMessages();
        return ResponseEntity.ok(users);
    }

    /**
     * GET /assistant/admin/{userId} — admin only. Full thread for a specific user.
     */
    @GetMapping("/admin/{userId}")
    public ResponseEntity<List<AssistantMessage>> adminUserHistory(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<AssistantMessage> messages = assistantService.getHistory(user);
        return ResponseEntity.ok(messages);
    }
}