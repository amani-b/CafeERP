package com.cafeerp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cafeerp.order.OrderStatus;
import com.cafeerp.settings.SettingsService;
import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;

/**
 * Plain unit tests for the demo showcase fixture (no Spring context).
 */
class DemoSeedDataTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private DemoSeedData.SeedBundle seed() {
        DemoSeedData.SeedBundle bundle = DemoSeedData.build(encoder);
        DemoSeedData.wireOrders(bundle);
        return bundle;
    }

    @Test
    void seed_hasFullShowcaseShape() {
        DemoSeedData.SeedBundle bundle = seed();

        assertThat(bundle.categories).hasSize(4);
        assertThat(bundle.menuItems).hasSize(13);
        assertThat(bundle.inventories).hasSize(13);
        assertThat(bundle.users).hasSize(3);
        assertThat(bundle.orders).hasSize(7);
        assertThat(bundle.settings).hasSize(1);
        assertThat(bundle.conversations).hasSize(1);
        assertThat(bundle.messages).hasSize(2);
        assertThat(bundle.sessionLogs).hasSize(2);
    }

    @Test
    void seed_showsTrilingualContent() {
        DemoSeedData.SeedBundle bundle = seed();

        // Amharic in Ge'ez script somewhere in the menu.
        assertThat(bundle.menuItems)
                .anyMatch(item -> item.getName() != null && item.getName().matches(".*[\\u1200-\\u137F]+.*"));
        // Amharic written in Latin letters (transliteration) somewhere too.
        assertThat(bundle.menuItems)
                .anyMatch(item -> item.getName() != null
                        && (item.getName().contains("(makiyato)")
                                || item.getName().contains("(jebena buna)")
                                || item.getName().contains("(shay)")));
        // And in a category description.
        assertThat(bundle.categories)
                .anyMatch(category -> category.getDescription() != null
                        && category.getDescription().matches(".*[\\u1200-\\u137F]+.*"));
    }

    @Test
    void seed_usersCoverThreeRolesWithKnownPassword() {
        DemoSeedData.SeedBundle bundle = seed();

        assertThat(bundle.users).extracting(user -> user.getUsername())
                .containsExactlyInAnyOrder("demo-admin", "demo-staff", "demo-kitchen");
        assertThat(bundle.users).extracting(user -> user.getRole())
                .containsExactlyInAnyOrder(Role.ADMIN, Role.STAFF, Role.KITCHEN);
        for (var user : bundle.users) {
            assertThat(encoder.matches(DemoSeedData.DEMO_PASSWORD, user.getPassword())).isTrue();
            assertThat(user.isMustChangePassword()).isFalse();
        }
        var admin = bundle.users.stream()
                .filter(user -> user.getUsername().equals("demo-admin")).findFirst().orElseThrow();
        assertThat(admin.getPermissions()).containsAll(EnumSet.allOf(Permission.class));
    }

    @Test
    void seed_ordersCoverKitchenAndReportScenarios() {
        DemoSeedData.SeedBundle bundle = seed();

        List<OrderStatus> statuses = bundle.orders.stream()
                .map(order -> order.getStatus()).toList();
        // Kitchen queue is non-empty (one of each active status) and history exists.
        assertThat(statuses).contains(OrderStatus.PENDING, OrderStatus.PREPARING,
                OrderStatus.READY, OrderStatus.COMPLETED);
        for (var order : bundle.orders) {
            assertThat(order.getItems()).isNotEmpty();
            assertThat(order.getTotalAmount()).isGreaterThan(BigDecimal.ZERO);
            assertThat(order.getItemCount()).isPositive();
            assertThat(order.getCreatedAt()).isNotNull();
        }
    }

    @Test
    void seed_inventoryShowcasesLowAndOutOfStock() {
        DemoSeedData.SeedBundle bundle = seed();

        // Chechebsa (id 9) is below its threshold; Fatira (id 10) is at zero.
        var chechebsa = bundle.inventories.stream()
                .filter(inv -> inv.getId() == 9L).findFirst().orElseThrow();
        assertThat(chechebsa.isTrackInventory()).isTrue();
        assertThat(chechebsa.getStockQuantity()).isLessThanOrEqualTo(chechebsa.getLowStockThreshold());
        var fatira = bundle.inventories.stream()
                .filter(inv -> inv.getId() == 10L).findFirst().orElseThrow();
        assertThat(fatira.getStockQuantity()).isZero();
    }

    @Test
    void seed_setsBusinessTimezone() {
        DemoSeedData.SeedBundle bundle = seed();

        assertThat(bundle.settings).anyMatch(setting ->
                setting.getKey().equals(SettingsService.TIMEZONE_KEY)
                        && setting.getValue().equals("Africa/Addis_Ababa"));
    }

    @Test
    void stores_areIndependentPerSession() {
        DemoSessionStore first = new DemoSessionStore(encoder);
        DemoSessionStore second = new DemoSessionStore(encoder);

        assertThat(first.categories).hasSize(4);
        var extra = new com.cafeerp.category.Category();
        extra.setName("Secret");
        first.categories.put(999L, extra);

        assertThat(second.categories).hasSize(4);
        assertThat(second.categories).doesNotContainKey(999L);
    }

    @Test
    void quota_allowsFifteenTurnsThenCaps() {
        DemoAssistantQuota quota = new DemoAssistantQuota();

        for (int i = 0; i < DemoAssistantQuota.MAX_MESSAGES_PER_SESSION; i++) {
            assertThat(quota.tryConsume()).isTrue();
        }
        assertThat(quota.tryConsume()).isFalse();
        assertThat(quota.remaining()).isZero();
    }
}
