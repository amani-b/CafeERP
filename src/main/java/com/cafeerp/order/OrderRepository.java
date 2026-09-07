package com.cafeerp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select distinct o from CafeOrder o left join fetch o.items order by o.createdAt desc")
    List<Order> findAllByOrderByCreatedAtDesc();

    /**
     * Phase 9: paged newest-first order lookup WITHOUT the items fetch join.
     * The assistant's getOrderHistory tool only reads order-level columns
     * (id, status, itemCount, totalAmount, createdAt), so fetching every
     * order's line items just to keep {@code limit} rows was pure waste —
     * now the limit pushes down to the database.
     */
    @Query("select o from CafeOrder o order by o.createdAt desc")
    Page<Order> findRecentPage(Pageable pageable);

    /** Status-filtered variant of {@link #findRecentPage(Pageable)}. */
    @Query("select o from CafeOrder o where o.status = :status order by o.createdAt desc")
    Page<Order> findRecentPageByStatus(@Param("status") OrderStatus status, Pageable pageable);

    @Query("select o from CafeOrder o left join fetch o.items where o.id = :id")
    java.util.Optional<Order> findByIdWithItems(Long id);

    @Query("select distinct o from CafeOrder o left join fetch o.items "
         + "where o.status in :statuses order by o.createdAt desc")
    List<Order> findByStatusIn(@Param("statuses") List<OrderStatus> statuses);

    @Query("select coalesce(sum(o.totalAmount), 0) from CafeOrder o where o.createdAt >= :from and o.createdAt <= :to")
    BigDecimal sumTotalAmountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from CafeOrder o where o.createdAt >= :from and o.createdAt <= :to")
    long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Phase 9: combined sales aggregate — total AND count in ONE round trip.
     * The report path previously issued these as two separate queries over
     * the same range. The single row layout is {@code [total, count]} where
     * total is numeric (BigDecimal when rows exist, Integer 0 when the range
     * is empty — callers must convert, not cast).
     */
    @Query("select coalesce(sum(o.totalAmount), 0), count(o) from CafeOrder o "
         + "where o.createdAt >= :from and o.createdAt <= :to")
    List<Object[]> sumAndCountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
