package com.cafeerp.assistant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cafeerp.user.User;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    /**
     * Legacy per-user thread. Ordered by createdAt then id: two messages on a
     * fast path (access-guard denial, deterministic lookup) can be persisted
     * within the same microsecond, so createdAt alone does not guarantee the
     * user's question sorts before the assistant's answer — the id tiebreak
     * (identity, insertion order) makes the order deterministic. This was the
     * root cause of history sometimes showing a reply ABOVE its question.
     */
    List<AssistantMessage> findByUserOrderByCreatedAtAscIdAsc(User user);

    /** All messages of one conversation, oldest first, deterministic order. */
    List<AssistantMessage> findByConversationOrderByCreatedAtAscIdAsc(AssistantConversation conversation);

    @Query("select distinct am.user from AssistantMessage am")
    List<User> findDistinctUsersWithMessages();

    /** Hard-delete support: remove a user's chat history. */
    void deleteByUser(User user);
}