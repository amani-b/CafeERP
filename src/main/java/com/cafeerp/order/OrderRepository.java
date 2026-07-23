package com.cafeerp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select distinct o from CafeOrder o left join fetch o.items order by o.createdAt desc")
    List<Order> findAllByOrderByCreatedAtDesc();

    @Query("select o from CafeOrder o left join fetch o.items where o.id = :id")
    java.util.Optional<Order> findByIdWithItems(Long id);

    @Query("select distinct o from CafeOrder o left join fetch o.items "
         + "where o.status in :statuses order by o.createdAt desc")
    List<Order> findByStatusIn(@Param("statuses") List<OrderStatus> statuses);

    @Query("select coalesce(sum(o.totalAmount), 0) from CafeOrder o where o.createdAt >= :from and o.createdAt <= :to")
    BigDecimal sumTotalAmountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from CafeOrder o where o.createdAt >= :from and o.createdAt <= :to")
    long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
