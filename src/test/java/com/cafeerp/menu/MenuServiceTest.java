package com.cafeerp.menu;

import java.math.BigDecimal;
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

import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private MenuService menuService;

    @Test
    void findById_whenFound_shouldReturnMenuItem() {
        MenuItem item = new MenuItem();
        item.setId(1L);
        item.setName("Latte");
        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(item));

        MenuItem result = menuService.findById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Latte");
    }

    @Test
    void findById_whenNotFound_shouldThrow() {
        when(menuItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.findById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Menu item not found");
    }

    @Test
    void save_newMenuItem_shouldPersistAndReturn() {
        Category category = new Category();
        category.setId(1L);

        MenuItem toSave = new MenuItem();
        toSave.setName("Latte");
        toSave.setPrice(new BigDecimal("4.50"));
        toSave.setCategory(category);

        MenuItem saved = new MenuItem();
        saved.setId(1L);
        saved.setName("Latte");
        saved.setPrice(new BigDecimal("4.50"));
        saved.setCategory(category);

        when(categoryRepository.getReferenceById(1L)).thenReturn(category);
        when(menuItemRepository.save(any())).thenReturn(saved);

        MenuItem result = menuService.save(toSave);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Latte");
        assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("4.50"));
        verify(menuItemRepository).save(any());
    }

    @Test
    void save_existingMenuItem_shouldUpdateAndReturn() {
        Category category = new Category();
        category.setId(1L);

        MenuItem toUpdate = new MenuItem();
        toUpdate.setId(1L);
        toUpdate.setName("Latte XL");
        toUpdate.setPrice(new BigDecimal("5.00"));
        toUpdate.setCategory(category);

        when(categoryRepository.getReferenceById(1L)).thenReturn(category);
        when(menuItemRepository.save(any())).thenReturn(toUpdate);

        MenuItem result = menuService.save(toUpdate);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Latte XL");
        assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("5.00"));
        verify(menuItemRepository).save(any());
    }
}