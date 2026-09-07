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

    // Package-private for access by AssistantService's routing classifier.
    //
    // SECURITY/UX BUG FIXED: this used to be "#?(\d+)" which matched ANY number
    // in ANY message ("got a table of 4 — pastry suggestions?" -> "Order not
    // found"). Now a match requires explicit order context: the word "order"
    // immediately before the number, or a leading '#' before the number.
    static final Pattern ORDER_ID_PATTERN =
            Pattern.compile("(?:order\\s*#?\\s*(\\d{1,9}))|(?:#(\\d{1,9}))",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Amharic order references: Ge'ez ትዕዛዝ / ትእዛዝ / ኦርደር (with optional
     * suffixed definitives ትዕዛዙ etc.) and transliterated tizaz/tiezaz
     * (tizazu, tizazew…) — e.g. "ትዕዛዝ 482 የት ደርሷል?", "tizaz 482 yet laye?".
     * Checked alongside {@link #ORDER_ID_PATTERN} so Amharic order lookups get
     * the same deterministic fast path (including the service-level routing
     * classifier that calls {@link #extractOrderId}).
     */
    static final Pattern AMHARIC_ORDER_ID_PATTERN = Pattern.compile(
            "(?:ትዕዛ(?:ዝ|ዙ|ዞች|ዞቹ)|ትእዛ(?:ዝ|ዙ|ዞች|ዞቹ)|ኦርደር|ኦርደሩ|ኦርደሮች)\\s*#?\\s*(\\d{1,9})"
            + "|(?:tizaz|tiezaz)(?:u|ew|w|un|ye|woch|och)?\\s*#?\\s*(\\d{1,9})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extract an order id from an order-looking query, or empty if none.
     */
    static java.util.Optional<Long> extractOrderId(String userMessage) {
        Matcher m = ORDER_ID_PATTERN.matcher(userMessage);
        if (m.find()) {
            String digits = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                return java.util.Optional.of(Long.parseLong(digits));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        }
        Matcher am = AMHARIC_ORDER_ID_PATTERN.matcher(userMessage);
        if (am.find()) {
            String digits = am.group(1) != null ? am.group(1) : am.group(2);
            try {
                return java.util.Optional.of(Long.parseLong(digits));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }

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
     *
     * <p>Honest Amharic degradation: the data lines keep the exact tool-format
     * shapes (numbers, names and statuses match the AI path and the audit
     * log), but the surrounding sentence is in the user's own script — a Ge'ez
     * or transliterated question never gets a bare English-only reply.
     */
    public AssistantService.AssistantReply tryAnswer(String userMessage, Role role) {
        Set<String> allowedTools = toolRegistry.allowedToolNamesForRole(role);
        String lower = userMessage.toLowerCase().trim();

        // 1. Order number query — available to all roles that have getOrderStatus
        if (allowedTools.contains("getOrderStatus")) {
            java.util.Optional<Long> orderIdOpt = extractOrderId(userMessage);
            if (orderIdOpt.isPresent()) {
                try {
                    Long orderId = orderIdOpt.get();
                    Order order = orderService.findById(orderId);
                    String text = String.format("Order #%d: status=%s, items=%d, total=%.2f, created=%s",
                            order.getId(), order.getStatus(), order.getItemCount(),
                            order.getTotalAmount(), order.getCreatedAt());
                    String url = role == Role.KITCHEN ? "/kitchen" : "/orders/" + orderId;
                    return new AssistantService.AssistantReply(
                            amharicWrap(userMessage,
                                    "እሺ — መረጃውን አገኘሁ፦\n",
                                    "eshi — merejawon agegnehu:\n") + text,
                            List.of(new SourceLink("View Order", url)));
                } catch (IllegalArgumentException e) {
                    return new AssistantService.AssistantReply(
                            amharicWrap(userMessage,
                                    "ይቅርታ — ትዕዛዙ አልተገኘም፦ ",
                                    "yikerta — tizazu altegegnem: ") + e.getMessage(),
                            List.of());
                }
            }
        }

        // 2. Menu / items query — available to all roles
        if (allowedTools.contains("getMenuItems") && containsAny(lower, "menu", "item", "items", "price", "prices", "available",
                "ምናሌ", "ሜኑ", "ዋጋ", "minale", "menale", "waga")) {
            List<MenuItem> items = menuService.findAll();
            String header = amharicWrap(userMessage,
                    "እሺ — የአሁኑ ምናሌ እነዚህ ናቸው፦\n",
                    "eshi — ahunachew minale enezih nachew:\n");
            StringBuilder sb = new StringBuilder(header.isEmpty() ? "Here are the current menu items:\n" : header);
            for (MenuItem item : items) {
                sb.append(String.format("  - %s: $%.2f %s\n",
                        item.getName(), item.getPrice(),
                        item.isAvailable() ? "(available)" : "(unavailable)"));
            }
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Menu", "/menu")));
        }

        // 3. Sales / revenue / top sellers — admin only
        if (containsAny(lower, "sales", "revenue", "top seller", "top sellers", "best seller", "best sellers",
                "ሽያጭ", "ሽያች", "ገቢ", "ትርፍ", "shyach", "shiyach", "gebi", "tirf")) {
            if (!allowedTools.contains("getSalesTotals")) {
                return new AssistantService.AssistantReply(
                        amharicDenied(userMessage,
                                "I'm sorry, sales and revenue information is only available to managers and administrators. "
                                + "Please ask an admin for help with this.",
                                "ይቅርታ — የሽያጭ እና የገቢ መረጃ ለማናጀሮች ብቻ ነው። እባክዎ ማናጀርዎን ይጠይቁ።",
                                "yikerta — ye-shyach na ye-gebi mereja le-manajeroch bicha new. ebakwo manajerwon yiteyiku."),
                        List.of());
            }
            String range = resolveRange(lower);
            var dateRange = reportService.resolveDateRange(range);
            var report = reportService.generateReport(dateRange.from(), dateRange.to());
            String text = String.format("Sales for %s: total=%.2f, orders=%d (from %s to %s)",
                    range, report.totalSales(), report.orderCount(), report.from(), report.to());
            return new AssistantService.AssistantReply(
                    amharicWrap(userMessage, "እሺ — የሽያጭ ሪፖርት፦\n", "eshi — ye-shyach report:\n") + text,
                    List.of(new SourceLink("View Sales Report", "/reports")));
        }

        // 4. Top selling items — admin only
        if (containsAny(lower, "top seller", "top sellers", "best seller", "best sellers", "most popular", "best selling",
                "ምርጥ", "ተወዳጅ")) {
            if (!allowedTools.contains("getTopSellingItems")) {
                return new AssistantService.AssistantReply(
                        amharicDenied(userMessage,
                                "I'm sorry, sales and revenue information is only available to managers and administrators. "
                                + "Please ask an admin for help with this.",
                                "ይቅርታ — የሽያጭ መረጃ ለማናጀሮች ብቻ ነው። እባክዎ ማናጀርዎን ይጠይቁ።",
                                "yikerta — ye-shyach mereja le-manajeroch bicha new. ebakwo manajerwon yiteyiku."),
                        List.of());
            }
            String range = resolveRange(lower);
            var dateRange = reportService.resolveDateRange(range);
            var report = reportService.generateReport(dateRange.from(), dateRange.to());
            String topHeader = amharicWrap(userMessage,
                    "እሺ — በጣም የሚሸጡ ዕቃዎች፦\n",
                    "eshi — betam yemishetu ekawch:\n");
            StringBuilder sb = new StringBuilder(topHeader.isEmpty() ? "Top 5 selling items:\n" : topHeader);
            int rank = 1;
            for (var item : report.topItems()) {
                sb.append(String.format("  %d. %s — %d sold\n", rank++, item.getItemName(), item.getTotalQuantity()));
            }
            if (report.topItems().isEmpty()) {
                sb.append(amharicWrap(userMessage,
                        "  (በዚህ ጊዜ ሽያጭ የለም)\n",
                        "  (bezihe gize shyach yelem)\n",
                        "  (no sales in this period)\n"));
            }
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Sales Report", "/reports")));
        }

        // 5. Inventory / stock — admin only
        if (containsAny(lower, "inventory", "stock", "ingredient", "ክምችት", "ስቶክ", "kimchit")) {
            if (!allowedTools.contains("getInventoryLevel")) {
                return new AssistantService.AssistantReply(
                        amharicDenied(userMessage,
                                "I'm sorry, inventory information is only available to managers and administrators. "
                                + "Please ask an admin for help with this.",
                                "ይቅርታ — የክምችት መረጃ ለማናጀሮች ብቻ ነው። እባክዎ ማናጀርዎን ይጠይቁ።",
                                "yikerta — ye-kimchit mereja le-manajeroch bicha new. ebakwo manajerwon yiteyiku."),
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
                            return new AssistantService.AssistantReply(
                                    amharicWrap(userMessage,
                                            "እሺ — የክምችት መረጃ፦\n",
                                            "eshi — ye-kimchit mereja:\n") + text,
                                    List.of(new SourceLink("View Inventory", "/inventory")));
                        }
                    }
                    return new AssistantService.AssistantReply(
                            amharicWrap(userMessage,
                                    "ለዚህ ዕቃ የተከታተለ ክምችት አልተገኘም፦ ",
                                    "lezihe eka yeteketatele kimchit altegegnem: ")
                            + item.getName(), List.of());
                }
            }
            // No specific item matched — list all inventory
            List<Inventory> all = inventoryService.findAll();
            String invHeader = amharicWrap(userMessage,
                    "እሺ — የአሁኑ የክምችት መጠን፦\n",
                    "eshi — ahunachew ye-kimchit meten:\n");
            StringBuilder sb = new StringBuilder(invHeader.isEmpty() ? "Current inventory levels:\n" : invHeader);
            for (Inventory inv : all) {
                sb.append(String.format("  - %s: %d in stock (threshold: %d)\n",
                        inv.getMenuItem().getName(), inv.getStockQuantity(), inv.getLowStockThreshold()));
            }
            return new AssistantService.AssistantReply(sb.toString(), List.of(new SourceLink("View Inventory", "/inventory")));
        }

        // 6. Kitchen queue — available to KITCHEN and ADMIN
        if (allowedTools.contains("getKitchenQueueSummary") && containsAny(lower, "kitchen", "queue", "order status", "preparing", "ready",
                "ኩሽና", "kushina", "ትዕዛዝ", "ትእዛዝ", "tizaz", "tiezaz")) {
            var active = orderService.findActiveOrders();
            long pending = active.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
            long preparing = active.stream().filter(o -> o.getStatus() == OrderStatus.PREPARING).count();
            long ready = active.stream().filter(o -> o.getStatus() == OrderStatus.READY).count();
            String text = String.format("Kitchen queue: PENDING=%d, PREPARING=%d, READY=%d (total active=%d)",
                    pending, preparing, ready, active.size());
            return new AssistantService.AssistantReply(
                    amharicWrap(userMessage, "እሺ — የኩሽና ረድፍ፦\n", "eshi — ye-kushina redf:\n") + text,
                    List.of(new SourceLink("View Kitchen Queue", "/kitchen")));
        }

        // No pattern matched
        return null;
    }

    /**
     * Build a role-scoped "unavailable" message listing what the user CAN ask about.
     * English variant (legacy callers without message context).
     */
    public AssistantService.AssistantReply unavailableMessage(Role role) {
        return unavailableMessage(role, null);
    }

    /**
     * Script-aware variant: when the user's turn was Amharic (Ge'ez or Latin),
     * the unavailable message itself is Amharic in the matching script — the
     * fallback degrades honestly instead of failing silent-English-only when
     * Amharic was expected.
     */
    public AssistantService.AssistantReply unavailableMessage(Role role, String userMessage) {
        Set<String> allowedTools = toolRegistry.allowedToolNamesForRole(role);
        AmharicLanguageSupport.Script script =
                AmharicLanguageSupport.detect(userMessage == null ? "" : userMessage);
        boolean geez = script == AmharicLanguageSupport.Script.GEEZ
                || (script == AmharicLanguageSupport.Script.MIXED
                        && AmharicLanguageSupport.containsGeez(userMessage));
        boolean trans = script == AmharicLanguageSupport.Script.TRANSLITERATED
                || (script == AmharicLanguageSupport.Script.MIXED && !geez);
        if (geez || trans) {
            return amharicUnavailable(allowedTools, geez);
        }
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

    /** Amharic unavailable message in the user's own script (Ge'ez or Latin). */
    private static AssistantService.AssistantReply amharicUnavailable(Set<String> allowedTools, boolean geez) {
        StringBuilder sb = new StringBuilder(geez
                ? "ይቅርታ — AI ረዳት ለአሁን አይገኝም። ግን እኔ እዚህ ነኝ — እነዚህን መጠየቅ ይችላሉ፦\n"
                : "yikerta — AI redate le-ahun aygegngem. gin ene ezih negn — enezihn meteyek yichalalu:\n");
        if (allowedTools.contains("getOrderStatus")) {
            sb.append(geez ? "  - የትዕዛዝ ሁኔታ (ለምሳሌ «ትዕዛዝ 5 የት ደርሷል?»)\n"
                    : "  - Ye-tizaz huneta (lemisale \"tizaz 5 yet dereswal?\")\n");
        }
        if (allowedTools.contains("getMenuItems")) {
            sb.append(geez ? "  - ምናሌ (ለምሳሌ «ምናሌው ምንድን ነው?»)\n"
                    : "  - Minale (lemisale \"minalew mindin new?\")\n");
        }
        if (allowedTools.contains("getKitchenQueueSummary")) {
            sb.append(geez ? "  - የኩሽና ረድፍ (ለምሳሌ «ኩሽና ላይ ምን አለ?»)\n"
                    : "  - Ye-kushina redf (lemisale \"kushina lay mindin ale?\")\n");
        }
        if (allowedTools.contains("getSalesTotals") || allowedTools.contains("getTopSellingItems")) {
            sb.append(geez ? "  - የሽያጭ ሪፖርት (ለምሳሌ «የዛሬ ሽያጭ ስንት ነው?»)\n"
                    : "  - Ye-shyach report (lemisale \"ye-zare shyach sint new?\")\n");
        }
        if (allowedTools.contains("getInventoryLevel")) {
            sb.append(geez ? "  - የክምችት መጠን (ለምሳሌ «የቡና ክምችት ስንት አለ?»)\n"
                    : "  - Ye-kimchit meten (lemisale \"ye-buna kimchit sint ale?\")\n");
        }
        sb.append(geez ? "\nእባክዎ ጥያቄዎን እንደገና ይሞክሩ።"
                : "\nEbakwo tiyakewon endegena yimokru.");
        return new AssistantService.AssistantReply(sb.toString(), List.of());
    }

    /**
     * Amharic-aware line prefix for a deterministic data block: Ge'ez turns get
     * the Ge'ez lead, transliterated turns the Latin lead, pure English turns
     * get nothing (byte-identical English path). Data lines after the prefix
     * are untouched.
     */
    static String amharicWrap(String userMessage, String geezPrefix, String transPrefix) {
        return amharicWrap(userMessage, geezPrefix, transPrefix, "");
    }

    /** Three-way variant with an explicit English lead (defaults to ""). */
    static String amharicWrap(String userMessage, String geezPrefix, String transPrefix, String englishPrefix) {
        AmharicLanguageSupport.Script script = AmharicLanguageSupport.detect(userMessage);
        return switch (script) {
            case GEEZ -> geezPrefix;
            case TRANSLITERATED -> transPrefix;
            case MIXED -> AmharicLanguageSupport.containsGeez(userMessage) ? geezPrefix : transPrefix;
            case ENGLISH -> englishPrefix;
        };
    }

    /** Permission-denial line in the user's own script. */
    static String amharicDenied(String userMessage, String english, String geez, String trans) {
        AmharicLanguageSupport.Script script = AmharicLanguageSupport.detect(userMessage);
        return switch (script) {
            case GEEZ -> geez;
            case TRANSLITERATED -> trans;
            case MIXED -> AmharicLanguageSupport.containsGeez(userMessage) ? geez : trans;
            case ENGLISH -> english;
        };
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

    private static final Pattern WER_WORD = Pattern.compile("\\bwer\\b");

    private static String resolveRange(String lower) {
        if (lower.contains("today") || lower.contains("ዛሬ") || containsAny(lower, "zare", "zarey")) return "today";
        if (lower.contains("week") || lower.contains("weekly") || lower.contains("ሳምንት")
                || containsAny(lower, "samint", "samnt")) return "week";
        if (lower.contains("month") || lower.contains("monthly") || lower.contains("ወር")
                || WER_WORD.matcher(lower).find()) return "month";
        return "all";
    }
}