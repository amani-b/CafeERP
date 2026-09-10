package com.cafeerp.demo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.order.Order;
import com.cafeerp.order.OrderItem;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.order.OrderStatus;

/**
 * Demo-mode {@link OrderRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 * <p>
 * Saves cascade line items into the session's item map (mirroring JPA's
 * {@code CascadeType.ALL} + orphan removal) so the report queries observe
 * the same shape they get from the database.
 */
@Repository("demoOrderRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoOrderRepository extends InMemoryJpaRepository<Order, Long>
        implements OrderRepository {

    private final DemoSessionStore store;

    public DemoOrderRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, Order> store() {
        return store.orders();
    }

    @Override
    protected Long idOf(Order entity) {
        return entity.getId();
    }

    @Override
    protected void setId(Order entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextOrderId();
    }

    @Override
    public <S extends Order> S save(S entity) {
        // Mirrors @PrePersist: orders are always stamped in UTC at creation.
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
        return super.save(entity);
    }

    @Override
    protected void afterSave(Order entity) {
        // Cascade items like JPA would: assign ids, link back, index them.
        for (OrderItem item : entity.getItems()) {
            if (item.getId() == null) {
                item.setId(store.nextOrderItemId());
            }
            item.setOrder(entity);
            store.orderItems().put(item.getId(), item);
        }
        // Orphan removal: drop indexed items no longer on the order.
        store.orderItems().values().removeIf(item -> item.getOrder() != null
                && entity.getId().equals(item.getOrder().getId())
                && !entity.getItems().contains(item));
    }

    @Override
    protected void afterDelete(Order entity) {
        store.orderItems().values().removeIf(item -> item.getOrder() != null
                && entity.getId().equals(item.getOrder().getId()));
    }

    private static final Comparator<Order> NEWEST_FIRST =
            Comparator.<Order, LocalDateTime>comparing(Order::getCreatedAt,
                            Comparator.<LocalDateTime>nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                    .reversed()
                    .thenComparing(Order::getId,
                            Comparator.<Long>nullsFirst(Comparator.<Long>naturalOrder()).reversed());

    @Override
    public List<Order> findAllByOrderByCreatedAtDesc() {
        return store().values().stream().sorted(NEWEST_FIRST).toList();
    }

    @Override
    public Page<Order> findRecentPage(Pageable pageable) {
        return pageOf(findAllByOrderByCreatedAtDesc(), pageable);
    }

    @Override
    public Page<Order> findRecentPageByStatus(OrderStatus status, Pageable pageable) {
        List<Order> filtered = store().values().stream()
                .filter(order -> order.getStatus() == status)
                .sorted(NEWEST_FIRST)
                .toList();
        return pageOf(filtered, pageable);
    }

    @Override
    public Optional<Order> findByIdWithItems(Long id) {
        return findById(id);
    }

    @Override
    public List<Order> findByStatusIn(List<OrderStatus> statuses) {
        return store().values().stream()
                .filter(order -> statuses.contains(order.getStatus()))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public BigDecimal sumTotalAmountBetween(LocalDateTime from, LocalDateTime to) {
        return store().values().stream()
                .filter(order -> inRange(order.getCreatedAt(), from, to))
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to) {
        return store().values().stream()
                .filter(order -> inRange(order.getCreatedAt(), from, to))
                .count();
    }

    @Override
    public List<Object[]> sumAndCountBetween(LocalDateTime from, LocalDateTime to) {
        List<Order> inRange = store().values().stream()
                .filter(order -> inRange(order.getCreatedAt(), from, to))
                .toList();
        BigDecimal total = inRange.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { total, (long) inRange.size() });
        return result;
    }

    private static boolean inRange(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        return value != null && !value.isBefore(from) && !value.isAfter(to);
    }
}
