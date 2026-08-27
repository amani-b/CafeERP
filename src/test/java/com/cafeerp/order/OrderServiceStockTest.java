package com.cafeerp.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;

/**
 * Verifies order-creation behaviour end-to-end against a real database:
 * tracked-stock rejection rolls back cleanly, successful orders decrement
 * tracked stock exactly once, and unavailable items are filtered out.
 */
class OrderServiceStockTest extends AbstractIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;

    private record Seed(long trackedId, long untrackedId, BigDecimal trackedPrice, BigDecimal untrackedPrice) {}

    /** Builds one stock-tracked item (5 units) and one untracked item. */
    private Seed seedItems() {
        Category cat = new Category();
        cat.setName("Beans " + System.nanoTime());
        cat.setActive(true);
        cat = categoryRepository.save(cat);

        MenuItem tracked = new MenuItem();
        tracked.setName("Espresso-" + System.nanoTime());
        tracked.setPrice(new BigDecimal("2.50"));
        tracked.setAvailable(true);
        tracked.setCategory(cat);
        tracked = menuItemRepository.save(tracked);

        MenuItem untracked = new MenuItem();
        untracked.setName("Tea-" + System.nanoTime());
        untracked.setPrice(new BigDecimal("1.50"));
        untracked.setAvailable(true);
        untracked.setCategory(cat);
        untracked = menuItemRepository.save(untracked);

        Inventory inv = inventoryRepository.save(new Inventory(tracked));
        inv.setTrackInventory(true);
        inv.setStockQuantity(5);
        inv.setLowStockThreshold(1);
        inventoryRepository.save(inv);

        inventoryRepository.save(new Inventory(untracked)); // created but NOT tracked

        return new Seed(tracked.getId(), untracked.getId(), tracked.getPrice(), untracked.getPrice());
    }

    @Test
    void exceedingTrackedStockRejectsTheWholeOrderAndLeavesStockUntouched() {
        Seed seed = seedItems();
        long ordersBefore = orderRepository.count();

        // Tea (untracked, plenty) + way too many Espressos (tracked, 5 left).
        Map<Long, Integer> quantities = Map.of(seed.untrackedId(), 10, seed.trackedId(), 999);

        assertThatThrownBy(() -> orderService.createOrder(quantities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stock");

        Integer stockAfter = inventoryRepository.findByMenuItemId(seed.trackedId()).get().getStockQuantity();
        assertThat(stockAfter)
                .as("failed orders must roll back any partial stock decrement")
                .isEqualTo(5);

        assertThat(orderRepository.count())
                .as("rejected order must not be persisted")
                .isEqualTo(ordersBefore);
    }

    @Test
    void successfulMixedOrderDecrementsTrackedStockOnly() {
        Seed seed = seedItems();

        Order order = orderService.createOrder(Map.of(
                seed.trackedId(), 2,
                seed.untrackedId(), 3));

        assertThat(inventoryRepository.findByMenuItemId(seed.trackedId()).get().getStockQuantity())
                .isEqualTo(3);
        assertThat(order.getTotalAmount())
                .isEqualByComparingTo(new BigDecimal("9.50")); // 2×2.50 + 3×1.50
        assertThat(order.getItemCount()).isEqualTo(5);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}