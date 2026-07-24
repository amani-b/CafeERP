package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;

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

    private final AssistantService assistantService;
    private final UserRepository userRepository;

    public AssistantController(AssistantService assistantService,
                               UserRepository userRepository) {
        this.assistantService = assistantService;
        this.userRepository = userRepository;
    }

    /**
     * POST /assistant/chat — any authenticated user can chat with the assistant.
     */
    @PostMapping("/chat")
    public ResponseEntity<AssistantReply> chat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

        AssistantReply reply = assistantService.processMessage(user, message);
        return ResponseEntity.ok(reply);
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