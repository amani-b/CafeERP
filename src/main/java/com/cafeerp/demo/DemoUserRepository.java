package com.cafeerp.demo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

/**
 * Demo-mode {@link UserRepository}: session-scoped, seeded per visitor with
 * the three fictional demo accounts. Replaces the JPA bean only when the
 * {@code demo} profile is active.
 */
@Repository("demoUserRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoUserRepository extends InMemoryJpaRepository<User, Long>
        implements UserRepository {

    private final DemoSessionStore store;

    public DemoUserRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, User> store() {
        return store.users();
    }

    @Override
    protected Long idOf(User entity) {
        return entity.getId();
    }

    @Override
    protected void setId(User entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextUserId();
    }

    @Override
    public Optional<User> findByUsernameAndDeletedAtIsNull(String username) {
        return findByUsername(username).filter(user -> user.getDeletedAt() == null);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return store().values().stream()
                .filter(user -> username.equals(user.getUsername()))
                .findFirst();
    }

    @Override
    public List<User> findAllByDeletedAtIsNullOrderByIdAsc() {
        return store().values().stream()
                .filter(user -> user.getDeletedAt() == null)
                .sorted(Comparator.comparing(User::getId))
                .toList();
    }

    @Override
    public List<User> findAllByDeletedAtIsNotNullOrderByIdAsc() {
        return store().values().stream()
                .filter(user -> user.getDeletedAt() != null)
                .sorted(Comparator.comparing(User::getId))
                .toList();
    }
}
