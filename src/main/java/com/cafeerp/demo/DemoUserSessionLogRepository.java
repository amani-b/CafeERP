package com.cafeerp.demo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;

/**
 * Demo-mode {@link UserSessionLogRepository}: session-scoped, seeded per
 * visitor. Replaces the JPA bean only when the {@code demo} profile is
 * active.
 */
@Repository("demoUserSessionLogRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoUserSessionLogRepository
        extends InMemoryJpaRepository<UserSessionLog, Long>
        implements UserSessionLogRepository {

    private final DemoSessionStore store;

    public DemoUserSessionLogRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, UserSessionLog> store() {
        return store.sessionLogs();
    }

    @Override
    protected Long idOf(UserSessionLog entity) {
        return entity.getId();
    }

    @Override
    protected void setId(UserSessionLog entity, Long id) {
        try {
            java.lang.reflect.Field field = UserSessionLog.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot assign demo session-log id", e);
        }
    }

    @Override
    protected Long nextId() {
        return store.nextSessionLogId();
    }

    @Override
    public <S extends UserSessionLog> S save(S entity) {
        // Mirrors @PrePersist (the entity exposes no setter for occurredAt).
        if (entity.getOccurredAt() == null) {
            try {
                java.lang.reflect.Field field = UserSessionLog.class.getDeclaredField("occurredAt");
                field.setAccessible(true);
                field.set(entity, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot stamp demo session log", e);
            }
        }
        return super.save(entity);
    }

    @Override
    public List<UserSessionLog> findByUsernameIgnoreCaseOrderByOccurredAtDescIdDesc(
            String username, Pageable pageable) {
        List<UserSessionLog> all = store().values().stream()
                .filter(log -> log.getUsername() != null
                        && log.getUsername().equalsIgnoreCase(username))
                .sorted(Comparator.<UserSessionLog, java.time.LocalDateTime>comparing(
                                UserSessionLog::getOccurredAt,
                                Comparator.<java.time.LocalDateTime>nullsFirst(
                                        Comparator.<java.time.LocalDateTime>naturalOrder()))
                        .reversed()
                        .thenComparing(UserSessionLog::getId,
                                Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()).reversed()))
                .toList();
        return pageOf(all, pageable).getContent();
    }

    @Override
    public void deleteByUserId(Long userId) {
        List<Long> ids = store().values().stream()
                .filter(log -> log.getUser() != null && userId.equals(log.getUser().getId()))
                .map(UserSessionLog::getId)
                .toList();
        deleteAllById(ids);
    }
}
