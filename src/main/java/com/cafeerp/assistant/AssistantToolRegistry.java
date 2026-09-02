package com.cafeerp.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuService;
import com.cafeerp.menu.MenuItemRepository;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderService;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.report.ReportService;
import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class AssistantToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolRegistry.class);

    private final OrderService orderService;
    private final MenuService menuService;
    private final ReportService reportService;
    private final InventoryService inventoryService;
    private final MenuItemRepository menuItemRepository;
    private final UserSessionLogRepository sessionLogRepository;
    private final ObjectMapper objectMapper;

    public AssistantToolRegistry(OrderService orderService,
                                 MenuService menuService,
                                 ReportService reportService,
                                 InventoryService inventoryService,
                                 MenuItemRepository menuItemRepository,
                                 UserSessionLogRepository sessionLogRepository,
                                 ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.menuService = menuService;
        this.reportService = reportService;
        this.inventoryService = inventoryService;
        this.menuItemRepository = menuItemRepository;
        this.sessionLogRepository = sessionLogRepository;
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

    // ---------------------------------------------------------------
    //  Phase 4: permission-scoped tool set for the AGENTIC path
    // ---------------------------------------------------------------

    /**
     * The tool set for a specific user, scoped to THEIR OWN effective
     * permissions. The AI must never be able to surface data the asking user
     * could not see themselves, so this is the single source of truth for the
     * agentic tool-calling path (write tools additionally require
     * {@link Permission#AI_AGENTIC_ACTIONS}).
     */
    public List<Map<String, Object>> toolsForUser(User user) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (user == null) {
            return tools;
        }

        // Menu is readable by everyone who may use the assistant.
        tools.add(menuItemsTool());

        if (AgenticPermissions.holds(user, Permission.ORDER_KITCHEN)) {
            tools.add(orderStatusTool());
            tools.add(orderHistoryTool());
            tools.add(kitchenQueueTool());
        }
        if (AgenticPermissions.holds(user, Permission.REPORT)) {
            tools.add(salesTotalsTool());
            tools.add(topSellingItemsTool());
        }
        if (AgenticPermissions.holds(user, Permission.INVENTORY)) {
            tools.add(inventoryLevelTool());
        }
        if (AgenticPermissions.holds(user, Permission.USER_MANAGEMENT)) {
            tools.add(userLoginHistoryTool());
            tools.add(userSessionActivityTool());
        }

        // Write tools: require AI_AGENTIC_ACTIONS plus the module permission.
        if (AgenticPermissions.isAgentic(user)) {
            if (AgenticPermissions.holds(user, Permission.ORDER_KITCHEN)) {
                tools.add(createOrderTool());
                tools.add(updateOrderStatusTool());
            }
            if (AgenticPermissions.holds(user, Permission.INVENTORY)) {
                tools.add(updateInventoryTool());
            }
        }
        return tools;
    }

    /** Tool-name set mirroring {@link #toolsForUser(User)} for dispatch checks. */
    public Set<String> allowedToolNamesForUser(User user) {
        return toolsForUser(user).stream()
                .map(t -> (String) ((Map<String, Object>) t.get("function")).get("name"))
                .collect(Collectors.toSet());
    }

    private Map<String, Object> orderHistoryTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getOrderHistory",
                "description", "List recent orders (newest first) with id, status, item count and total. Optionally filter by status (PENDING, PREPARING, READY, COMPLETED).",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "status", Map.of(
                            "type", "string",
                            "description", "Optional status filter: PENDING, PREPARING, READY or COMPLETED"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "How many orders to return (default 10, max 20)"
                        )
                    ),
                    "required", List.of()
                )
            )
        );
    }

    private Map<String, Object> userLoginHistoryTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getUserLoginHistory",
                "description", "List the most recent LOGIN and LOGOUT events for a user account, newest first. Requires user-management permission.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "username", Map.of(
                            "type", "string",
                            "description", "The username to look up"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "How many events to return (default 10, max 20)"
                        )
                    ),
                    "required", List.of("username")
                )
            )
        );
    }

    private Map<String, Object> userSessionActivityTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "getUserSessionActivity",
                "description", "Get a session summary for a user: most recent login, most recent logout and total recorded events. Requires user-management permission.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "username", Map.of(
                            "type", "string",
                            "description", "The username to look up"
                        )
                    ),
                    "required", List.of("username")
                )
            )
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

    private Map<String, Object> createOrderTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "createOrder",
                "description", "Create a new customer order from menu items. This tool records a pending action that the user must approve in the chat UI.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "items", Map.of(
                            "type", "array",
                            "description", "The order lines",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "itemName", Map.of("type", "string", "description", "Exact menu item name"),
                                    "quantity", Map.of("type", "integer", "description", "Quantity (minimum 1)")
                                ),
                                "required", List.of("itemName", "quantity")
                            )
                        ),
                        "taskOrder", Map.of(
                            "type", "integer",
                            "description", "1-based position of this task in the user's message (first-mentioned task = 1, second = 2, ...). Required when the message requests multiple tasks."
                        )
                    ),
                    "required", List.of("items")
                )
            )
        );
    }

    private Map<String, Object> updateOrderStatusTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "updateOrderStatus",
                "description", "Change the status of an existing order (PENDING, PREPARING, READY, COMPLETED). This tool records a pending action that the user must approve in the chat UI.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "orderId", Map.of("type", "integer", "description", "The numeric ID of the order"),
                        "status", Map.of("type", "string", "description", "New status: PENDING, PREPARING, READY or COMPLETED"),
                        "taskOrder", Map.of("type", "integer", "description", "1-based position of this task in the user's message (first-mentioned task = 1, second = 2, ...). Required when the message requests multiple tasks.")
                    ),
                    "required", List.of("orderId", "status")
                )
            )
        );
    }

    private Map<String, Object> updateInventoryTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "updateInventory",
                "description", "Set the stock quantity of a tracked menu item. Low-risk: may run automatically when the user has enabled the auto low-risk autonomy mode.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "itemName", Map.of("type", "string", "description", "Exact menu item name"),
                        "stockQuantity", Map.of("type", "integer", "description", "The new stock quantity (0 or more)"),
                        "taskOrder", Map.of("type", "integer", "description", "1-based position of this task in the user's message (first-mentioned task = 1, second = 2, ...). Required when the message requests multiple tasks.")
                    ),
                    "required", List.of("itemName", "stockQuantity")
                )
            )
        );
    }

    // ---------------------------------------------------------------
    //  Write-tool metadata (confirmation policy + audit descriptions)
    // ---------------------------------------------------------------

    /** True when the tool mutates data (vs. read-only lookup). */
    public boolean isWriteTool(String toolName) {
        return switch (toolName) {
            case "createOrder", "updateOrderStatus", "updateInventory" -> true;
            default -> false;
        };
    }

    /**
     * The module permission that must accompany {@code AI_AGENTIC_ACTIONS} for
     * this write tool to be executable. Empty for read tools.
     */
    public Optional<Permission> requiredPermissionFor(String toolName) {
        return switch (toolName) {
            case "createOrder", "updateOrderStatus" -> Optional.of(Permission.ORDER_KITCHEN);
            case "updateInventory" -> Optional.of(Permission.INVENTORY);
            default -> Optional.empty();
        };
    }

    /**
     * Confirmation policy: order-impacting actions ALWAYS require explicit
     * user confirmation regardless of autonomy mode (no fully-autonomous
     * tier); inventory updates may auto-run in AUTO_LOW_RISK mode.
     */
    public boolean requiresConfirmation(String toolName, AgenticAutonomy autonomy) {
        if (!isWriteTool(toolName)) {
            return false;
        }
        if (autonomy == AgenticAutonomy.AUTO_LOW_RISK && "updateInventory".equals(toolName)) {
            return false;
        }
        return true;
    }

    /** Human-readable one-liner of what the write tool would do (confirmation card / audit log). */
    public String describeAction(String toolName, String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson == null ? "{}" : argumentsJson,
                    new TypeReference<Map<String, Object>>() {});
            return switch (toolName) {
                case "createOrder" -> {
                    StringBuilder sb = new StringBuilder("Create a new order:");
                    for (Map<String, Object> item : castItemList(args.get("items"))) {
                        sb.append(String.format(" %s x%s;", item.get("itemName"), item.get("quantity")));
                    }
                    yield sb.toString();
                }
                case "updateOrderStatus" -> String.format("Change status of order #%s to %s.",
                        args.get("orderId"), String.valueOf(args.get("status")).toUpperCase());
                case "updateInventory" -> String.format("Set stock of \"%s\" to %s units.",
                        args.get("itemName"), args.get("stockQuantity"));
                default -> "Execute " + toolName;
            };
        } catch (Exception e) {
            return "Execute " + toolName;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castItemList(Object raw) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    items.add((Map<String, Object>) map);
                }
            }
        }
        return items;
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
    //  Allowed tool names per role (shared with deterministic fallback)
    // ---------------------------------------------------------------

    /**
     * Returns the set of tool names that the given role is allowed to call.
     * Used by both the AI tool-calling path and the deterministic Tier 2 fallback
     * to enforce consistent role scoping.
     */
    public Set<String> allowedToolNamesForRole(Role role) {
        List<Map<String, Object>> tools = switch (role) {
            case STAFF -> toolsForStaff();
            case KITCHEN -> toolsForKitchen();
            case ADMIN, SUPER_ADMIN -> toolsForAdmin();
        };
        return tools.stream()
                .map(t -> (String) ((Map<String, Object>) t.get("function")).get("name"))
                .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------
    //  Tool dispatch
    // ---------------------------------------------------------------

    /**
     * Executes a tool call and returns a human-readable result string.
     */
    public String execute(String toolName, String argumentsJson) {
        return execute(toolName, argumentsJson, null);
    }

    /**
     * Executes a tool call on behalf of a user. Write tools re-check the
     * actor's permissions here (defense in depth on top of
     * {@link #allowedToolNamesForUser}) so an AI can never execute an action
     * the user lacks the permission for, even if the model hallucinated the
     * tool call.
     */
    public String execute(String toolName, String argumentsJson, User actor) {
        enforceWritePermissions(toolName, actor);
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
                case "getOrderHistory": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String statusRaw = args.get("status") == null ? null
                            : String.valueOf(args.get("status")).trim().toUpperCase();
                    OrderStatus statusFilter = statusRaw == null || statusRaw.isBlank() ? null
                            : OrderStatus.valueOf(statusRaw);
                    int limit = args.get("limit") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;
                    List<Order> orders = orderService.findAll().stream()
                            .filter(o -> statusFilter == null || o.getStatus() == statusFilter)
                            .limit(limit)
                            .toList();
                    if (orders.isEmpty()) {
                        return "No orders found" + (statusFilter != null ? " with status " + statusFilter : "") + ".";
                    }
                    StringBuilder sb = new StringBuilder("Recent orders (newest first):\n");
                    for (Order order : orders) {
                        sb.append(String.format("  - Order #%d: status=%s, items=%d, total=%.2f, created=%s%n",
                                order.getId(), order.getStatus(), order.getItemCount(),
                                order.getTotalAmount(), order.getCreatedAt()));
                    }
                    return sb.toString();
                }
                case "getUserLoginHistory": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String username = String.valueOf(args.get("username"));
                    int limit = args.get("limit") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;
                    List<UserSessionLog> events = sessionLogRepository
                            .findByUsernameIgnoreCaseOrderByOccurredAtDescIdDesc(
                                    username, org.springframework.data.domain.PageRequest.of(0, limit));
                    if (events.isEmpty()) {
                        return "No login/logout events recorded for user '" + username + "'.";
                    }
                    StringBuilder sb = new StringBuilder("Session history for '" + username + "' (newest first):\n");
                    for (UserSessionLog event : events) {
                        sb.append(String.format("  - %s at %s%n", event.getEvent(), event.getOccurredAt()));
                    }
                    return sb.toString();
                }
                case "getUserSessionActivity": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String username = String.valueOf(args.get("username"));
                    List<UserSessionLog> events = sessionLogRepository
                            .findByUsernameIgnoreCaseOrderByOccurredAtDescIdDesc(
                                    username, org.springframework.data.domain.PageRequest.of(0, 100));
                    if (events.isEmpty()) {
                        return "No session activity recorded for user '" + username + "'.";
                    }
                    var lastLogin = events.stream()
                            .filter(e -> e.getEvent() == UserSessionLog.Event.LOGIN).findFirst();
                    var lastLogout = events.stream()
                            .filter(e -> e.getEvent() == UserSessionLog.Event.LOGOUT).findFirst();
                    return String.format(
                            "Session summary for '%s': total events=%d, last login=%s, last logout=%s",
                            username, events.size(),
                            lastLogin.map(UserSessionLog::getOccurredAt).map(Object::toString).orElse("never"),
                            lastLogout.map(UserSessionLog::getOccurredAt).map(Object::toString).orElse("no logout recorded"));
                }
                case "createOrder": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    List<Map<String, Object>> lines = castItemList(args.get("items"));
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("order must contain at least one item");
                    }
                    Map<Long, Integer> quantities = new java.util.LinkedHashMap<>();
                    StringBuilder summary = new StringBuilder();
                    for (Map<String, Object> line : lines) {
                        String itemName = String.valueOf(line.get("itemName")).trim();
                        int quantity = line.get("quantity") instanceof Number n ? n.intValue() : 0;
                        if (quantity < 1) {
                            throw new IllegalArgumentException("quantity must be at least 1 for '" + itemName + "'");
                        }
                        MenuItem menuItem = menuItemRepository.findAll().stream()
                                .filter(m -> m.getName().equalsIgnoreCase(itemName))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("menu item not found: " + itemName));
                        quantities.merge(menuItem.getId(), quantity, Integer::sum);
                        summary.append(String.format("%s x%d, ", menuItem.getName(), quantity));
                    }
                    Order created = orderService.createOrder(quantities);
                    return String.format("Created order #%d (%s) total=%.2f",
                            created.getId(), summary.substring(0, summary.length() - 2),
                            created.getTotalAmount());
                }
                case "updateOrderStatus": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    long orderId = ((Number) args.get("orderId")).longValue();
                    OrderStatus status = OrderStatus.valueOf(
                            String.valueOf(args.get("status")).trim().toUpperCase());
                    orderService.updateStatus(orderId, status);
                    return String.format("Order #%d status changed to %s", orderId, status);
                }
                case "updateInventory": {
                    Map<String, Object> args = objectMapper.readValue(argumentsJson,
                            new TypeReference<Map<String, Object>>() {});
                    String itemName = String.valueOf(args.get("itemName"));
                    int stockQuantity = ((Number) args.get("stockQuantity")).intValue();
                    if (stockQuantity < 0) {
                        throw new IllegalArgumentException("stockQuantity cannot be negative");
                    }
                    Inventory inv = inventoryService.findAll().stream()
                            .filter(i -> i.getMenuItem().getName().equalsIgnoreCase(itemName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("tracked inventory item not found: " + itemName));
                    inventoryService.update(inv.getId(), inv.isTrackInventory(),
                            stockQuantity, inv.getLowStockThreshold());
                    return String.format("Updated stock of \"%s\" to %d units",
                            inv.getMenuItem().getName(), stockQuantity);
                }
                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (IllegalArgumentException e) {
            log.warn("Tool call failed (business error): tool={}, args={}", toolName, argumentsJson, e);
            return "Not found: " + e.getMessage();
        } catch (SecurityException e) {
            log.warn("Tool call rejected (permission): tool={}, user={}",
                    toolName, actor == null ? "?" : actor.getUsername(), e);
            return "Permission denied: you are not allowed to use " + toolName + ".";
        } catch (Exception e) {
            log.error("Tool call error: tool={}, args={}", toolName, argumentsJson, e);
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }

    /**
     * Write tools require the caller to hold {@code AI_AGENTIC_ACTIONS} plus
     * the owning module permission. Enforced here (defense in depth) so an AI
     * can never execute an action the user lacks the permission for, even if
     * the model hallucinated the tool call.
     */
    private void enforceWritePermissions(String toolName, User actor) {
        if (!isWriteTool(toolName)) {
            return;
        }
        boolean allowed = actor != null
                && AgenticPermissions.holds(actor, Permission.AI_AGENTIC_ACTIONS)
                && requiredPermissionFor(toolName)
                        .map(p -> AgenticPermissions.holds(actor, p))
                        .orElse(false);
        if (!allowed) {
            throw new SecurityException("User is not allowed to execute " + toolName);
        }
    }
}