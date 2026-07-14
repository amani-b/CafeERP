package com.cafeerp.inventory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public List<Inventory> findAll() {
        return inventoryRepository.findAll();
    }

    /**
     * Returns the number of tracked inventory items whose stockQuantity
     * is at or below their lowStockThreshold.
     */
    @Transactional(readOnly = true)
    public long countLowStock() {
        return inventoryRepository.findAll().stream()
                .filter(inv -> inv.isTrackInventory() && inv.getStockQuantity() <= inv.getLowStockThreshold())
                .count();
    }

    @Transactional
    public void update(Long id, boolean trackInventory, int stockQuantity, int lowStockThreshold) {
        Inventory inv = inventoryRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Inventory not found: id={}", id);
                    return new IllegalArgumentException("Inventory not found");
                });

        int oldStock = inv.getStockQuantity();
        int oldThreshold = inv.getLowStockThreshold();
        boolean oldTrack = inv.isTrackInventory();

        inv.setTrackInventory(trackInventory);
        inv.setStockQuantity(stockQuantity);
        inv.setLowStockThreshold(lowStockThreshold);
        inventoryRepository.save(inv);

        log.info("Inventory updated: id={}, menuItem={}, trackInventory={}->{}, stockQuantity={}->{}, lowStockThreshold={}->{}",
                inv.getId(), inv.getMenuItem().getName(),
                oldTrack, trackInventory,
                oldStock, stockQuantity,
                oldThreshold, lowStockThreshold);
    }
}