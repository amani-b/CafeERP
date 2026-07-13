package com.cafeerp.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select distinct o from CafeOrder o left join fetch o.items order by o.createdAt desc")
    List<Order> findAllByOrderByCreatedAtDesc();
}
