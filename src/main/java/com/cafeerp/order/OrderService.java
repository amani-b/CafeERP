package com.cafeerp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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

    /**
     * Phase 9: newest-first page of orders WITHOUT line items, for the
     * assistant's getOrderHistory tool. The limit is enforced by the
     * database (no full-table fetch join + in-memory filter/limit).
     */
    @Transactional(readOnly = true)
    public List<Order> findRecent(int limit) {
        return orderRepository.findRecentPage(PageRequest.of(0, limit)).getContent();
    }

    /** Status-filtered variant of {@link #findRecent(int)}. */
    @Transactional(readOnly = true)
    public List<Order> findRecentByStatus(OrderStatus status, int limit) {
        return orderRepository.findRecentPageByStatus(status, PageRequest.of(0, limit)).getContent();
    }

    @Transactional(readOnly = true)
    public Order findById(Long id) {
        return orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> {
                    log.warn("Order not found: id={}", id);
                    return new IllegalArgumentException("Order not found");
                });
    }

    @Transactional(readOnly = true)
    public List<Order> findActiveOrders() {
        return orderRepository.findByStatusIn(
                List.of(OrderStatus.PENDING, OrderStatus.PREPARING, OrderStatus.READY));
    }

    @Transactional
    public Order updateStatus(Long id, OrderStatus newStatus) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> {
                    log.warn("Order not found for status update: id={}", id);
                    return new IllegalArgumentException("Order not found");
                });
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        log.info("Order status updated: id={}, status={}", saved.getId(), saved.getStatus());
        return saved;
    }

    /**
     * Creates an order from a quantity map.
     * <p>
     * Availability filtering: unavailable menu items are silently excluded
     * (they should never have been selectable), but tracked inventory items
     * with insufficient stock REJECT the whole order with a clear message —
     * silently dropping a paid-for line item would short both the customer
     * and the sales report.
     */
    @Transactional
    public Order createOrder(Map<Long, Integer> quantities) {
        Order order = new Order();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

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
                log.warn("Order rejected: insufficient stock for menu item id={}, name={}, requested={}",
                        menuItemId, menuItem.getName(), quantity);
                throw new IllegalArgumentException(
                        "Insufficient stock for \"" + menuItem.getName() + "\".");
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
