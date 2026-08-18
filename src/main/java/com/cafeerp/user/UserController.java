package com.cafeerp.user;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/users")
public class UserController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userService.findAll());
        return "users/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("user", new User());
        return "users/create";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute User user, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "users/create";
        }

        if (user.getPassword() == null || user.getPassword().length() < MIN_PASSWORD_LENGTH) {
            bindingResult.rejectValue("password", "error.password",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
            return "users/create";
        }

        if (userService.usernameExists(user.getUsername())) {
            bindingResult.rejectValue("username", "error.username", "Username already exists.");
            return "users/create";
        }

        userService.createUser(user);
        return "redirect:/users";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.findById(id));
        return "users/edit";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute User user, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            user.setId(id);
            return "users/edit";
        }

        User existing = userService.findById(id);
        if (!existing.getUsername().equals(user.getUsername())
                && userService.usernameExists(user.getUsername())) {
            bindingResult.rejectValue("username", "error.username", "Username already exists.");
            user.setId(id);
            return "users/edit";
        }

        user.setId(id);
        userService.updateUser(user);
        return "redirect:/users";
    }
}