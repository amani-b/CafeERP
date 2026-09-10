package com.cafeerp.demo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.cafeerp.assistant.AssistantConversation;
import com.cafeerp.assistant.AssistantMessage;
import com.cafeerp.assistant.AssistantMessageRole;
import com.cafeerp.category.Category;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderItem;
import com.cafeerp.order.OrderStatus;
import com.cafeerp.settings.AppSetting;
import com.cafeerp.settings.SettingsService;
import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserSessionLog;

/**
 * Static showcase fixture for the {@code demo} profile.
 * <p>
 * Everything here is fictional (the "Abyssinia Brew" demo cafe) and doubles as
 * the template every visitor session clones from — {@link DemoSessionStore}
 * calls {@link #build(PasswordEncoder)} once per session, so the template
 * itself is never mutated. The seed deliberately mixes English, Amharic in
 * Ge'ez script, and Amharic written in Latin letters (transliteration), since
 * trilingual content is a feature the demo should show off.
 */
public final class DemoSeedData {

    /** Password for all three demo accounts (documented in the README). */
    public static final String DEMO_PASSWORD = "demo1234";

    private DemoSeedData() {
    }

    /** One fully-wired object graph with stable, cross-linked ids. */
    public static SeedBundle build(PasswordEncoder passwordEncoder) {
        SeedBundle bundle = new SeedBundle();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // ---- Categories ----
        bundle.categories.add(category(1L, "Hot Drinks", "ሙቅ መጠጦች (muq metetoch) — espresso, jebena buna, tea"));
        bundle.categories.add(category(2L, "Cold Drinks", "Spris, juices and iced drinks"));
        bundle.categories.add(category(3L, "Breakfast", "ቁርስ (qurs) — ful, chechebsa, fatira"));
        bundle.categories.add(category(4L, "Pastries & Snacks", "Croissants, muffins, sambusa"));

        // ---- Menu items ----
        bundle.menuItems.add(menuItem(1L, "Espresso", "60.00", true, bundle.categories.get(0)));
        bundle.menuItems.add(menuItem(2L, "Macchiato / ማኪያቶ (makiyato)", "80.00", true, bundle.categories.get(0)));
        bundle.menuItems.add(menuItem(3L, "Jebena Buna / ጀበና ቡና (jebena buna)", "70.00", true, bundle.categories.get(0)));
        bundle.menuItems.add(menuItem(4L, "Shay / ሻይ (shay)", "45.00", true, bundle.categories.get(0)));
        bundle.menuItems.add(menuItem(5L, "Spris / ስፕሪስ (spris)", "110.00", true, bundle.categories.get(1)));
        bundle.menuItems.add(menuItem(6L, "Avocado Juice", "130.00", true, bundle.categories.get(1)));
        bundle.menuItems.add(menuItem(7L, "Iced Latte", "120.00", true, bundle.categories.get(1)));
        bundle.menuItems.add(menuItem(8L, "Ful / ፉል (ful)", "90.00", true, bundle.categories.get(2)));
        bundle.menuItems.add(menuItem(9L, "Chechebsa / ጨጨብሳ (chechebsa)", "85.00", true, bundle.categories.get(2)));
        bundle.menuItems.add(menuItem(10L, "Fatira", "75.00", true, bundle.categories.get(2)));
        bundle.menuItems.add(menuItem(11L, "Croissant", "95.00", true, bundle.categories.get(3)));
        bundle.menuItems.add(menuItem(12L, "Sambusa (2pc)", "60.00", true, bundle.categories.get(3)));
        bundle.menuItems.add(menuItem(13L, "Blueberry Muffin", "70.00", false, bundle.categories.get(3)));

        // ---- Inventory (one row per menu item, like V3 does in production) ----
        // Item 9 sits below its threshold (low-stock showcase); item 10 has
        // zero tracked stock (order-rejection showcase); 7/11/13 are untracked.
        bundle.inventories.add(inventory(1L, findMenu(bundle, 1L), true, 120, 20, now));
        bundle.inventories.add(inventory(2L, findMenu(bundle, 2L), true, 96, 15, now));
        bundle.inventories.add(inventory(3L, findMenu(bundle, 3L), true, 40, 10, now));
        bundle.inventories.add(inventory(4L, findMenu(bundle, 4L), true, 200, 30, now));
        bundle.inventories.add(inventory(5L, findMenu(bundle, 5L), true, 35, 10, now));
        bundle.inventories.add(inventory(6L, findMenu(bundle, 6L), true, 25, 8, now));
        bundle.inventories.add(inventory(7L, findMenu(bundle, 7L), false, 0, 0, now));
        bundle.inventories.add(inventory(8L, findMenu(bundle, 8L), true, 50, 12, now));
        bundle.inventories.add(inventory(9L, findMenu(bundle, 9L), true, 4, 10, now));
        bundle.inventories.add(inventory(10L, findMenu(bundle, 10L), true, 0, 5, now));
        bundle.inventories.add(inventory(11L, findMenu(bundle, 11L), false, 0, 0, now));
        bundle.inventories.add(inventory(12L, findMenu(bundle, 12L), true, 60, 15, now));
        bundle.inventories.add(inventory(13L, findMenu(bundle, 13L), false, 0, 0, now));

        // ---- Users (one per showcase role) ----
        bundle.users.add(user(1L, "demo-admin", passwordEncoder.encode(DEMO_PASSWORD),
                Role.ADMIN, EnumSet.allOf(Permission.class)));
        bundle.users.add(user(2L, "demo-staff", passwordEncoder.encode(DEMO_PASSWORD), Role.STAFF, null));
        bundle.users.add(user(3L, "demo-kitchen", passwordEncoder.encode(DEMO_PASSWORD), Role.KITCHEN, null));

        // ---- Historical orders (spread across today / this week for reports,
        // with three still active so the kitchen queue is non-empty) ----
        bundle.orders.add(order(1L, OrderStatus.COMPLETED, now.minusDays(6).withHour(9).withMinute(5),
                line(bundle, 3L, 2), line(bundle, 4L, 2)));
        bundle.orders.add(order(2L, OrderStatus.COMPLETED, now.minusDays(5).withHour(10).withMinute(30),
                line(bundle, 2L, 1), line(bundle, 11L, 2)));
        bundle.orders.add(order(3L, OrderStatus.COMPLETED, now.minusDays(2).withHour(8).withMinute(45),
                line(bundle, 8L, 2), line(bundle, 5L, 1)));
        bundle.orders.add(order(4L, OrderStatus.COMPLETED, now.minusDays(1).withHour(16).withMinute(20),
                line(bundle, 2L, 2), line(bundle, 12L, 4)));
        bundle.orders.add(order(5L, OrderStatus.READY, now.minusHours(3), line(bundle, 5L, 2)));
        bundle.orders.add(order(6L, OrderStatus.PREPARING, now.minusMinutes(40),
                line(bundle, 3L, 1), line(bundle, 9L, 1)));
        bundle.orders.add(order(7L, OrderStatus.PENDING, now.minusMinutes(5),
                line(bundle, 2L, 1), line(bundle, 6L, 1)));

        // ---- Settings ----
        bundle.settings.add(new AppSetting(SettingsService.TIMEZONE_KEY, "Africa/Addis_Ababa"));

        // ---- One sample assistant thread (shows the history UI non-empty) ----
        User admin = bundle.users.get(0);
        AssistantConversation conversation = new AssistantConversation(admin);
        conversation.setId(1L);
        conversation.setTitle("How much is makiyato?");
        conversation.setCreatedAt(now.minusDays(1));
        conversation.setLastActivityAt(now.minusDays(1).plusMinutes(2));
        bundle.conversations.add(conversation);

        AssistantMessage question = new AssistantMessage(admin, AssistantMessageRole.USER,
                "Selam! makiyato yint new? (How much is a macchiato?)", conversation);
        question.setId(1L);
        question.setCreatedAt(now.minusDays(1));
        AssistantMessage answer = new AssistantMessage(admin, AssistantMessageRole.ASSISTANT,
                "Selam! **Macchiato / ማኪያቶ** is **80.00** and available. Anything else I can check for you?",
                conversation);
        answer.setId(2L);
        answer.setCreatedAt(now.minusDays(1).plusMinutes(2));
        bundle.messages.add(question);
        bundle.messages.add(answer);

        // ---- Session audit seed (so the session-history tools have content) ----
        bundle.sessionLogs.add(sessionLog(1L, admin, UserSessionLog.Event.LOGIN, now.minusDays(1)));
        bundle.sessionLogs.add(sessionLog(2L, bundle.users.get(1), UserSessionLog.Event.LOGIN,
                now.minusHours(6)));

        return bundle;
    }

