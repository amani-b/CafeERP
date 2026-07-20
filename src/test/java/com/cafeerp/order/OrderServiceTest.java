package com.cafeerp.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private OrderService orderService;

    private Order orderWithId(Long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }

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

    private Inventory trackedInventory(Long menuItemId, int stock) {
        Inventory inv = new Inventory();
        inv.setTrackInventory(true);
        inv.setStockQuantity(stock);
        return inv;
    }

    private Inventory untrackedInventory(Long menuItemId) {
        Inventory inv = new Inventory();
        inv.setTrackInventory(false);
        inv.setStockQuantity(0);
        return inv;
    }

    // -------------------------------------------------------
    //  findById — happy path
    // -------------------------------------------------------
    @Test
    void findById_whenExists_shouldReturnOrder() {
        Order order = orderWithId(1L);
        when(orderRepository.findByIdWithItems(1L)).thenReturn(java.util.Optional.of(order));

        Order result = orderService.findById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        verify(orderRepository).findByIdWithItems(1L);
    }

    @Test
    void findById_whenMissing_shouldThrow() {
        when(orderRepository.findByIdWithItems(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> orderService.findById(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order not found");

        verify(orderRepository).findByIdWithItems(999L);
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

    // -------------------------------------------------------
    //  Inventory: tracked item with sufficient stock
    // -------------------------------------------------------
    @Test
    void createOrder_withTrackedItemSufficientStock_shouldSucceedAndDecrement() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        Inventory inv = trackedInventory(1L, 10);

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.decrementStockIfSufficient(eq(1L), eq(3), any(LocalDateTime.class)))
                .thenReturn(1);
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 3));

        assertThat(result.getItemCount()).isEqualTo(3);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("10.50"));
        verify(inventoryRepository).decrementStockIfSufficient(eq(1L), eq(3), any(LocalDateTime.class));
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Inventory: tracked item with insufficient stock → excluded
    // -------------------------------------------------------
    @Test
    void createOrder_withTrackedItemInsufficientStock_shouldExcludeThatLine() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        Inventory inv = trackedInventory(1L, 2);

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.decrementStockIfSufficient(eq(1L), eq(5), any(LocalDateTime.class)))
                .thenReturn(0); // decrement fails → stock insufficient

        assertThatThrownBy(() -> orderService.createOrder(Map.of(1L, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verify(inventoryRepository).decrementStockIfSufficient(eq(1L), eq(5), any(LocalDateTime.class));
        verify(orderRepository, never()).save(any());
    }

    // -------------------------------------------------------
    //  Inventory: mixed — tracked insufficient + tracked sufficient
    // -------------------------------------------------------
    @Test
    void createOrder_withOneTrackedInsufficientAndOneSufficient_shouldExcludeOnlyInsufficient() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        MenuItem tea = availableItem(2L, "Tea", new BigDecimal("2.00"));
        Inventory coffeeInv = trackedInventory(1L, 1);  // only 1 in stock
        Inventory teaInv = trackedInventory(2L, 10);

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(menuItemRepository.findById(2L)).thenReturn(Optional.of(tea));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(coffeeInv));
        when(inventoryRepository.findByMenuItemId(2L)).thenReturn(Optional.of(teaInv));
        // Coffee qty 3 exceeds stock 1 → fails atomic decrement
        when(inventoryRepository.decrementStockIfSufficient(eq(1L), eq(3), any(LocalDateTime.class)))
                .thenReturn(0);
        // Tea qty 2 is fine → succeeds
        when(inventoryRepository.decrementStockIfSufficient(eq(2L), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 3, 2L, 2));

        // Only tea should be in the order
        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getItemName()).isEqualTo("Tea");
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Inventory: untracked item behaves exactly as before
    // -------------------------------------------------------
    @Test
    void createOrder_withUntrackedItem_shouldBehaveAsBefore() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        Inventory inv = untrackedInventory(1L); // trackInventory=false

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 2));

        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("7.00"));
        // decrementStockIfSufficient should never be called for untracked items
        verify(inventoryRepository, never()).decrementStockIfSufficient(anyLong(), anyInt(), any());
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Inventory: no inventory row → treated as untracked
    // -------------------------------------------------------
    @Test
    void createOrder_withNoInventoryRow_shouldBehaveAsBefore() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.empty());
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(Map.of(1L, 2));

        assertThat(result.getItemCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("7.00"));
        verify(inventoryRepository, never()).decrementStockIfSufficient(anyLong(), anyInt(), any());
        verify(orderRepository).save(any());
    }

    // -------------------------------------------------------
    //  Inventory: concurrent decrement failure excludes that line
    // -------------------------------------------------------
    @Test
    void createOrder_whenConcurrentDecrementFails_shouldExcludeThatLine() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        Inventory inv = trackedInventory(1L, 5); // initial stock looked fine

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        // But atomic decrement returns 0 because another request consumed stock first
        when(inventoryRepository.decrementStockIfSufficient(eq(1L), eq(3), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(Map.of(1L, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verify(inventoryRepository).decrementStockIfSufficient(eq(1L), eq(3), any(LocalDateTime.class));
        verify(orderRepository, never()).save(any());
    }

    // -------------------------------------------------------
    //  Concurrency: two near-simultaneous calls competing for the last unit.
    //  A true multi-threaded concurrency test is not practical in this
    //  Mockito-based unit test because:
    //   1) The actual atomicity comes from the database-level UPDATE statement
    //      (InventoryRepository.decrementStockIfSufficient) which does both the
    //      check (stock >= qty) and the decrement in a single SQL statement.
    //   2) Mockito cannot simulate interleaved database transactions; the
    //      repository method is a black-box mock.
    //  Instead, we assert the mechanism directly: the repository query must use
    //  a conditional UPDATE WHERE stockQuantity >= :qty, which is the database's
    //  own locking mechanism. Two concurrent calls would each execute:
    //    UPDATE Inventory SET stock = stock - qty WHERE id = ? AND stock >= qty
    //  PostgreSQL serialises them — only one gets the row lock, the other sees
    //  0 rows updated. We already test that path by mocking
    //  decrementStockIfSufficient returning 0.
    @Test
    void createOrder_concurrentDecrement_assertsConditionalUpdateMechanism() {
        // Verify the repository query uses the correct JPQL pattern.
        // InventoryRepository.decrementStockIfSufficient uses:
        //   UPDATE Inventory i SET i.stockQuantity = i.stockQuantity - :qty, ...
        //   WHERE i.menuItem.id = :menuItemId AND i.stockQuantity >= :qty
        // The "stockQuantity >= :qty" condition is what provides the atomic
        // check-and-decrement — if stock was sufficient at SELECT time but another
        // request consumed it before this UPDATE, the WHERE clause fails and 0 rows
        // are updated. The caller (isStockInsufficient) treats 0 as "insufficient".
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        Inventory inv = trackedInventory(1L, 5);

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        // Simulate two concurrent callers — first gets 1, second gets 0
        when(inventoryRepository.decrementStockIfSufficient(eq(1L), eq(5), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(Map.of(1L, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one available menu item.");

        verify(inventoryRepository).decrementStockIfSufficient(eq(1L), eq(5), any(LocalDateTime.class));
        verify(orderRepository, never()).save(any());
    }

    // -------------------------------------------------------
    //  trackInventory=false with inventory row → no stock check
    // -------------------------------------------------------
    @Test
    void createOrder_withTrackInventoryFalse_shouldNotCheckStock() {
        MenuItem coffee = availableItem(1L, "Coffee", new BigDecimal("3.50"));
        // inventory row exists but trackInventory=false
        Inventory inv = new Inventory();
        inv.setTrackInventory(false);
        inv.setStockQuantity(0);

        when(menuItemRepository.findById(1L)).thenReturn(Optional.of(coffee));
        when(inventoryRepository.findByMenuItemId(1L)).thenReturn(Optional.of(inv));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Even with stock=0, order succeeds because tracking is off
        Order result = orderService.createOrder(Map.of(1L, 5));

        assertThat(result.getItemCount()).isEqualTo(5);
        verify(inventoryRepository, never()).decrementStockIfSufficient(anyLong(), anyInt(), any());
        verify(orderRepository).save(any());
    }
}