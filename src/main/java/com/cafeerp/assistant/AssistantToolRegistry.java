package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuService;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderService;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.report.ReportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class AssistantToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolRegistry.class);

    private final OrderService orderService;
    private final MenuService menuService;
    private final ReportService reportService;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public AssistantToolRegistry(OrderService orderService,
                                 MenuService menuService,
                                 ReportService reportService,
                                 InventoryService inventoryService,
                                 ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.menuService = menuService;
        this.reportService = reportService;
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    //  Tool definitions (OpenAI function-calling format)
    // ---------------------------------------------------------------

    public List<Map<String, Object>> toolsForStaff() {
        return List.of(orderStatusTool(), menuItemsTool());
    }

    public List<Map<String, Object>> toolsForKitchen() {
        return List.of(orderStatusTool(), menuItemsTool(), kitchenQueueTool());
    }

    public List<Map<String, Object>> toolsForAdmin() {
        return List.of(
            orderStatusTool(), menuItemsTool(),
            salesTotalsTool(), topSellingItemsTool(),
            inventoryLevelTool(), kitchenQueueTool()
        );
    }

    private Map<String, Object> orderStatusTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getOrderStatus",
                "description", "Get the current status (PENDING, PREPARING, READY, COMPLETED) of a specific order by its ID number.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "orderId", Map.of(
                            "type", "integer",
                            "description", "The numeric ID of the order to look up"
                        )
                    ),
                    "required", List.of("orderId")
                )
            )
        );
    }

    private Map<String, Object> menuItemsTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getMenuItems",
                "description", "List all menu items with their prices and availability.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
                )
            )
        );
    }

    private Map<String, Object> salesTotalsTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getSalesTotals",
                "description", "Get total sales amount and order count for a given period. Use presets like 'today', 'week', 'month', or 'all'.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "range", Map.of(
                            "type", "string",
                            "enum", List.of("today", "week", "month", "all"),
                            "description", "The date range preset"
                        )
                    ),
                    "required", List.of("range")
                )
            )
        );
    }

    private Map<String, Object> topSellingItemsTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getTopSellingItems",
                "description", "Get the top 5 best-selling menu items for a given period.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "range", Map.of(
                            "type", "string",
                            "enum", List.of("today", "week", "month", "all"),
                            "description", "The date range preset"
                        )
                    ),
                    "required", List.of("range")
                )
            )
        );
    }

    private Map<String, Object> inventoryLevelTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getInventoryLevel",
                "description", "Check the stock level for a specific menu item by its name. Returns quantity and low-stock threshold if tracked.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "itemName", Map.of(
                            "type", "string",
                            "description", "The name of the menu item"
                        )
                    ),
                    "required", List.of("itemName")
                )
            )
        );
    }

    private Map<String, Object> kitchenQueueTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getKitchenQueueSummary",
                "description", "Get a summary of orders currently in the kitchen queue, grouped by status (PENDING, PREPARING, READY).",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
                )
            )
        );
    }

    // ---------------------------------------------------------------
    //  Tool dispatch
    // ---------------------------------------------------------------

    /**
     * Executes a tool call and returns a human-readable result string.
     */
    public String execute(String toolName, String argumentsJson) {
        try {
            switch (toolName) {
                case "getOrderStatus": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    Number orderIdNum = (Number) args.get("orderId");
                    Long orderId = orderIdNum.longValue();
                    Order order = orderService.findById(orderId);
                    return String.format("Order #%d: status=%s, items=%d, total=%.2f, created=%s",
                            order.getId(), order.getStatus(), order.getItemCount(),
                            order.getTotalAmount(), order.getCreatedAt());
                }
                case "getMenuItems": {
                    List<MenuItem> items = menuService.findAll();
                    StringBuilder sb = new StringBuilder("Menu items:\n");
                    for (MenuItem item : items) {
                        sb.append(String.format("  - %s: $%.2f %s\n",
                                item.getName(), item.getPrice(),
                                item.isAvailable() ? "(available)" : "(unavailable)"));
                    }
                    return sb.toString();
                }
                case "getSalesTotals": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String range = (String) args.get("range");
                    var dateRange = reportService.resolveDateRange(range);
                    var report = reportService.generateReport(dateRange.from(), dateRange.to());
                    return String.format("Sales for %s: total=%.2f, orders=%d (from %s to %s)",
                            range, report.totalSales(), report.orderCount(),
                            report.from(), report.to());
                }
                case "getTopSellingItems": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String range = (String) args.get("range");
                    var dateRange = reportService.resolveDateRange(range);
                    var report = reportService.generateReport(dateRange.from(), dateRange.to());
                    StringBuilder sb = new StringBuilder("Top 5 selling items:\n");
                    int rank = 1;
                    for (var item : report.topItems()) {
                        sb.append(String.format("  %d. %s — %d sold\n", rank++, item.getItemName(), item.getTotalQuantity()));
                    }
                    if (report.topItems().isEmpty()) {
                        sb.append("  (no sales in this period)");
                    }
                    return sb.toString();
                }
                case "getInventoryLevel": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String itemName = (String) args.get("itemName");
                    List<Inventory> all = inventoryService.findAll();
                    for (Inventory inv : all) {
                        if (inv.getMenuItem().getName().equalsIgnoreCase(itemName)) {
                            return String.format("%s: stock=%d, threshold=%d, tracking=%s",
                                    inv.getMenuItem().getName(),
                                    inv.getStockQuantity(),
                                    inv.getLowStockThreshold(),
                                    inv.isTrackInventory() ? "yes" : "no");
                        }
                    }
                    return "Item not found: " + itemName;
                }
                case "getKitchenQueueSummary": {
                    var active = orderService.findActiveOrders();
                    long pending = active.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
                    long preparing = active.stream().filter(o -> o.getStatus() == OrderStatus.PREPARING).count();
                    long ready = active.stream().filter(o -> o.getStatus() == OrderStatus.READY).count();
                    return String.format("Kitchen queue: PENDING=%d, PREPARING=%d, READY=%d (total active=%d)",
                            pending, preparing, ready, active.size());
                }
                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (IllegalArgumentException e) {
            log.warn("Tool call failed (business error): tool={}, args={}", toolName, argumentsJson, e);
            return "Not found: " + e.getMessage();
        } catch (Exception e) {
            log.error("Tool call error: tool={}, args={}", toolName, argumentsJson, e);
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }
}