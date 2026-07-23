package com.cafeerp.order;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("""
        select oi.itemName as itemName, sum(oi.quantity) as totalQuantity
        from OrderItem oi
        join oi.order o
        where o.createdAt >= :from and o.createdAt <= :to
        group by oi.itemName
        order by totalQuantity desc
        """)
    List<ItemSalesProjection> findTopSellingItems(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);
}