package com.cafeerp.assistant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuService;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderService;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.report.ReportService;
import com.cafeerp.report.ReportService.DateRange;
import com.cafeerp.report.ReportService.ReportData;
import com.cafeerp.user.Role;

@ExtendWith(MockitoExtension.class)
class DeterministicFallbackHandlerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private MenuService menuService;

    @Mock
    private ReportService reportService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private AssistantToolRegistry toolRegistry;

    private DeterministicFallbackHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeterministicFallbackHandler(orderService, menuService,
                reportService, inventoryService, toolRegistry);
    }

    // ---------------------------------------------------------------
    //  Order status queries
    // ---------------------------------------------------------------

    @Test
    void orderStatus_withValidOrderId_shouldReturnStatus() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        Order order = new Order();
        order.setId(5L);
        order.setStatus(OrderStatus.READY);
        order.setItemCount(3);
        order.setTotalAmount(BigDecimal.valueOf(25.50));
        order.setCreatedAt(LocalDateTime.now());

        when(orderService.findById(5L)).thenReturn(order);

        AssistantReply reply = handler.tryAnswer("What's the status of order 5?", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("Order #5"));
        assertTrue(reply.text().contains("READY"));
    }

    @Test
    void orderStatus_withHashPrefix_shouldAlsoWork() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        Order order = new Order();
        order.setId(42L);
        order.setStatus(OrderStatus.PREPARING);

        when(orderService.findById(42L)).thenReturn(order);

        AssistantReply reply = handler.tryAnswer("What's up with #42?", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("Order #42"));
    }

    @Test
    void orderStatus_notFound_shouldReturnNotFound() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        when(orderService.findById(999L))
                .thenThrow(new IllegalArgumentException("Order not found"));

        AssistantReply reply = handler.tryAnswer("order 999 status?", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("not found"));
    }

    @Test
    void orderStatus_roleWithoutGetOrderStatus_shouldNotMatch() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getMenuItems"));

        AssistantReply reply = handler.tryAnswer("order 5 status?", Role.STAFF);

        assertNull(reply);
    }

    // ---------------------------------------------------------------
    //  Menu queries
    // ---------------------------------------------------------------

    @Test
    void menuQuery_shouldReturnMenuItems() {
        when(toolRegistry.allowedToolNamesForRole(Role.KITCHEN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getKitchenQueueSummary"));

        MenuItem coffee = new MenuItem();
        coffee.setName("Coffee");
        coffee.setPrice(BigDecimal.valueOf(3.50));
        MenuItem tea = new MenuItem();
        tea.setName("Tea");
        tea.setPrice(BigDecimal.valueOf(2.50));

        when(menuService.findAll()).thenReturn(List.of(coffee, tea));

        AssistantReply reply = handler.tryAnswer("What's on the menu?", Role.KITCHEN);

        assertNotNull(reply);
        assertTrue(reply.text().contains("Coffee"));
        assertTrue(reply.text().contains("Tea"));
    }

    // ---------------------------------------------------------------
    //  Sales queries — admin only
    // ---------------------------------------------------------------

    @Test
    void salesQuery_byStaff_shouldBeRefused() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        AssistantReply reply = handler.tryAnswer("What were sales today?", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("only available"));
    }

    @Test
    void salesQuery_byAdmin_shouldReturnSales() {
        when(toolRegistry.allowedToolNamesForRole(Role.ADMIN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getSalesTotals",
                        "getTopSellingItems", "getInventoryLevel", "getKitchenQueueSummary"));

        LocalDateTime now = LocalDateTime.now();
        when(reportService.resolveDateRange("today")).thenReturn(new DateRange(now, now));
        when(reportService.generateReport(now, now))
                .thenReturn(new ReportData(BigDecimal.valueOf(1250.00), 42, List.of(), now, now));

        AssistantReply reply = handler.tryAnswer("What were sales today?", Role.ADMIN);

        assertNotNull(reply);
        assertTrue(reply.text().contains("1250"));
        assertTrue(reply.text().contains("42"));
    }

    // ---------------------------------------------------------------
    //  Inventory queries — admin only
    // ---------------------------------------------------------------

    @Test
    void inventoryQuery_byStaff_shouldBeRefused() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        AssistantReply reply = handler.tryAnswer("How much coffee stock do we have?", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("only available"));
    }

    @Test
    void inventoryQuery_byAdmin_shouldReturnStock() {
        when(toolRegistry.allowedToolNamesForRole(Role.ADMIN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getSalesTotals",
                        "getTopSellingItems", "getInventoryLevel", "getKitchenQueueSummary"));

        MenuItem coffee = new MenuItem();
        coffee.setId(1L);
        coffee.setName("Coffee");
        coffee.setPrice(BigDecimal.valueOf(3.50));
        when(menuService.findAll()).thenReturn(List.of(coffee));

        Inventory inv = new Inventory();
        inv.setMenuItem(coffee);
        inv.setStockQuantity(50);
        inv.setLowStockThreshold(10);
        inv.setTrackInventory(true);
        when(inventoryService.findAll()).thenReturn(List.of(inv));

        AssistantReply reply = handler.tryAnswer("How much coffee do we have in inventory?", Role.ADMIN);

        assertNotNull(reply);
        assertTrue(reply.text().contains("Coffee"));
        assertTrue(reply.text().contains("50"));
    }

    // ---------------------------------------------------------------
    //  Kitchen queue queries
    // ---------------------------------------------------------------

    @Test
    void kitchenQueueQuery_shouldReturnSummary() {
        when(toolRegistry.allowedToolNamesForRole(Role.KITCHEN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getKitchenQueueSummary"));

        Order pending1 = new Order();
        pending1.setStatus(OrderStatus.PENDING);
        Order pending2 = new Order();
        pending2.setStatus(OrderStatus.PENDING);
        Order preparing1 = new Order();
        preparing1.setStatus(OrderStatus.PREPARING);
        Order ready1 = new Order();
        ready1.setStatus(OrderStatus.READY);

        when(orderService.findActiveOrders()).thenReturn(List.of(pending1, pending2, preparing1, ready1));

        AssistantReply reply = handler.tryAnswer("What's in the kitchen queue?", Role.KITCHEN);

        assertNotNull(reply);
        assertTrue(reply.text().contains("PENDING=2"));
        assertTrue(reply.text().contains("PREPARING=1"));
        assertTrue(reply.text().contains("READY=1"));
    }

    // ---------------------------------------------------------------
    //  Unmatched queries
    // ---------------------------------------------------------------

    @Test
    void unmatchedQuery_shouldReturnNull() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        AssistantReply reply = handler.tryAnswer("What is the meaning of life?", Role.STAFF);

        assertNull(reply);
    }

    // ---------------------------------------------------------------
    //  unavailableMessage — role-scoped
    // ---------------------------------------------------------------

    @Test
    void unavailableMessage_forStaff_shouldListOrderAndMenu() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        AssistantReply reply = handler.unavailableMessage(Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
        assertTrue(reply.text().contains("menu"));
        assertTrue(reply.text().contains("order"));
    }

    @Test
    void unavailableMessage_forAdmin_shouldListAll() {
        when(toolRegistry.allowedToolNamesForRole(Role.ADMIN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getSalesTotals",
                        "getTopSellingItems", "getInventoryLevel", "getKitchenQueueSummary"));

        AssistantReply reply = handler.unavailableMessage(Role.ADMIN);

        assertNotNull(reply);
        assertTrue(reply.text().contains("Sales reports"));
        assertTrue(reply.text().contains("Inventory levels"));
        assertTrue(reply.text().contains("Kitchen queue"));
    }

    // ---------------------------------------------------------------
    //  Kitchen source links
    // ---------------------------------------------------------------

    @Test
    void orderStatus_forKitchenRole_shouldLinkToKitchen() {
        when(toolRegistry.allowedToolNamesForRole(Role.KITCHEN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getKitchenQueueSummary"));

        Order order = new Order();
        order.setId(3L);
        order.setStatus(OrderStatus.PREPARING);

        when(orderService.findById(3L)).thenReturn(order);

        AssistantReply reply = handler.tryAnswer("order 3 status?", Role.KITCHEN);

        assertNotNull(reply);
        boolean hasKitchenLink = reply.links().stream()
                .anyMatch(l -> l.url().equals("/kitchen"));
        assertTrue(hasKitchenLink);
    }

    @Test
    void orderStatus_forStaffRole_shouldLinkToOrders() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        Order order = new Order();
        order.setId(3L);
        order.setStatus(OrderStatus.READY);

        when(orderService.findById(3L)).thenReturn(order);

        AssistantReply reply = handler.tryAnswer("order 3 status?", Role.STAFF);

        assertNotNull(reply);
        boolean hasOrdersLink = reply.links().stream()
                .anyMatch(l -> l.url().equals("/orders/3"));
        assertTrue(hasOrdersLink);
    }

    // ---------------------------------------------------------------
    //  Tool-call hallucination (security) — simulated via fallback handler
    // ---------------------------------------------------------------

    @Test
    void toolCallValidation_hallucinatedTool_shouldNotMatchAnyPattern() {
        when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));

        AssistantReply reply = handler.tryAnswer("top sellers today", Role.STAFF);

        assertNotNull(reply);
        assertTrue(reply.text().contains("only available"));
    }
}