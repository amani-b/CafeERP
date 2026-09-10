package com.cafeerp.demo;

import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;

/**
 * Demo-mode {@link CategoryRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 */
@Repository("demoCategoryRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoCategoryRepository extends InMemoryJpaRepository<Category, Long>
        implements CategoryRepository {

    private final DemoSessionStore store;

    public DemoCategoryRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<Long, Category> store() {
        return store.categories();
    }

    @Override
    protected Long idOf(Category entity) {
        return entity.getId();
    }

    @Override
    protected void setId(Category entity, Long id) {
        entity.setId(id);
    }

    @Override
    protected Long nextId() {
        return store.nextCategoryId();
    }
}
