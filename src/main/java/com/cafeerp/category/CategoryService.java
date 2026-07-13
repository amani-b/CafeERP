package com.cafeerp.category;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Category not found: id={}", id);
                    return new IllegalArgumentException("Category not found");
                });
    }

    public Category save(Category category) {
        boolean isNew = category.getId() == null;
        Category saved = categoryRepository.save(category);
        if (isNew) {
            log.info("Category created: id={}, name={}", saved.getId(), saved.getName());
        } else {
            log.info("Category updated: id={}, name={}", saved.getId(), saved.getName());
        }
        return saved;
    }
}
