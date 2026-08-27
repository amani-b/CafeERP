package com.cafeerp.user;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cafeerp.assistant.AssistantConversationRepository;
import com.cafeerp.assistant.AssistantMessageRepository;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // Used only by the admin hard-delete to remove the deleted user's chat
    // history (the sole FK referencing cafe_user). Repositories, not services —
    // no bean-level dependency cycle with the assistant package.
    private final AssistantMessageRepository messageRepository;
    private final AssistantConversationRepository conversationRepository;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AssistantMessageRepository messageRepository,
                       AssistantConversationRepository conversationRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * Changes the password for the given user.
     *
     * @param username        the logged-in user's username
     * @param currentPassword the current (old) password submitted
     * @param newPassword     the desired new password
     * @param confirmPassword repeated new password for confirmation
     * @throws IllegalArgumentException if validation fails (message is user-facing)
     */
    @Transactional
    public void changePassword(String username, String currentPassword,
                               String newPassword, String confirmPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        // Validate current password
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        // Validate new password length (minimum 8 characters)
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "New password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }

        // Validate confirmation matches
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("New password and confirmation do not match.");
        }

        // Encode and persist
        String encoded = passwordEncoder.encode(newPassword);
        user.setPassword(encoded);
        user.setMustChangePassword(false);
        userRepository.save(user);

        log.info("Password changed for user '{}' at {}", username, LocalDateTime.now());
    }

    // ---------------------------------------------------------------
    //  User management (admin)
    // ---------------------------------------------------------------

    /** Default admin list — active (not soft-deleted) users only. */
    public List<User> findAll() {
        return userRepository.findAllByDeletedAtIsNullOrderByIdAsc();
    }

    /** "Show inactive" admin list — soft-deleted users only. */
    public List<User> findAllDeleted() {
        return userRepository.findAllByDeletedAtIsNotNullOrderByIdAsc();
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("User not found: id={}", id);
                    return new IllegalArgumentException("User not found");
                });
    }

    public boolean usernameExists(String username) {
        return userRepository.findByUsername(username).isPresent();
    }

    @Transactional
    public User createUser(User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        User saved = userRepository.save(user);
        log.info("User created: id={}, username={}, role={}",
                saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    @Transactional
    public User updateUser(User user) {
        User existing = findById(user.getId());
        existing.setUsername(user.getUsername());
        existing.setRole(user.getRole());
        existing.setMustChangePassword(user.isMustChangePassword());
        User saved = userRepository.save(existing);
        log.info("User updated: id={}, username={}, role={}",
                saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    // ---------------------------------------------------------------
    //  Deletion (admin) — soft (default) and hard
    // ---------------------------------------------------------------

    /**
     * SOFT DELETE (deactivate) — the default, primary delete action.
     * <p>
     * Marks the account inactive by stamping {@code deleted_at}: the user can
     * no longer log in and disappears from default user lists, but the row and
     * all foreign-key references (assistant messages, etc.) are retained, so
     * the action is fully reversible via {@link #reactivateUser(Long)}.
     *
     * @throws IllegalArgumentException if the user does not exist or is already
     *                                  deactivated
     */
    @Transactional
    public User deactivateUser(Long id) {
        User user = findById(id);
        if (user.isDeleted()) {
            throw new IllegalArgumentException("User is already deactivated");
        }
        user.setDeletedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("User deactivated (soft delete): id={}, username={}",
                saved.getId(), saved.getUsername());
        return saved;
    }

    /** Reverses a soft delete — the account can log in again. */
    @Transactional
    public User reactivateUser(Long id) {
        User user = findById(id);
        if (!user.isDeleted()) {
            throw new IllegalArgumentException("User is not deactivated");
        }
        user.setDeletedAt(null);
        User saved = userRepository.save(user);
        log.info("User reactivated: id={}, username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * HARD DELETE — permanent, irreversible removal of the user record.
     * <p>
     * Reference handling per relationship (verified against the schema — the
     * ONLY foreign key referencing cafe_user is assistant_message.user_id):
     * <ul>
     *   <li><b>assistant_message / assistant_conversation</b> — the user's chat
     *       history is deleted along with them: it is personal conversation
     *       data with a NOT NULL FK and no meaning without the account, and it
     *       is not a business record.</li>
     *   <li><b>Orders, kitchen tickets, inventory, menu, categories</b> — no
     *       user reference exists in the schema (orders are not attributed to
     *       a creating user), so nothing needs reassigning or nullifying and
     *       no business record is ever cascade-deleted.</li>
     *   <li><b>createdBy-style audit fields</b> — none exist anywhere in the
     *       schema, so there is nothing to reassign.</li>
     * </ul>
     *
     * @param expectedUsername the username typed by the admin to confirm the
     *                         irreversible action; must match exactly
     * @throws IllegalArgumentException if the user does not exist or the
     *                                  confirmation does not match
     */
    @Transactional
    public void hardDeleteUser(Long id, String expectedUsername) {
        User user = findById(id);

        if (expectedUsername == null || !expectedUsername.equals(user.getUsername())) {
            log.warn("Hard delete confirmation mismatch for user id={} (typed '{}')",
                    id, expectedUsername);
            throw new IllegalArgumentException(
                    "Confirmation does not match the username. Type the username exactly to confirm.");
        }

        // 1. Chat history — messages first (FK to conversation), then conversations.
        messageRepository.deleteByUser(user);
        conversationRepository.deleteByUser(user);

        // 2. The account itself. No other table references cafe_user.
        userRepository.delete(user);
        log.info("User HARD deleted: id={}, username={}", id, user.getUsername());
    }
}