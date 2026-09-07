package com.cafeerp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.assistant.AssistantToolRegistry;
import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;
import com.cafeerp.menu.MenuService;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.order.OrderService;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.report.ReportService;
import com.cafeerp.settings.SettingsService;

/**
 * Phase 9 (performance pass) regression tests: the assistant's read-access
 * tools and the report path now use paged/indexed/aggregate queries instead
 * of load-everything-and-scan. These tests pin the CORRECTNESS of the new
 * query paths (limits, status filters, newest-first order, case-insensitive
 * name lookups, combined sum+count) against the real Flyway-migrated schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase9PerfIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired MenuService menuService;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ReportService reportService;
    @Autowired SettingsService settingsService;
    @Autowired AssistantToolRegistry toolRegistry;

    private MenuItem trackedItem;
    private MenuItem untrackedItem;
    private MenuItem lowItem;
    private final List<Long> seededOrderIds = new ArrayList<>();

    @BeforeAll
    void seedCatalogAndOrders() {
        Category category = new Category();
        category.setName("Perf Test Category");
        category.setActive(true);
        category = categoryRepository.save(category);

        trackedItem = menuItem("Perf Espresso", "2.50", category);
        untrackedItem = menuItem("Perf Croissant", "3.00", category);
        lowItem = menuItem("Perf Low Beans", "1.00", category);

        // High stock: the five seeded orders (quantities 1..5 = 15 units
        // total) decrement this without ever going low.
        saveInventory(trackedItem, true, 100, 5);
        saveInventory(untrackedItem, false, 0, 5); // untracked: never counts
        saveInventory(lowItem, true, 2, 5); // low: 2 <= 5, never ordered

        // Five orders, alternating status, increasing totals.
        for (int i = 1; i <= 5; i++) {
            Order order = orderService.createOrder(Map.of(trackedItem.getId(), i));
            if (i % 2 == 0) {
                order = orderService.updateStatus(order.getId(), OrderStatus.COMPLETED);
            }
            seededOrderIds.add(order.getId());
        }
    }

    private MenuItem menuItem(String name, String price, Category category) {
        return menuItemRepository.findFirstByNameIgnoreCase(name).orElseGet(() -> {
            MenuItem item = new MenuItem();
            item.setName(name);
            item.setPrice(new BigDecimal(price));
            item.setAvailable(true);
            item.setCategory(category);
            return menuItemRepository.save(item);
        });
    }

    private void saveInventory(MenuItem item, boolean tracked, int stock, int threshold) {
        if (inventoryRepository.findByMenuItem_NameIgnoreCase(item.getName()).isEmpty()) {
            Inventory inv = new Inventory();
            inv.setMenuItem(item);
            inv.setTrackInventory(tracked);
            inv.setStockQuantity(stock);
            inv.setLowStockThreshold(threshold);
            inventoryRepository.save(inv);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void findRecent_shouldRespectLimitAndBeNewestFirst() {
        List<Order> recent = orderService.findRecent(3);

        assertThat(recent).hasSize(3);
        for (int i = 1; i < recent.size(); i++) {
            assertThat(recent.get(i - 1).getCreatedAt())
                    .isAfterOrEqualTo(recent.get(i).getCreatedAt());
        }
        // Equivalence with the legacy full-list path: the same first page.
        List<Long> legacyFirstPage = orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .limit(3).map(Order::getId).toList();
        assertThat(recent.stream().map(Order::getId).toList()).isEqualTo(legacyFirstPage);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void findRecentByStatus_shouldFilterByStatus() {
        List<Order> pending = orderService.findRecentByStatus(OrderStatus.PENDING, 10);

        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(o -> o.getStatus() == OrderStatus.PENDING);

        List<Order> completed = orderService.findRecentByStatus(OrderStatus.COMPLETED, 10);
        assertThat(completed).isNotEmpty();
        assertThat(completed).allMatch(o -> o.getStatus() == OrderStatus.COMPLETED);

        assertThat(pending.size() + completed.size()).isGreaterThanOrEqualTo(seededOrderIds.size());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void nameLookups_shouldBeCaseInsensitive() {
        assertThat(menuItemRepository.findFirstByNameIgnoreCase("perf espresso"))
                .map(MenuItem::getId).contains(trackedItem.getId());
        assertThat(menuService.findByNameIgnoreCase("PERF CROISSANT"))
                .map(MenuItem::getId).contains(untrackedItem.getId());
        assertThat(inventoryService.findByItemNameIgnoreCase("pErF eSpReSsO"))
                .map(inv -> inv.getMenuItem().getId()).contains(trackedItem.getId());
        assertThat(inventoryService.findByItemNameIgnoreCase("no such item")).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void countLowStock_shouldMatchManualPredicate() {
        long expected = inventoryRepository.findAll().stream()
                .filter(inv -> inv.isTrackInventory()
                        && inv.getStockQuantity() <= inv.getLowStockThreshold())
                .count();

        assertThat(inventoryService.countLowStock()).isEqualTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void report_shouldCombineSumAndCountInOneQuery() {
        java.time.ZoneId zone = settingsService.getTimeZone();
        LocalDate today = LocalDate.now(zone);
        java.time.LocalDateTime from = today.atStartOfDay();
        java.time.LocalDateTime to = today.atTime(java.time.LocalTime.MAX);
        var report = reportService.generateReport(from, to);

        // Cross-check against the two legacy single-purpose queries over the
        // identical UTC bounds the service converts to internally.
        java.time.LocalDateTime fromUtc = from.atZone(zone)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        java.time.LocalDateTime toUtc = to.atZone(zone)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        BigDecimal legacySum = orderRepository.sumTotalAmountBetween(fromUtc, toUtc);
        long legacyCount = orderRepository.countByCreatedAtBetween(fromUtc, toUtc);

        assertThat(report.totalSales()).isEqualByComparingTo(legacySum);
        assertThat(report.orderCount()).isEqualTo(legacyCount);
        assertThat(report.orderCount()).isGreaterThanOrEqualTo(seededOrderIds.size());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void report_emptyRange_shouldYieldZeroNotNull() {
        // Exercises the coalesce-empty-range path of the combined aggregate
        // (the total column comes back as Integer 0, not BigDecimal).
        var report = reportService.generateReport(
                LocalDate.of(1999, 1, 1).atStartOfDay(),
                LocalDate.of(1999, 1, 2).atStartOfDay());

        assertThat(report.totalSales()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.orderCount()).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void orderHistoryTool_shouldPageAndFilter() throws Exception {
        String two = toolRegistry.execute("getOrderHistory", "{\"limit\": 2}", null);
        assertThat(two.lines().filter(l -> l.contains("Order #")).count()).isEqualTo(2);

        String pending = toolRegistry.execute(
                "getOrderHistory", "{\"status\": \"PENDING\", \"limit\": 20}", null);
        assertThat(pending).contains("status=PENDING");
        assertThat(pending).doesNotContain("status=COMPLETED");
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    void inventoryTool_shouldFindByNameRegardlessOfCase() throws Exception {
        String result = toolRegistry.execute(
                "getInventoryLevel", "{\"itemName\": \"pErF eSpReSsO\"}", null);

        assertThat(result).startsWith("Perf Espresso: stock=");
        assertThat(result).contains("tracking=yes");
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    void buildVersionEndpoint_shouldStillSelfAnnounce() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/build-version"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyOrNullString())));
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    void renderedPage_shouldDeferHeadScriptsAndStampBuildVersion() throws Exception {
        // Real request/response through the full MVC + Security + Thymeleaf
        // stack: the dashboard exercises countLowStock() (now an aggregate
        // query) and renders the layout head.
        org.springframework.mock.web.MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        String html = mockMvc.perform(MockMvcRequestBuilders.get("/").session(staff))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Phase 9 initial-load fix: every head script must be deferred.
        assertThat(html).contains("core.iife.js\" defer");
        assertThat(html).contains("marked.min.js\" defer");
        assertThat(html).contains("purify.min.js\" defer");
        assertThat(html).contains("/js/app.js\" defer");

        // Self-announce mechanism intact: the page embeds the stamp AND the
        // server endpoint reports the identical value the banner polls.
        java.util.regex.Matcher meta = java.util.regex.Pattern
                .compile("<meta name=\"build-version\" content=\"([^\"]*)\"")
                .matcher(html);
        assertThat(meta.find()).isTrue();
        String pageVersion = meta.group(1);
        assertThat(pageVersion).isNotBlank();
        String serverVersion = mockMvc.perform(MockMvcRequestBuilders.get("/build-version"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(serverVersion.trim()).isEqualTo(pageVersion.trim());
    }
}
