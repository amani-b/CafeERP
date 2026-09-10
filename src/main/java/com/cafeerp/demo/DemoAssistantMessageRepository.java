package com.cafeerp.demo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.assistant.AssistantConversation;
import com.cafeerp.assistant.AssistantMessage;
import com.cafeerp.assistant.AssistantMessageRepository;
import com.cafeerp.user.User;

/**
 * Demo-mode {@link AssistantMessageRepository}: session-scoped, so each
 * visitor's chat history is private to their session. Replaces the JPA bean
 * only when the {@code demo} profile is active.
 */
@Repository("demoAssistantMessageRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoAssistantMessageRepository extends InMemoryJpaRepository<AssistantMessage, Long>
        implements AssistantMessageRepository {

    private final DemoSessionStore store;

    public DemoAssistantMessageRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, AssistantMessage> store() {
        return store.messages();
    }

    @Override
    protected Long idOf(AssistantMessage entity) {
        return entity.getId();
    }

    @Override
    protected void setId(AssistantMessage entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextMessageId();
    }

    @Override
    public <S extends AssistantMessage> S save(S entity) {
        // Mirrors @PrePersist: chat messages are always stamped in UTC.
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
        return super.save(entity);
    }

    private static final Comparator<AssistantMessage> OLDEST_FIRST =
            Comparator.<AssistantMessage, LocalDateTime>comparing(AssistantMessage::getCreatedAt,
                            Comparator.<LocalDateTime>nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                    .thenComparing(AssistantMessage::getId,
                            Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()));

    private static boolean ownedBy(AssistantMessage message, User user) {
        return message.getUser() != null && user != null
                && user.getId() != null
                && user.getId().equals(message.getUser().getId());
    }

    private static boolean inConversation(AssistantMessage message, AssistantConversation conversation) {
        return message.getConversation() != null && conversation != null
                && conversation.getId() != null
                && conversation.getId().equals(message.getConversation().getId());
    }

    @Override
    public List<AssistantMessage> findByUserOrderByCreatedAtAscIdAsc(User user) {
        return store().values().stream()
                .filter(message -> ownedBy(message, user))
                .sorted(OLDEST_FIRST)
                .toList();
    }

    @Override
    public List<AssistantMessage> findByConversationOrderByCreatedAtAscIdAsc(
            AssistantConversation conversation) {
        return store().values().stream()
                .filter(message -> inConversation(message, conversation))
                .sorted(OLDEST_FIRST)
                .toList();
    }

    @Override
    public List<User> findUsersWithMessagesOrderByMostRecent() {
        List<AssistantMessage> ordered = new ArrayList<>(store().values());
        ordered.sort(Comparator.<AssistantMessage, LocalDateTime>comparing(AssistantMessage::getCreatedAt,
                        Comparator.<LocalDateTime>nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                .reversed());
        Map<Long, User> seen = new java.util.LinkedHashMap<>();
        for (AssistantMessage message : ordered) {
            User user = message.getUser();
            if (user != null && user.getId() != null) {
                seen.putIfAbsent(user.getId(), user);
            }
        }
        return new ArrayList<>(seen.values());
    }

    @Override
    public void deleteByUser(User user) {
        List<Long> ids = store().values().stream()
                .filter(message -> ownedBy(message, user))
                .map(AssistantMessage::getId)
                .toList();
        deleteAllById(ids);
    }

    @Override
    public void deleteByConversation(AssistantConversation conversation) {
        List<Long> ids = store().values().stream()
                .filter(message -> inConversation(message, conversation))
                .map(AssistantMessage::getId)
                .toList();
        deleteAllById(ids);
    }
}
