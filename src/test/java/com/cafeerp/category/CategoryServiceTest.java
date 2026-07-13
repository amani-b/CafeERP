package com.cafeerp.category;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void findById_whenFound_shouldReturnCategory() {
        Category category = new Category();
        category.setId(1L);
        category.setName("Beverages");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        Category result = categoryService.findById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Beverages");
    }

    @Test
    void findById_whenNotFound_shouldThrow() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category not found");
    }

    @Test
    void save_newCategory_shouldPersistAndReturn() {
        Category toSave = new Category();
        toSave.setName("Snacks");

        Category saved = new Category();
        saved.setId(1L);
        saved.setName("Snacks");

        when(categoryRepository.save(toSave)).thenReturn(saved);

        Category result = categoryService.save(toSave);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Snacks");
        verify(categoryRepository).save(toSave);
    }

    @Test
    void save_existingCategory_shouldUpdateAndReturn() {
        Category toUpdate = new Category();
        toUpdate.setId(1L);
        toUpdate.setName("Beverages v2");

        Category saved = new Category();
        saved.setId(1L);
        saved.setName("Beverages v2");

        when(categoryRepository.save(any())).thenReturn(saved);

        Category result = categoryService.save(toUpdate);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Beverages v2");
        verify(categoryRepository).save(toUpdate);
    }
}