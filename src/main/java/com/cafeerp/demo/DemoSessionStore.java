package com.cafeerp.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.assistant.AssistantActionLog;
import com.cafeerp.assistant.AssistantConversation;
import com.cafeerp.assistant.AssistantMessage;
import com.cafeerp.category.Category;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderItem;
import com.cafeerp.settings.AppSetting;
import com.cafeerp.user.User;
import com.cafeerp.user.UserSessionLog;

/**
 * One visitor's private sandbox.
 * <p>
 * A proxied bean tied to the visitor's {@code HttpSession}: the first access
 * in a session clones the static {@link DemoSeedData} fixture into this
 * session's maps, and every later access in the same session sees that
 * session's data only. Sessions never share a collection, and nothing here
 * ever touches the production database. When the session expires (25 minutes
 * of inactivity, see {@code application-demo.properties}) Spring evicts this
 * bean and the sandbox is gone — no cleanup job needed.
 * <p>
 * Active only under the {@code demo} profile; production wiring is untouched.
 */
@Component
@Profile("demo")
@SessionScope
public class DemoSessionStore {

    public final Map<Long, Category> categories = new LinkedHashMap<>();
    public final Map<Long, MenuItem> menuItems = new LinkedHashMap<>();
    public final Map<Long, Inventory> inventories = new LinkedHashMap<>();
    public final Map<Long, Order> orders = new LinkedHashMap<>();
    public final Map<Long, OrderItem> orderItems = new LinkedHashMap<>();
    public final Map<Long, User> users = new LinkedHashMap<>();
    public final Map<String, AppSetting> settings = new LinkedHashMap<>();
    public final Map<Long, AssistantConversation> conversations = new LinkedHashMap<>();
    public final Map<Long, AssistantMessage> messages = new LinkedHashMap<>();
    public final Map<Long, AssistantActionLog> actionLogs = new LinkedHashMap<>();
    public final Map<Long, UserSessionLog> sessionLogs = new LinkedHashMap<>();

    public final AtomicLong categoryIds = new AtomicLong(1L);
    public final AtomicLong menuItemIds = new AtomicLong(1L);
    public final AtomicLong inventoryIds = new AtomicLong(1L);
    public final AtomicLong orderIds = new AtomicLong(1L);
    public final AtomicLong orderItemIds = new AtomicLong(1L);
    public final AtomicLong userIds = new AtomicLong(1L);
    public final AtomicLong conversationIds = new AtomicLong(1L);
    public final AtomicLong messageIds = new AtomicLong(1L);
    public final AtomicLong actionLogIds = new AtomicLong(1L);
    public final AtomicLong sessionLogIds = new AtomicLong(1L);

    private final PasswordEncoder passwordEncoder;

    public DemoSessionStore(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        seed();
    }

    // ---- Accessors (repositories MUST use these, never the fields: the
    // injected store is a session-scoped proxy, whose fields are unpopulated
    // — only method calls delegate to the visitor's real session instance).
    public Map<Long, Category> categories() { return categories; }
    public Map<Long, MenuItem> menuItems() { return menuItems; }
    public Map<Long, Inventory> inventories() { return inventories; }
    public Map<Long, Order> orders() { return orders; }
    public Map<Long, OrderItem> orderItems() { return orderItems; }
    public Map<Long, User> users() { return users; }
    public Map<String, AppSetting> settings() { return settings; }
    public Map<Long, AssistantConversation> conversations() { return conversations; }
    public Map<Long, AssistantMessage> messages() { return messages; }
    public Map<Long, AssistantActionLog> actionLogs() { return actionLogs; }
    public Map<Long, UserSessionLog> sessionLogs() { return sessionLogs; }

    public long nextCategoryId() { return categoryIds.getAndIncrement(); }
    public long nextMenuItemId() { return menuItemIds.getAndIncrement(); }
    public long nextInventoryId() { return inventoryIds.getAndIncrement(); }
    public long nextOrderId() { return orderIds.getAndIncrement(); }
    public long nextOrderItemId() { return orderItemIds.getAndIncrement(); }
    public long nextUserId() { return userIds.getAndIncrement(); }
    public long nextConversationId() { return conversationIds.getAndIncrement(); }
    public long nextMessageId() { return messageIds.getAndIncrement(); }
    public long nextActionLogId() { return actionLogIds.getAndIncrement(); }
    public long nextSessionLogId() { return sessionLogIds.getAndIncrement(); }

    /**
     * Clones the static showcase fixture into this session's maps. Called
     * once per session (from the constructor, so the store is never observed
     * half-seeded); every new visitor starts from the same known-good state.
     */
    private void seed() {
        DemoSeedData.SeedBundle bundle = DemoSeedData.build(passwordEncoder);
        DemoSeedData.wireOrders(bundle);

        for (Category category : bundle.categories) {
            categories.put(category.getId(), category);
        }
        for (MenuItem item : bundle.menuItems) {
            menuItems.put(item.getId(), item);
        }
        for (Inventory inventory : bundle.inventories) {
            inventories.put(inventory.getId(), inventory);
        }
        for (Order order : bundle.orders) {
            orders.put(order.getId(), order);
            for (OrderItem item : order.getItems()) {
                orderItems.put(item.getId(), item);
            }
        }
        for (User user : bundle.users) {
            users.put(user.getId(), user);
        }
        for (AppSetting setting : bundle.settings) {
            settings.put(setting.getKey(), setting);
        }
        for (AssistantConversation conversation : bundle.conversations) {
            conversations.put(conversation.getId(), conversation);
        }
        for (AssistantMessage message : bundle.messages) {
            messages.put(message.getId(), message);
        }

        categoryIds.set(maxId(categories) + 1);
        menuItemIds.set(maxId(menuItems) + 1);
        inventoryIds.set(maxId(inventories) + 1);
        orderIds.set(maxId(orders) + 1);
        orderItemIds.set(maxId(orderItems) + 1);
        userIds.set(maxId(users) + 1);
        conversationIds.set(maxId(conversations) + 1);
        messageIds.set(maxId(messages) + 1);
        // actionLogs start empty; sessionLogs continue after the seeded rows.
        for (UserSessionLog log : bundle.sessionLogs) {
            sessionLogs.put(log.getId(), log);
        }
        sessionLogIds.set(maxId(sessionLogs) + 1);
    }

    private static long maxId(Map<Long, ?> map) {
        return map.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }
}
