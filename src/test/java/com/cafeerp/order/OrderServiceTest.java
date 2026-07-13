package com.cafeerp.order;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @InjectMocks
    private OrderService orderService;

    private MenuItem availableItem(Long id, String name, BigDecimal price) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName(name);
        item.setPrice(price);
        item.setAvailable(true);
        return item;
    }

    private MenuItem unavailableItem(Long id, String name, BigDecimal price) {
        MenuItem item = availableItem(id, name, price);
        item.setAvailable(false);
        return item;
    }

    // -------------------------------------------------------
    //  Single available item
    // -------------------------------------------------------
    @Test
    void createOrder_withOneAvailableItem_shouldSucceed() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 2));

        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("7.00"));
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Multiple items sum totals
    // -------------------------------------------------------
    @Test
    void createOrder_withMultipleItems_shouldSumTotalsCorrectly() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        MenuItem croissant = availableItem(2L, "Croissant", new BigDecimal("4.00"));
        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(menuItemRepository.findById(2L)).thenReturn(Optional.of(croissant));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 2, 2L, 1));

        assertThat(result.getItemCount()).isEqualTo(3);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("11.00"));
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Unavailable item silently excluded
    // -------------------------------------------------------
    @Test
    void createOrder_withUnavailableItem_shouldExcludeIt() {
        MenuItem tea = availableItem(1L, "Tea", new BigDecimal("2.50"));
        MenuItem unavailableCake = unavailableItem(2L, "Cake", new BigDecimal("5.00"));
        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(tea));
        when(menuItemRepository.findById(2L)).thenReturn(Optional.of(unavailableCake));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 1, 2L, 1));

        assertThat(result.getItemCount()).isEqualTo(1);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("2.50"));
        // Only the available item should be added
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getItemName()).isEqualTo("Tea");
    }

    // -------------------------------------------------------
    //  Zero or negative quantity excluded (while valid items succeed)
    // -------------------------------------------------------
    @Test
    void createOrder_withZeroQuantity_shouldExcludeThatItem() {
        MenuItem tea = availableItem(2L, "Tea", new BigDecimal("2.00"));
        when(menuItemRepository.findById(2L)).thenReturn(Optional.of(tea));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 0, 2L, 2));

        // Coffee with qty 0 should be excluded; Tea x 2 should be included
        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getItemName()).isEqualTo("Tea");
        verify(orderRepository).save(any());
    }

    @Test
    void createOrder_withNegativeQuantity_shouldExcludeThatItem() {
        MenuItem tea = availableItem(2L, "Tea", new BigDecimal("2.00"));
        when(menuItemRepository.findById(2L)).thenReturn(Optional.of(tea));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, -1, 2L, 2));

        // Coffee with qty -1 should be excluded; Tea x 2 should be included
        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getItemName()).isEqualTo("Tea");
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  All items unavailable/invalid → IllegalArgumentException
    // -------------------------------------------------------
    @Test
    void createOrder_withAllItemsUnavailable_shouldThrow() {
        MenuItem unavailableCoffee = unavailableItem(1L, "Coffee", new BigDecimal("3.50"));
        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(unavailableCoffee));

        assertThatThrownBy(() -> orderService.createOrder(Map.of(1L, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_withAllZeroQuantities_shouldThrow() {
        assertThatThrownBy(() -> orderService.createOrder(Map.of(1L, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verifyNoInteractions(menuItemRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_withItemNotFound_shouldExcludeIt() {
        when(menuItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createOrder(Map.of(99L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verify(orderRepository, never()).save(any());
    }
}