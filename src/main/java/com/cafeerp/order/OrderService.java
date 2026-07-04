package com.cafeerp.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;

    public OrderService(OrderRepository orderRepository, MenuItemRepository menuItemRepository) {
        this.orderRepository = orderRepository;
        this.menuItemRepository = menuItemRepository;
    }

    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Order createOrder(Map<Long, Integer> quantities) {
        Order order = new Order();

        quantities.forEach((menuItemId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                return;
            }

            menuItemRepository.findById(menuItemId)
                    .filter(MenuItem::isAvailable)
                    .filter(menuItem -> menuItem.getPrice() != null)
                    .filter(menuItem -> menuItem.getPrice().compareTo(BigDecimal.ZERO) >= 0)
                    .ifPresent(menuItem -> order.addItem(menuItem, quantity));
        });

        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("Select at least one available menu item.");
        }

        return orderRepository.save(order);
    }
}
