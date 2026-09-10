package com.cafeerp.demo;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.assistant.AssistantConversation;
import com.cafeerp.assistant.AssistantConversationRepository;
import com.cafeerp.user.User;

/**
 * Demo-mode {@link AssistantConversationRepository}: session-scoped, so each
 * visitor's chat threads are private to their session. Replaces the JPA bean
 * only when the {@code demo} profile is active.
 */
@Repository("demoAssistantConversationRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoAssistantConversationRepository
        extends InMemoryJpaRepository<AssistantConversation, Long>
        implements AssistantConversationRepository {

    private final DemoSessionStore store;

    public DemoAssistantConversationRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, AssistantConversation> store() {
        return store.conversations();
    }

    @Override
    protected Long idOf(AssistantConversation entity) {
        return entity.getId();
    }

    @Override
    protected void setId(AssistantConversation entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextConversationId();
    }

    @Override
    public <S extends AssistantConversation> S save(S entity) {
        // Mirrors @PrePersist/@PreUpdate: activity timestamps stay in UTC.
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        if (entity.getLastActivityAt() == null) {
            entity.setLastActivityAt(entity.getCreatedAt() != null ? entity.getCreatedAt() : now);
        }
        return super.save(entity);
    }

    private static final Comparator<AssistantConversation> NEWEST_ACTIVITY_FIRST =
            Comparator.<AssistantConversation, LocalDateTime>comparing(AssistantConversation::getLastActivityAt,
                            Comparator.<LocalDateTime>nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                    .reversed()
                    .thenComparing(AssistantConversation::getId,
                            Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()).reversed());

    private static boolean ownedBy(AssistantConversation conversation, User user) {
        return conversation.getUser() != null && user != null
                && user.getId() != null
                && user.getId().equals(conversation.getUser().getId());
    }

    @Override
    public List<AssistantConversation> findByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(User user) {
        return store().values().stream()
                .filter(c -> ownedBy(c, user) && c.getArchivedAt() == null)
                .sorted(NEWEST_ACTIVITY_FIRST)
                .toList();
    }

    @Override
    public List<AssistantConversation> findByUserAndArchivedAtIsNotNullOrderByLastActivityAtDesc(User user) {
        return store().values().stream()
                .filter(c -> ownedBy(c, user) && c.getArchivedAt() != null)
                .sorted(NEWEST_ACTIVITY_FIRST)
                .toList();
    }

    @Override
    public Optional<AssistantConversation> findFirstByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(User user) {
        return findByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(user).stream().findFirst();
    }

    @Override
    public List<AssistantConversation> findByArchivedAtIsNullAndLastActivityAtBefore(LocalDateTime cutoff) {
        return store().values().stream()
                .filter(c -> c.getArchivedAt() == null
                        && c.getLastActivityAt() != null
                        && c.getLastActivityAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public List<AssistantConversation> findByArchivedAtIsNotNullAndArchivedAtBefore(LocalDateTime cutoff) {
        return store().values().stream()
                .filter(c -> c.getArchivedAt() != null && c.getArchivedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public void deleteByUser(User user) {
        List<Long> ids = store().values().stream()
                .filter(c -> ownedBy(c, user))
                .map(AssistantConversation::getId)
                .toList();
        deleteAllById(ids);
    }
}
