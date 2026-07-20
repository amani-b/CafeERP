package com.cafeerp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryRepository inventoryRepository;

    public OrderService(OrderRepository orderRepository,
                        MenuItemRepository menuItemRepository,
                        InventoryRepository inventoryRepository) {
        this.orderRepository = orderRepository;
        this.menuItemRepository = menuItemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Order findById(Long id) {
        return orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> {
                    log.warn("Order not found: id={}", id);
                    return new IllegalArgumentException("Order not found");
                });
    }

    @Transactional
    public Order createOrder(Map<Long, Integer> quantities) {
        Order order = new Order();
        LocalDateTime now = LocalDateTime.now();

        quantities.forEach((menuItemId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                return;
            }

            Optional<MenuItem> opt = menuItemRepository.findById(menuItemId)
                    .filter(MenuItem::isAvailable)
                    .filter(menuItem -> menuItem.getPrice() != null)
                    .filter(menuItem -> menuItem.getPrice().compareTo(BigDecimal.ZERO) >= 0);

            if (opt.isEmpty()) {
                return;
            }

            MenuItem menuItem = opt.get();

            if (isStockInsufficient(menuItemId, quantity, now)) {
                log.warn("Insufficient stock for menu item: id={}, name={}", menuItemId, menuItem.getName());
                return;
            }

            order.addItem(menuItem, quantity);
        });

        if (order.getItems().isEmpty()) {
            log.warn("Order creation failed: no items selected or none available");
            throw new IllegalArgumentException("Select at least one available menu item.");
        }

        Order saved = orderRepository.save(order);
        log.info("Order created: id={}, items={}, total={}", saved.getId(), saved.getItemCount(), saved.getTotalAmount());
        return saved;
    }

    /**
     * For a tracked inventory item, atomically checks and decrements stock.
     * Returns true if the item should be excluded (insufficient stock).
     * Untracked items and items without an inventory row are never affected.
     */
    private boolean isStockInsufficient(Long menuItemId, int quantity, LocalDateTime now) {
        Optional<Inventory> invOpt = inventoryRepository.findByMenuItemId(menuItemId);
        if (invOpt.isEmpty()) {
            return false; // no inventory record → not tracked
        }
        if (!invOpt.get().isTrackInventory()) {
            return false; // not tracked → unaffected
        }
        // Atomic conditional UPDATE — check and decrement in one statement.
        // Returns 1 if the row was updated (stock sufficient), 0 if not.
        return inventoryRepository.decrementStockIfSufficient(menuItemId, quantity, now) == 0;
    }
}
