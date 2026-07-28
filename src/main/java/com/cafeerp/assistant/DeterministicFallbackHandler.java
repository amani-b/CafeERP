package com.cafeerp.assistant;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cafeerp.assistant.AssistantService.SourceLink;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuService;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderService;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.report.ReportService;
import com.cafeerp.user.Role;

/**
 * Deterministic (non-AI) fallback that answers user queries by pattern-matching
 * against known keywords and calling the same service methods the AI path uses.
 * Used when all model providers have failed.
 */
@Component
public class DeterministicFallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(DeterministicFallbackHandler.class);

    private final OrderService orderService;
    private final MenuService menuService;
    private final ReportService reportService;
    private final InventoryService inventoryService;
    private final AssistantToolRegistry toolRegistry;

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("#?(\\d+)");

    public DeterministicFallbackHandler(OrderService orderService,
                                        MenuService menuService,
                                        ReportService reportService,
                                        InventoryService inventoryService,
                                        AssistantToolRegistry toolRegistry) {
        this.orderService = orderService;
        this.menuService = menuService;
        this.reportService = reportService;
        this.inventoryService = inventoryService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Attempt to answer a user message deterministically. Returns null if no
     * pattern matched (caller should use the generic "unavailable" message).
     */
    public AssistantService.AssistantReply tryAnswer(String userMessage, Role role) {
        Set<String> allowedTools = toolRegistry.allowedToolNamesForRole(role);
        String lower = userMessage.toLowerCase().trim();

        // 1. Order number query — available to all roles that have getOrderStatus
        if (allowedTools.contains("getOrderStatus")) {
            Matcher orderMatcher = ORDER_ID_PATTERN.matcher(userMessage);
            if (orderMatcher.find()) {
                try {
                    Long orderId = Long.parseLong(orderMatcher.group(1));
                    Order order = orderService.findById(orderId);
                    String text = String.format("Order #%d: status=%s, items=%d, total=%.2f, created=%s",
                            order.getId(), order.getStatus(), order.getItemCount(),
                            order.getTotalAmount(), order.getCreatedAt());
                    String url = role == Role.KITCHEN ? "/kitchen" : "/orders/" + orderId;
                    return new AssistantService.AssistantReply(text, List.of(new SourceLink("View Order", url)));
                } catch (IllegalArgumentException e) {
                    return new AssistantService.AssistantReply("Order not found: " + e.getMessage(), List.of());
                }
            }
        }

        // 2. Menu / items query — available to all roles
        if (allowedTools.contains("getMenuItems") && containsAny(lower, "menu", "item", "items", "price", "prices", "available")) {
            List<MenuItem> items = menuService.findAll();
            StringBuilder sb = new StringBuilder("Here are the current menu items:\n");
            for (MenuItem item : items) {
                sb.append(String.format("  - %s: $%.2f %s\n",
                        item.getName(), item.getPrice(),
                        item.isAvailable() ? "(available)" : "(unavailable)"));
            }
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Menu", "/menu")));
        }

        // 3. Sales / revenue / top sellers — admin only
        if (containsAny(lower, "sales", "revenue", "top seller", "top sellers", "best seller", "best sellers")) {
            if (!allowedTools.contains("getSalesTotals")) {
                return new AssistantService.AssistantReply(
                        "I'm sorry, sales and revenue information is only available to managers and administrators. "
                        + "Please ask an admin for help with this.",
                        List.of());
            }
            String range = resolveRange(lower);
            var dateRange = reportService.resolveDateRange(range);
            var report = reportService.generateReport(dateRange.from(), dateRange.to());
            String text = String.format("Sales for %s: total=%.2f, orders=%d (from %s to %s)",
                    range, report.totalSales(), report.orderCount(), report.from(), report.to());
            return new AssistantService.AssistantReply(text, List.of(new SourceLink("View Sales Report", "/reports")));
        }

        // 4. Top selling items — admin only
        if (containsAny(lower, "top seller", "top sellers", "best seller", "best sellers", "most popular", "best selling")) {
            if (!allowedTools.contains("getTopSellingItems")) {
                return new AssistantService.AssistantReply(
                        "I'm sorry, sales and revenue information is only available to managers and administrators. "
                        + "Please ask an admin for help with this.",
                        List.of());
            }
            String range = resolveRange(lower);
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
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Sales Report", "/reports")));
        }

        // 5. Inventory / stock — admin only
        if (containsAny(lower, "inventory", "stock", "ingredient")) {
            if (!allowedTools.contains("getInventoryLevel")) {
                return new AssistantService.AssistantReply(
                        "I'm sorry, inventory information is only available to managers and administrators. "
                        + "Please ask an admin for help with this.",
                        List.of());
            }
            // Try to match an item name
            List<MenuItem> items = menuService.findAll();
            for (MenuItem item : items) {
                if (lower.contains(item.getName().toLowerCase())) {
                    List<Inventory> all = inventoryService.findAll();
                    for (Inventory inv : all) {
                        if (inv.getMenuItem().getName().equalsIgnoreCase(item.getName())) {
                            String text = String.format("%s: stock=%d, threshold=%d, tracking=%s",
                                    inv.getMenuItem().getName(),
                                    inv.getStockQuantity(),
                                    inv.getLowStockThreshold(),
                                    inv.isTrackInventory() ? "yes" : "no");
                            return new AssistantService.AssistantReply(text, List.of(new SourceLink("View Inventory", "/inventory")));
                        }
                    }
                    return new AssistantService.AssistantReply("No inventory tracking found for " + item.getName(), List.of());
                }
            }
            // No specific item matched — list all inventory
            List<Inventory> all = inventoryService.findAll();
            StringBuilder sb = new StringBuilder("Current inventory levels:\n");
            for (Inventory inv : all) {
                sb.append(String.format("  - %s: %d in stock (threshold: %d)\n",
                        inv.getMenuItem().getName(), inv.getStockQuantity(), inv.getLowStockThreshold()));
            }
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Inventory", "/inventory")));
        }

        // 6. Kitchen queue — available to KITCHEN and ADMIN
        if (allowedTools.contains("getKitchenQueueSummary") && containsAny(lower, "kitchen", "queue", "order status", "preparing", "ready")) {
            var active = orderService.findActiveOrders();
            long pending = active.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
            long preparing = active.stream().filter(o -> o.getStatus() == OrderStatus.PREPARING).count();
            long ready = active.stream().filter(o -> o.getStatus() == OrderStatus.READY).count();
            String text = String.format("Kitchen queue: PENDING=%d, PREPARING=%d, READY=%d (total active=%d)",
                    pending, preparing, ready, active.size());
            return new AssistantService.AssistantReply(text, List.of(new SourceLink("View Kitchen Queue", "/kitchen")));
        }

        // No pattern matched
        return null;
    }

    /**
     * Build a role-scoped "unavailable" message listing what the user CAN ask about.
     */
    public AssistantService.AssistantReply unavailableMessage(Role role) {
        Set<String> allowedTools = toolRegistry.allowedToolNamesForRole(role);
        StringBuilder sb = new StringBuilder(
                "The AI assistant is temporarily unavailable. ");
        sb.append("You can still ask me about:\n");

        if (allowedTools.contains("getOrderStatus")) {
            sb.append("  - Order status (e.g. \"What's the status of order 5?\")\n");
        }
        if (allowedTools.contains("getMenuItems")) {
            sb.append("  - Menu items (e.g. \"What's on the menu?\")\n");
        }
        if (allowedTools.contains("getKitchenQueueSummary")) {
            sb.append("  - Kitchen queue (e.g. \"What's in the kitchen queue?\")\n");
        }
        if (allowedTools.contains("getSalesTotals") || allowedTools.contains("getTopSellingItems")) {
            sb.append("  - Sales reports (e.g. \"What were sales today?\")\n");
        }
        if (allowedTools.contains("getInventoryLevel")) {
            sb.append("  - Inventory levels (e.g. \"How much stock of coffee do we have?\")\n");
        }

        sb.append("\nPlease try your question again, or ask a manager if you need further assistance.");
        return new AssistantService.AssistantReply(sb.toString(), List.of());
    }

    // ---------------------------------------------------------------
    //  Private helpers
    // ---------------------------------------------------------------

    private static boolean containsAny(String lower, String... keywords) {
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveRange(String lower) {
        if (lower.contains("today")) return "today";
        if (lower.contains("week") || lower.contains("weekly")) return "week";
        if (lower.contains("month") || lower.contains("monthly")) return "month";
        return "all";
    }
}