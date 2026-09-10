package com.cafeerp.demo;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.menu.MenuItem;

/**
 * Demo-mode {@link InventoryRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 */
@Repository("demoInventoryRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoInventoryRepository extends InMemoryJpaRepository<Inventory, Long>
        implements InventoryRepository {

    private final DemoSessionStore store;

    public DemoInventoryRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, Inventory> store() {
        return store.inventories();
    }

    @Override
    protected Long idOf(Inventory entity) {
        return entity.getId();
    }

    @Override
    protected void setId(Inventory entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextInventoryId();
    }

    @Override
    public Optional<Inventory> findByMenuItem(MenuItem menuItem) {
        if (menuItem == null || menuItem.getId() == null) {
            return Optional.empty();
        }
        return findByMenuItemId(menuItem.getId());
    }

    @Override
    public Optional<Inventory> findByMenuItemId(Long menuItemId) {
        return store().values().stream()
                .filter(inv -> inv.getMenuItem() != null
                        && menuItemId.equals(inv.getMenuItem().getId()))
                .findFirst();
    }

    @Override
    public Optional<Inventory> findByMenuItem_NameIgnoreCase(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return store().values().stream()
                .filter(inv -> inv.getMenuItem() != null
                        && inv.getMenuItem().getName() != null
                        && inv.getMenuItem().getName().equalsIgnoreCase(wanted))
                .findFirst();
    }

    @Override
    public long countLowStockItems() {
        return store().values().stream()
                .filter(inv -> inv.isTrackInventory()
                        && inv.getStockQuantity() <= inv.getLowStockThreshold())
                .count();
    }

    @Override
    public int decrementStockIfSufficient(Long menuItemId, int qty, LocalDateTime now) {
        Optional<Inventory> found = findByMenuItemId(menuItemId);
        if (found.isEmpty()) {
            return 0;
        }
        Inventory inventory = found.get();
        synchronized (inventory) {
            if (inventory.getStockQuantity() < qty) {
                return 0;
            }
            inventory.setStockQuantity(inventory.getStockQuantity() - qty);
            inventory.setLastUpdatedAt(now);
            return 1;
        }
    }
}