    // ---- Builders ----

    private static Category category(Long id, String name, String description) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setDescription(description);
        category.setActive(true);
        return category;
    }

    private static MenuItem menuItem(Long id, String name, String price, boolean available, Category category) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName(name);
        item.setPrice(new BigDecimal(price));
        item.setAvailable(available);
        item.setCategory(category);
        return item;
    }

    private static Inventory inventory(Long id, MenuItem menuItem, boolean tracked,
                                       int stock, int threshold, LocalDateTime now) {
        Inventory inventory = new Inventory();
        inventory.setId(id);
        inventory.setMenuItem(menuItem);
        inventory.setTrackInventory(tracked);
        inventory.setLowStockThreshold(threshold);
        // setStockQuantity stamps lastUpdatedAt to "now" — overwrite right
        // after so the seed keeps a stable timestamp.
        inventory.setStockQuantity(stock);
        inventory.setLastUpdatedAt(now);
        return inventory;
    }

    private static User user(Long id, String username, String encodedPassword, Role role,
                             java.util.Set<Permission> permissions) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setRole(role);
        user.setMustChangePassword(false);
        user.setPermissions(permissions == null ? java.util.Set.of() : permissions);
        return user;
    }

    /** One order line: resolved against the bundle's own menu instances. */
    private static long[] line(SeedBundle bundle, long menuItemId, int quantity) {
        return new long[] { menuItemId, quantity };
    }

    private static Order order(Long id, OrderStatus status, LocalDateTime createdAt, long[]... lines) {
        // Placeholder — real wiring happens in wireOrders below, where the
        // bundle's menu instances are available.
        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        order.setCreatedAt(createdAt);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setItemCount(0);
        order.getItems().clear();
        pendingLines(order, lines);
        return order;
    }

    private static void pendingLines(Order order, long[][] lines) {
        order.setItems(new ArrayList<>());
        for (long[] line : lines) {
            OrderItem stub = new OrderItem();
            stub.setQuantity((int) line[1]);
            // menuItemId smuggled via itemName slot until wiring; replaced below.
            stub.setItemName("menu:" + line[0]);
            stub.setOrder(order);
            order.getItems().add(stub);
        }
    }

    private static MenuItem findMenu(SeedBundle bundle, long id) {
        return bundle.menuItems.stream().filter(m -> m.getId() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed menu item missing: " + id));
    }

    private static UserSessionLog sessionLog(Long id, User user, UserSessionLog.Event event,
                                             LocalDateTime occurredAt) {
        UserSessionLog log = new UserSessionLog(user, event, "demo-seed-session");
        try {
            java.lang.reflect.Field idField = UserSessionLog.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(log, id);
            java.lang.reflect.Field atField = UserSessionLog.class.getDeclaredField("occurredAt");
            atField.setAccessible(true);
            atField.set(log, occurredAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot seed session log", e);
        }
        return log;
    }

    /**
     * Replaces the wiring stubs left by {@link #order} with real
     * {@link OrderItem}s (proper menu reference, snapshot name, prices and
     * totals), assigning stable item ids. Called by {@link DemoSessionStore}
     * right after {@link #build} so ids stay unique per session.
     */
    static void wireOrders(SeedBundle bundle) {
        long itemId = 1L;
        for (Order order : bundle.orders) {
            List<OrderItem> wired = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            int count = 0;
            for (OrderItem stub : order.getItems()) {
                long menuId = Long.parseLong(stub.getItemName().substring("menu:".length()));
                MenuItem menuItem = findMenu(bundle, menuId);
                OrderItem item = new OrderItem();
                item.setId(itemId++);
                item.setOrder(order);
                item.setMenuItem(menuItem);
                item.setItemName(menuItem.getName());
                item.setQuantity(stub.getQuantity());
                item.setUnitPrice(menuItem.getPrice());
                item.setSubtotal(menuItem.getPrice().multiply(BigDecimal.valueOf(stub.getQuantity())));
                wired.add(item);
                total = total.add(item.getSubtotal());
                count += item.getQuantity();
            }
            order.setItems(wired);
            order.setTotalAmount(total);
            order.setItemCount(count);
        }
    }

    /** Mutable holder for one session's cloned showcase state. */
    public static final class SeedBundle {
        public final List<Category> categories = new ArrayList<>();
        public final List<MenuItem> menuItems = new ArrayList<>();
        public final List<Inventory> inventories = new ArrayList<>();
        public final List<User> users = new ArrayList<>();
        public final List<Order> orders = new ArrayList<>();
        public final List<AppSetting> settings = new ArrayList<>();
        public final List<AssistantConversation> conversations = new ArrayList<>();
        public final List<AssistantMessage> messages = new ArrayList<>();
        public final List<UserSessionLog> sessionLogs = new ArrayList<>();
    }
}
