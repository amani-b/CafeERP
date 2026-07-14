package com.cafeerp.inventory;

import com.cafeerp.menu.MenuItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import java.time.LocalDateTime;

@Entity
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "menu_item_id", unique = true, nullable = false)
    private MenuItem menuItem;

    @Column(nullable = false)
    private boolean trackInventory = false;

    @Column(nullable = false)
    private int stockQuantity = 0;

    @Column(nullable = false)
    private int lowStockThreshold = 0;

    @Column
    private LocalDateTime lastUpdatedAt;

    public Inventory() {
    }

    public Inventory(MenuItem menuItem) {
        this.menuItem = menuItem;
    }

    @PreUpdate
    public void preUpdate() {
        this.lastUpdatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public MenuItem getMenuItem() {
        return menuItem;
    }

    public void setMenuItem(MenuItem menuItem) {
        this.menuItem = menuItem;
    }

    public boolean isTrackInventory() {
        return trackInventory;
    }

    public void setTrackInventory(boolean trackInventory) {
        this.trackInventory = trackInventory;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    /**
     * Sets the stock quantity and updates the lastUpdatedAt timestamp.
     */
    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
        this.lastUpdatedAt = LocalDateTime.now();
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public LocalDateTime getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(LocalDateTime lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}