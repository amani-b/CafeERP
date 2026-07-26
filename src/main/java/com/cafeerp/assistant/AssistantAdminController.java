package com.cafeerp.assistant;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

@Controller
@RequestMapping("/admin/assistant")
public class AssistantAdminController {

    private final AssistantService assistantService;
    private final UserRepository userRepository;

    public AssistantAdminController(AssistantService assistantService,
                                    UserRepository userRepository) {
        this.assistantService = assistantService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String adminList(Model model) {
        List<User> users = assistantService.getUsersWithMessages();
        model.addAttribute("users", users);
        return "assistant/admin-list";
    }

    @GetMapping("/{userId}")
    public String adminThread(@PathVariable Long userId, Model model) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<AssistantMessage> messages = assistantService.getHistory(user);
        model.addAttribute("messages", messages);
        model.addAttribute("userName", user.getUsername());
        return "assistant/admin-thread";
    }
}