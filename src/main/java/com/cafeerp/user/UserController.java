package com.cafeerp.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/users")
// NOTE: /users/** is already admin-tier-only in SecurityConfig's filter chain
// (the primary server-side enforcement); these @PreAuthorize annotations are
// the second layer, evaluated on the handler itself. Phase 3: an ADMIN-tier
// user additionally needs the USER_MANAGEMENT permission (SUPER_ADMIN always
// passes — see PermissionService).
@PreAuthorize("@permissions.has('USER_MANAGEMENT')")
public class UserController {

    /** Minimum password length for newly created accounts (going forward only). */
    static final int MIN_PASSWORD_LENGTH = 12;

    private final UserService userService;
    private final PermissionService permissionService;

    public UserController(UserService userService, PermissionService permissionService) {
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public String list(@RequestParam(value = "showInactive", required = false) String showInactive,
                       Model model) {
        boolean inactive = "1".equals(showInactive) || "true".equals(showInactive);
        model.addAttribute("users", inactive ? userService.findAllDeleted() : userService.findAll());
        model.addAttribute("showInactive", inactive);
        return "users/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("user", new User());
        addPermissionModelAttributes(model, null);
        return "users/create";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute User user, BindingResult bindingResult,
                         @RequestParam(value = "permissions", required = false) List<String> permissions,
                         Authentication authentication, Model model) {
        if (bindingResult.hasErrors()) {
            addPermissionModelAttributes(model, null);
            return "users/create";
        }

        if (user.getPassword() == null || user.getPassword().length() < MIN_PASSWORD_LENGTH) {
            bindingResult.rejectValue("password", "error.password",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
            addPermissionModelAttributes(model, null);
            return "users/create";
        }

        if (userService.usernameExists(user.getUsername())) {
            bindingResult.rejectValue("username", "error.username", "Username already exists.");
            addPermissionModelAttributes(model, null);
            return "users/create";
        }

        Set<Permission> granted = validateGrant(permissions, user.getRole(), bindingResult, authentication);
        if (bindingResult.hasErrors()) {
            addPermissionModelAttributes(model, null);
            return "users/create";
        }

        userService.createUser(user, granted);
        return "redirect:/users";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        User user = userService.findById(id);
        model.addAttribute("user", user);
        addPermissionModelAttributes(model, user);
        return "users/edit";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute User user, BindingResult bindingResult,
                         @RequestParam(value = "permissions", required = false) List<String> permissions,
                         Authentication authentication, Model model) {
        if (bindingResult.hasErrors()) {
            user.setId(id);
            addPermissionModelAttributes(model, userService.findById(id));
            return "users/edit";
        }

        User existing = userService.findById(id);
        if (!existing.getUsername().equals(user.getUsername())
                && userService.usernameExists(user.getUsername())) {
            bindingResult.rejectValue("username", "error.username", "Username already exists.");
            user.setId(id);
            addPermissionModelAttributes(model, existing);
            return "users/edit";
        }

        user.setId(id);
        Set<Permission> granted = validateGrant(permissions, user.getRole(), bindingResult, authentication);
        if (bindingResult.hasErrors()) {
            addPermissionModelAttributes(model, existing);
            return "users/edit";
        }

        userService.updateUser(user, granted);
        return "redirect:/users";
    }

    // ---------------------------------------------------------------
    //  Granular permission grants (Phase 3)
    // ---------------------------------------------------------------

    /**
     * Validates the submitted permission checkboxes against the acting admin's
     * own scope (fail closed): a scoped admin can never grant more than they
     * hold, and only the super admin can grant the AI capabilities. Returns
     * the validated set, or registers a binding error and returns null.
     */
    private Set<Permission> validateGrant(List<String> permissions, Role targetRole,
                                          BindingResult bindingResult, Authentication authentication) {
        try {
            permissionService.assertCanGrant(authentication, permissions, targetRole);
        } catch (IllegalArgumentException e) {
            bindingResult.reject("error.permissions", e.getMessage());
            return null;
        }
        if (permissions == null) {
            return Set.of();
        }
        Set<Permission> granted = new java.util.HashSet<>();
        for (String name : permissions) {
            try {
                granted.add(Permission.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // unknown names already rejected by assertCanGrant
            }
        }
        return granted;
    }

    /** Checkbox list (limited to what the actor may grant) + current selections. */
    private void addPermissionModelAttributes(Model model, User target) {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        model.addAttribute("grantablePermissions", permissionService.grantablePermissions(auth));
        model.addAttribute("isSuperAdmin", auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority())));
        model.addAttribute("selectedPermissions",
                target == null ? java.util.Set.of() : target.getPermissions());
    }

    // ---------------------------------------------------------------
    //  Deletion (admin) — soft delete is the default/primary action;
    //  hard delete is irreversible and double-confirmed.
    // ---------------------------------------------------------------

    /**
     * SOFT DELETE — deactivates the account. The user can no longer log in and
     * is hidden from the default list, but all data and references are kept.
     * Reversible via {@link #activate}.
     */
    @PostMapping("/deactivate/{id}")
    public String deactivate(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        String error = selfDeleteGuard(id, authentication);
        if (error != null) {
            redirectAttributes.addFlashAttribute("errorMessage", error);
            return "redirect:/users";
        }
        User user = userService.deactivateUser(id);
        redirectAttributes.addFlashAttribute("successMessage",
                "User '" + user.getUsername() + "' deactivated. They can no longer log in.");
        return "redirect:/users";
    }

    /** Reverses a soft delete — the account is active and can log in again. */
    @PostMapping("/activate/{id}")
    public String activate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        User user = userService.reactivateUser(id);
        redirectAttributes.addFlashAttribute("successMessage",
                "User '" + user.getUsername() + "' reactivated.");
        return "redirect:/users";
    }

    /**
     * HARD DELETE — permanent, irreversible. Requires {@code confirmName} to
     * equal the target's username (type-to-confirm), enforced server-side; the
     * UI additionally gates the button behind a type-to-confirm modal.
     */
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id,
                         @RequestParam(value = "confirmName", required = false) String confirmName,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        String error = selfDeleteGuard(id, authentication);
        if (error != null) {
            redirectAttributes.addFlashAttribute("errorMessage", error);
            return "redirect:/users";
        }
        User user = userService.findById(id);
        try {
            userService.hardDeleteUser(id, confirmName);
            redirectAttributes.addFlashAttribute("successMessage",
                    "User '" + user.getUsername() + "' permanently deleted.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    /** An admin must not be able to deactivate/delete their own account. */
    private String selfDeleteGuard(Long id, Authentication authentication) {
        User target = userService.findById(id);
        if (authentication != null && authentication.getName().equals(target.getUsername())) {
            return "You cannot deactivate or delete your own account.";
        }
        return null;
    }
}