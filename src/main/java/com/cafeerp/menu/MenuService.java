package com.cafeerp.menu;

import java.util.List;

import com.cafeerp.category.CategoryRepository;
import org.springframework.stereotype.Service;

@Service
public class MenuService {

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
                .orElseThrow(() -> new IllegalArgumentException("Menu item not found"));
    }

    public MenuItem save(MenuItem menuItem) {
        Long categoryId = menuItem.getCategory().getId();
        menuItem.setCategory(categoryRepository.getReferenceById(categoryId));
        return menuItemRepository.save(menuItem);
    }
}
