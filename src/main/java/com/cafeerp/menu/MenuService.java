package com.cafeerp.menu;

import java.util.List;

import com.cafeerp.category.CategoryRepository;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final MenuItemRepository menuItemRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;

    public MenuService(MenuItemRepository menuItemRepository,
                       CategoryRepository categoryRepository,
                       InventoryRepository inventoryRepository) {
        this.menuItemRepository = menuItemRepository;
        this.categoryRepository = categoryRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public List<MenuItem> findAll() {
        return menuItemRepository.findAll();
    }

    public List<MenuItem> findAvailable() {
        return menuItemRepository.findByAvailableTrue();
    }

    public MenuItem findById(Long id) {
        return menuItemRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Menu item not found: id={}", id);
                    return new IllegalArgumentException("Menu item not found");
                });
    }

    @Transactional
    public MenuItem save(MenuItem menuItem) {
        boolean isNew = menuItem.getId() == null;
        Long categoryId = menuItem.getCategory().getId();
        menuItem.setCategory(categoryRepository.getReferenceById(categoryId));
        MenuItem saved = menuItemRepository.save(menuItem);
        if (isNew) {
            inventoryRepository.save(new Inventory(saved));
            log.info("Menu item created: id={}, name={}", saved.getId(), saved.getName());
        } else {
            log.info("Menu item updated: id={}, name={}", saved.getId(), saved.getName());
        }
        return saved;
    }
}
