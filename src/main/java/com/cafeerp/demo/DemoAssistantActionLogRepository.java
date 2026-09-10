package com.cafeerp.demo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.assistant.AssistantActionLog;
import com.cafeerp.assistant.AssistantActionLogRepository;
import com.cafeerp.user.User;

/**
 * Demo-mode {@link AssistantActionLogRepository}: session-scoped, so each
 * visitor's AI-action trail is private to their session. Replaces the JPA
 * bean only when the {@code demo} profile is active.
 */
@Repository("demoAssistantActionLogRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoAssistantActionLogRepository
        extends InMemoryJpaRepository<AssistantActionLog, Long>
        implements AssistantActionLogRepository {

    private final DemoSessionStore store;

    public DemoAssistantActionLogRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, AssistantActionLog> store() {
        return store.actionLogs();
    }

    @Override
    protected Long idOf(AssistantActionLog entity) {
        return entity.getId();
    }

    @Override
    protected void setId(AssistantActionLog entity, Long id) {
        try {
            java.lang.reflect.Field field = AssistantActionLog.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot assign demo action-log id", e);
        }
    }

    @Override
    protected Long nextId() {
        return store.nextActionLogId();
    }

    @Override
    public <S extends AssistantActionLog> S save(S entity) {
        // Mirrors @PrePersist (the entity exposes no setter for createdAt).
        if (entity.getCreatedAt() == null) {
            setField(entity, "createdAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
        return super.save(entity);
    }

    private static void setField(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot assign demo field " + field, e);
        }
    }

    private static final Comparator<AssistantActionLog> NEWEST_FIRST =
            Comparator.<AssistantActionLog, java.time.LocalDateTime>comparing(AssistantActionLog::getCreatedAt,
                            Comparator.<java.time.LocalDateTime>nullsFirst(
                                    Comparator.<java.time.LocalDateTime>naturalOrder()))
                    .reversed()
                    .thenComparing(AssistantActionLog::getId,
                            Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()).reversed());

    private static boolean ownedBy(AssistantActionLog log, User user) {
        return log.getUser() != null && user != null
                && user.getId() != null
                && user.getId().equals(log.getUser().getId());
    }

    @Override
    public List<AssistantActionLog> findByUserOrderByCreatedAtDescIdDesc(User user, Pageable pageable) {
        List<AssistantActionLog> all = store().values().stream()
                .filter(log -> ownedBy(log, user))
                .sorted(NEWEST_FIRST)
                .toList();
        return pageOf(all, pageable).getContent();
    }

    @Override
    public List<AssistantActionLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable) {
        List<AssistantActionLog> all = store().values().stream()
                .sorted(NEWEST_FIRST)
                .toList();
        return pageOf(all, pageable).getContent();
    }

    @Override
    public List<AssistantActionLog> findByUserAndStatusOrderByIdDesc(
            User user, AssistantActionLog.Status status) {
        return store().values().stream()
                .filter(log -> ownedBy(log, user) && log.getStatus() == status)
                .sorted(Comparator.<AssistantActionLog, Long>comparing(AssistantActionLog::getId,
                        Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()).reversed()))
                .toList();
    }

    @Override
    public void deleteByUserId(Long userId) {
        List<Long> ids = store().values().stream()
                .filter(log -> log.getUser() != null && userId.equals(log.getUser().getId()))
                .map(AssistantActionLog::getId)
                .toList();
        deleteAllById(ids);
    }
}
