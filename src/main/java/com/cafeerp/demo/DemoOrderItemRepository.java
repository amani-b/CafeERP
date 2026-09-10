package com.cafeerp.demo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.order.ItemSalesProjection;
import com.cafeerp.order.OrderItem;
import com.cafeerp.order.OrderItemRepository;

/**
 * Demo-mode {@link OrderItemRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 */
@Repository("demoOrderItemRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoOrderItemRepository extends InMemoryJpaRepository<OrderItem, Long>
        implements OrderItemRepository {

    private final DemoSessionStore store;

    public DemoOrderItemRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, OrderItem> store() {
        return store.orderItems();
    }

    @Override
    protected Long idOf(OrderItem entity) {
        return entity.getId();
    }

    @Override
    protected void setId(OrderItem entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextOrderItemId();
    }

    @Override
    public List<ItemSalesProjection> findTopSellingItems(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (OrderItem item : store().values()) {
            LocalDateTime createdAt = item.getOrder() != null ? item.getOrder().getCreatedAt() : null;
            if (createdAt == null || createdAt.isBefore(from) || createdAt.isAfter(to)) {
                continue;
            }
            totals.merge(item.getItemName(), (long) item.getQuantity(), Long::sum);
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new DemoItemSales(entry.getKey(), entry.getValue()))
                .map(ItemSalesProjection.class::cast)
                .toList();
    }

    /** Simple in-memory {@link ItemSalesProjection}. */
    public record DemoItemSales(String itemName, Long totalQuantity) implements ItemSalesProjection {
        @Override
        public String getItemName() {
            return itemName;
        }

        @Override
        public Long getTotalQuantity() {
            return totalQuantity;
        }
    }
}
