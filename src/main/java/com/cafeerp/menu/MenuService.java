package com.cafeerp.menu;

import java.util.List;

import com.cafeerp.category.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final MenuItemRepository menuItemRepository;
    private final CategoryRepository categoryRepository;

    public MenuService(MenuItemRepository menuItemRepository, CategoryRepository categoryRepository) {
        this.menuItemRepository = menuItemRepository;
        this.categoryRepository = categoryRepository;
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

    public MenuItem save(MenuItem menuItem) {
        boolean isNew = menuItem.getId() == null;
        Long categoryId = menuItem.getCategory().getId();
        menuItem.setCategory(categoryRepository.getReferenceById(categoryId));
        MenuItem saved = menuItemRepository.save(menuItem);
        if (isNew) {
            log.info("Menu item created: id={}, name={}", saved.getId(), saved.getName());
        } else {
            log.info("Menu item updated: id={}, name={}", saved.getId(), saved.getName());
        }
        return saved;
    }
}
