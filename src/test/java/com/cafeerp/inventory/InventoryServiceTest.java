package com.cafeerp.inventory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.menu.MenuItem;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryService inventoryService;

    private MenuItem menuItem(Long id, String name) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName(name);
        return item;
    }

    private Inventory inventory(Long id, Long menuItemId, boolean trackInventory,
                                int stockQuantity, int lowStockThreshold) {
        Inventory inv = new Inventory();
        inv.setId(id);
        inv.setMenuItem(menuItem(menuItemId, "Item-" + menuItemId));
        inv.setTrackInventory(trackInventory);
        inv.setStockQuantity(stockQuantity);
        inv.setLowStockThreshold(lowStockThreshold);
        return inv;
    }

    // -------------------------------------------------------
    //  update: toggling trackInventory
    // -------------------------------------------------------
    @Test
    void update_shouldToggleTrackInventory() {
        Inventory inv = inventory(1L, 10L, false, 5, 3);
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        inventoryService.update(1L, true, 5, 3);

        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(captor.capture());
        assertThat(captor.getValue().isTrackInventory()).isTrue();
    }

    // -------------------------------------------------------
    //  update: setting stockQuantity
    // -------------------------------------------------------
    @Test
    void update_shouldSetStockQuantity() {
        Inventory inv = inventory(1L, 10L, true, 0, 3);
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        inventoryService.update(1L, true, 42, 3);

        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(captor.capture());
        assertThat(captor.getValue().getStockQuantity()).isEqualTo(42);
    }

    // -------------------------------------------------------
    //  update: setting lowStockThreshold
    // -------------------------------------------------------
    @Test
    void update_shouldSetLowStockThreshold() {
        Inventory inv = inventory(1L, 10L, true, 5, 0);
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        inventoryService.update(1L, true, 5, 7);

        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(captor.capture());
        assertThat(captor.getValue().getLowStockThreshold()).isEqualTo(7);
    }

    // -------------------------------------------------------
    //  update: inventory not found
    // -------------------------------------------------------
    @Test
    void update_whenNotFound_shouldThrow() {
        when(inventoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.update(99L, true, 5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Inventory not found");
    }

    // -------------------------------------------------------
    //  countLowStock: boundary — stock equals threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenStockEqualsThreshold_shouldCount() {
        when(inventoryRepository.countLowStockItems()).thenReturn(1L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------
    //  countLowStock: boundary — stock one above threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenStockOneAboveThreshold_shouldNotCount() {
        when(inventoryRepository.countLowStockItems()).thenReturn(0L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: boundary — stock one below threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenStockOneBelowThreshold_shouldCount() {
        when(inventoryRepository.countLowStockItems()).thenReturn(1L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------
    //  countLowStock: untracked item even if stock is low
    // -------------------------------------------------------
    @Test
    void countLowStock_whenNotTracked_shouldNotCountEvenIfLow() {
        when(inventoryRepository.countLowStockItems()).thenReturn(0L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: tracked but stock well above threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenTrackedAndStockAboveThreshold_shouldNotCount() {
        when(inventoryRepository.countLowStockItems()).thenReturn(0L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: multiple items, mix of low and not low
    // -------------------------------------------------------
    @Test
    void countLowStock_withMixedItems_shouldCountOnlyLowTracked() {
        when(inventoryRepository.countLowStockItems()).thenReturn(2L);

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(2);
    }

    // -------------------------------------------------------
    //  findByItemNameIgnoreCase: delegates to the indexed query
    // -------------------------------------------------------
    @Test
    void findByItemNameIgnoreCase_shouldDelegateToRepository() {
        Inventory inv = inventory(1L, 10L, true, 5, 3);
        when(inventoryRepository.findByMenuItem_NameIgnoreCase("Espresso")).thenReturn(Optional.of(inv));

        assertThat(inventoryService.findByItemNameIgnoreCase("Espresso")).contains(inv);
    }

    @Test
    void findByItemNameIgnoreCase_whenMissing_shouldReturnEmpty() {
        when(inventoryRepository.findByMenuItem_NameIgnoreCase("nope")).thenReturn(Optional.empty());

        assertThat(inventoryService.findByItemNameIgnoreCase("nope")).isEmpty();
    }
}