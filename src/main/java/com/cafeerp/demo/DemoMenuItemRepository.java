package com.cafeerp.demo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;

/**
 * Demo-mode {@link MenuItemRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 */
@Repository("demoMenuItemRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoMenuItemRepository extends InMemoryJpaRepository<MenuItem, Long>
        implements MenuItemRepository {

    private final DemoSessionStore store;

    public DemoMenuItemRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, MenuItem> store() {
        return store.menuItems();
    }

    @Override
    protected Long idOf(MenuItem entity) {
        return entity.getId();
    }

    @Override
    protected void setId(MenuItem entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextMenuItemId();
    }

    @Override
    public List<MenuItem> findByAvailableTrue() {
        return store().values().stream()
                .filter(MenuItem::isAvailable)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Override
    public Optional<MenuItem> findFirstByNameIgnoreCase(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return store().values().stream()
                .filter(item -> item.getName() != null && item.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }
}
