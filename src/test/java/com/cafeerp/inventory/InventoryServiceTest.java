package com.cafeerp.inventory;

import java.util.List;
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
        Inventory inv = inventory(1L, 10L, true, 5, 5);
        when(inventoryRepository.findAll()).thenReturn(List.of(inv));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------
    //  countLowStock: boundary — stock one above threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenStockOneAboveThreshold_shouldNotCount() {
        Inventory inv = inventory(1L, 10L, true, 6, 5);
        when(inventoryRepository.findAll()).thenReturn(List.of(inv));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: boundary — stock one below threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenStockOneBelowThreshold_shouldCount() {
        Inventory inv = inventory(1L, 10L, true, 4, 5);
        when(inventoryRepository.findAll()).thenReturn(List.of(inv));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------
    //  countLowStock: untracked item even if stock is low
    // -------------------------------------------------------
    @Test
    void countLowStock_whenNotTracked_shouldNotCountEvenIfLow() {
        Inventory inv = inventory(1L, 10L, false, 1, 5);
        when(inventoryRepository.findAll()).thenReturn(List.of(inv));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: tracked but stock well above threshold
    // -------------------------------------------------------
    @Test
    void countLowStock_whenTrackedAndStockAboveThreshold_shouldNotCount() {
        Inventory inv = inventory(1L, 10L, true, 100, 5);
        when(inventoryRepository.findAll()).thenReturn(List.of(inv));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(0);
    }

    // -------------------------------------------------------
    //  countLowStock: multiple items, mix of low and not low
    // -------------------------------------------------------
    @Test
    void countLowStock_withMixedItems_shouldCountOnlyLowTracked() {
        Inventory inv1 = inventory(1L, 10L, true, 2, 5);   // low
        Inventory inv2 = inventory(2L, 20L, true, 10, 5);  // not low
        Inventory inv3 = inventory(3L, 30L, false, 1, 5);  // not tracked
        Inventory inv4 = inventory(4L, 40L, true, 5, 5);   // low (equal)
        when(inventoryRepository.findAll()).thenReturn(List.of(inv1, inv2, inv3, inv4));

        long count = inventoryService.countLowStock();

        assertThat(count).isEqualTo(2);
    }
}