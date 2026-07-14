package com.cafeerp.inventory;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cafeerp.menu.MenuItem;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByMenuItem(MenuItem menuItem);

    Optional<Inventory> findByMenuItemId(Long menuItemId);

    /**
     * Atomically decrements stock for a menu item if sufficient stock exists.
     * Returns the number of rows updated (1 on success, 0 if stock insufficient
     * or item not found). This prevents overselling under concurrent requests
     * because the check-and-decrement happens in a single SQL statement.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Inventory i SET i.stockQuantity = i.stockQuantity - :qty, "
         + "i.lastUpdatedAt = :now "
         + "WHERE i.menuItem.id = :menuItemId AND i.stockQuantity >= :qty")
    int decrementStockIfSufficient(@Param("menuItemId") Long menuItemId,
                                   @Param("qty") int qty,
                                   @Param("now") LocalDateTime now);
}
