package com.cafeerp.assistant;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cafeerp.user.User;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    /** Default history list: newest activity first, archived excluded. */
    List<AssistantConversation> findByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(User user);

    /** "Archived" filter: recoverable past conversations, newest first. */
    List<AssistantConversation> findByUserAndArchivedAtIsNotNullOrderByLastActivityAtDesc(User user);

    /** The conversation a plain (no explicit id) chat turn should attach to. */
    Optional<AssistantConversation> findFirstByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(User user);

    /** Candidates for the auto-archival scheduled job. */
    List<AssistantConversation> findByArchivedAtIsNullAndLastActivityAtBefore(LocalDateTime cutoff);

    /** Hard-delete support: remove a user's conversations (messages first). */
    void deleteByUser(User user);
}